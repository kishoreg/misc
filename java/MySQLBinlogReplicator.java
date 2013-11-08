package edu.washington.folgers.replication;

import com.google.code.or.OpenReplicator;
import com.google.code.or.binlog.BinlogEventListener;
import com.google.code.or.binlog.BinlogEventV4;
import com.google.code.or.binlog.impl.event.*;
import com.google.code.or.common.glossary.Column;
import com.google.code.or.common.glossary.Row;

// If want to release this, copy / paste these dependencies in
import edu.washington.folgers.tuple.Pair;
import edu.washington.folgers.util.BinlogUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uses {@link OpenReplicator} to replicate MySQL databases from a master to one or more slaves.
 *
 * @author Greg Brandt (gbrandt@linkedin.com)
 */
public class MySQLBinlogReplicator
{
  private static final Logger LOG = Logger.getLogger(MySQLBinlogReplicator.class);

  /** The maximum number of replication events in the queue before stopping binlog scanning */
  private static final int MAX_QUEUE_SIZE = 1000;
  /** The amount of time to wait to stop OpenReplicator on shutdown */
  private static final int OR_STOP_TIMEOUT_SECONDS = 60;
  /** The number of events to wait until creating a new checkpoint */
  private static final int CHECKPOINT_SAMPLE_PERIOD = 50;

  /** The allowed replication operations */
  private enum Operation
  {
    INSERT,
    DELETE
  }

  /** Supported MySQL column types */
  private enum ColumnType
  {
    INT,
    TINYINT,
    SMALLINT,
    MEDIUMINT,
    BIGINT,

    FLOAT,
    DOUBLE,
    DECIMAL,

    // TODO: time-related ones (http://www.tutorialspoint.com/mysql/mysql-data-types.htm)

    CHAR,
    VARCHAR,

    BLOB,
    TINYBLOB,
    MEDIUMBLOB,
    LONGBLOB,
    TEXT
  }

  private final MySQLConfig _master;
  private final Map<String, List<MySQLConfig>> _dbNameToSlaves;
  private final AtomicBoolean _isShutdown;
  private final BlockingQueue<WriteEvent> _queue;
  private final OpenReplicator _openReplicator;
  private final Thread _consumerThread;
  private final AtomicLong _numEvents;
  private final ConcurrentMap<Long, SchemaEvent> _schemas;

  public MySQLBinlogReplicator(MySQLConfig master,
                               Map<String, List<MySQLConfig>> dbNameToSlaves)
  {
    _master = master;
    _isShutdown = new AtomicBoolean(false);
    _queue = new ArrayBlockingQueue<WriteEvent>(MAX_QUEUE_SIZE);
    _schemas = new ConcurrentHashMap<Long, SchemaEvent>();
    _numEvents = new AtomicLong(0);

    _dbNameToSlaves = new HashMap<String, List<MySQLConfig>>();
    for (Map.Entry<String, List<MySQLConfig>> entry : dbNameToSlaves.entrySet())
    {
      _dbNameToSlaves.put(entry.getKey().toLowerCase(), entry.getValue());
    }

    _openReplicator = new OpenReplicator();
    _openReplicator.setUser(master.user);
    _openReplicator.setPassword(master.password);
    _openReplicator.setHost(master.host);
    _openReplicator.setPort(master.port);
    _openReplicator.setServerId(master.serverId);
    _openReplicator.setBinlogEventListener(new ProducerBinlogEventListener());

    _consumerThread = new Thread(new ConsumerWorker());
  }

  /**
   * Inspects all slaves to determine the binlog offset from which to start replaying events.
   * If a reasonable checkpoint can't be found, start replaying events from the earliest binlog
   * segment the master has.
   */
  public void start() throws Exception
  {
    // Determine any checkpoints on the slave
    List<Pair<String, Integer>> checkpoints = new ArrayList<Pair<String, Integer>>();
    int numSlaves = 0;
    for (Map.Entry<String, List<MySQLConfig>> entry : _dbNameToSlaves.entrySet())
    {
      for (MySQLConfig slave : entry.getValue())
      {
        numSlaves++;
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try
        {
          conn = slave.getConnection();

          // Create the checkpoint table if necessary
          stmt = conn.createStatement();
          stmt.execute("CREATE TABLE IF NOT EXISTS `mysql`.`rpl_checkpoint` " +
                               "(`master_id` INT(32), `binlog_file` VARCHAR(63), " +
                               "`binlog_pos` INT(64), PRIMARY KEY(`master_id`))");
          stmt.close();

          // Find any checkpoint for this master_id
          pstmt = conn.prepareStatement("SELECT * FROM `mysql`.`rpl_checkpoint` WHERE `master_id` = ?");
          pstmt.setInt(1, _master.serverId);
          pstmt.execute();
          rs = pstmt.getResultSet();
          while (rs.next())
          {
            checkpoints.add(Pair.make(rs.getString("binlog_file"), rs.getInt("binlog_pos")));
          }
        }
        catch (SQLException e)
        {
          LOG.error(e);
        }
        finally
        {
          if (rs != null) rs.close();
          if (stmt != null) stmt.close();
          if (pstmt != null) pstmt.close();
          if (conn != null) conn.close();
        }
      }
    }

    // If can't resolve a checkpoint, start from oldest binlog on master
    if (checkpoints.size() != numSlaves)
    {
      Connection conn = DriverManager.getConnection(_master.toString());
      List<String> binaryLogs = BinlogUtils.showBinaryLogs(conn);
      _openReplicator.setBinlogFileName(binaryLogs.get(0));
      _openReplicator.setBinlogPosition(4); // The first entry
      conn.close();
      LOG.info(String.format("Couldn't find checkpoint to start from, starting from oldest binlog and position %s,%s", binaryLogs.get(0), 4));
    }
    // Otherwise, pick the lowest checkpoint
    else
    {
      Collections.sort(checkpoints, new Comparator<Pair<String, Integer>>()
      {
        @Override
        public int compare(Pair<String, Integer> p1, Pair<String, Integer> p2)
        {
          if (p1.first.equals(p2.first))
          {
            return (int) (BinlogUtils.getBinlogFileNumber(p1.first) - BinlogUtils.getBinlogFileNumber(p2.first));
          }
          return p1.second - p2.second;
        }
      });
      Pair<String, Integer> oldestBinlog = checkpoints.get(0);
      _openReplicator.setBinlogFileName(oldestBinlog.first);
      _openReplicator.setBinlogPosition(oldestBinlog.second);
      LOG.info(String.format("Starting from lowest checkpoint %s,%s", oldestBinlog.first, oldestBinlog.second));
    }
    _consumerThread.start();
    _openReplicator.start();
    // TODO: Sometimes it fails when we use a checkpoint... we should verify that the checkpoint is there before we start

    LOG.info("Started " + MySQLBinlogReplicator.class.getName());
  }

  /**
   * Stops replaying binlog events
   */
  public void stop() throws Exception
  {
    if (_isShutdown.getAndSet(true))
      return;
    _openReplicator.stop(OR_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    _consumerThread.join();
  }

  /**
   * This event listener populates a local cache of MySQL schemas on TableMapEvents,
   * and adds write events to a blocking queue, which the replication consumer thread
   * takes from.
   */
  private class ProducerBinlogEventListener implements BinlogEventListener
  {
    @Override
    public void onEvents(BinlogEventV4 event)
    {
      if (event instanceof TableMapEvent)
      {
        // TODO: Remove from cache if it's a drop table event!
        // Or maybe clear caches on an exception
        TableMapEvent te = (TableMapEvent) event;
        if (!_schemas.containsKey(te.getTableId()))
        {
          _schemas.putIfAbsent(te.getTableId(), parseSchemaEvent(te));
        }
      }
      else
      {
        WriteEvent write = parseWriteEvent(event);
        if (write != null)
        {
          putWhileBlockingForever(write);
        }
      }
    }

    /**
     * Parses TableMapEvents, then reads the schema corresponding to that table from MySQL
     *
     * @param event
     *  An OpenReplicator table map event
     * @return
     *  A tuple of (dbName, tableName, list of (columnName, columnType))
     */
    private SchemaEvent parseSchemaEvent(TableMapEvent event)
    {
      String dbName = new String(event.getDatabaseName().getValue());
      String tableName = new String(event.getTableName().getValue());
      List<Pair<String, ColumnType>> columns = new ArrayList<Pair<String, ColumnType>>();

      // Look up the table schema and generate a list of (columnName, columnType) pairs
      Connection conn = null;
      Statement stmt = null;
      ResultSet rs = null;
      try
      {
        conn = _master.getConnection();
        stmt = conn.createStatement();
        rs = stmt.executeQuery(String.format("DESCRIBE `%s`.`%s`", dbName, tableName));

        while (rs.next())
        {
          String fieldName = rs.getString("Field");
          String typeStr = rs.getString("Type");

          int parenIdx = typeStr.indexOf("(");
          if (parenIdx >= 0)
            typeStr = typeStr.substring(0, parenIdx);

          ColumnType columnType = ColumnType.valueOf(typeStr.toUpperCase());
          columns.add(Pair.make(fieldName, columnType));
        }
      }
      catch (SQLException e)
      {
        throw new RuntimeException(e);
      }
      finally
      {
        try
        {
          if (rs != null) rs.close();
          if (stmt != null) stmt.close();
          if (conn != null) conn.close();
        }
        catch (SQLException e)
        {
          // Okay
        }
      }

      return new SchemaEvent()
              .setDBName(dbName)
              .setTableName(tableName)
              .setColumns(columns);
    }

    /**
     * Returns a tuple (replicationOperation, tableId, rows) where replicationOperation
     * is the MySQL operation that should be performed on the slave, the tableId is the
     * OpenReplicator table id (see: _schemas), and rows is the list of rows in the txn.
     * Returns null if the event wasn't a write event.
     */
    private WriteEvent parseWriteEvent(BinlogEventV4 event)
    {
      if (event instanceof WriteRowsEvent)
      {
        WriteRowsEvent we = (WriteRowsEvent) event;
        return new WriteEvent()
                .setOperation(Operation.INSERT)
                .setTableId(we.getTableId())
                .setNextBinlogOffset(we.getHeader().getNextPosition())
                .setRows(we.getRows());
      }
      else if (event instanceof WriteRowsEventV2)
      {
        WriteRowsEventV2 we = (WriteRowsEventV2) event;
        return new WriteEvent()
                .setOperation(Operation.INSERT)
                .setTableId(we.getTableId())
                .setNextBinlogOffset(we.getHeader().getNextPosition())
                .setRows(we.getRows());
      }
      else if (event instanceof UpdateRowsEvent)
      {
        UpdateRowsEvent ue = (UpdateRowsEvent) event;
        List<Row> afterImages = new ArrayList<Row>(ue.getRows().size());
        for (com.google.code.or.common.glossary.Pair<Row> row : ue.getRows())
        {
          afterImages.add(row.getAfter());
        }
        return new WriteEvent()
                .setOperation(Operation.INSERT)
                .setTableId(ue.getTableId())
                .setNextBinlogOffset(ue.getHeader().getNextPosition())
                .setRows(afterImages);
      }
      else if (event instanceof UpdateRowsEventV2)
      {
        UpdateRowsEventV2 ue = (UpdateRowsEventV2) event;
        List<Row> afterImages = new ArrayList<Row>(ue.getRows().size());
        for (com.google.code.or.common.glossary.Pair<Row> row : ue.getRows())
        {
          afterImages.add(row.getAfter());
        }
        return new WriteEvent()
                .setOperation(Operation.INSERT)
                .setTableId(ue.getTableId())
                .setNextBinlogOffset(ue.getHeader().getNextPosition())
                .setRows(afterImages);
      }
      else if (event instanceof DeleteRowsEvent)
      {
        DeleteRowsEvent de = (DeleteRowsEvent) event;
        return new WriteEvent()
                .setOperation(Operation.DELETE)
                .setTableId(de.getTableId())
                .setNextBinlogOffset(de.getHeader().getNextPosition())
                .setRows(de.getRows());
      }
      else if (event instanceof DeleteRowsEventV2)
      {
        DeleteRowsEventV2 de = (DeleteRowsEventV2) event;
        return new WriteEvent()
                .setOperation(Operation.DELETE)
                .setTableId(de.getTableId())
                .setNextBinlogOffset(de.getHeader().getNextPosition())
                .setRows(de.getRows());
      }
      else
      {
        return null;
      }
    }

    /** Ignores interrupted exceptions, so we just wait until we can add something to the queue */
    private void putWhileBlockingForever(WriteEvent write)
    {
      boolean isPut = false;
      while (!isPut)
      {
        try
        {
          _queue.put(write);
          isPut = true;
        }
        catch (InterruptedException e)
        {
          // continue
        }
      }
    }
  }

  /**
   * A worker that consumes from a blocking queue, and replicates write requests until they're successful.
   */
  private class ConsumerWorker implements Runnable
  {
    private WriteEvent _inFlightWrite = null;

    @Override
    public void run()
    {
      while (!_isShutdown.get() || !_queue.isEmpty())
      {
        try
        {
          if (_inFlightWrite == null)
          {
            _inFlightWrite = _queue.take();
          }

          boolean success = replicateWrite(_inFlightWrite);

          if (success)
          {
            LOG.debug("Replicated " + _inFlightWrite);
            long currentCount = _numEvents.addAndGet(1);
            if (currentCount % CHECKPOINT_SAMPLE_PERIOD == 0)
            {
              generateCheckPoint(_inFlightWrite);
            }
            _inFlightWrite = null;
          }
          else
          {
            LOG.debug("Failed! " + _inFlightWrite);
          }
        }
        catch (Exception e)
        {
          e.printStackTrace();
        }
      }
    }

    /**
     * Replicates a write to a slave
     *
     * @param write
     *  A tuple of (operation, tableId, list of rows in txn)
     * @return
     *  true if the write succeeded remotely
     */
    private boolean replicateWrite(WriteEvent write)
    {
      boolean success = true;
      SchemaEvent schema = _schemas.get(write.tableId);

      List<MySQLConfig> slaves = _dbNameToSlaves.get(schema.dbName);
      if (slaves == null)
        return true;  // This db is not supposed to be replicated

      for (MySQLConfig slave : slaves)
      {
        // TODO: We can parallelize here among slaves (some kind of fork / join idea). This is easy to do
        //  And increases throughput, but we're still hurt by slow slaves

        // TODO: We can parallelize among partitions. However, this breaks the strict ordering property we get
        //  by consuming binlog events in order, so it makes it harder to checkpoint. But it shouldn't be
        //  too hard to overcome this by using an increasing low-watermark or something (or TCP-style sliding window)

        Connection conn = null;
        PreparedStatement stmt = null;
        try
        {
          conn = slave.getConnection();
          stmt = statementFor(conn, write, slave.serverId);

          for (Row row : write.rows)
          {
            List<Column> columns = row.getColumns();
            int idx = 1;

            for (Pair<String, ColumnType> columnPair : schema.columns)
            {
              switch (columnPair.second)
              {
                case INT:
                case TINYINT:
                case SMALLINT:
                case MEDIUMINT:
                case BIGINT:
                  stmt.setInt(idx, (Integer) columns.get(idx - 1).getValue());
                  break;
                case FLOAT:
                case DOUBLE:
                case DECIMAL:
                  stmt.setDouble(idx, (Double) columns.get(idx - 1).getValue());
                  break;
                case CHAR:
                case VARCHAR:
                  stmt.setString(idx, new String((byte[]) columns.get(idx - 1).getValue()));
                  break;
                case BLOB:
                case TINYBLOB:
                case MEDIUMBLOB:
                case LONGBLOB:
                case TEXT:
                  stmt.setBytes(idx, (byte[]) columns.get(idx - 1).getValue());
                  break;
                default:
                  throw new UnsupportedOperationException();
              }
              idx++;
            }

            stmt.execute();
            switch (write.operation)
            {
              case INSERT:
                success = stmt.getUpdateCount() > 0;
                break;
              case DELETE:
                success = stmt.getUpdateCount() == 0 || stmt.getUpdateCount() == 1;
                break;
            }
          }
        }
        catch (SQLException e)
        {
          e.printStackTrace();
          success = false;
        }
        finally
        {
          try
          {
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
          }
          catch (SQLException e)
          {
            // Okay
          }
        }
      }

      return success;
    }

    /**
     * Generates a checkpoint on the slaves of the current master status
     */
    private void generateCheckPoint(WriteEvent write)
    {
      Connection masterConn = null;
      Pair<String, Long> masterStatus = null;
      try
      {
        masterConn = _master.getConnection();
        masterStatus = BinlogUtils.showStatus(masterConn, BinlogUtils.InstanceState.MASTER);
      }
      catch (SQLException e)
      {
        LOG.error(e);
        return;
      }
      finally
      {
        try
        {
          if (masterConn != null) masterConn.close();
        }
        catch (SQLException e)
        {
          LOG.error(e);
        }
      }

      for (Map.Entry<String, List<MySQLConfig>> entry : _dbNameToSlaves.entrySet())
      {
        for (MySQLConfig slave : entry.getValue())
        {
          Connection conn = null;
          PreparedStatement stmt = null;
          try
          {
            conn = slave.getConnection();

            String binlogFileName = masterStatus.first;

            int idx = 1;
            stmt = conn.prepareStatement("INSERT INTO `mysql`.`rpl_checkpoint` VALUES(?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "`binlog_file`=values(`binlog_file`), " +
                    "`binlog_pos`=values(`binlog_pos`)");
            stmt.setInt(idx++, _master.serverId);
            stmt.setString(idx++, binlogFileName);
            stmt.setLong(idx, write.nextBinlogOffset);
            stmt.execute();

            LOG.info(String.format("Generated checkpoint on MASTER %s,%s", binlogFileName, write.nextBinlogOffset));
          }
          catch (SQLException e)
          {
            LOG.error(e);
          }
          finally
          {
            try
            {
              if (stmt != null) stmt.close();
              if (conn != null) conn.close();
            }
            catch (SQLException e)
            {
              LOG.error(e);
            }
          }
        }
      }
    }

    /**
     * Returns a prepared statement which can be executed to perform the actual replication.
     *
     * @param conn
     *  A slave connection
     * @param write
     *  A tuple of (operation, tableId, list of rows in txn)
     * @return
     *  A prepared statement that can be used to write each of the rows
     */
    private PreparedStatement statementFor(Connection conn, WriteEvent write, int serverId) throws SQLException
    {
      SchemaEvent schema = _schemas.get(write.tableId);
      String dbName = schema.dbName;
      String tableName = schema.tableName;
      List<Pair<String,ColumnType>> columns = schema.columns;

      StringBuilder sb = new StringBuilder();
      PreparedStatement stmt = null;
      switch (write.operation)
      {
        case INSERT:

          sb.append("INSERT INTO `").append(dbName).append("`.`").append(tableName).append("` (");
          if (!columns.isEmpty())
            sb.append("`").append(columns.get(0).first).append("`");
          for (int i = 1; i < columns.size(); i++)
            sb.append(", `").append(columns.get(i).first).append("`");

          sb.append(") VALUES(");
          if (!columns.isEmpty())
            sb.append("?");
          for (int i = 1; i < columns.size(); i++)
            sb.append(",?");

          sb.append(") ON DUPLICATE KEY UPDATE ");
          if (!columns.isEmpty())
            sb.append("`").append(columns.get(0).first).append("`=values(`").append(columns.get(0).first).append("`)");
          for (int i = 1; i < columns.size(); i++)
            sb.append(", `").append(columns.get(0).first).append("`=values(`").append(columns.get(0).first).append("`)");

          stmt = conn.prepareStatement(sb.toString());
          return stmt;

        case DELETE:

          sb.append("DELETE FROM `").append(dbName).append("`.`").append(tableName).append("` WHERE ");
          if (!columns.isEmpty())
            sb.append("`").append(columns.get(0).first).append("` = ?");
          for (int i = 1; i < columns.size(); i++)
            sb.append(" AND `").append(columns.get(i).first).append("` = ?");

          stmt = conn.prepareStatement(sb.toString());
          return stmt;

        default:

          throw new UnsupportedOperationException();
      }
    }
  }

  /** Encapsulates a configuration necessary to connect to MySQL */
  public static class MySQLConfig
  {
    private static final String ENCODING = "UTF-8";

    public String user;
    public String password;
    public String host;
    public int port;
    public int serverId;

    public MySQLConfig setUser(String user) { this.user = user; return this; }
    public MySQLConfig setPassword(String password) { this.password = password; return this; }
    public MySQLConfig setHost(String host) { this.host = host; return this; }
    public MySQLConfig setPort(int port) { this.port = port; return this; }
    public MySQLConfig setServerId(int serverId) { this.serverId = serverId; return this; }

    /**
     * The value of this can be used as a JDBC connection string
     */
    @Override
    public String toString()
    {
      try
      {
        return String.format("jdbc:mysql://%s:%d?user=%s&password=%s",
                             URLEncoder.encode(host, ENCODING),
                             port,
                             URLEncoder.encode(user, ENCODING),
                             URLEncoder.encode(password, ENCODING));
      }
      catch (UnsupportedEncodingException e)
      {
        return null;
      }
    }

    /** @return a connection to this MySQL instance */
    public Connection getConnection() throws SQLException
    {
      return DriverManager.getConnection(toString());
    }
  }

  private static class WriteEvent
  {
    Operation operation;
    Long tableId;
    Long nextBinlogOffset;
    List<Row> rows;

    public WriteEvent setOperation(Operation operation) { this.operation = operation; return this; }
    public WriteEvent setTableId(Long tableId) { this.tableId = tableId; return this; }
    public WriteEvent setNextBinlogOffset(Long nextBinlogOffset) { this.nextBinlogOffset = nextBinlogOffset; return this; }
    public WriteEvent setRows(List<Row> rows) { this.rows = rows; return this; }

    @Override
    public String toString()
    {
      return operation + " " + rows;
    }
  }

  private static class SchemaEvent
  {
    public String dbName;
    public String tableName;
    public List<Pair<String, ColumnType>> columns;

    public SchemaEvent setDBName(String dbName) { this.dbName = dbName; return this; }
    public SchemaEvent setTableName(String tableName) { this.tableName = tableName; return this; }
    public SchemaEvent setColumns(List<Pair<String, ColumnType>> columns) { this.columns = columns; return this; }
  }

//  public static void main(String[] args) throws Exception
//  {
//    BasicConfigurator.configure();
//
//    MySQLConfig master = new MySQLConfig().setHost("localhost").setPort(5535).setUser("root").setPassword("msandbox").setServerId(1);
//    List<MySQLConfig> slaves = new ArrayList<MySQLConfig>();
//    MySQLConfig slave = new MySQLConfig().setHost("localhost").setPort(5536).setUser("root").setPassword("msandbox").setServerId(2);
//    slaves.add(slave);
//    Map<String, List<MySQLConfig>> partitionToSlaves = new HashMap<String, List<MySQLConfig>>();
//    partitionToSlaves.put("test", slaves);
//
//    MySQLBinlogReplicator replicator = new MySQLBinlogReplicator(master, partitionToSlaves);
//    replicator.start();
//  }

  /**
   * Runs replicator with a JSON-encoded configuration file: E.g.
   *
   * {
   *   "master": {
   *     "host": "localhost",
   *     "port": 3306,
   *     "user": "test",
   *     "password": "test"
   *     "serverId": 1
   *   },
   *   "slaves": [
   *     {
   *       "host": "localhost",
   *       "port": 3307,
   *       "user": "test",
   *       "password": "test",
   *       "serverId": 2
   *     }
   *   ],
   *   "databases": {
   *     "test": [ "localhost:3307" ],
   *     "MyDB": [ "localhost:3307" ]
   *   }
   * }
   */
  public static void main(String[] args) throws Exception
  {
    BasicConfigurator.configure();

    Options options = new Options();
    options.addOption("c", "config", true, "JSON application config");

    CommandLineParser parser = new GnuParser();
    CommandLine commandLine = parser.parse(options, args);
    if (!commandLine.hasOption("c"))
      throw new IllegalStateException("Configuration file required");

    JsonNode config = new ObjectMapper().readTree(new FileInputStream(new File(commandLine.getOptionValue("c"))));

    MySQLConfig master = new MySQLConfig()
            .setHost(config.get("master").get("host").asText())
            .setPort(config.get("master").get("port").asInt())
            .setUser(config.get("master").get("user").asText())
            .setPassword(config.get("master").get("password").asText())
            .setServerId(config.get("master").get("serverId").asInt());

    Map<String, MySQLConfig> slaveHostPortToConfig = new HashMap<String, MySQLConfig>();
    for (JsonNode slaveNode : config.get("slaves"))
    {
      MySQLConfig slave = new MySQLConfig()
              .setHost(slaveNode.get("host").asText())
              .setPort(slaveNode.get("port").asInt())
              .setUser(slaveNode.get("user").asText())
              .setPassword(slaveNode.get("password").asText());
      slaveHostPortToConfig.put(slaveNode.get("host").asText() + ":" + slaveNode.get("port").asText(), slave);
    }

    Map<String, List<MySQLConfig>> dbToSlaves = new HashMap<String, List<MySQLConfig>>();
    Iterator<Map.Entry<String, JsonNode>> dbs = config.get("databases").getFields();
    while (dbs.hasNext())
    {
      Map.Entry<String, JsonNode> entry = dbs.next();
      List<MySQLConfig> slaves = new ArrayList<MySQLConfig>();
      for (JsonNode slaveHostPort : entry.getValue())
      {
        slaves.add(slaveHostPortToConfig.get(slaveHostPort.asText()));
      }
      dbToSlaves.put(entry.getKey(), slaves);
    }

    new MySQLBinlogReplicator(master, dbToSlaves).start();
  }
}
