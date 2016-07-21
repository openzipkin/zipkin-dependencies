/**
 * Copyright 2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.dependencies.mysql;

import com.google.common.collect.ImmutableMap;
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
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.execution.datasources.jdbc.DefaultSource;
import scala.Tuple2;
import zipkin.DependencyLink;
import zipkin.internal.DependencyLinker;

import static zipkin.internal.Util.checkNotNull;
import static zipkin.internal.Util.midnightUTC;

public final class MySQLDependenciesJob {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    Map<String, String> sparkProperties = ImmutableMap.of(
        "spark.ui.enabled", "false"
    );

    String db = getEnv("MYSQL_DB", "zipkin");
    String host = getEnv("MYSQL_HOST", "localhost");
    int port = Integer.parseInt(getEnv("MYSQL_TCP_PORT", "3306"));
    String user = getEnv("MYSQL_USER", "");
    String password = getEnv("MYSQL_PASS", "");
    int maxConnections = Integer.parseInt(getEnv("MYSQL_MAX_CONNECTIONS", "10"));
    boolean useSsl = Boolean.parseBoolean(getEnv("MYSQL_USE_SSL", "false"));

    // local[*] master lets us run & test the job locally without setting a Spark cluster
    String sparkMaster = getEnv("SPARK_MASTER", "local[*]");

    // By default the job only works on traces whose first timestamp is today
    long day = midnightUTC(System.currentTimeMillis());

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

    public MySQLDependenciesJob build() {
      return new MySQLDependenciesJob(this);
    }

    Builder() {
    }
  }

  final String db;
  final long day;
  final String dateStamp;
  final String url;
  final String user;
  final String password;
  final SparkConf conf;

  MySQLDependenciesJob(Builder builder) {
    this.db = builder.db;
    this.day = builder.day;
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.dateStamp = df.format(new Date(builder.day));
    this.url = new StringBuilder("jdbc:mysql://")
        .append(builder.host).append(":").append(builder.port)
        .append("/").append(builder.db)
        .append("?autoReconnect=true")
        .append("&useSSL=").append(builder.useSsl).toString();
    this.user = builder.user;
    this.password = builder.password;
    this.conf = new SparkConf(true)
        .setMaster(builder.sparkMaster)
        .setAppName(getClass().getName());
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
    }
  }

  public void run() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("driver", org.mariadb.jdbc.Driver.class.getName()); // prevents shade from skipping
    options.put("url", url);
    options.put("user", user);
    options.put("password", password);

    long microsLower = day * 1000;
    long microsUpper = (day * 1000) + TimeUnit.DAYS.toMicros(1) - 1;

    String linksQuery = String.format(
        "select distinct s.trace_id, s.parent_id, s.id, a.a_key, a.endpoint_service_name " +
            "from zipkin_spans s left outer join zipkin_annotations a on " +
            "  (s.trace_id = a.trace_id and s.id = a.span_id and a.a_key in ('ca', 'sr', 'sa'))" +
            "where s.start_ts between %s and %s group by s.trace_id, s.id, a.a_key",
        microsLower, microsUpper);

    options.put("dbtable", "(" + linksQuery + ") as link_spans");

    System.out.printf("Running Dependencies job for %s: start_ts between %s and %s%n", dateStamp,
        microsLower, microsUpper);

    JavaSparkContext sc = new JavaSparkContext(conf);

    List<DependencyLink> links = new SQLContext(sc).read().format(DefaultSource.class.getName())
        .options(options).load()
        .toJavaRDD()
        .groupBy(r -> r.getLong(0))
        .flatMapValues(rows -> {
          DependencyLinker linker = new DependencyLinker();
          linker.putTrace(new DependencyLinkSpanIterator(rows.iterator()));
          return linker.link();
        })
        .values()
        .mapToPair(link -> Tuple2.apply(Tuple2.apply(link.parent, link.child), link))
        .reduceByKey((l, r) -> DependencyLink.create(l.parent, l.child, l.callCount + r.callCount))
        .values().collect();

    sc.stop();

    saveToMySQL(links);
    System.out.println("Dependencies: " + links);
  }

  void saveToMySQL(List<DependencyLink> links) {
    try (Connection con = DriverManager.getConnection(url, user, password)) {
      PreparedStatement replace = con.prepareStatement(
              "REPLACE INTO zipkin_dependencies (day, parent, child, call_count) VALUES (?,?,?,?)");
      for (DependencyLink link : links) {
        replace.setDate(1, new java.sql.Date(day));
        replace.setString(2, link.parent);
        replace.setString(3, link.child);
        replace.setLong(4, link.callCount);
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
