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
package zipkin.dependencies.elasticsearch;

import com.google.common.collect.ImmutableMap;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.elasticsearch.spark.rdd.api.java.JavaEsSpark;
import scala.Tuple2;
import zipkin.Codec;
import zipkin.DependencyLink;
import zipkin.Span;
import zipkin.internal.DependencyLinkSpan;
import zipkin.internal.DependencyLinker;
import zipkin.internal.MergeById;
import zipkin.internal.moshi.JsonAdapter;
import zipkin.internal.moshi.Moshi;

import static zipkin.internal.Util.checkNotNull;
import static zipkin.internal.Util.midnightUTC;

public final class ElasticsearchDependenciesJob {

  /** Runs with defaults, starting today */
  public static void main(String[] args) {
    new ElasticsearchDependenciesJob(new Builder()).run();
  }

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
        "es.nodes", getEnv("ES_NODES", "127.0.0.1")
    );

    // local[*] master lets us run & test the job locally without setting a Spark cluster
    String sparkMaster = getEnv("SPARK_MASTER", "local[*]");

    // By default the job only works on traces whose first timestamp is today
    long day = midnightUTC(System.currentTimeMillis());

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

  static final JsonAdapter<TraceId> traceIdReader =
      new Moshi.Builder().build().adapter(TraceId.class);

  static final class TraceId {
    String traceId;
  }

  ElasticsearchDependenciesJob(Builder builder) {
    this.index = builder.index;
    this.day = builder.day;
    this.dateStamp = new SimpleDateFormat("yyyy-MM-dd").format(new Date(builder.day));
    this.conf = new SparkConf(true)
        .setMaster(builder.sparkMaster)
        .setAppName(getClass().getName());
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
    }
  }

  public void run() {
    String bucket = index + "-" + dateStamp;

    System.out.println("Processing spans from " + bucket + "/span");

    JavaSparkContext sc = new JavaSparkContext(conf);
    JavaRDD<Map<String, Object>> links = JavaEsSpark.esJsonRDD(sc, bucket + "/span")
        .groupBy(pair -> traceIdReader.fromJson(pair._2).traceId)
        .flatMap(pair -> toLinks(pair._2))
        .mapToPair(link -> Tuple2.apply(Tuple2.apply(link.parent, link.child), link))
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
    List<Span> spans = new LinkedList<>();
    for (Tuple2<String, String> row : rows) {
      spans.add(Codec.JSON.readSpan(row._2.getBytes()));
    }
    List<DependencyLinkSpan> linkSpans = new LinkedList<>();
    for (Span s : MergeById.apply(spans)) {
      linkSpans.add(DependencyLinkSpan.from(s));
    }
    return new DependencyLinker().putTrace(linkSpans.iterator()).link();
  }

  private static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null ? result : defaultValue;
  }
}
