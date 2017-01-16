/**
 * Copyright 2016-2017 The OpenZipkin Authors
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
package zipkin.dependencies.cassandra;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.spark.connector.cql.CassandraConnector;
import com.datastax.spark.connector.japi.CassandraRow;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.runtime.AbstractFunction1;
import zipkin.Codec;
import zipkin.DependencyLink;
import zipkin.Span;
import zipkin.internal.Dependencies;
import zipkin.internal.DependencyLinker;
import zipkin.internal.GroupByTraceId;

import static com.datastax.spark.connector.japi.CassandraJavaUtil.javaFunctions;
import static zipkin.internal.ApplyTimestampAndDuration.guessTimestamp;
import static zipkin.internal.Util.checkNotNull;
import static zipkin.internal.Util.midnightUTC;

public final class CassandraDependenciesJob {
  static final Logger logger = LoggerFactory.getLogger(CassandraDependenciesJob.class);

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    String keyspace = getEnv("CASSANDRA_KEYSPACE", "zipkin");
    /** Comma separated list of hosts / IPs part of Cassandra cluster. Defaults to localhost */
    String contactPoints = getEnv("CASSANDRA_CONTACT_POINTS", "localhost");

    Map<String, String> sparkProperties = ImmutableMap.of(
        "spark.ui.enabled", "false",
        "spark.cassandra.connection.host", parseHosts(contactPoints),
        "spark.cassandra.connection.port", parsePort(contactPoints),
        "spark.cassandra.auth.username", getEnv("CASSANDRA_USERNAME", ""),
        "spark.cassandra.auth.password", getEnv("CASSANDRA_PASSWORD", "")
    );

    // local[*] master lets us run & test the job locally without setting a Spark cluster
    String sparkMaster = getEnv("SPARK_MASTER", "local[*]");
    // needed when not in local mode
    String[] jars;

    // By default the job only works on traces whose first timestamp is today
    long day = midnightUTC(System.currentTimeMillis());

    /** When set, this indicates which jars to distribute to the cluster. */
    public Builder jars(String... jars) {
      this.jars = jars;
      return this;
    }

    /** Keyspace to store dependency rowsToLinks. Defaults to "zipkin" */
    public Builder keyspace(String keyspace) {
      this.keyspace = checkNotNull(keyspace, "keyspace");
      return this;
    }

    /** Day (in epoch milliseconds) to process dependencies for. Defaults to today. */
    public Builder day(long day) {
      this.day = midnightUTC(day);
      return this;
    }

    public CassandraDependenciesJob build() {
      return new CassandraDependenciesJob(this);
    }

    Builder() {
    }
  }

  private static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null ? result : defaultValue;
  }

  final String keyspace;
  final long day;
  final String dateStamp;
  final SparkConf conf;

  CassandraDependenciesJob(Builder builder) {
    this.keyspace = builder.keyspace;
    this.day = builder.day;
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.dateStamp = df.format(new Date(builder.day));
    this.conf = new SparkConf(true)
        .setMaster(builder.sparkMaster)
        .setAppName(getClass().getName());
    if (builder.jars != null) conf.setJars(builder.jars);
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
    }
  }

  public void run() {
    long microsLower = day * 1000;
    long microsUpper = (day * 1000) + TimeUnit.DAYS.toMicros(1) - 1;

    System.out.printf("Running Dependencies job for %s: %s ≤ Span.timestamp %s%n", dateStamp,
        microsLower, microsUpper);

    SparkContext sc = new SparkContext(conf);

    List<DependencyLink> links = javaFunctions(sc).cassandraTable(keyspace, "traces")
        .spanBy(r -> r.getLong("trace_id"), Long.class)
        .flatMap(pair -> toLinks(microsLower, microsUpper, pair._2))
        .mapToPair(link -> tuple2(tuple2(link.parent, link.child), link))
        .reduceByKey((l, r) -> DependencyLink.create(l.parent, l.child, l.callCount + r.callCount))
        .values().collect();

    sc.stop();

    saveToCassandra(links);
    System.out.println("Dependencies: " + links);
  }

  // static to avoid spark trying to serialize the enclosing class
  static Iterable<DependencyLink> toLinks(long startTs, long endTs, Iterable<CassandraRow> rows) {
    List<Span> sameTraceId = new LinkedList<>();
    for (CassandraRow row : rows) {
      try {
        sameTraceId.add(Codec.THRIFT.readSpan(row.getBytes("span")));
      } catch (RuntimeException e) {
        logger.warn(String.format(
            "Unable to decode span from traces where trace_id=%s and ts=%s and span_name='%s'",
            row.getLong("trace_id"), row.getDate("ts").getTime(), row.getString("span_name")), e);
      }
    }
    DependencyLinker linker = new DependencyLinker();
    for (List<Span> trace : GroupByTraceId.apply(sameTraceId, true, true)) {
      // check to see if the trace is within the interval
      Long timestamp = guessTimestamp(trace.get(0));
      if (timestamp == null ||
          timestamp < startTs ||
          timestamp > endTs) {
        continue;
      }
      linker.putTrace(trace);
    }
    return linker.link();
  }

  void saveToCassandra(List<DependencyLink> links) {
    Dependencies thrift = Dependencies.create(day, day /** ignored */, links);
    ByteBuffer blob = thrift.toThrift();

    CassandraConnector.apply(conf).withSessionDo(new AbstractFunction1<Session, Void>() {
      @Override public Void apply(Session session) {
        session.execute(QueryBuilder.insertInto(keyspace, "dependencies")
            .value("day", new Date(day))
            .value("dependencies", blob)
        );
        return null;
      }
    });
    System.out.println("Saved with day=" + dateStamp);
  }

  static String parseHosts(String contactPoints) {
    List<String> result = new LinkedList<>();
    for (String contactPoint : contactPoints.split(",")) {
      HostAndPort parsed = HostAndPort.fromString(contactPoint);
      result.add(parsed.getHostText());
    }
    return Joiner.on(',').join(result);
  }

  /** Returns the consistent port across all contact points or 9042 */
  static String parsePort(String contactPoints) {
    Set<Integer> ports = Sets.newLinkedHashSet();
    for (String contactPoint : contactPoints.split(",")) {
      HostAndPort parsed = HostAndPort.fromString(contactPoint);
      ports.add(parsed.getPortOrDefault(9042));
    }
    return ports.size() == 1 ? String.valueOf(ports.iterator().next()) : "9042";
  }

  /** Added so the code is compilable against scala 2.10 (used in spark 1.6.2) */
  private static <T1, T2> Tuple2<T1, T2> tuple2(T1 v1, T2 v2) {
    return new Tuple2<>(v1, v2); // in scala 2.11+ Tuple.apply works naturally
  }
}
