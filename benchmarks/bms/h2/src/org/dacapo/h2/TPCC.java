package org.dacapo.h2;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.security.AccessController;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.derbyTesting.system.oe.client.Display;
import org.apache.derbyTesting.system.oe.client.Load;
import org.apache.derbyTesting.system.oe.client.MultiThreadSubmitter;
import org.apache.derbyTesting.system.oe.client.Operations;
import org.apache.derbyTesting.system.oe.client.Submitter;
import org.apache.derbyTesting.system.oe.load.ThreadInsert;
import org.apache.derbyTesting.system.oe.util.OERandom;

import org.dacapo.parser.Config;
import org.h2.tools.RunScript;
import org.h2.tools.Backup;
import org.h2.tools.Restore;
import org.h2.tools.Script;

public class TPCC {
  public final static int RETRY_LIMIT = 5;

  // h2 driver settings
  private final static String DRIVER_NAME = "org.h2.Driver";
  private final static String URL_BASE = "jdbc:h2:";
  private final static String DATABASE_NAME_DISK   = "testdb";
  private final static String DATABASE_NAME_MEMORY = "mem:testdb";
  private final static String CREATE_SUFFIX = "";

  private final static String USERNAME = "user";
  private final static String PASSWORD = "password";
  private final static String USER = "derby";
  private final static String PASS = "derby";
  private final static String BACKUP_NAME = "db.zip";
  private final static String DATABASE_DIRECTORY = "db";


  // default configuration for external testing of derby
  // database scale (see TPC-C documentation) number of terminals (clients) that
  // run transactions
  private final static int DEF_NUM_OF_TERMINALS = 2;
  // database scale (see TPC-C documentation)
  private final static short DEF_SCALE = 1;
  // number of transactions each terminal (client) runs
  private final static int DEF_TRANSACTIONS_PER_TERMINAL = 100;
  // number of warehoueses (see TPC-C documentation)
  private final static short DEF_NUM_OF_WAREHOUSES = 1;

  // Basic configurable items
  // number of threads to perform loading of the database
  private int loaderThreads = 1;

  // this loaderThreads seems determininstic and should be
  // to the number of CPU cores
  private short scale = DEF_SCALE;
  private int numberOfTerminals = DEF_NUM_OF_TERMINALS;
  private int transactionsPerTerminal = DEF_TRANSACTIONS_PER_TERMINAL;
  private short numberOfWarehouses = DEF_NUM_OF_WAREHOUSES;
  private boolean generate = false; // by default we use the pre-generated database
  private boolean inMemoryDB = false; // by default use a disk basked database
  private boolean reportPreIterationTimes = false;

  // OLTP runners
  private Connection[] connections;
  private Submitter[] submitters;
  private Display[] displays;
  private OERandom[] rands;

  private Config config;
  private File scratch;
  private boolean preserve;
  private String size;
  private Driver driver;
  private Properties properties;
  private Connection conn;

  private boolean firstIteration;

  // Location of the database, this specifies the directory (folder) on a file
  // system where the database is stored. This application must have be able to
  // create this directory (folder)
  private String database;

  private String createSuffix = CREATE_SUFFIX;

  // A random seed for initializing the database and the OLTP terminals.
  private final static long SEED = 897523978813691l;
  private final static int SEED_STEP = 100000;

  public static TPCC make(Config config, File scratch, Boolean preserve) throws Exception {
    return new TPCC(config, scratch, preserve.booleanValue());
  }

  public TPCC(Config config, File scratch, boolean preserve) throws Exception {
    this.config = config;
    this.scratch = scratch;
    this.preserve = preserve;
  }

  public void prepare(String size) throws Exception {
    this.firstIteration = true;
    this.size = size;

    configure();

    // make the database relative to the scratch location
    if (inMemoryDB)
      database = DATABASE_NAME_MEMORY;
    else
      database = new File(new File(scratch, "db"), DATABASE_NAME_DISK).toURI().toString();

    // seem to need to set this early and in the system properties
    Class.forName(DRIVER_NAME);
    driver = DriverManager.getDriver(URL_BASE + database);

    properties = (Properties) System.getProperties().clone();

    properties.setProperty(USERNAME, USER);
    properties.setProperty(PASSWORD, PASS);

    // make a seeded random number generator for each submitter
    rands = new OERandom[numberOfTerminals];

    // for the moment we are not interested displaying the results of each
    // transaction so we leave each entry in the displays array as a null
    displays = new Display[numberOfTerminals];

    // create a set of Submitter each with a Standard operations implementation
    connections = new Connection[numberOfTerminals];
    submitters = new Submitter[numberOfTerminals];
  }

  private void preIterationDiskDB() throws Exception {
    File dbDir = new File(scratch, DATABASE_DIRECTORY);

    // delete the database if it exists
    if (preserve && !firstIteration)
      deleteDatabase();

    if (firstIteration && this.generate) {

      // create the database
      createSchema();

      // generate data
      loadData();

      // generate primary indexes
      createIndexes();

      // generate foreign keys
      createConstraints();

      // close last connection returning database to a stable state
      closeConnection();

      // backup the database

      org.h2.tools.Backup.execute(new File(scratch, BACKUP_NAME)
          .getAbsolutePath(), dbDir.getAbsolutePath(), DATABASE_NAME_DISK, true);
      
      // subsequently we only restore
      firstIteration = false;
    } else {
      org.h2.tools.Restore.execute(new File(scratch, BACKUP_NAME)
          .getAbsolutePath(), dbDir.getAbsolutePath(), DATABASE_NAME_DISK, true);
    }
  }

  private void preIterationMemoryDB() throws Exception {
    // create the database
    createSchema();

    // generate the data
    loadData();

    // generate indexes
    createIndexes();

    // generate foreign keys
    createConstraints();
    
    // keep connection open so that database stays in memory
  }

  public void preIteration(String size) throws Exception {
    // we can't change size after the initial prepare(size)
    assert this.size.equalsIgnoreCase(size);

    long start = System.currentTimeMillis();

    if (inMemoryDB)
      preIterationMemoryDB();
    else
      preIterationDiskDB();

    // make sure we have the same seeds each run
    for (int i = 0; i < rands.length; i++) {
      rands[i] = new OERandom(SEED_STEP * i, SEED + SEED_STEP * i);
    }

    // create a Submitter for each thread, and then pass to
    // org.apache.derbyTesting.system.oe.client.MultiThreadSubmitter.multiRun
    for (int i = 0; i < submitters.length; i++) {
      connections[i] = makeConnection(false);

      Operations ops = new Operation(connections[i]);

      submitters[i] = new TPCCSubmitter(null, ops, rands[i], numberOfWarehouses);
    }

    long preGCTimeMillis = System.currentTimeMillis() - start;

    // clean up any hang-over from previous iterations
    System.gc();

    // Get elapsed time in milliseconds
    long elapsedTimeMillis = System.currentTimeMillis() - start;

    if (reportPreIterationTimes) {
      System.err.println("GC         time=" + (elapsedTimeMillis - preGCTimeMillis));
      System.err.println("Elapse     time=" + elapsedTimeMillis);
    }
  }

  public void iteration(String size) throws Exception {
    // we can't change size after the initial prepare(size)
    assert this.size.equalsIgnoreCase(size);

    MultiThreadSubmitter
        .multiRun(submitters, displays, transactionsPerTerminal);

    System.out.println();

    report(System.err);
  }

  public void postIteration(String size) throws Exception {
    // we can't change size after the initial prepare(size)
    assert this.size.equalsIgnoreCase(size);

    for (int i = 0; i < submitters.length; i++) {
      submitters[i] = null;
      connections[i].close();
      connections[i] = null;
    }
    
    if (!preserve || inMemoryDB)
      deleteDatabase();
  }

  public void cleanup() throws Exception {
    if (!preserve || inMemoryDB)
      deleteDatabase();
  }

  // ----------------------------------------------------------------------------------
  private void report(PrintStream os) {
    os.println("BEGIN: transaction count report");
    for (int i = 0; i < submitters.length; i++) {
      submitters[i].printReport(os);
    }
    os.println("END: transaction count report");
  }

  private void createSchema() throws Exception {
    // create schema
    runScript("schema.sql");
    runScript("delivery.sql");
  }

  private void createIndexes() throws Exception {
    runScript("primarykey.sql");
    runScript("index.sql");
  }

  private void createConstraints() throws Exception {
    // create key constraints
    runScript("foreignkey.sql");
  }

  private void loadData() throws Exception {

    // Use simple insert statements to insert data.
    // currently only this form of load is present, once we have
    // different implementations, the loading mechanism will need
    // to be configurable taking an option from the command line
    // arguments.
    DataSource ds = new TPCCDataSource(driver, database, properties);

    Load loader = new ThreadInsert(ds);
    loader.setSeed(SEED);
    loader.setupLoad(getConnection(), scale);
    if (loaderThreads > 0)
      loader.setThreadCount(loaderThreads);

    loader.populateAllTables();

    // Way to populate data is extensible. Any other implementation
    // of org.apache.derbyTesting.system.oe.client.Load can be used
    // to load data. configurable using the oe.load.insert property
    // that is defined in oe.properties
    // One extension would be to have an implementation that
    // uses bulkinsert vti to load data.

    return;
  }

  // helper function for getting and setting a connection for initial
  // setup of the database
  private Connection getConnection() throws SQLException {
    if (conn != null) {
      if (!conn.isClosed())
        return conn;
      conn = null;
    }
    return conn = makeConnection(true);
  }

  private void closeConnection() throws SQLException {
    if (conn != null) {
      if (!conn.isClosed()) {
        try {
          conn.commit();
        } finally {
          conn.close();
        }
      }
    }
    conn = null;
  }

  // helper functions to run sql schema and database creation scripts
  private void runScript(String scriptBase) throws Exception {

    String script = "org/apache/derbyTesting/system/oe/schema/" + scriptBase;
    int errorCount = runScript(script, "US-ASCII");
    assert errorCount == 0;
  }

  public int runScript(String resource, String encoding) throws Exception {

    URL sql = getTestResource(resource);

    assert sql != null;

    InputStream sqlIn = openTestResource(sql);
    Connection conn = getConnection();
    int numErrors = runScript(sqlIn, encoding);
    sqlIn.close();

    if (!conn.isClosed() && !conn.getAutoCommit())
      conn.commit();

    return numErrors;
  }

  public int runScript(InputStream script, String encoding) throws Exception {
    ResultSet results = RunScript.execute(getConnection(),
        new InputStreamReader(script));

    if (results != null)
      results.close();

    return 0;
  }

  // helper function for getting database setup and creation scripts
  private static URL getTestResource(final String name) {

    return (URL) AccessController
        .doPrivileged(new java.security.PrivilegedAction() {

          public Object run() {
            return TPCC.class.getClassLoader().getResource(name);

          }

        });
  }

  private static InputStream openTestResource(final URL url) throws Exception {
    return (InputStream) AccessController
        .doPrivileged(new java.security.PrivilegedExceptionAction() {

          public Object run() throws IOException {
            return url.openStream();

          }

        });
  }

  // construct a database connection
  private Connection makeConnection(boolean create) throws SQLException {
    Properties prop = properties;
    if (create) {
      prop = (Properties) properties.clone();
      // add create properties to the set of properties
      prop.setProperty("create", "true");
    }

    // return driver.connect(URL_BASE + getDatabaseName() +
    // (create?createSuffix:""), prop);
    return driver.connect(getDatabaseURLString(create), prop); // URL_BASE +
                                                               // getDatabaseName()
                                                               // +
                                                               // (create?createSuffix:""),
                                                               // prop);
  }

  // database name helper functions
  private String getDatabaseName() {
    return database;
  }

  // form the database url string
  private String getDatabaseURLString(boolean create) {
    return URL_BASE + getDatabaseName() + (create ? createSuffix : "");
  }

  // helper function for recursively deleting the database directory
  private boolean deleteDatabase() throws SQLException, URISyntaxException {
    if (inMemoryDB)
    {
      closeConnection();
      return true;
    } else
      return deleteDirectory(new File(new URI(database)));
  }

  private static boolean deleteDirectory(File path) {
    if (path.exists()) {
      File[] files = path.listFiles();
      for (int i = 0; i < files.length; i++) {
        if (files[i].isDirectory()) {
          deleteDirectory(files[i]);
        } else {
          files[i].delete();
        }
      }
    }
    return path.delete();
  }

  // helper function for interpreting the configuration data
  private void configure()
  {
    String[] args = config.preprocessArgs(size,scratch);

    int totalTx = this.numberOfTerminals * this.transactionsPerTerminal;
    for (int i = 0; i < args.length; i++)
    {
      if ("--numberOfTerminals".equalsIgnoreCase(args[i]))
      {
        this.numberOfTerminals = Integer.parseInt(args[++i]);
      } else if ("--totalTransactions".equalsIgnoreCase(args[i]))
      {
        totalTx = Integer.parseInt(args[++i]);
      } else if ("--scale".equalsIgnoreCase(args[i]))
      {
        this.scale = Short.parseShort(args[++i]);
      } else if ("--numberOfWarehouses".equalsIgnoreCase(args[i]))
      {
        this.numberOfWarehouses = Short.parseShort(args[++i]);
      } else if ("--generate".equalsIgnoreCase(args[i]))
      {
        this.generate = true;
      } else if ("--memory".equalsIgnoreCase(args[i]))
      {
        this.inMemoryDB = true;
      } else if ("--disk".equalsIgnoreCase(args[i]))
      {
        this.inMemoryDB = false;
      } else if ("--reportPreIterationTimes".equalsIgnoreCase(args[i]))
      {
        this.reportPreIterationTimes = true;
      } else if ("--createSuffix".equalsIgnoreCase(args[i]))
      {
    	this.createSuffix = args[++i];
      }
    }
    // calculate the transactions per terminals now that we know the 
    // total number to be executed and the number of terminals
    this.transactionsPerTerminal = totalTx / this.numberOfTerminals;
  }

  private File getBackupDir() {
    return new File(scratch, "backup");
  }

  private File getBackupFile(String fileName) {
    return new File(getBackupDir(), fileName);
  }

  private void backupData() throws Exception {
    getBackupDir().mkdirs();

    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("orderline.csv").getAbsolutePath()
        + "', 'SELECT * FROM ORDERLINE', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("neworders.csv").getAbsolutePath()
        + "', 'SELECT * FROM NEWORDERS', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("history.csv").getAbsolutePath()
        + "', 'SELECT * FROM HISTORY', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("orders.csv").getAbsolutePath()
        + "', 'SELECT * FROM ORDERS', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("customer.csv").getAbsolutePath()
        + "', 'SELECT * FROM CUSTOMER', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("stock.csv").getAbsolutePath()
        + "', 'SELECT * FROM STOCK', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("district.csv").getAbsolutePath()
        + "', 'SELECT * FROM DISTRICT', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("item.csv").getAbsolutePath()
        + "', 'SELECT * FROM ITEM', 'UTF-8');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream("CALL CSVWRITE('"
        + getBackupFile("warehouse.csv").getAbsolutePath()
        + "', 'SELECT * FROM WAREHOUSE', 'UTF-8');"), "US-ASCII");
  }

  private void restoreData() throws Exception {
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO WAREHOUSE SELECT * FROM CSVREAD('"
            + getBackupFile("warehouse.csv").getAbsolutePath() + "');"),
        "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO ITEM SELECT * FROM CSVREAD('"
            + getBackupFile("item.csv").getAbsolutePath() + "');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO DISTRICT SELECT * FROM CSVREAD('"
            + getBackupFile("district.csv").getAbsolutePath() + "');"),
        "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO STOCK SELECT * FROM CSVREAD('"
            + getBackupFile("stock.csv").getAbsolutePath() + "');"), "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO CUSTOMER SELECT * FROM CSVREAD('"
            + getBackupFile("customer.csv").getAbsolutePath() + "');"),
        "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO ORDERS SELECT * FROM CSVREAD('"
            + getBackupFile("orders.csv").getAbsolutePath() + "');"),
        "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO HISTORY SELECT * FROM CSVREAD('"
            + getBackupFile("history.csv").getAbsolutePath() + "');"),
        "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO NEWORDERS SELECT * FROM CSVREAD('"
            + getBackupFile("neworders.csv").getAbsolutePath() + "');"),
        "US-ASCII");
    runScript(new java.io.StringBufferInputStream(
        "INSERT INTO ORDERLINE SELECT * FROM CSVREAD('"
            + getBackupFile("orderline.csv").getAbsolutePath() + "');"),
        "US-ASCII");
  }

}