/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.delta.bigquery;

import avro.shaded.com.google.common.collect.ImmutableList;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Clustering;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.EncryptionConfiguration;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FormatOptions;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatus;
import com.google.cloud.bigquery.LoadJobConfiguration;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import io.cdap.cdap.api.common.Bytes;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.delta.api.DDLEvent;
import io.cdap.delta.api.DDLOperation;
import io.cdap.delta.api.DMLEvent;
import io.cdap.delta.api.DeltaFailureException;
import io.cdap.delta.api.DeltaTargetContext;
import io.cdap.delta.api.EventConsumer;
import io.cdap.delta.api.Offset;
import io.cdap.delta.api.ReplicationError;
import io.cdap.delta.api.Sequenced;
import io.cdap.delta.api.SortKey;
import io.cdap.delta.api.SourceProperties;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.TimeoutExceededException;
import net.jodah.failsafe.event.ExecutionAttemptedEvent;
import net.jodah.failsafe.function.ContextualRunnable;
import org.apache.twill.common.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Consumes change events and applies them to BigQuery.
 * <p>
 * Writes to BigQuery in three steps.
 * <p>
 * Step 1 - Write a batch of changes to GCS
 * <p>
 * Each batch of changes is written to GCS as an object with path:
 * <p>
 * [staging bucket]/cdap/cdc/[app name]/[table id]/[batch id]
 * <p>
 * Batch id is the timestamp that the first event in the batch was processed.
 * The size of the batch is determined through configuration.
 * There is a maximum number of rows to include in each batch and a maximum amount of time to wait in between batches.
 * Each object is written in avro format and contains the columns in the destination table plus two additional columns:
 * _op: CREATE | UPDATE | DELETE
 * _batch_id: the batch id
 * <p>
 * Changes in the batch do not span across a DDL event, so they are guaranteed to conform to the same schema.
 * Failure scenarios are:
 * <p>
 * 1. The program dies after the object is written, but before the offset is persisted.
 * When the program starts up again, events for the batch will be replayed.
 * The consumer will not know which events are duplicates, so duplicate events will be written out to GCS.
 * This will not matter because of the behavior of later steps.
 * 2. The program dies before the object is written, which is always before the offset is persisted.
 * In this case, nothing was ever persisted to GCS and everything behaves as if it was the first time
 * the events were seen.
 * 3. The write to GCS fails for some reason. For example, permissions were revoked, quota was hit,
 * there was a temporary outage, etc. In this scenario, the write will be repeatedly retried until it
 * succeeds. It may need manual intervention to succeed.
 * <p>
 * Step 2 - Load data from GCS into staging BigQuery table
 * <p>
 * This step happens after the offset from Step 1 is successfully persisted. This will load the object
 * into a staging table in BigQuery. The staging table has the same schema as the rows in the GCS object.
 * It is clustered on _batch_id in order to make reads and deletes on the _batch_id efficient.
 * The job id for the load is of the form [app name]_stage_[dataset]_[table]_[batch id]_[retry num].
 * Failure scenarios are:
 * <p>
 * 1. The load job fails for some reason. For example, permissions were revoked, quota was hit, temporary outage, etc.
 * The load will be repeatedly retried until is succeeds. It may need manual intervention to succeed.
 * 2. The program dies. When the program starts up again, events will be replayed from the last committed offset.
 * <p>
 * Step 3 - Merge a batch of data from the staging BigQuery table into the target table
 * <p>
 * This step happens after the load job to the staging table has succeeded. The consumer runs a merge query of the form:
 * <p>
 * MERGE [dataset].[target table] as T
 * USING (SELECT * FROM [dataset].[staging table] WHERE _batch_id = [batch id]) as S
 * ON [row equality condition]
 * WHEN MATCHED AND S._OP = "DELETE"
 * DELETE
 * WHEN MATCHED AND S._OP = "UPDATE"
 * UPDATE(...)
 * WHEN NOT MATCHED AND S._OP = "INSERT"
 * INSERT(...)
 * VALUES(...)
 * <p>
 * The job id is of the form [app name]_merge_[dataset]_[table]_[batch id]_[retry_num].
 * This query ensures that it does not matter if there are duplicate events in the batch objects on GCS.
 * Duplicate inserts and deletes will not match and be ignored.
 * Duplicate updates will update the target row to be the same that it already is.
 * Once the job succeeds, the corresponding GCS object is deleted and the offset of the latest event is committed.
 * Failure scenarios are:
 * <p>
 * 1. The merge query fails for some reason. The consumer will retry until it succeeds.
 * It may need manual intervention to succeed.
 * 2. The program dies. Events are replayed from the last committed offset when the program starts back up.
 * 3. The GCS delete fails. The error is logged, but the consumer proceeds on.
 * Manual deletion of the object is required.
 */
public class BigQueryEventConsumer implements EventConsumer {
  private static final Logger LOG = LoggerFactory.getLogger(BigQueryEventConsumer.class);
  private static final Gson GSON = new Gson();
  private static final String RETAIN_STAGING_TABLE = "retain.staging.table";
  private static final String DIRECT_LOADING_IN_PROGRESS_PREFIX = "bigquery-direct-load-in-progress-";

  private final DeltaTargetContext context;
  private final BigQuery bigQuery;
  private final int loadIntervalSeconds;
  private final String stagingTablePrefix;
  private final MultiGCSWriter gcsWriter;
  private final Bucket bucket;
  private final String project;
  private final EncryptionConfiguration encryptionConfig;
  private final RetryPolicy<Object> commitRetryPolicy;
  private final Map<TableId, Long> latestSeenSequence;
  private final Map<TableId, Long> latestMergedSequence;
  private final Map<TableId, List<String>> primaryKeyStore;
  private final Map<TableId, SortKeyState> sortKeyStore;
  private final boolean requireManualDrops;
  private final long baseRetryDelay;
  private final int maxClusteringColumns;
  private final boolean sourceRowIdSupported;
  private final SourceProperties.Ordering sourceEventOrdering;
  private final String datasetName;
  private final boolean retainStagingTable;
  private final boolean softDeletesEnabled;
  private ScheduledExecutorService scheduledExecutorService;
  private ScheduledFuture<?> scheduledFlush;
  private ExecutorService executorService;
  private Offset latestOffset;
  private long latestSequenceNum;
  private Exception flushException;
  private final AtomicBoolean shouldStop;
  private RetryPolicy<Object> gcsWriterRetryPolicy = new RetryPolicy<>()
                                                .withMaxAttempts(25)
                                                .withMaxDuration(Duration.of(2, ChronoUnit.MINUTES))
                                                .withBackoff(1, 30, ChronoUnit.SECONDS)
                                                .withJitter(0.1);

  // have to keep all the records in memory in case there is a failure writing to GCS
  // cannot write to a temporary file on local disk either in case there is a failure writing to disk
  // Without keeping the entire batch in memory, there would be no way to recover the records that failed to write

  BigQueryEventConsumer(DeltaTargetContext context, Storage storage, BigQuery bigQuery, Bucket bucket,
                        String project, int loadIntervalSeconds, String stagingTablePrefix, boolean requireManualDrops,
                        @Nullable EncryptionConfiguration encryptionConfig, @Nullable Long baseRetryDelay,
                        @Nullable String datasetName, boolean softDeletesEnabled) {
    this.context = context;
    this.bigQuery = bigQuery;
    this.loadIntervalSeconds = loadIntervalSeconds;
    this.stagingTablePrefix = stagingTablePrefix;
    this.bucket = bucket;
    this.project = project;
    this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    this.latestSequenceNum = 0L;
    this.encryptionConfig = encryptionConfig;
    // these maps are only accessed in synchronized methods so they do not need to be thread safe.
    this.latestMergedSequence = new HashMap<>();
    this.latestSeenSequence = new HashMap<>();
    this.primaryKeyStore = new HashMap<>();
    this.sortKeyStore = new HashMap<>();
    this.commitRetryPolicy = new RetryPolicy<>()
      .withMaxAttempts(Integer.MAX_VALUE)
      .withMaxDuration(Duration.of(5, ChronoUnit.MINUTES))
      .withBackoff(1, 60, ChronoUnit.SECONDS)
      .onFailedAttempt(failureContext -> {
        // log on the first failure and then once per minute
        if (failureContext.getAttemptCount() == 1 || !failureContext.getElapsedTime().minusMinutes(1).isNegative()) {
          LOG.warn("Error committing offset. Changes will be blocked until this succeeds.",
                   failureContext.getLastFailure());
        }
      })
      .onSuccess(successContext -> {
        if (successContext.getAttemptCount() > 1) {
          LOG.info("Commited offset successfully after {} retries", (successContext.getAttemptCount() - 1));
        }
      });
    this.requireManualDrops = requireManualDrops;
    this.executorService = Executors.newCachedThreadPool(Threads.createDaemonThreadFactory("bq-daemon-%d"));
    this.gcsWriter = new MultiGCSWriter(storage, bucket.getName(),
                                        String.format("cdap/delta/%s/", context.getApplicationName()),
                                        context, executorService);
    this.baseRetryDelay = baseRetryDelay == null ? 10L : baseRetryDelay;
    String maxClusteringColumnsStr = context.getRuntimeArguments().get("gcp.bigquery.max.clustering.columns");
    // current max clustering columns is set as 4 in big query side, use that as default max value
    // https://cloud.google.com/bigquery/docs/creating-clustered-tables#limitations
    this.maxClusteringColumns = maxClusteringColumnsStr == null ? 4 : Integer.parseInt(maxClusteringColumnsStr);
    this.sourceRowIdSupported =
      context.getSourceProperties() != null && context.getSourceProperties().isRowIdSupported();
    this.sourceEventOrdering = context.getSourceProperties() == null ? SourceProperties.Ordering.ORDERED :
      context.getSourceProperties().getOrdering();
    this.datasetName = datasetName;
    this.retainStagingTable = Boolean.parseBoolean(context.getRuntimeArguments().get(RETAIN_STAGING_TABLE));
    this.softDeletesEnabled = softDeletesEnabled;
    this.shouldStop = new AtomicBoolean(false);
  }

  @Override
  public void start() {
    scheduledFlush = scheduledExecutorService.scheduleAtFixedRate(() -> {
      try {
        flush();
      } catch (InterruptedException e) {
        // just return and let things end
      } catch (Exception e) {
        flushException = e;
      }
    }, loadIntervalSeconds, loadIntervalSeconds, TimeUnit.SECONDS);
  }

  @Override
  public void stop() {
    if (scheduledFlush != null) {
      scheduledFlush.cancel(true);
    }
    scheduledExecutorService.shutdownNow();
    executorService.shutdownNow();
    shouldStop.set(true);
    try {
      scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS);
      executorService.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // just return and let everything end
    }
  }

  @Override
  public synchronized void applyDDL(Sequenced<DDLEvent> sequencedEvent) throws Exception {
    // this is non-null if an error happened during a time scheduled flush
    if (flushException != null) {
      throw flushException;
    }

    DDLEvent event = sequencedEvent.getEvent();
    DDLOperation ddlOperation = event.getOperation();
    String normalizedDatabaseName = BigQueryUtils.getNormalizedDatasetName(datasetName,
      event.getOperation().getDatabaseName());
    String normalizedTableName = BigQueryUtils.normalizeTableName(ddlOperation.getTableName());
    String normalizedStagingTableName = normalizedTableName == null ? null :
      BigQueryUtils.normalizeTableName(stagingTablePrefix + normalizedTableName);

    RetryPolicy<Object> retryPolicy = createBaseRetryPolicy(baseRetryDelay)
      .abortOn(ex -> ex instanceof DeltaFailureException)
      .onFailedAttempt(failureContext -> {
        handleBigQueryFailure(normalizedDatabaseName, event.getOperation().getSchemaName(), normalizedTableName,
                              String.format("Failed to apply '%s' DDL event",  GSON.toJson(event)), failureContext);
      });

    runWithRetryPolicy(
      ctx -> {
        try {
          handleDDL(event, normalizedDatabaseName, normalizedTableName, normalizedStagingTableName);
        } catch (BigQueryException ex) {
          //Unsupported DDL Operation
          if (isInvalidOperationError(ex)) {
            throw new DeltaFailureException("Non recoverable error in applying DDL event, aborting", ex);
          }
        }
      },
      String.format("Exhausted retries trying to apply '%s' DDL event",
                    event.getOperation()), retryPolicy
    );

    latestOffset = event.getOffset();

    if (LOG.isTraceEnabled()) {
      LOG.trace("DDL offset: {} seq num: {}", latestOffset.get(), latestSequenceNum);
    }
    context.incrementCount(event.getOperation());
    if (event.isSnapshot()) {
      context.setTableSnapshotting(normalizedDatabaseName, normalizedTableName);
    } else {
      context.setTableReplicating(normalizedDatabaseName, normalizedTableName);
    }
  }

  private void handleDDL(DDLEvent event, String normalizedDatabaseName, String normalizedTableName,
                         String normalizedStagingTableName)
    throws IOException, DeltaFailureException, InterruptedException {

    switch (event.getOperation().getType()) {
      case CREATE_DATABASE:
        DatasetId datasetId = DatasetId.of(project, normalizedDatabaseName);
        if (bigQuery.getDataset(datasetId) == null) {
          DatasetInfo datasetInfo = DatasetInfo.newBuilder(datasetId).setLocation(bucket.getLocation()).build();
          try {
            bigQuery.create(datasetInfo);
          } catch (BigQueryException e) {
            // It is possible that in multiple worker instances scenario
            // dataset is created by another worker instance after this worker instance
            // determined that dataset does not exists. Ignore error if dataset is created.
            if (e.getCode() != BigQueryTarget.CONFLICT) {
              throw e;
            }
          }
        }
        break;
      case DROP_DATABASE:
        datasetId = DatasetId.of(project, normalizedDatabaseName);
        primaryKeyStore.clear();
        if (bigQuery.getDataset(datasetId) != null) {
          if (requireManualDrops) {
            String message = String.format("Encountered an event to drop dataset '%s' in project '%s', " +
                                             "but the target is configured to require manual drops. " +
                                             "Please manually drop the dataset to make progress.",
                                           normalizedDatabaseName, project);
            LOG.error(message);
            throw new RuntimeException(message);
          }
          bigQuery.delete(datasetId);
        }
        break;
      case CREATE_TABLE:
        TableId tableId = TableId.of(project, normalizedDatabaseName, normalizedTableName);
        Table table = bigQuery.getTable(tableId);
        // SNAPSHOT data is directly loaded in the target table. Check if any such direct load was in progress
        // for the current table when target received CREATE_TABLE ddl. This indicates that the snapshot was abandoned
        // because of some failure scenario. Delete the existing table if any.
        byte[] state = context.getState(String.format(DIRECT_LOADING_IN_PROGRESS_PREFIX + "%s-%s",
                                                      normalizedDatabaseName, normalizedTableName));
        if (table != null && state != null && state.length != 0 && Bytes.toBoolean(state)) {
          bigQuery.delete(tableId);
        }
        List<String> primaryKeys = event.getPrimaryKey();
        List<String> normalizedPrimaryKeys = primaryKeys.stream()
          .map(BigQueryUtils::normalizeFieldName)
          .collect(Collectors.toList());
        updatePrimaryKeys(tableId, normalizedPrimaryKeys);
        // TODO: check schema of table if it exists already
        if (table == null) {
          List<String> clusteringSupportedKeys = getClusteringSupportedKeys(primaryKeys, event.getSchema());
          Clustering clustering = maxClusteringColumns <= 0 || clusteringSupportedKeys.isEmpty() ? null :
            Clustering.newBuilder()
              .setFields(clusteringSupportedKeys.subList(0, Math.min(maxClusteringColumns,
                                                                     clusteringSupportedKeys.size())))
              .build();
          TableDefinition tableDefinition = StandardTableDefinition.newBuilder()
            .setSchema(Schemas.convert(addSupplementaryColumnsToTargetSchema(event.getSchema(), tableId)))
            .setClustering(clustering)
            .build();

          TableInfo.Builder builder = TableInfo.newBuilder(tableId, tableDefinition);
          if (encryptionConfig != null) {
            builder.setEncryptionConfiguration(encryptionConfig);
          }
          TableInfo tableInfo = builder.build();
          bigQuery.create(tableInfo);
        }
        break;
      case DROP_TABLE:
        // need to flush changes before dropping the table, otherwise the next flush will write data that
        // shouldn't exist
        flush();
        tableId = TableId.of(project, normalizedDatabaseName, normalizedTableName);
        primaryKeyStore.remove(tableId);
        table = bigQuery.getTable(tableId);
        if (table != null) {
          if (requireManualDrops) {
            String message = String.format("Encountered an event to drop table '%s' in dataset '%s' in project '%s', " +
                                             "but the target is configured to require manual drops. " +
                                             "Please manually drop the table to make progress.",
                                           normalizedTableName, normalizedDatabaseName, project);
            LOG.error(message);
            throw new RuntimeException(message);
          }
          bigQuery.delete(tableId);
        }
        TableId stagingTableId = TableId.of(project, normalizedDatabaseName, normalizedStagingTableName);
        Table stagingTable = bigQuery.getTable(stagingTableId);
        if (stagingTable != null) {
          bigQuery.delete(stagingTableId);
        }
        break;
      case ALTER_TABLE:
        // need to flush any changes before altering the table to ensure all changes before the schema change
        // are in the table when it is altered.
        flush();
        // after a flush, the staging table will be gone, so no need to alter it.
        tableId = TableId.of(project, normalizedDatabaseName, normalizedTableName);
        table = bigQuery.getTable(tableId);
        primaryKeys = event.getPrimaryKey();
        List<String> clusteringSupportedKeys = getClusteringSupportedKeys(primaryKeys, event.getSchema());
        Clustering clustering = maxClusteringColumns <= 0 ? null :
          Clustering.newBuilder()
            .setFields(clusteringSupportedKeys.subList(0, Math.min(maxClusteringColumns, primaryKeys.size())))
            .build();
        TableDefinition tableDefinition = StandardTableDefinition.newBuilder()
          .setSchema(Schemas.convert(addSupplementaryColumnsToTargetSchema(event.getSchema(), tableId)))
          .setClustering(clustering)
          .build();
        TableInfo.Builder builder = TableInfo.newBuilder(tableId, tableDefinition);
        if (encryptionConfig != null) {
          builder.setEncryptionConfiguration(encryptionConfig);
        }
        TableInfo tableInfo = builder.build();
        if (table == null) {
          bigQuery.create(tableInfo);
        } else {
          bigQuery.update(tableInfo);
        }
        normalizedPrimaryKeys = primaryKeys.stream()
          .map(BigQueryUtils::normalizeFieldName)
          .collect(Collectors.toList());
        updatePrimaryKeys(tableId, normalizedPrimaryKeys);
        break;
      case RENAME_TABLE:
        // TODO: flush changes, execute a copy job, delete previous table, drop old staging table, remove old entry
        //  in primaryKeyStore, put new entry in primaryKeyStore
        LOG.warn("Rename DDL events are not supported. Ignoring rename event in database {} from table {} to table {}.",
                 event.getOperation().getDatabaseName(), event.getOperation().getPrevTableName(),
                 event.getOperation().getTableName());
        break;
      case TRUNCATE_TABLE:
        flush();
        tableId = TableId.of(project, normalizedDatabaseName, normalizedTableName);
        table = bigQuery.getTable(tableId);
        if (table != null) {
          tableDefinition = table.getDefinition();
          bigQuery.delete(tableId);
        } else {
          primaryKeys = event.getPrimaryKey();
          clustering = maxClusteringColumns <= 0 ? null :
            Clustering.newBuilder()
              .setFields(primaryKeys.subList(0, Math.min(maxClusteringColumns, primaryKeys.size())))
              .build();
          tableDefinition = StandardTableDefinition.newBuilder()
            .setSchema(Schemas.convert(addSupplementaryColumnsToTargetSchema(event.getSchema(), tableId)))
            .setClustering(clustering)
            .build();
        }

        builder = TableInfo.newBuilder(tableId, tableDefinition);
        if (encryptionConfig != null) {
          builder.setEncryptionConfiguration(encryptionConfig);
        }
        tableInfo = builder.build();
        bigQuery.create(tableInfo);
        break;
    }
  }

  @VisibleForTesting
  static List<String> getClusteringSupportedKeys(List<String> primaryKeys, Schema recordSchema) {
    List<String> result = new ArrayList<>();
    for (String key : primaryKeys) {
      if (Schemas.isClusteringSupported(recordSchema.getField(key))) {
        result.add(BigQueryUtils.normalizeFieldName(key));
      }
    }
    return result;
  }

  private void updatePrimaryKeys(TableId tableId, List<String> primaryKeys) throws DeltaFailureException, IOException {
    if (primaryKeys.isEmpty()) {
      throw new DeltaFailureException(
        String.format("Table '%s' in database '%s' has no primary key. Tables without a primary key are" +
                        " not supported.", tableId.getTable(), tableId.getDataset()));
    }
    List<String> existingKey = primaryKeyStore.get(tableId);
    if (primaryKeys.equals(existingKey)) {
      return;
    }
    primaryKeyStore.put(tableId, primaryKeys);
    context.putState(getTableStateKey(tableId),
                     Bytes.toBytes(GSON.toJson(new BigQueryTableState(primaryKeys, getSortKeys(tableId)))));
  }

  private List<String> getPrimaryKeys(TableId targetTableId) throws IOException, DeltaFailureException {
    List<String> primaryKeys = primaryKeyStore.get(targetTableId);
    if (primaryKeys == null) {
      byte[] stateBytes = context.getState(getTableStateKey(targetTableId));
      if (stateBytes == null || stateBytes.length == 0) {
        throw new DeltaFailureException(
          String.format("Primary key information for table '%s' in dataset '%s' could not be found. This can only " +
                          "happen if state was corrupted. Please create a new replicator and start again.",
                        targetTableId.getTable(), targetTableId.getDataset()));
      }
      BigQueryTableState targetTableState = GSON.fromJson(new String(stateBytes), BigQueryTableState.class);
      primaryKeys = targetTableState.getPrimaryKeys();
      primaryKeyStore.put(targetTableId, primaryKeys);
    }
    return primaryKeys;
  }

  private Schema addSupplementaryColumnsToTargetSchema(Schema original, TableId tableId) throws IOException {
    List<Schema.Field> fields = new ArrayList<>(original.getFields().size() + 4);
    fields.add(Schema.Field.of(Constants.SEQUENCE_NUM, Schema.of(Schema.Type.LONG)));
    fields.add(Schema.Field.of(Constants.IS_DELETED, Schema.nullableOf(Schema.of(Schema.Type.BOOLEAN))));
    fields.add(Schema.Field.of(Constants.ROW_ID, Schema.nullableOf(Schema.of(Schema.Type.STRING))));
    fields.add(Schema.Field.of(Constants.SOURCE_TIMESTAMP, Schema.nullableOf(Schema.of(Schema.Type.LONG))));
    if (sourceEventOrdering == SourceProperties.Ordering.UN_ORDERED) {
      List<Schema.Type> sortKeys = getSortKeys(tableId);
      if (sortKeys != null) {
        fields.add(Schema.Field.of(Constants.SORT_KEYS, Schemas.getSortKeysSchema(sortKeys)));
      }
    }
    fields.addAll(original.getFields());
    return Schema.recordOf(original.getRecordName() + ".sequenced", fields);
  }

  private void commitOffset() throws DeltaFailureException {
    try {
      Failsafe.with(commitRetryPolicy).run(() -> {
        if (latestOffset != null) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("Committing offset : {} and seq num: {}", latestOffset.get(), latestSequenceNum);
          }
          context.commitOffset(latestOffset, latestSequenceNum);
        }
      });
    } catch (Exception e) {
      throw new DeltaFailureException(e.getMessage(), e);
    }
  }

  @Override
  public synchronized void applyDML(Sequenced<DMLEvent> sequencedEvent) throws Exception {
    // this is non-null if an error happened during a time scheduled flush
    if (flushException != null) {
      throw flushException;
    }
    DMLEvent event = sequencedEvent.getEvent();
    String normalizedDatabaseName = BigQueryUtils.getNormalizedDatasetName(datasetName,
       event.getOperation().getDatabaseName());
    String normalizedTableName = BigQueryUtils.normalizeTableName(event.getOperation().getTableName());
    DMLEvent normalizedDMLEvent = BigQueryUtils.normalize(event)
      .setDatabaseName(normalizedDatabaseName)
      .setTableName(normalizedTableName)
      .build();
    long sequenceNumber = sequencedEvent.getSequenceNumber();

    TableId tableId = TableId.of(project, normalizedDatabaseName, normalizedTableName);

    Long latestMergedSequencedNum = latestMergedSequence.get(tableId);
    if (latestMergedSequencedNum == null) {
      // first event of the table
      latestMergedSequencedNum = getLatestSequenceNum(tableId);
      latestMergedSequence.put(tableId, latestMergedSequencedNum);
      // latestSeenSequence will replace the latestMergedSequence at the end of flush()
      // set this default value to avoid dup query of max merged sequence num in next `flush()`
      latestSeenSequence.put(tableId, latestMergedSequencedNum);
    }

    // it's possible that some previous events were merged to target table but offset were not committed
    // because offset is committed when the whole batch of all the tables were merged.
    // so it's possible we see an event that was already merged to target table
    if (sequenceNumber > latestMergedSequencedNum) {
      latestSeenSequence.put(tableId, sequenceNumber);
      //Only write events which have not already been applied
      Failsafe.with(gcsWriterRetryPolicy)
              .run(() -> gcsWriter.write(new Sequenced<>(normalizedDMLEvent, sequenceNumber)));
    }

    latestOffset = event.getOffset();
    latestSequenceNum = sequenceNumber;
    if (LOG.isTraceEnabled()) {
      LOG.trace("DML event:{} offset: {} seq num : {}", GSON.toJson(event), latestOffset.get(), latestSequenceNum);
    }
    context.incrementCount(event.getOperation());

    if (event.isSnapshot()) {
      context.setTableSnapshotting(normalizedDatabaseName, normalizedTableName);
    } else {
      context.setTableReplicating(normalizedDatabaseName, normalizedTableName);
    }

    if (sourceEventOrdering == SourceProperties.Ordering.UN_ORDERED && !getCachedSortKeys(tableId).isPresent()) {
      storeSortKeys(tableId, event.getSortKeys());
    }
  }

  @VisibleForTesting
  synchronized void flush() throws InterruptedException, IOException, DeltaFailureException {
    Map<MultiGCSWriter.BlobType, Collection<TableBlob>> tableBlobsByBlobType;
    // if this throws an IOException, we want to propagate it, since we need the app to reset state to the last
    // commit and replay events. This is because previous events are written directly to an outputstream to GCS
    // and then dropped, so we cannot simply retry the flush here.
    try {
      tableBlobsByBlobType = gcsWriter.flush();
    } catch (IOException e) {
      flushException = e;
      throw e;
    }

    processBlobsInParallel(tableBlobsByBlobType.get(MultiGCSWriter.BlobType.SNAPSHOT));
    processBlobsInParallel(tableBlobsByBlobType.get(MultiGCSWriter.BlobType.STREAMING));

    latestMergedSequence.clear();
    latestMergedSequence.putAll(latestSeenSequence);
    commitOffset();
  }

  private void processBlobsInParallel(Collection<TableBlob> tableBlobs)
    throws InterruptedException, DeltaFailureException {
    List<Future<?>> mergeFutures = new ArrayList<>(tableBlobs.size());
    for (TableBlob blob : tableBlobs) {
      // submit a callable instead of a runnable so that it can throw checked exceptions
      mergeFutures.add(executorService.submit((Callable<Void>) () -> {
        if (blob.isSnapshotOnly()) {
          context.putState(String.format(DIRECT_LOADING_IN_PROGRESS_PREFIX + "%s-%s", blob.getDataset(),
                                         blob.getTable()),
                           Bytes.toBytes(true));
          directLoadToTarget(blob);
        } else {
          context.putState(String.format(DIRECT_LOADING_IN_PROGRESS_PREFIX + "%s-%s", blob.getDataset(),
                                         blob.getTable()),
                           Bytes.toBytes(false));
          mergeTableChanges(blob);
        }
        return null;
      }));
    }

    DeltaFailureException exception = null;
    for (Future mergeFuture : mergeFutures) {
      try {
        getMergeFuture(mergeFuture);
      } catch (InterruptedException e) {
        throw e;
      } catch (DeltaFailureException e) {
        if (exception != null) {
          exception.addSuppressed(e);
        } else {
          exception = e;
        }
      }
    }
    if (exception != null) {
      throw exception;
    }
  }

  private void directLoadToTarget(TableBlob blob) throws Exception {
    LOG.debug("Direct loading batch {} of {} events into target table {}.{}", blob.getBatchId(), blob.getNumEvents(),
              blob.getDataset(), blob.getTable());
    TableId targetTableId = TableId.of(project, blob.getDataset(), blob.getTable());
    long retryDelay = Math.min(91, context.getMaxRetrySeconds()) - 1;
    runWithRetries(runContext -> loadTable(targetTableId, blob, JobType.LOAD_TARGET, runContext.getAttemptCount()),
                   retryDelay,
                   blob.getDataset(),
                   blob.getSourceDbSchemaName(),
                   blob.getTable(),
                   String.format("Failed to load a batch of changes from GCS into staging table for %s.%s",
                                 blob.getDataset(), blob.getTable()),
                   "Exhausted retries while attempting to load changed to the staging table.");
    try {
      blob.getBlob().delete();
    } catch (Exception e) {
      // there is no retry for this cleanup error since it will not affect future functionality.
      LOG.warn("Failed to delete temporary GCS object {} in bucket {}. The object will need to be manually deleted.",
               blob.getBlob().getBlobId().getName(), blob.getBlob().getBlobId().getBucket(), e);
    }
    LOG.debug("Direct loading of batch {} of {} events into target table {}.{} done", blob.getBatchId(),
              blob.getNumEvents(), blob.getDataset(), blob.getTable());
  }

  private void mergeTableChanges(TableBlob blob) throws DeltaFailureException, InterruptedException {
    String normalizedStagingTableName = BigQueryUtils.normalizeTableName(stagingTablePrefix + blob.getTable());

    TableId stagingTableId = TableId.of(project, blob.getDataset(), normalizedStagingTableName);
    long retryDelay = Math.min(91, context.getMaxRetrySeconds()) - 1;
    runWithRetries(runContext -> loadTable(stagingTableId, blob, JobType.LOAD_STAGING, runContext.getAttemptCount()),
                   retryDelay,
                   blob.getDataset(),
                   blob.getSourceDbSchemaName(),
                   blob.getTable(),
                   String.format("Failed to load a batch of changes from GCS into staging table for %s.%s",
                                 blob.getDataset(), blob.getTable()),
                   "Exhausted retries while attempting to load changed to the staging table.");

    runWithRetries(runContext -> mergeStagingTable(stagingTableId, blob, runContext.getAttemptCount()),
                   retryDelay,
                   blob.getDataset(),
                   blob.getSourceDbSchemaName(),
                   blob.getTable(),
                   String.format("Failed to merge a batch of changes from the staging table into %s.%s",
                                 blob.getDataset(), blob.getTable()),
                   String.format("Exhausted retries while attempting to merge changes into target table %s.%s. "
                                   + "Check that the service account has the right permissions "
                                   + "and the table was not modified.", blob.getDataset(), blob.getTable()));

    try {
      blob.getBlob().delete();
    } catch (Exception e) {
      // there is no retry for this cleanup error since it will not affect future functionality.
      LOG.warn("Failed to delete temporary GCS object {} in bucket {}. The object will need to be manually deleted.",
               blob.getBlob().getBlobId().getName(), blob.getBlob().getBlobId().getBucket(), e);
    }
    // clean up staging table after merging is done, there is no retry for this clean up since it will not affect
    // future functionality
    if (!retainStagingTable) {
      bigQuery.delete(stagingTableId);
    }
  }

  private void loadTable(TableId tableId, TableBlob blob, JobType jobType, int attemptNumber)
    throws InterruptedException, IOException, DeltaFailureException {
    LOG.info("Loading batch {} of {} events into {} table for {}.{} {}", blob.getBatchId(), blob.getNumEvents(),
             jobType.isForTargetTable() ? "target" : "staging", blob.getDataset(), blob.getTable(),
             attemptNumber > 0 ? "attempt: " + attemptNumber : "");

    Job loadJob = null;
    if (attemptNumber > 0) {
      // Check if any job from previous attempts was successful to avoid loading the same data multiple times
      // which can lead to data inconsistency
      loadJob = getPreviousJobIfNotFailed(blob, attemptNumber, jobType);
    }

    if (loadJob == null) {
      loadJob = createLoadJob(tableId, blob, attemptNumber, jobType);
    }

    Job completedJob = loadJob.waitFor();
    if (completedJob == null) {
      // should not happen since we just submitted the job
      throw new IOException("Load job no longer exists. Will be retried till retry timeout is reached.");
    }
    if (completedJob.getStatus().getError() != null) {
      // load job failed
      throw new IOException(String.format("Failed to execute BigQuery load job: %s",
                                          completedJob.getStatus().getError()));
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Loaded batch {} into staging table for {}.{}", blob.getBatchId(), blob.getDataset(), blob.getTable());
    }
  }

  private Job getPreviousJobIfNotFailed(TableBlob blob, int attemptNumber, JobType jobType) {
    Job previousJob = getJobFromPreviousAttemptsIfExists(blob, attemptNumber, jobType);
    if (previousJob != null) {
      if (isFailedJob(previousJob)) {
        LOG.warn("Previous job {} failed with error {} attempting to run a new job", previousJob,
                 previousJob.getStatus().getError());
      } else {
        return previousJob;
      }
    }
    return null;
  }

  private Job createLoadJob(TableId tableId, TableBlob blob, int attemptNumber, JobType jobType)
    throws IOException, DeltaFailureException {
    Table table = bigQuery.getTable(tableId);
    if (table == null) {
      List<String> primaryKeys = getPrimaryKeys(TableId.of(project, blob.getDataset(), blob.getTable()));
      Clustering clustering = maxClusteringColumns <= 0 ? null : Clustering.newBuilder()
        .setFields(primaryKeys.subList(0, Math.min(maxClusteringColumns, primaryKeys.size())))
        .build();

      Schema schema = jobType.isForTargetTable() ? blob.getTargetSchema() : blob.getStagingSchema();
      TableDefinition tableDefinition = StandardTableDefinition.newBuilder()
        .setLocation(bucket.getLocation())
        .setSchema(Schemas.convert(schema))
        .setClustering(clustering)
        .build();
      TableInfo.Builder builder = TableInfo.newBuilder(tableId, tableDefinition);
      if (encryptionConfig != null) {
        builder.setEncryptionConfiguration(encryptionConfig);
      }
      TableInfo tableInfo = builder.build();
      bigQuery.create(tableInfo);
    }
    // load data from GCS object into staging BQ table
    // batch id is a timestamp generated at the time the first event was seen, so the job id is
    // guaranteed to be different from the previous batch for the table
    JobId jobId = JobId.newBuilder()
      .setLocation(bucket.getLocation())
      .setJob(getJobId(jobType, blob.getDataset(), blob.getTable(), blob.getBatchId(), attemptNumber))
      .build();
    BlobId blobId = blob.getBlob().getBlobId();
    String uri = String.format("gs://%s/%s", blobId.getBucket(), blobId.getName());

    // Explicitly set schema for load jobs
    com.google.cloud.bigquery.Schema bqSchema
      = Schemas.convert(jobType.isForTargetTable()  ? blob.getTargetSchema() : blob.getStagingSchema());
    LoadJobConfiguration.Builder jobConfigBuilder = LoadJobConfiguration
      .newBuilder(tableId, uri)
      .setSchema(bqSchema)
      .setSchemaUpdateOptions(ImmutableList.of(JobInfo.SchemaUpdateOption.ALLOW_FIELD_ADDITION));
    if (encryptionConfig != null) {
      jobConfigBuilder.setDestinationEncryptionConfiguration(encryptionConfig);
    }
    if (blob.isJsonFormat()) {
      jobConfigBuilder.setFormatOptions(FormatOptions.json());
    } else {
      jobConfigBuilder.setFormatOptions(FormatOptions.avro());
      jobConfigBuilder.setUseAvroLogicalTypes(true);
    }
    LoadJobConfiguration loadJobConf = jobConfigBuilder.build();
    JobInfo jobInfo = JobInfo.newBuilder(loadJobConf)
      .setJobId(jobId)
      .build();
    return BigQueryUtils.createBigQueryJob(bigQuery, jobInfo);
  }

  private void mergeStagingTable(TableId stagingTableId, TableBlob blob,
                                 int attemptNumber) throws InterruptedException, IOException, DeltaFailureException {

    LOG.info("Merging batch {} for {}.{} {}", blob.getBatchId(), blob.getDataset(), blob.getTable(),
             attemptNumber > 0 ? "attempt: " + attemptNumber : "");

    Job mergeJob = null;
    if (attemptNumber > 0) {
      // Check if any job from previous attempts was successful to avoid merging the same data multiple times
      // which can lead to data inconsistency
      mergeJob = getPreviousJobIfNotFailed(blob, attemptNumber, JobType.MERGE_TARGET);
    }

    if (mergeJob == null) {
      mergeJob = createMergeJob(stagingTableId, blob, attemptNumber);
    }

    Job completedJob = mergeJob.waitFor();
    if (completedJob == null) {
      // should not happen since we just submitted the job
      throw new IOException("Merge query job no longer exists. Will be retried till retry timeout is reached.");
    }
    if (completedJob.getStatus().getError() != null) {
      // merge job failed
      throw new IOException(String.format("Failed to execute BigQuery merge query job: %s",
                                          completedJob.getStatus().getError()));
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Merged batch {} into {}.{}", blob.getBatchId(), blob.getDataset(), blob.getTable());
    }
  }

  private Job createMergeJob(TableId stagingTableId, TableBlob blob, int attemptNumber)
    throws IOException, DeltaFailureException {
    TableId targetTableId = TableId.of(project, blob.getDataset(), blob.getTable());
    List<String> primaryKeys = getPrimaryKeys(targetTableId);
    Optional<List<Schema.Type>> sortKeys = getCachedSortKeys(targetTableId);

    /*
     * Merge data from staging BQ table into target table.
     *
     * Four independent cases to be considered while performing merge operation:
     *
     * Case 1: Source generates events without row id and events are ordered.
     *
     * If the source table has two columns -- id and name -- the staging table will look something like:
     *
     * | _batch_id      | _sequence_num | _op    | _before_id | _before_name | id | name
     * | 1234567890     | 2             | INSERT |            |              | 0  | alice
     * | 1234567890     | 3             | UPDATE | 0          | alice        | 1  | alice
     * | 1234567890     | 4             | UPDATE | 1          | alice        | 2  | alice
     * | 1234567890     | 5             | DELETE | 2          | alice        | 2  | alice
     * | 1234567890     | 6             | INSERT |            |              | 0  | Alice
     * | 1234567890     | 7             | INSERT |            |              | 1  | blob
     * | 1234567890     | 8             | UPDATE | 1          | blob         | 1  | Bob
     *
     * If the primary key is the 'id' field, the merge is performed by running the following query:
     *
     * MERGE [target table] as T
     * USING ($DIFF_QUERY) as D
     * ON T.id = D._before_id
     * WHEN MATCHED AND D._op = "DELETE"
     *   DELETE
     * WHEN MATCHED AND D._op IN ("INSERT", "UPDATE")
     *   UPDATE id = D.id, name = D.name
     * WHEN NOT MATCHED AND D._op IN ("INSERT", "UPDATE")
     *   INSERT (_sequence_num, id, name) VALUES (D._sequence_num, name)
     *
     * where the $DIFF_QUERY is:
     *
     * SELECT A.* FROM
     *   (SELECT * FROM [staging table] WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as A
     *   LEFT OUTER JOIN
     *   (SELECT * FROM [staging table] WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as B
     *   ON A.id = B._before_id AND A._sequence_num < B._sequence_num
     *   WHERE B._before_id IS NULL
     *
     * The purpose of the query is to flatten events within the same batch that have the same primary key.
     * For example, with the example data given above, the result of the diff query is:
     *
     * | _batch_id      | _sequence_num | _op    | _before_id | _before_name | id | name
     * | 1234567890     | 5             | DELETE |            |              | 2  | alice
     * | 1234567890     | 6             | INSERT |            |              | 0  | Alice
     * | 1234567890     | 8             | UPDATE |            |              | 1  | Bob
     *
     * The $LATEST_APPLIED part of the query is required for idempotency. If a previous run of the pipeline merged
     * some results into the target table, but died before it could commit its offset, the merge query could end up
     * doing the wrong thing because what goes into a batch is not deterministic due to the time bound on batches.
     *
     * Case 2: Source generates events without row id and events are unordered.
     * The major differences between Case 1 and Case 2 are that:
     * a. For diff query, when join staging table with its self, join condition should contain:
     *    $ORDERING_CONDITION instead of A._sequence_num < B._sequence_num
     *    to make sure events in A are joining with events happening later in B. Because for ordered events, sequence
     *    num can decide the ordering while for unordered events, source timestamp can decide the ordering.
     *
     *    Where $ORDERING_CONDITION is of the form
     *
     *     (A._sort._key_0 < B._sort._key_0) OR
     *     (A._sort._key_0 = B._sort._key_0  AND A._sort._key_1 < B._sort._key_1) OR
     *     ...
     *     (A._sort._key_0 = B._sort._key_0  ... AND A._sort._key_n-1 = B._sort._key_n-1  AND
     *     A._sort._key_n < B._sort._key_n)
     *
     *    _sort is a struct column consisting of list of fields (_key_0, _key_1 ... _key_n) that each record
     *    can be sorted by, in the order that the comparison should be performed
     *
     * b. When merging a delete event, instead of deleting the row, we update the '_is_delete' column to true.
     *    Because it's possible that an earlier happening update event comes late, if we delete the row, this late
     *    coming update event will insert a new row. second difference makes sure such event will be ignored.
     * c. When merging delete and update event, we add an additional condition $ORDERING_CONDITION
     *    Because it's possible that an earlier happening update event comes late, we should ignore such event, if
     *    events happening later than this event has already been applied to target table.
     *
     *  So the merge query would be :
     *
     *  MERGE [target table] as T
     *  USING ($DIFF_QUERY) as D
     *  ON T.id = D._before_id
     *  WHEN MATCHED AND D._op = "DELETE" AND $ORDERING_CONDITION
     *    UPDATE SET T._is_deleted = true
     *  WHEN MATCHED AND D._op IN ("INSERT", "UPDATE") AND $ORDERING_CONDITION
     *    UPDATE id = D.id, name = D.name
     *  WHEN NOT MATCHED AND D._op IN ("INSERT", "UPDATE")
     *    INSERT (_sequence_num, _is_deleted, _source_timestamp, _sort, id, name) VALUES (D._sequence_num, false, D
     * ._source_timestamp, D._sort,  id, name)
     *
     * The purpose $DIFF_QUERY is same as case 1 but the form of it  would be:
     *   SELECT A.* FROM
     *   (SELECT * FROM [staging table] WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as A
     *   LEFT OUTER JOIN
     *   (SELECT * FROM [staging table] WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as B
     *   ON A.id = B._before_id AND $ORDERING_CONDITION
     *   WHERE B._before_id IS NULL
     *
     * Case 3: Source generates events with row id and events are ordered.
     *
     * If the source table has two columns -- id and name -- the staging table will look something like:
     *
     * | _batch_id      | _sequence_num     | _op    | _row_id | id | name
     * | 1234567890     |       20          | INSERT |  ABCD   | 0  | alice
     * | 1234567890     |       40          | UPDATE |  ABCD   | 2  | alice
     * | 1234567890     |       50          | DELETE |  ABCD   | 2  | alice
     * | 1234567890     |       60          | INSERT |  ABCD   | 0  | alice
     * | 1234567890     |       70          | INSERT |  ABCE   | 1  | bob
     * | 1234567890     |       30          | UPDATE |  ABCD   | 1  | alice
     * | 1234567890     |       80          | UPDATE |  ABCE   | 1  | Bob
     * | 1234567890     |       20          | INSERT |  ABCD   | 0  | alice
     *
     * Merge in this case is performed by running following query:
     *
     * MERGE [target table] as T
     *  USING ($DIFF_QUERY) as D
     * ON T.row_id = D.row_id
     * WHEN MATCHED AND D.op = “DELETE”
     *    DELETE
     * WHEN MATCHED AND D.op IN (“INSERT”, “UPDATE”)
     *    UPDATE _sequence_num = D._sequence_num, id = D.id, name = D.name
     * WHEN NOT MATCHED
     *    INSERT (_sequence_num, _row_id, id, name) VALUES
     *           (D._sequence_num, D._row_id, D.id, D.name)
     *
     *
     * where the $DIFF_QUERY is:
     *
     * SELECT A.* FROM
     *      (SELECT * FROM [staging table]
     *          WHERE batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as A
     *      LEFT OUTER JOIN
     *      (SELECT * FROM [staging table]
     *          WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as B
     *      ON A._row_id = B._row_id AND A._sequence_num < B._sequence_num
     * WHERE B._row_id IS NULL
     *
     * Similar to the case of events without row id, the above $DIFF_QUERY flattens events within same batch
     * that have the same _row_id and finds out the latest event.
     *
     * The result of $DIFF_QUERY for the example above would be:
     *
     * | _batch_id      | _sequence_num     | _op    | _row_id | id | name
     * | 1234567890     |       60          | INSERT |  ABCD   | 0  | alice
     * | 1234567890     |       80          | UPDATE |  ABCE   | 1  | Bob
     *
     *
     *  Case 4: Source generates events with row id and events are unordered.
     *  Similar as the differences between Case 1 & Case 2, the differences between Case 3 and Case 4 are that:
     *  a. For diff query, when join staging table with its self, join condition should contain:
     *    $ORDERING_CONDITION instead of A._sequence_num < B._sequence_num
     *    to make sure events in A are joining with events happening later in B. Because for ordered events, sequence
     *    num can decide the ordering while for unordered events, _sort can decide the ordering.
     *  b. When merging a delete event, instead of deleting the row, we update the '_is_delete' column to true.
     *    Because it's possible that an earlier happening update event comes late, if we delete the row, this late
     *    coming update event will insert a new row. second difference makes sure such event will be ignored.
     *  c. When merging delete and update event, we add an additional $ORDERING_CONDITION
     *    Because it's possible that an earlier happening update event comes late, we should ignore such event, if
     *    events happening later than this event has already been applied to target table.
     *   $ORDERING_CONDITION is the same as mentioned in Case 2
     *
     * So the merge query would be :
     *
     * MERGE [target table] as T
     *  USING ($DIFF_QUERY) as D
     * ON T.row_id = D.row_id
     * WHEN MATCHED AND D.op = “DELETE” AND $ORDERING_CONDITION
     *    UPDATE _is_deleted = true
     * WHEN MATCHED AND D.op IN (“INSERT”, “UPDATE”) AND $ORDERING_CONDITION
     *    UPDATE _sequence_num = D._sequence_num, _source_timestamp = D._source_timestamp,
     *      _sort = D._sort, id = D.id, name = D.name
     * WHEN NOT MATCHED
     *    INSERT (_sequence_num, _row_id, _source_timestamp, _sort, _is_deleted, id, name) VALUES
     *           (D._sequence_num, D._row_id, D._source_timestamp, D._sort, false, D.id, D.name)
     *
     * The purpose $DIFF_QUERY is same as case 3 but the form of it  would be:
     * SELECT A.* FROM
     *      (SELECT * FROM [staging table]
     *          WHERE batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as A
     *      LEFT OUTER JOIN
     *      (SELECT * FROM [staging table]
     *          WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as B
     *      ON A._row_id = B._row_id AND $ORDERING_CONDITION
     * WHERE B._row_id IS NULL
     */

    String diffQuery =
      createDiffQuery(stagingTableId, primaryKeys, blob.getBatchId(), latestMergedSequence.get(targetTableId),
                      sourceRowIdSupported, sourceEventOrdering, sortKeys);
    if (LOG.isTraceEnabled()) {
      LOG.trace("Diff query : {}", diffQuery);
    }
    String mergeQuery =
      createMergeQuery(targetTableId, primaryKeys, blob.getTargetSchema(), diffQuery, sourceRowIdSupported,
                       sourceEventOrdering, softDeletesEnabled, sortKeys);
    if (LOG.isTraceEnabled()) {
      LOG.trace("Merge query : {}", mergeQuery);
    }

    if (sortKeys.isPresent() && !sortKeyStore.get(targetTableId).isAddedToTarget()) {
      addSortKeyToTargetTable(targetTableId, sortKeys.get());
    }

    QueryJobConfiguration.Builder jobConfigBuilder = QueryJobConfiguration.newBuilder(mergeQuery);
    if (encryptionConfig != null) {
      jobConfigBuilder.setDestinationEncryptionConfiguration(encryptionConfig);
    }
    QueryJobConfiguration mergeJobConf = jobConfigBuilder.build();
    // job id will be different even after a retry because batchid is the timestamp when the first
    // event in the batch was seen
    JobId jobId = JobId.newBuilder()
      .setLocation(bucket.getLocation())
      .setJob(getJobId(JobType.MERGE_TARGET, blob.getDataset(), blob.getTable(), blob.getBatchId(), attemptNumber))
      .build();
    JobInfo jobInfo = JobInfo.newBuilder(mergeJobConf)
      .setJobId(jobId)
      .build();
    return BigQueryUtils.createBigQueryJob(bigQuery, jobInfo);
  }

  static String createDiffQuery(TableId stagingTable, List<String> primaryKeys, long batchId,
                                Long latestSequenceNumInTargetTable, boolean sourceRowIdSupported,
                                SourceProperties.Ordering sourceEventsOrdering, Optional<List<Schema.Type>> sortKeys) {
    String joinCondition;
    String whereClause;
    /*
     * Diff Query will be of following form:
     * SELECT A.* FROM
     *   (SELECT * FROM [staging table] WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as A
     *   LEFT OUTER JOIN
     *   (SELECT * FROM [staging table] WHERE _batch_id = 1234567890 AND _sequence_num > $LATEST_APPLIED) as B
     *   ON ($JOIN_CONDITION)
     *   WHERE ($WHERE_CLAUSE)
     *
     * Following are the differences between events with/without row id.
     * 1. For event with row Id, we use the row Id to match the row, $JOIN_CONDITION will contain:
     *     A._row_id = B._row_id
     *    and $WHERE_CLAUSE will be
     *     B._row_id IS NULL
     * 2. For event without row Id, we use primary keys to match the row, $JOIN_CONDITION will be (assuming id is the
     * PK):
     *    A.id = B._before_id
     *    and $WHERE_CLAUSE will be :
     *    B._before_id IS NULL
     *
     * Following are the differences between ordered and un-ordered events:
     * 1. For Ordered events, the $JOIN_CONDITION will contain :
     *    A._sequence_num < B._sequence_num
     *    Because events are ordered, this makes sure event in B happens later than event in A
     * 2. For Un-Ordered events, the $JOIN_CONDITION will contain $ORDERING_CONDITION :
     *     (A._sort._key_0 < B._sort._key_0) OR
     *     (A._sort._key_0 = B._sort._key_0  AND A._sort._key_1 < B._sort._key_1) OR
     *     ...
     *     (A._sort._key_0 = B._sort._key_0  ... AND A._sort._key_n-1 = B._sort._key_n-1 AND
     *         A._sort._key_n < B._sort._key_n)
     *    Because events are unordered, sequence number doesn't ensure the ordering but sort keys can.
     *    This makes sure event in B happens later than event in A
     */
    if (sourceRowIdSupported) {
      joinCondition = "A._row_id = B._row_id ";
      whereClause = " B._row_id IS NULL ";
    } else {
      joinCondition = primaryKeys.stream()
        .map(name -> String.format("A.`%s` = B.`_before_%s`", name, name))
        .collect(Collectors.joining(" AND "));

      whereClause = primaryKeys.stream()
        .map(name -> String.format("B.`_before_%s` IS NULL", name))
        .collect(Collectors.joining(" AND "));
    }

    if (sourceEventsOrdering == SourceProperties.Ordering.ORDERED) {
      joinCondition += String.format(" AND A.%s < B.%1$s\n", Constants.SEQUENCE_NUM);

    } else {
      joinCondition += getOrderingCondition(sortKeys, "A", "B");
    }
    return "SELECT A.* FROM\n" +
      "(SELECT * FROM " + BigQueryUtils.wrapInBackTick(stagingTable.getDataset(), stagingTable.getTable()) +
      " WHERE _batch_id = " + batchId +
      " AND _sequence_num > " + latestSequenceNumInTargetTable + ") as A\n" +
      "LEFT OUTER JOIN\n" +
      "(SELECT * FROM " + BigQueryUtils.wrapInBackTick(stagingTable.getDataset(), stagingTable.getTable()) +
      " WHERE _batch_id = " + batchId +
      " AND _sequence_num > " + latestSequenceNumInTargetTable + ") as B\n" +
      "ON " + joinCondition +
      " WHERE " + whereClause;
  }

  static String createMergeQuery(TableId targetTableId, List<String> primaryKeys, Schema targetSchema,
                                 String diffQuery, boolean sourceRowIdSupported,
                                 SourceProperties.Ordering sourceEventOrdering, boolean softDeletesEnabled,
                                 Optional<List<Schema.Type>> sortKeys) {
    String mergeCondition;

    /*
     * Merge query will be of the following form:
     *
     * MERGE [target table] as T
     * USING ($DIFF_QUERY) as D
     * ON ($MERGE_CONDITION)
     * WHEN MATCHED AND D._op = "DELETE" ($UPDATE_AND_DELETE_CONDITION) THEN
     *   ($DELETE_OPERATION)
     * WHEN MATCHED AND D._op IN ("INSERT", "UPDATE") ($UPDATE_AND_DELETE_CONDITION) THEN
     *   UPDATE id = D.id, name = D.name
     * WHEN NOT MATCHED AND D._op IN ("INSERT", "UPDATE") THEN
     *   INSERT (id, name) VALUES (id, name)
     *
     *
     *
     * Following are the differences between events with/without row id.
     * 1. For event with row Id, we use the row Id to match the row, $MERGE_CONDITION will be:
     *    D._row_id = T._row_id
     * 2. For event without row Id, we use primary keys to match the row, $MERGE_CONDITION will be (assuming id is the
     * PK):
     *    T.id = D._before_id
     *
     * Following are the differences between ordered and un-ordered events:
     * 1. For Ordered events, the $DELETE_OPERATION will be :
     *    DELETE
     *    That is we delete the row directly for merging delete event
     * 2. For Un-ordered events , the $DELETE_OPERATION will be :
     *    UPDATE SET T._is_deleted = true
     *    This is not to delete the row for merging delete event but to update the '_is_delete' column to true.
     *    Because it's possible that an earlier happening update event comes late, if we delete the row, this late
     *    coming update event will insert a new row. While difference 4. makes sure such event will be ignored.
     * 3. For Ordered events, $UPDATE_AND_DELETE_CONDITION will be :
     *    ""
     *    Which means no additional conditions is needed
     * 4. For Un-ordered events, $UPDATE_AND_DELETE_CONDITION will be $ORDERING_CONDITION
     *    Because it's possible that an earlier happening update event comes late, we should ignore such event, if
     *    events happening later than this event has already been applied to target table.
     * 5. For Un-ordered events, when a delete event is not matching any row in the target table (this could happen
     *    when insert and delete to the same row are in the same batch and thus diff query will only get the delete
     *    event in the result) , we should insert a row with _is_delete as true, because it's possible there is
     *    another update event to the same row that will arrive late in next batch and if we don't have this row here
     *    this late arriving update event will insert a new row. So for unordered events, we need below :
     *
     *    WHEN NOT MATCHED AND D._op IN ("DELETE") THEN
     *    INSERT (id, name, _is_deleted) VALUES (id, name, true)
     */

    if (sourceRowIdSupported) {
      // if source supports row Id , we use row id to match the row
      mergeCondition = " T._row_id = D._row_id ";

    } else {
      // if source doesn't support row Id, we use primary keys to match the row
      mergeCondition = primaryKeys.stream()
        .map(name -> String.format("T.`%s` = D.`_before_%s`", name, name))
        .collect(Collectors.joining(" AND "));

    }

    // target table schema always contains nullable fields such as _is_deleted, _row_id and _source_timestamp.
    // however these fields may not be in the staging table, so filter out these fields before
    // we perform INSERT/UPDATE queries based on the values from the staging table.
    final Predicate<Schema.Field> predicate = field -> {
      // filter out _is_deleted field for operations INSERT and UPDATE as it will be set when
      // the operation is DELETE.
      if (field.getName().equals(Constants.IS_DELETED)) {
        return false;
      }

      if (field.getName().equals(Constants.SOURCE_TIMESTAMP) || field.getName().contains(Constants.SORT_KEYS)) {
        return sourceEventOrdering == SourceProperties.Ordering.UN_ORDERED;
      }

      if (sourceRowIdSupported) {
        return true;
      }
      // filter out the _row_id field for source that doesn't support row Id, as this field will not be present in the
      // staging table.
      return !field.getName().equals(Constants.ROW_ID);
    };

    String deleteOperation;
    String updateAndDeleteCondition;

    if (sourceEventOrdering == SourceProperties.Ordering.ORDERED) {
      if (softDeletesEnabled) {
        // for ordered sources if soft deletes is enabled, mark the record as deleted without actually deleting it
        deleteOperation = "  UPDATE SET " + Constants.IS_DELETED + " = true ";
        // don't update the record with _is_deleted is set to true
        updateAndDeleteCondition = " AND " + Constants.IS_DELETED + " IS NOT TRUE ";
      } else {
        // for ordered events we can just delete the row for DELETE event
        deleteOperation = "  DELETE";
        // sequence number is incremental
        // it can decide whether it's a duplicate event, in diff query we already make sure only events with
        // sequence number greater than the max sequence number in target table can be merged.
        // for ordered events , they can be directly applied to the row matched
        updateAndDeleteCondition = "";
      }
    } else {
      // for unordered events
      deleteOperation = "  UPDATE SET " + targetSchema.getFields().stream()
        .filter(predicate)
        .map(Schema.Field::getName)
        .map(name -> String.format("`%s` = D.`%s`", name, name))
        .collect(Collectors.joining(", ")) + ", " + Constants.IS_DELETED + " = true ";
      // if events are unordered , sort keys can decide the ordering
      // if an event happening earlier comes later , it's possible that some events happening later against the same
      // row has already been merged, so this late coming event should be ignored.
      updateAndDeleteCondition = getOrderingCondition(sortKeys, "T", "D");
    }

    String mergeQuery = "MERGE " +
      BigQueryUtils.wrapInBackTick(targetTableId.getDataset(), targetTableId.getTable()) + " as T\n" +
      "USING (" + diffQuery + ") as D\n" +
      "ON " + mergeCondition + "\n" +
      "WHEN MATCHED AND D._op = \"DELETE\" " + updateAndDeleteCondition + "THEN\n" +
      deleteOperation + "\n" +
      // In a case when a replicator is paused for too long and crashed when resumed
      // user will create a new replicator against the same target
      // in this case the target already has some data
      // so the new repliator's snapshot will generate insert events that match some existing data in the
      // targe. That's why in the match case, we still need the insert opertion.
      "WHEN MATCHED AND D._op IN (\"INSERT\", \"UPDATE\") " + updateAndDeleteCondition + "THEN\n" +
      "  UPDATE SET " +
      targetSchema.getFields().stream()
        .filter(predicate)
        .map(Schema.Field::getName)
        .map(name -> String.format("`%s` = D.`%s`", name, name))
        // explicitly set "_is_deleted" to null for the case when this row was previously deleted and the
        // "_is_deleted" column was set to "true" and now a new insert is to insert the same row , we need to
        // reset "_is_deleted" back to null.
        .collect(Collectors.joining(", ")) + ", " + Constants.IS_DELETED + " = null\n" +
      "WHEN NOT MATCHED AND D._op IN (\"INSERT\", \"UPDATE\") THEN\n" +
      "  INSERT (" +
      targetSchema.getFields().stream()
        .filter(predicate)
        .map(field -> BigQueryUtils.BACKTICK + field.getName() + BigQueryUtils.BACKTICK)
        .collect(Collectors.joining(", ")) +
      ") VALUES (" +
      targetSchema.getFields().stream()
        .filter(predicate)
        .map(field -> BigQueryUtils.BACKTICK + field.getName() + BigQueryUtils.BACKTICK)
        .collect(Collectors.joining(", ")) + ")";

    if (sourceEventOrdering == SourceProperties.Ordering.UN_ORDERED) {
      mergeQuery += "\nWHEN NOT MATCHED AND D._op = \"DELETE\" THEN\n" +
        "  INSERT (" +
        targetSchema.getFields().stream()
          .filter(predicate)
          .map(field -> BigQueryUtils.BACKTICK + field.getName() + BigQueryUtils.BACKTICK)
          .collect(Collectors.joining(", ")) +
        ", " + Constants.IS_DELETED + ") VALUES (" +
        targetSchema.getFields().stream()
          .filter(predicate)
          .map(field -> BigQueryUtils.BACKTICK + field.getName() + BigQueryUtils.BACKTICK)
          .collect(Collectors.joining(", ")) + ", true)";
    }
    return mergeQuery;
  }

  private boolean isFailedJob(Job previousJob) {
    return previousJob.getStatus() != null && previousJob.getStatus().getState() == JobStatus.State.DONE
      && previousJob.getStatus().getError() != null;
  }

  /**
   * The method checks if any previous attempt for the job exists and returns the first job found
   * while iterating from the provided (attemptNumber - 1) to 0
   * Existence of the job is checked by creating deterministic job Id containing attempt number
   *
   * @param blob blob to create deterministic job Id
   * @param attemptNumber attempt number below which to check previous jobs
   * @param jobType job type to create deterministic job Id
   * @return first job which exists while iterating from the provided
   * (attemptNumber - 1) to 0, null if no job exists
   */
  private Job getJobFromPreviousAttemptsIfExists(TableBlob blob, int attemptNumber, JobType jobType) {
    for (int prevAttemptNumber = attemptNumber - 1; prevAttemptNumber >= 0; prevAttemptNumber--) {
      JobId jobId = JobId.newBuilder()
        .setLocation(bucket.getLocation())
        .setJob(getJobId(jobType, blob.getDataset(), blob.getTable(), blob.getBatchId(), prevAttemptNumber))
        .build();

      Job job = bigQuery.getJob(jobId);
      if (job != null) {
        return job;
      }
    }
    return null;
  }

  private void runWithRetries(ContextualRunnable runnable, long retryDelay, String dataset, String schema, String table,
                              String onFailedAttemptMessage, String retriesExhaustedMessage)
    throws DeltaFailureException, InterruptedException {

    runWithRetryPolicy(runnable, retriesExhaustedMessage, createBaseRetryPolicy(retryDelay)
      //Do not retry in case of invalid requests errors, but let the retrey happen from Worker
      //which can potentially mitigate the issue
      .abortOn(this::isInvalidOperationError)
      .onFailedAttempt(failureContext -> {
        handleBigQueryFailure(dataset, schema, table, onFailedAttemptMessage, failureContext);
      }));
  }

  private void runWithRetryPolicy(ContextualRunnable runnable, String retriesExhaustedMessage,
                                  RetryPolicy<Object>... retryPolicies)
    throws DeltaFailureException, InterruptedException {
    try {
      Failsafe.with(retryPolicies).run(runnable);
    } catch (TimeoutExceededException e) {
      // if the retry timeout was reached, throw a DeltaFailureException to fail the pipeline immediately
      DeltaFailureException exc = new DeltaFailureException(retriesExhaustedMessage, e);
      flushException = exc;
      throw exc;
    } catch (FailsafeException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      if (e.getCause() instanceof DeltaFailureException) {
        throw (DeltaFailureException) e.getCause();
      }
      throw e;
    }
  }

  private void handleBigQueryFailure(String dataset, String schema, String table, String onFailedAttemptMessage,
                                     ExecutionAttemptedEvent<Object> failureContext) {
    Throwable t = failureContext.getLastFailure();
    LOG.error(onFailedAttemptMessage, t);
    if (t.getCause() instanceof BigQueryException) {
      t = t.getCause();
    }
    //Logging error list
    if (t instanceof BigQueryException) {
      List<BigQueryError> errors = ((BigQueryException) t).getErrors();
      if (errors != null) {
        errors.forEach(err -> LOG.error(err.getMessage()));
      }
    }
    // its ok to set table state every retry, because this is a no-op if there is no change to the state.
    try {
      context.setTableError(dataset, table, new ReplicationError(t));
    } catch (Exception e) {
      // setting table state is not a fatal error, log a warning and continue on
      LOG.warn(String.format("Unable to set error state for table %s.%s.%s " +
                               "Replication state for the table may be incorrect.",
                             dataset, schema, table), e);
    }
  }

  /**
   * Creates the ordering condition to ensure that event in B happens later than event in A
   * assuming aliasLeft = A and aliasRight = B
   * <p>
   * Scenario 1 - Sort keys are present
   * For 3 sort keys, 'A' as leftAlias and 'B' as rightAlias
   * (A._sort._key_0 is NOT NULL AND B._sort._key_0 is NOT NULL AND
   * (A._sort._key_0 < B._sort._key_0) OR
   * (A._sort._key_0 = B._sort._key_0  AND A._sort._key_1 < B._sort._key_1) OR
   * (A._sort._key_0 = B._sort._key_0  AND  A._sort._key_1 = B._sort._key_1  AND A._sort._key_2 < B._sort._key_2))
   * OR $BACKWARD_COMPAT_CONDITION)
   * <p>
   * where $BACKWARD_COMPAT_CONDITION takes care of the scenario where one or both rows do not have sort key
   * It is used to ensure the ordering for older data which might not have sort keys in upgrade scenarios
   * (A._sort._key_0 is NULL OR B._sort._key_0 is NULL) AND
   * (A._source_timestamp < B._source_timestamp OR A._source_timestamp = B._source_timestamp AND
   * A._sequence_num < B._sequence_num)
   * <p>
   * Scenario 2 - Sort keys are not present, use source timestamp for ordering
   * (A._source_timestamp < B._source_timestamp OR A._source_timestamp = B._source_timestamp AND
   * A._sequence_num < B._sequence_num)
   *
   * @param sortKeys   Optionals list of sort keys
   * @param aliasLeft  Alias of the left table to be used for comparison
   * @param aliasRight Alias of the right table to be used for comparison
   * @return ordering condition
   */
  private static String getOrderingCondition(Optional<List<Schema.Type>> sortKeys, String aliasLeft,
                                             String aliasRight) {
    StringBuilder condition = new StringBuilder();
    if (sortKeys.isPresent()) {
      int sortKeyCount = sortKeys.get().size();
      String firstSortKey = Constants.SORT_KEYS + "." + Constants.SORT_KEY_FIELD + "_" + 0;
      // A._sort._key_0 is NOT NULL AND B._sort._key_0 is NOT NULL
      condition.append(
        String.format(" AND (( %2$s.%1$s is NOT NULL AND %3$s.%1$s is NOT NULL AND (",
                      firstSortKey, aliasLeft, aliasRight));

      // Main condition for comparing sort keys
      StringBuilder prefix = new StringBuilder("(");
      for (int i = 0; i < sortKeyCount; i++) {
        condition.append(prefix);
        String fieldName = Constants.SORT_KEYS + "." + Constants.SORT_KEY_FIELD + "_" + i;
        // A._sort._key_0 < B._sort._key_0
        condition.append(String.format("%1$s.%3$s < %2$s.%3$s", aliasLeft, aliasRight, fieldName));
        condition.append(")");
        if (i != sortKeyCount - 1) {
          condition.append(" OR \n");
        }
        // A._sort._key_0 = B._sort._key_0 AND
        prefix.append(String.format("%1$s.%3$s = %2$s.%3$s  AND ", aliasLeft, aliasRight, fieldName));
      }
      condition.append("))");

      // $BACKWARD_COMPAT_CONDITION in case sort key column is null for either row
      condition.append(" OR \n");
      // A._sort._key_0 is NULL AND B._sort._key_0 is NULL
      condition.append(
        String.format("(( %1$s.%3$s is NULL OR %2$s.%3$s is NULL )", aliasLeft, aliasRight, firstSortKey));
      // A._source_timestamp < B._source_timestamp OR A._source_timestamp = B._source_timestamp AND
      // A._sequence_num < B._sequence_num
      condition.append(
        String.format(" AND ( %1$s.%3$s < %2$s.%3$s OR ( %1$s.%3$s = %2$s.%3$s AND %1$s.%4$s < %2$s.%4$s ))))",
                      aliasLeft, aliasRight, Constants.SOURCE_TIMESTAMP, Constants.SEQUENCE_NUM));
    } else {
      // Sort keys not available in schema, fallback to source timestamp and sequence num
      // A._source_timestamp < B._source_timestamp OR A._source_timestamp = B._source_timestamp AND
      // A._sequence_num < B._sequence_num
      condition.append(String.format(" AND (%1$s.%3$s < %2$s.%3$s" +
                                       " OR (%1$s.%3$s = %2$s.%3$s AND %1$s.%4$s < %2$s.%4$s))",
                                     aliasLeft, aliasRight, Constants.SOURCE_TIMESTAMP, Constants.SEQUENCE_NUM));
    }
    return condition.toString();
  }

  private long getLatestSequenceNum(TableId tableId) throws InterruptedException, DeltaFailureException {
    RetryPolicy<Object> retryPolicy = createBaseRetryPolicy(baseRetryDelay)
      .onFailedAttempt(failureContext -> {
        Throwable t = failureContext.getLastFailure();
        LOG.error("Failed to read maximum sequence number from {}.{}.", tableId.getDataset(), tableId.getTable(), t);
      });

    try {
      return Failsafe.with(retryPolicy).get(() -> {
        long answer = BigQueryUtils.getMaximumSequenceNumberForTable(bigQuery, tableId, encryptionConfig);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Loaded {} as the latest merged sequence number for {}.{}", answer, tableId.getDataset(),
                    tableId.getTable());
        }
        return answer;
      });
    } catch (TimeoutExceededException e) {
      // if the retry timeout was reached, throw a DeltaFailureException to fail the pipeline immediately
      throw new DeltaFailureException(e.getMessage(), e);
    } catch (FailsafeException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      throw e;
    }
  }

  private void addSortKeyToTargetTable(TableId targetTableId, List<Schema.Type> sortKeys) {
    // Get the table, schema and fields from the already-existing table
    Table table = bigQuery.getTable(targetTableId);
    com.google.cloud.bigquery.Schema schema = table.getDefinition().getSchema();
    FieldList fields = schema.getFields();
    //check if fields already exists
    if (fields.stream().noneMatch(f -> f.getName().equals(Constants.SORT_KEYS))) {
      Schema.Field sortKeyField = Schema.Field.of(Constants.SORT_KEYS, Schemas.getSortKeysSchema(sortKeys));

      List<Field> fieldList = new ArrayList<Field>(fields);
      fieldList.add(Schemas.convertToBigQueryField(sortKeyField));
      // Update the table with the new schema
      com.google.cloud.bigquery.Schema updatedSchema = com.google.cloud.bigquery.Schema.of(fieldList);
      table.toBuilder().setDefinition(StandardTableDefinition.of(updatedSchema)).build().update();
    }
    sortKeyStore.get(targetTableId).setAddedToTarget(true);
  }

  private void storeSortKeys(TableId tableId, List<SortKey> sortKeys) throws IOException, DeltaFailureException {
    if (sortKeys != null && !sortKeys.isEmpty()) {
      List<Schema.Type> sortKeyTypes = sortKeys.stream()
        .map(SortKey::getType).collect(Collectors.toList());
      sortKeyStore.put(tableId, new SortKeyState(sortKeyTypes));
      context.putState(getTableStateKey(tableId),
                       Bytes.toBytes(GSON.toJson(new BigQueryTableState(getPrimaryKeys(tableId), sortKeyTypes))));
    }
  }

  private List<Schema.Type> getSortKeys(TableId tableId) throws IOException {
    SortKeyState sortKeyState = sortKeyStore.get(tableId);
    if (sortKeyState == null) {
      byte[] stateBytes = context.getState(getTableStateKey(tableId));
      if (stateBytes != null && stateBytes.length > 0) {
        BigQueryTableState targetTableState = GSON.fromJson(new String(stateBytes), BigQueryTableState.class);
        if (targetTableState.getSortKeys() != null) {
          sortKeyState = new SortKeyState(targetTableState.getSortKeys());
          sortKeyStore.put(tableId, sortKeyState);
        }
      }
    }
    return sortKeyState != null ? sortKeyState.getSortKeys() : null;
  }

  private Optional<List<Schema.Type>> getCachedSortKeys(TableId targetTableId) throws IOException {
    SortKeyState sortKeyState = sortKeyStore.get(targetTableId);
    return Optional.ofNullable(sortKeyState != null ? sortKeyState.getSortKeys() : null);
  }

  private String getTableStateKey(TableId tableId) {
    return String.format("bigquery-%s-%s", tableId.getDataset(), tableId.getTable());
  }

  private String getJobId(JobType jobType, String dataset, String table, long batchId, int attemptNumber) {
    return String.format("%s_%s_%s_%s_%d_%d", context.getApplicationName(), jobType.getId(), dataset,
                         table, batchId, attemptNumber);
  }

  /**
   * Utility method that unwraps ExecutionExceptions and propagates their cause as-is when possible.
   * Expects to be given a Future for a call to mergeTableChanges.
   */
  private static <T> T getMergeFuture(Future<T> mergeFuture) throws InterruptedException, DeltaFailureException {
    try {
      return mergeFuture.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof DeltaFailureException) {
        throw (DeltaFailureException) cause;
      }
      if (cause instanceof InterruptedException) {
        throw (InterruptedException) cause;
      }
      // should not happen unless mergeTables is changed without changing this.
      throw new RuntimeException(cause.getMessage(), cause);
    }
  }

  private <T> RetryPolicy<T> createBaseRetryPolicy(long baseDelay) {
    return createBaseRetryPolicy(baseDelay, Integer.MAX_VALUE);
  }

  private <T> RetryPolicy<T> createBaseRetryPolicy(long baseDelay, int maxAttempts) {
    RetryPolicy<T> retryPolicy = new RetryPolicy<>();
    retryPolicy.abortOn(f -> shouldStop.get());
    if (context.getMaxRetrySeconds() < 1) {
      return retryPolicy.withMaxAttempts(1);
    }

    long maxDelay = Math.max(baseDelay + 1, loadIntervalSeconds);
    return retryPolicy.withMaxAttempts(maxAttempts)
      .withBackoff(baseDelay, maxDelay, ChronoUnit.SECONDS);
  }

  private boolean isInvalidOperationError(Throwable ex) {
    return ex instanceof BigQueryException && BigQueryUtils.isInvalidOperationError((BigQueryException) ex);
  }
}
