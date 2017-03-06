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
package zipkin.dependencies.elasticsearch;

import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.elasticsearch.spark.rdd.api.java.JavaEsSpark;
import scala.Tuple2;
import zipkin.DependencyLink;
import zipkin.internal.Nullable;
import zipkin.internal.gson.stream.JsonReader;
import zipkin.internal.gson.stream.MalformedJsonException;

import static zipkin.internal.Util.checkNotNull;
import static zipkin.internal.Util.midnightUTC;

public final class ElasticsearchDependenciesJob {
  static final Logger logger = LogManager.getLogger(ElasticsearchDependenciesJob.class);

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    String index = getEnv("ES_INDEX", "zipkin");

    final Map<String, String> sparkProperties = new LinkedHashMap<>();

    Builder() {
      sparkProperties.put("spark.ui.enabled", "false");
      // don't die if there are no spans
      sparkProperties.put("es.index.read.missing.as.empty", "true");
      sparkProperties.put("es.nodes.wan.only", getEnv("ES_NODES_WAN_ONLY", "false"));
      // NOTE: unlike zipkin, this uses the http port
      sparkProperties.put("es.nodes", getEnv("ES_HOSTS", "127.0.0.1"));
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

    /** The index prefix to use when generating daily index names. Defaults to "zipkin" */
    public Builder index(String index) {
      this.index = checkNotNull(index, "index");
      return this;
    }

    /** Day (in epoch milliseconds) to process dependencies for. Defaults to today. */
    public Builder day(long day) {
      this.day = midnightUTC(day);
      return this;
    }

    /** Ensures that logging is setup. Particularly important when in cluster mode. */
    public Builder logInitializer(Runnable logInitializer) {
      this.logInitializer = checkNotNull(logInitializer, "logInitializer");
      return this;
    }

    public ElasticsearchDependenciesJob build() {
      return new ElasticsearchDependenciesJob(this);
    }
  }

  final String index;
  final long day;
  final String dateStamp;
  final SparkConf conf;
  @Nullable final Runnable logInitializer;

  ElasticsearchDependenciesJob(Builder builder) {
    this.index = builder.index;
    this.day = builder.day;
    String dateSeparator = getEnv("ES_DATE_SEPARATOR", "-");
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd".replace("-", dateSeparator));
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.dateStamp = df.format(new Date(builder.day));
    this.conf = new SparkConf(true)
        .setMaster(builder.sparkMaster)
        .setAppName(getClass().getName());
    if (builder.jars != null) conf.setJars(builder.jars);
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
    }
    this.logInitializer = builder.logInitializer;
  }

  public void run() {
    String bucket = index + "-" + dateStamp;

    logger.info("Processing spans from " + bucket + "/span");

    JavaSparkContext sc = new JavaSparkContext(conf);

    JavaRDD<Map<String, Object>> links = JavaEsSpark.esJsonRDD(sc, bucket + "/span")
        .groupBy(pair -> traceId(pair._2))
        .flatMapValues(new TraceIdAndJsonToDependencyLinks(logInitializer))
        .values()
        .mapToPair(link -> tuple2(tuple2(link.parent, link.child), link))
        .reduceByKey((l, r) -> DependencyLink.create(l.parent, l.child, l.callCount + r.callCount))
        .values()
        .map(ElasticsearchDependenciesJob::dependencyLinkJson);

    logger.info("Saving dependency links to " + bucket + "/dependencylink");
    JavaEsSpark.saveToEs(links, bucket + "/dependencylink",
        Collections.singletonMap("es.mapping.id", "id")); // allows overwriting the link
    logger.info("Done");
    sc.stop();
  }

  /**
   * Same as {@linkplain DependencyLink}, except it adds an ID field so the job can be re-run,
   * overwriting a prior run's value for the link.
   */
  static Map<String, Object> dependencyLinkJson(DependencyLink l) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", l.parent + "|" + l.child);
    result.put("parent", l.parent);
    result.put("child", l.child);
    result.put("callCount", l.callCount);
    return result;
  }

  private static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null ? result : defaultValue;
  }

  static String traceId(String json) throws IOException {
    JsonReader reader = new JsonReader(new StringReader(json));
    reader.beginObject();
    while (reader.hasNext()) {
      String nextName = reader.nextName();
      if (nextName.equals("traceId")) {
        return reader.nextString();
      } else {
        reader.skipValue();
      }
    }
    throw new MalformedJsonException("no traceId in " + json);
  }

  /** Added so the code is compilable against scala 2.10 (used in spark 1.6.2) */
  private static <T1, T2> Tuple2<T1, T2> tuple2(T1 v1, T2 v2) {
    return new Tuple2<>(v1, v2); // in scala 2.11+ Tuple.apply works naturally
  }
}
