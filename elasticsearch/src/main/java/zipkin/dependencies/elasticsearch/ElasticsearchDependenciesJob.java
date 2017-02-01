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

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.elasticsearch.spark.rdd.api.java.JavaEsSpark;
import scala.Tuple2;
import zipkin.Codec;
import zipkin.DependencyLink;
import zipkin.Span;
import zipkin.internal.DependencyLinker;
import zipkin.internal.GroupByTraceId;
import zipkin.internal.gson.stream.JsonReader;
import zipkin.internal.gson.stream.MalformedJsonException;

import static zipkin.internal.Util.checkNotNull;
import static zipkin.internal.Util.midnightUTC;

public final class ElasticsearchDependenciesJob {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    String index = getEnv("ES_INDEX", "zipkin");

    Map<String, String> sparkProperties = ImmutableMap.of(
        "spark.ui.enabled", "false",
        // avoids strange class not found bug on Logger.setLevel
        "spark.akka.logLifecycleEvents", "true",
        // don't die if there are no spans
        "es.index.read.missing.as.empty", "true",
        "es.nodes.wan.only", getEnv("ES_NODES_WAN_ONLY", "false"),
        // NOTE: unlike zipkin, this uses the http port
        "es.nodes", getEnv("ES_HOSTS", "127.0.0.1")
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

    public ElasticsearchDependenciesJob build() {
      return new ElasticsearchDependenciesJob(this);
    }

    Builder() {
    }
  }

  final String index;
  final long day;
  final String dateStamp;
  final SparkConf conf;

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
  }

  public void run() {
    String bucket = index + "-" + dateStamp;

    System.out.println("Processing spans from " + bucket + "/span");

    JavaSparkContext sc = new JavaSparkContext(conf);
    JavaRDD<Map<String, Object>> links = JavaEsSpark.esJsonRDD(sc, bucket + "/span")
        .groupBy(pair -> traceId(pair._2))
        .flatMap(pair -> toLinks(pair._2))
        .mapToPair(link -> tuple2(tuple2(link.parent, link.child), link))
        .reduceByKey((l, r) -> DependencyLink.create(l.parent, l.child, l.callCount + r.callCount))
        .values()
        .map(l -> ImmutableMap.<String, Object>of(
            "parent", l.parent,
            "child", l.child,
            "id", l.parent + "|" + l.child,
            "callCount", l.callCount
        ));

    System.out.println("Saving dependency links to " + bucket + "/dependencylink");
    JavaEsSpark.saveToEs(links, bucket + "/dependencylink",
        ImmutableMap.of("es.mapping.id", "id")); // unique id, which allows overwriting the link
    System.out.println("Dependencies: " + links.collect());
    sc.stop();
  }

  // static to avoid spark trying to serialize the enclosing class
  static Iterable<DependencyLink> toLinks(Iterable<Tuple2<String, String>> rows) {
    List<Span> sameTraceId = new LinkedList<>();
    for (Tuple2<String, String> row : rows) {
      sameTraceId.add(Codec.JSON.readSpan(row._2.getBytes()));
    }
    DependencyLinker linker = new DependencyLinker();
    for (List<Span> trace : GroupByTraceId.apply(sameTraceId, true, true)) {
      linker.putTrace(trace);
    }
    return linker.link();
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
