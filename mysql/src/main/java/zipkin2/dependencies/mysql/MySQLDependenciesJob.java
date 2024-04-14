/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import zipkin2.DependencyLink;

import static com.google.common.base.Preconditions.checkNotNull;
import static zipkin2.internal.DateUtil.midnightUTC;

public final class MySQLDependenciesJob {
  static final Logger log = LoggerFactory.getLogger(MySQLDependenciesJob.class);

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    Map<String, String> sparkProperties = new LinkedHashMap<>();

    String db = getEnv("MYSQL_DB", "zipkin");
    String host = getEnv("MYSQL_HOST", "localhost");
    int port = Integer.parseInt(getEnv("MYSQL_TCP_PORT", "3306"));
    String user = getEnv("MYSQL_USER", "");
    String password = getEnv("MYSQL_PASS", "");
    int maxConnections = Integer.parseInt(getEnv("MYSQL_MAX_CONNECTIONS", "10"));
    boolean useSsl = Boolean.parseBoolean(getEnv("MYSQL_USE_SSL", "false"));

    // local[*] master lets us run & test the job locally without setting a Spark cluster
    String sparkMaster = getEnv("SPARK_MASTER", "local[*]");
    // needed when not in local mode
    String[] jars;
    Runnable logInitializer;

    // By default the job only works on traces whose first timestamp is today
    long day = midnightUTC(System.currentTimeMillis());

    /** When set, this indicates which jars to distribute to the cluster. */
    public Builder jars(String... jars) {
      this.jars = jars;
      return this;
    }

    /** The database to use. Defaults to "zipkin" */
    public Builder db(String db) {
      this.db = checkNotNull(db, "db");
      return this;
    }

    /** Defaults to localhost */
    public Builder host(String host) {
      this.host = checkNotNull(host, "host");
      return this;
    }

    /** Defaults to 3306 */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /** MySQL authentication, which defaults to empty string. */
    public Builder user(String user) {
      this.user = checkNotNull(user, "user");
      return this;
    }

    /** MySQL authentication, which defaults to empty string. */
    public Builder password(String password) {
      this.password = checkNotNull(password, "password");
      return this;
    }

    /** Maximum concurrent connections, defaults to 10 */
    public Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    /**
     * Requires `javax.net.ssl.trustStore` and `javax.net.ssl.trustStorePassword`, defaults to
     * false
     */
    public Builder useSsl(boolean useSsl) {
      this.useSsl = useSsl;
      return this;
    }

    /** Day (in epoch milliseconds) to process dependencies for. Defaults to today. */
    public Builder day(long day) {
      this.day = midnightUTC(day);
      return this;
    }

    /** Extending more configuration of spark. */
    public Builder conf(Map<String, String> conf) {
      sparkProperties.putAll(conf);
      return this;
    }

    /** Ensures that logging is setup. Particularly important when in cluster mode. */
    public Builder logInitializer(Runnable logInitializer) {
      this.logInitializer = checkNotNull(logInitializer, "logInitializer");
      return this;
    }

    public MySQLDependenciesJob build() {
      return new MySQLDependenciesJob(this);
    }

    Builder() {
      sparkProperties.put("spark.ui.enabled", "false");
    }
  }

  final long day;
  final String dateStamp;
  final String url;
  final String user;
  final String password;
  final SparkConf conf;
  @Nullable final Runnable logInitializer;

  MySQLDependenciesJob(Builder builder) {
    this.day = builder.day;
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.dateStamp = df.format(new Date(builder.day));
    this.url = new StringBuilder("jdbc:mysql://")
        .append(builder.host).append(":").append(builder.port)
        .append("/").append(builder.db)
        .append("?permitMysqlScheme") // spark doesn't understand "mariadb"
        .append("&autoReconnect=true")
        .append("&useSSL=").append(builder.useSsl).toString();
    this.user = builder.user;
    this.password = builder.password;
    this.conf = new SparkConf(true)
        .setMaster(builder.sparkMaster)
        .setAppName(getClass().getName());
    if (builder.jars != null) conf.setJars(builder.jars);
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
      log.debug("Spark conf properties: {}={}", entry.getKey(), entry.getValue());
    }
    this.logInitializer = builder.logInitializer;
  }

  public void run() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("driver", org.mariadb.jdbc.Driver.class.getName()); // prevents shade from skipping
    options.put("url", url);
    options.put("user", user);
    options.put("password", password);

    boolean hasTraceIdHigh = hasTraceIdHigh();
    Function<Row, Long> rowTraceId = r -> r.getLong(hasTraceIdHigh ? 1 : 0); // trace_id

    long microsLower = day * 1000;
    long microsUpper = (day * 1000) + TimeUnit.DAYS.toMicros(1) - 1;

    String fields = "s.trace_id, s.parent_id, s.id, a.a_key, a.endpoint_service_name, a.a_type";
    if (hasTraceIdHigh) fields = "s.trace_id_high, " + fields;
    String groupByFields = fields.replace("s.parent_id, ", "");
    String linksQuery = String.format(
        "select distinct %s "+
            "from zipkin_spans s left outer join zipkin_annotations a on " +
            "  (s.trace_id = a.trace_id and s.id = a.span_id " +
            "     and a.a_key in ('lc', 'ca', 'cs', 'sa', 'sr', 'ma', 'ms', 'mr', 'error')) " +
            "where s.start_ts between %s and %s group by %s",
        fields, microsLower, microsUpper, groupByFields);

    options.put("dbtable", "(" + linksQuery + ") as link_spans");

    log.info("Running Dependencies job for {}: start_ts between {} and {}", dateStamp, microsLower,
        microsUpper);

    JavaSparkContext sc = new JavaSparkContext(conf);

    List<DependencyLink> links = new SQLContext(sc).read()
        .format("org.apache.spark.sql.execution.datasources.jdbc.JdbcRelationProvider")
        .options(options)
        .load()
        .toJavaRDD()
        .groupBy(rowTraceId)
        .flatMapValues(new RowsToDependencyLinks(logInitializer, hasTraceIdHigh))
        .values()
        .mapToPair(l -> Tuple2.apply(Tuple2.apply(l.parent(), l.child()), l))
        .reduceByKey((l, r) -> DependencyLink.newBuilder()
          .parent(l.parent())
          .child(l.child())
          .callCount(l.callCount() + r.callCount())
          .errorCount(l.errorCount() + r.errorCount())
          .build())
        .values().collect();

    sc.stop();

    log.info("Saving with day=" + dateStamp);
    saveToMySQL(links);
    log.info("Done");
  }

  private boolean hasTraceIdHigh() {
    boolean hasTraceIdHigh;
    try (Connection connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("select trace_id_high from zipkin_spans limit 1");
      hasTraceIdHigh = true;
    } catch (SQLException e) {
      if (!e.getMessage().contains("trace_id_high")) throw new RuntimeException(e);
      log.warn("zipkin_spans.trace_id_high doesn't exist, so 128-bit trace ids are not supported.");
      hasTraceIdHigh = false;
    }
    return hasTraceIdHigh;
  }

  void saveToMySQL(List<DependencyLink> links) {
    try (Connection con = DriverManager.getConnection(url, user, password)) {
      PreparedStatement replace = con.prepareStatement(
              "REPLACE INTO zipkin_dependencies (day, parent, child, call_count, error_count) VALUES (?,?,?,?,?)");
      for (DependencyLink link : links) {
        replace.setDate(1, new java.sql.Date(day));
        replace.setString(2, link.parent());
        replace.setString(3, link.child());
        replace.setLong(4, link.callCount());
        replace.setLong(5, link.errorCount());
        replace.executeUpdate();
      }
    } catch (SQLException e) {
      throw new RuntimeException("Could not save links " + links, e);
    }
  }

  private static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null ? result : defaultValue;
  }
}
