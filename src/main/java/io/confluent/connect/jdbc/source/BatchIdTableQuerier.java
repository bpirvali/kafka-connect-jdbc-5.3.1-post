/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.source;

import io.confluent.connect.jdbc.dialect.DatabaseDialect;
import io.confluent.connect.jdbc.util.CachedConnectionProvider;
import io.confluent.connect.jdbc.util.TableId;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

public class BatchIdTableQuerier extends TimestampIncrementingTableQuerier {
  public static final String ERR_CONF_LOAD_STATUS_UPPER_BOUND_DOES_NOT_EXIST =
          " from Load-status table does not exist in the source table";
  public static boolean DEBUG = true;
  public static final List<String> offsets = new ArrayList<>();

  public static final String ERR_CONF_MULTIPLE_TS_COLUMNS =
          "validateBatchModeConfig: batch-mode does not support multilple timestamp columns!";
  public static final String ERR_CONF_EMPTY_TS_COLUMN =
          "validateBatchModeConfig: Empty string is specified for timestamp column!";
  public static final String ERR_CONF_NO_TS_OR_INC_COLUMN =
          "validateBatchModeConfig: Neither Timestamp column nor "
                  + "incremental column was found!";
  public static final String ERR_CONF_BOTH_TS_INC_COLUMN_USED =
          "validateBatchModeConfig: Usage of both incremental and timestamp column "
          + " is not allowed in batch-mode!";
  public static final String ERR_CONF_QUERY_MODE_NOT_SUPPORTED =
          "validateBatchModeConfig: query is not supported in batch-mode yet!";

  private static final Logger log = LoggerFactory.getLogger(BatchIdTableQuerier.class);

  public static final String TEMPLATE_SRC_TABLE_NAME = "__TABLE_NAME__";
  public static final String TEMPLATE_OFFSET = "__OFFSET__";
  private final String srcTableName;
  private final String batchType;
  private TimestampIncrementingOffset oldOffset;
  private final BatchIdManager batchIdManager;

  // pre-run-check values
  private Timestamp runToOffsetTs = null;
  private Long runToOffsetLong = null;
  private String preQuerySql = null;

  private final BatchModeInfo batchModeInfo;
  private final Connection conn;

  // -------------------------------------------
  // -- BatchModeInfo
  // -------------------------------------------
  public static class BatchModeInfo {
    public final CachedConnectionProvider cachedConnectionProvider;
    public final DatabaseDialect dialect;
    public final String preQuery;
    public final String offsetsTableName ;
    public final String strStartOffset;
    public final String tableOrQuery;
    public final List<String> timestampColumns;
    public final String timestampColumn;
    public final String incrementingColumn;

    public BatchModeInfo(CachedConnectionProvider connectionProvider,
                         DatabaseDialect dialect,
                         JdbcSourceTaskConfig config,
                         String tableOrQuery,
                         List<String> timestampColumns,
                         String incrementingColumn) {
      this.cachedConnectionProvider = connectionProvider;
      this.dialect = dialect;
      this.preQuery = config.getString(JdbcSourceTaskConfig.BATCH_QUERY_PRE_RUN_CHECK_CONFIG);
      this.offsetsTableName = config.getString(JdbcSourceTaskConfig.BATCH_OFFSETS_STORAGE_CONFIG);
      this.strStartOffset = config.getString(
              JdbcSourceTaskConfig.BATCH_DEFAULT_OFFSET_START_CONFIG);
      this.tableOrQuery = tableOrQuery;
      this.timestampColumn = validateBatchModeConfig(config);
      this.timestampColumns = timestampColumns;
      this.incrementingColumn = (timestampColumn != null ? null : incrementingColumn);
    }
  }

  // -------------------------------------------
  // -- validateTimeStampColumn
  // -------------------------------------------
  private static String validateTimeStampColumn(List<String> timestampColumns) {
    String timestampColumn = null;
    if (timestampColumns != null) {

      // more than one timestamp column
      if (timestampColumns.size() > 1) {
        throw new ConnectException(ERR_CONF_MULTIPLE_TS_COLUMNS);
      } else if (timestampColumns.size() == 1) {
        timestampColumn = timestampColumns.get(0);

        // null/empty timestamp column
        if (timestampColumn == null || timestampColumn.length() == 0) {
          throw new ConnectException(ERR_CONF_EMPTY_TS_COLUMN);
        }
      }
    }
    return timestampColumn;
  }

  // -------------------------------------------
  // -- validateBatchModeConfig
  // -------------------------------------------
  public static String validateBatchModeConfig(JdbcSourceTaskConfig config) {
    String incrementingColumn =
            config.getString(JdbcSourceTaskConfig.INCREMENTING_COLUMN_NAME_CONFIG);

    List<String> timestampColumns =
            config.getList(JdbcSourceTaskConfig.TIMESTAMP_COLUMN_NAME_CONFIG);

    String query = config.getString(JdbcSourceTaskConfig.QUERY_CONFIG);
    boolean noIncrementalColumn = incrementingColumn == null || incrementingColumn.length() == 0;

    String timestampColumn = validateTimeStampColumn(timestampColumns);

    // neither timestamp nor incremental column
    if (timestampColumn == null && noIncrementalColumn) {
      throw new ConnectException(ERR_CONF_NO_TS_OR_INC_COLUMN);
    }

    // both timestamp or incremental columns
    if (!noIncrementalColumn &&  timestampColumn != null) {
      throw new ConnectException(ERR_CONF_BOTH_TS_INC_COLUMN_USED);
    }

    // query is not supported yet!
    if (query != null && query.length() > 0) {
      throw new ConnectException(ERR_CONF_QUERY_MODE_NOT_SUPPORTED);
    }
    return timestampColumn;
  }

  // -------------------------------------------
  // -- createBatchIdManager
  // -------------------------------------------
  public static BatchIdManager createBatchIdManager(
          BatchModeInfo batchModeInfo,
          DatabaseDialect dialect,
          String topicPrefix,
          String tableOrQuery) {
    // srcTableName
    TableId tableId = dialect.parseTableIdentifier(tableOrQuery);
    String srcTableName = tableId.schemaName() + "." + tableId.tableName();

    // batch-type
    String batchType = getBatchType(batchModeInfo.timestampColumn);

    // TODO: BP: 2020-03-13 If storage = "kafka.<topic-name> ==> create a kafka-batch-manager
    return new JdbcBatchIdManager(batchModeInfo, dialect,
            batchType, topicPrefix, srcTableName);
  }

  private static String getBatchType(String timestampColumn) {
    if (timestampColumn == null) {
      return BatchIdManager.BATCH_TYPE_LONG;
    }
    return BatchIdManager.BATCH_TYPE_TIMESTAMP;
  }

  public BatchIdTableQuerier(BatchModeInfo batchModeInfo,
                             QueryMode mode, BatchIdManager batchIdManager,
                             String topicPrefix, Map<String, Object> offsetMap,
                             Long timestampDelay, TimeZone timeZone) {
    super(batchModeInfo.dialect, mode, batchModeInfo.tableOrQuery, topicPrefix,
            batchModeInfo.timestampColumns, batchModeInfo.incrementingColumn,
            offsetMap, timestampDelay, timeZone);

    // config is already validated in the BatchModeInfo Constructor

    // SQL connection
    conn = batchModeInfo.cachedConnectionProvider.getConnection();
    try {
      conn.setAutoCommit(true);
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    } catch (SQLException e) {
      throw new ConnectException("Exception in BatchIdTableQuerier", e);
    }

    this.batchModeInfo = batchModeInfo;

    // tableName
    srcTableName = tableId.schemaName() + "." + tableId.tableName();

    batchType = getBatchType(batchModeInfo.timestampColumn);

    this.batchIdManager = batchIdManager;

    // set initial offset
    offset = getInitialoffset();
  }

  private TimestampIncrementingOffset getInitialoffset() {
    return new TimestampIncrementingOffset(batchIdManager.getLastOffsetTimestamp(),
            batchIdManager.getLastOffsetLong());
  }

  private void setBatchId(TimestampIncrementingOffset oldOffset,
                          TimestampIncrementingOffset newOffset) {
    switch (batchType) {
      case BatchIdManager.BATCH_TYPE_LONG:
        long oldId = oldOffset.getIncrementingOffset();
        long newId = newOffset.getIncrementingOffset();
        if (BatchIdTableQuerier.DEBUG) {
          BatchIdTableQuerier.offsets.add(oldId + " | " + newId);
        }
        if (oldId != batchIdManager.getStartOffsetLong() && oldId != newId) {
          batchIdManager.setLastOffsetLong(oldId);
        }
        break;

      case BatchIdManager.BATCH_TYPE_TIMESTAMP:
        Timestamp oldTS = oldOffset.getTimestampOffset();
        long oldLong = oldTS.getTime();
        long newLong = newOffset.getTimestampOffset().getTime();
        log.info("setBatchId: old-offset: {}, new-offset: {}",
                oldOffset.getTimestampOffset(), newOffset.getTimestampOffset());
        if (BatchIdTableQuerier.DEBUG) {
          BatchIdTableQuerier.offsets.add(oldTS.toString() + " | "
                  + newOffset.getTimestampOffset().toString());
        }
        if (oldLong !=  batchIdManager.getStartOffsetTs().getTime() && oldLong != newLong) {
          batchIdManager.setLastOffsetTimestamp(oldTS);
        }
        break;

      default:
        assert false;
    }
  }

  @Override
  public SourceRecord extractRecord() throws SQLException {
    Struct record = new Struct(schemaMapping.schema());
    for (SchemaMapping.FieldSetter setter : schemaMapping.fieldSetters()) {
      try {
        setter.setField(record, resultSet);
      } catch (IOException e) {
        log.warn("Error mapping fields into Connect record", e);
        throw new ConnectException(e);
      } catch (SQLException e) {
        log.warn("SQL error mapping fields into Connect record", e);
        throw new DataException(e);
      }
    }
    oldOffset = offset;
    offset = getCriteria().extractValues(schemaMapping.schema(), record, offset);
    // -------------------------------------------
    // BP: 2020-03-07 15:54:02
    // -------------------------------------------
    // Sending the batch-id to be set in case of a change
    log.info("extractRecord: old-offset:{}, new-offset:{}",
            oldOffset != null ? oldOffset.getTimestampOffset() : "null",
            offset != null ? offset.getTimestampOffset() : "null");
    setBatchId(oldOffset, offset);
    oldOffset = offset;
    return new SourceRecord(getPartition(), null, getTopic(), record.schema(), record);
  }

  @Override
  public Timestamp beginTimetampValue() {
    return batchIdManager.getLastOffsetTimestamp();
  }

  @Override
  public Timestamp endTimetampValue()  throws SQLException {
    return runToOffsetTs;
    //return new Timestamp(runToOffsetTs.getTime() + 1);
  }

  @Override
  public Long lastIncrementedValue() {
    return batchIdManager.getLastOffsetLong();
  }

  @Override
  public Long higestIncrementedValue() {
    return runToOffsetLong;
  }

  @Override
  public boolean doPostProcessing() {
    boolean lastCompletedOffsetIsSet =  setLastCompletedOffsetInDB();
    if (lastCompletedOffsetIsSet) {
      log.info("-----------------------------------------------------");
      log.info("Done processing the batch! ");
      log.info("-----------------------------------------------------");
      return true;
    }
    return false;
  }

  @Override
  public boolean doPreProcessing() {
    return checkPreRun();
  }

  // -------------------------------------------
  // setLastCompletedOffsetInDB
  // -------------------------------------------
  private boolean setLastCompletedOffsetInDB() {
    boolean processed  = false;
    switch (batchType) {
      case BatchIdManager.BATCH_TYPE_LONG:
        processed = setLastOffsetLong();
        break;

      case BatchIdManager.BATCH_TYPE_TIMESTAMP:
        processed = setLastOffsetTimestamp();
        break;

      default:
        assert false;
    }
    if (!processed) {
      log.warn("setLastCompletedOffsetInDB failed to set the last completed "
              + "offset into offset-table!");
      log.warn("Probably Load_status's RunTo-offset is higher "
              + "than offset of last row in the table!");
      log.warn("runToOffsetTs: {}, runToOffsetLong: {}", runToOffsetTs, runToOffsetLong);
    }
    return processed;
  }

  //  private boolean isAnyDataReadFromDB() {
  //    assert !(offset != null && oldOffset == null) : "!(offset != null && oldOffset == null)";
  //    assert !(offset == null && oldOffset != null) : "!(offset == null && oldOffset != null)";
  //
  //    return !(offset == null & oldOffset == null);
  //  }

  private boolean setLastOffsetTimestamp() {
    //    if (!isAnyDataReadFromDB()) {
    //      return false;
    //    }
    Timestamp tsLastOffset = oldOffset.getTimestampOffset();
    if (runToOffsetTs != null && runToOffsetTs.equals(tsLastOffset)) {
      batchIdManager.setLastOffsetTimestamp(tsLastOffset);
      runToOffsetTs = null;

      return true;
    }
    return false;
  }

  private boolean setLastOffsetLong() {
    //    if (!isAnyDataReadFromDB()) {
    //      return false;
    //    }
    Long lastOffset = oldOffset.getIncrementingOffset();
    if (runToOffsetLong != null && runToOffsetLong.equals(lastOffset)) {
      batchIdManager.setLastOffsetLong(lastOffset);
      runToOffsetLong = null;
      return true;
    }
    return false;
  }
  // -------------------------------------------
  // / setLastCompletedOffsetInDB
  // -------------------------------------------

  // -------------------------------------------
  // checkPreRun
  // -------------------------------------------
  /**
   *
   * @return true means run, false means wait
   */
  private boolean checkPreRun() {
    // if no query ==> we will run
    if (batchModeInfo.preQuery.trim().length() == 0) {
      return true;
    }

    // if we have a value to run to ==> we will run
    if (runToOffsetLong != null || runToOffsetTs != null) {
      return true;
    }

    // since we did not have a value ==> try to set a value
    setRunToOffset();

    // check again
    return (runToOffsetLong != null || runToOffsetTs != null);
  }

  private void setRunToOffset() {
    //Timestamp tsRunTo = null;
    PreparedStatement stmt = null;
    ResultSet rs = null;
    boolean checkRunToOffset = false;
    String offsetType = null;
    try {
      stmt = getPreQueryStatement();
      rs = stmt.executeQuery();
      if (rs != null && rs.next()) {
        switch (batchType) {
          case BatchIdManager.BATCH_TYPE_TIMESTAMP:
            runToOffsetTs = rs.getTimestamp(1);
            offsetType = "runToOffsetTs: '" + runToOffsetTs.toString() + "'";
            break;
          case BatchIdManager.BATCH_TYPE_LONG:
            runToOffsetLong = rs.getLong(1);
            offsetType = "runToOffsetLong: '" + runToOffsetLong.toString() + "'";
            break;
          default:
            assert false;
        }
        checkRunToOffset = batchIdManager.checkRunToOffset(runToOffsetTs, runToOffsetLong);
        if (!checkRunToOffset) {
          throw new ConnectException(offsetType + ERR_CONF_LOAD_STATUS_UPPER_BOUND_DOES_NOT_EXIST
                  + ": '" + srcTableName + "'!");
        }
      } else {
        log.info("--------------------------------------------------------------------------");
        log.info("pre-query: '{}' is not returning any data", preQuerySql);
        log.info("--------------------------------------------------------------------------");
      }
    } catch (SQLException e) {
      log.error("Exception in setRunToOffset", e);
    } finally {
      try {
        if (rs != null) {
          rs.close();
        }
        if (stmt != null) {
          stmt.close();
        }
      } catch (SQLException e) {
        log.error("Exception closing ResultSet in setRunToOffset", e);
        throw new ConnectException(e.getMessage(), e);
      }
    }
  }

  private PreparedStatement getPreQueryStatement() {
    preQuerySql = batchModeInfo.preQuery.trim();
    if (preQuerySql.length() == 0) {
      return null;
    }

    String tableName = "'" + srcTableName + "'";
    preQuerySql = preQuerySql.replaceAll(TEMPLATE_SRC_TABLE_NAME, tableName);
    switch (batchType) {
      case BatchIdManager.BATCH_TYPE_LONG:
        Long runFromLong = batchIdManager.getLastOffsetLong();
        preQuerySql = preQuerySql.replaceAll(TEMPLATE_OFFSET, "" + runFromLong);
        break;

      case BatchIdManager.BATCH_TYPE_TIMESTAMP:
        Timestamp runFromTs =  batchIdManager.getLastOffsetTimestamp();
        String pattern = "yyyy-MM-dd HH:mm:ss";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        String strTs = "'" + formatter.format(runFromTs.toLocalDateTime()) + "'";

        preQuerySql = preQuerySql.replaceAll(TEMPLATE_OFFSET, strTs);
        break;

      default:
        assert false;
    }
    try {
      log.info("pre-run-query: {}", preQuerySql);
      return dialect.createPreparedStatement(conn, preQuerySql);
    } catch (SQLException e) {
      log.error("Exception in getPreQueryStatement", e);
      throw new ConnectException(e.getMessage(), e);
    }
    //return null;
  }
  // -------------------------------------------
  // / checkPreRun
  // -------------------------------------------

  @Override
  public String toString() {
    return "BatchIdTableQuerier{"
            + "table=" + tableId
            + ", query='" + query + '\''
            + ", topicPrefix='" + topicPrefix + '\''
            + ", incrementingColumn='" + (getIncrementingColumnName() != null
            ? getIncrementingColumnName()
            : "") + '\''
            + ", timestampColumns=" + getTimestampColumnNames()
            + '}';
  }

}
