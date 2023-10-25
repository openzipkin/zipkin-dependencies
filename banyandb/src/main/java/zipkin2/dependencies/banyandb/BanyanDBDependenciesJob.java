/*
 * Copyright 2016-2023 The OpenZipkin Authors
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

package zipkin2.dependencies.banyandb;

import org.apache.skywalking.banyandb.v1.client.BanyanDBClient;
import org.apache.skywalking.banyandb.v1.client.Element;
import org.apache.skywalking.banyandb.v1.client.MeasureBulkWriteProcessor;
import org.apache.skywalking.banyandb.v1.client.MeasureWrite;
import org.apache.skywalking.banyandb.v1.client.TagAndValue;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.reflect.ClassTag$;
import zipkin2.DependencyLink;

import javax.annotation.Nullable;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static zipkin2.internal.DateUtil.midnightUTC;

public class BanyanDBDependenciesJob {
  static final Charset UTF_8 = Charset.forName("UTF-8");

  private static final Logger log = LoggerFactory.getLogger(BanyanDBDependenciesJob.class);

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    String host = getEnv("BANYANDB_HOST", "127.0.0.1");
    String port = getEnv("BANYANDB_PORT", "17912");

    final Map<String, String> sparkProperties = new LinkedHashMap<>();

    Builder() {
      sparkProperties.put("spark.ui.enabled", "false");
    }

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

    public Builder host(String hosts) {
      this.host = checkNotNull(hosts, "host");
      return this;
    }

    public Builder port(String hosts) {
      this.host = checkNotNull(hosts, "port");
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

    public BanyanDBDependenciesJob build() {
      return new BanyanDBDependenciesJob(this);
    }
  }

  private static String getSystemPropertyAsFileResource(String key) {
    String prop = System.getProperty(key, "");
    return prop != null && !prop.isEmpty() ? "file:" + prop : prop;
  }

  final long day;
  final String dateStamp;
  final SparkConf conf;
  @Nullable
  final Runnable logInitializer;
  final String dbAddress;

  BanyanDBDependenciesJob(Builder builder) {
    SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.day = builder.day;
    this.dateStamp = df.format(new Date(builder.day));
    this.conf = new SparkConf(true).setMaster(builder.sparkMaster).setAppName(getClass().getName());
    if (builder.jars != null) conf.setJars(builder.jars);
    this.dbAddress = builder.host + ":" + builder.port;
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
      log.debug("Spark conf properties: {}={}", entry.getKey(), entry.getValue());
    }
    this.logInitializer = builder.logInitializer;
  }

  public void run() {
    log.info("Processing spans from {}", this.dateStamp);
    JavaSparkContext sc = new JavaSparkContext(conf);
    try {
      final long startMilli = midnightUTC(this.day);
      final long endMilli = startMilli + TimeUnit.DAYS.toMillis(1) - 1;
      final List<DependencyLink> dependencyLinks = JavaRDD.fromRDD(new BanyanDBRDD(sc.sc(), this.dbAddress, startMilli, endMilli, 10), ClassTag$.MODULE$.apply(Element.class))
          .groupBy(e -> e.getTagValue("trace_id"))
          .flatMapValues(new BanyanDBTraceConvertor())
          .values()
          .mapToPair(l -> Tuple2.apply(Tuple2.apply(l.parent(), l.child()), l))
          .reduceByKey((l, r) -> DependencyLink.newBuilder()
              .parent(l.parent())
              .child(l.child())
              .callCount(l.callCount() + r.callCount())
              .errorCount(l.errorCount() + r.errorCount())
              .build())
          .values()
          .collect();

      saveLinks(dependencyLinks);
    } catch (Throwable e) {
      log.error("Processing failure", e);
    } finally {
      sc.stop();
    }
  }

  private void saveLinks(List<DependencyLink> links) throws Exception {
    final BanyanDBClient client = new BanyanDBClient(this.dbAddress);
    try {
      client.connect();
      client.findMeasure("measure-default", "zipkin_dependency_minute");
      final MeasureBulkWriteProcessor processor = client.buildMeasureWriteProcessor(links.size(), 1, 1);
      for (DependencyLink link : links) {
        final MeasureWrite writer = client.createMeasureWrite("measure-default", "zipkin_dependency_minute", this.day);
        writer.tag("analyze_day", TagAndValue.longTagValue(this.day))
            .tag("parent", TagAndValue.stringTagValue(link.parent()))
            .tag("child", TagAndValue.stringTagValue(link.child()))
            .field("call_count", TagAndValue.longFieldValue(link.callCount()))
            .field("error_count", TagAndValue.longFieldValue(link.errorCount()));
        processor.add(writer);
      }

      Thread.sleep(TimeUnit.SECONDS.toMillis(5));
      processor.close();
    } finally {
      client.close();
    }
  }

  private static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null && !result.isEmpty() ? result : defaultValue;
  }

}
