/*
 * Copyright 2016-2019 The OpenZipkin Authors
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
package zipkin2.dependencies.elasticsearch;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nullable;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.elasticsearch.spark.rdd.api.java.JavaEsSpark;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import zipkin2.DependencyLink;
import zipkin2.codec.SpanBytesDecoder;

import static com.google.common.base.Preconditions.checkNotNull;
import static zipkin2.internal.DateUtil.midnightUTC;

public final class ElasticsearchDependenciesJob {
  static final Charset UTF_8 = Charset.forName("UTF-8");

  private static final Logger log = LoggerFactory.getLogger(ElasticsearchDependenciesJob.class);

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    String index = getEnv("ES_INDEX", "zipkin");
    String hosts = getEnv("ES_HOSTS", "127.0.0.1");
    String username = getEnv("ES_USERNAME", null);
    String password = getEnv("ES_PASSWORD", null);

    final Map<String, String> sparkProperties = new LinkedHashMap<>();

    Builder() {
      sparkProperties.put("spark.ui.enabled", "false");
      // don't die if there are no spans
      sparkProperties.put("es.index.read.missing.as.empty", "true");
      sparkProperties.put("es.nodes.wan.only", getEnv("ES_NODES_WAN_ONLY", "false"));
      sparkProperties.put(
          "es.net.ssl.keystore.location",
          getSystemPropertyAsFileResource("javax.net.ssl.keyStore"));
      sparkProperties.put(
          "es.net.ssl.keystore.pass", System.getProperty("javax.net.ssl.keyStorePassword", ""));
      sparkProperties.put(
          "es.net.ssl.truststore.location",
          getSystemPropertyAsFileResource("javax.net.ssl.trustStore"));
      sparkProperties.put(
          "es.net.ssl.truststore.pass", System.getProperty("javax.net.ssl.trustStorePassword", ""));
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

    public Builder hosts(String hosts) {
      this.hosts = checkNotNull(hosts, "hosts");
      sparkProperties.put("es.nodes.wan.only", "true");
      return this;
    }

    /** username used for basic auth. Needed when Shield or X-Pack security is enabled */
    public Builder username(String username) {
      this.username = username;
      return this;
    }

    /** password used for basic auth. Needed when Shield or X-Pack security is enabled */
    public Builder password(String password) {
      this.password = password;
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

  private static String getSystemPropertyAsFileResource(String key) {
    String prop = System.getProperty(key, "");
    return prop != null && !prop.isEmpty() ? "file:" + prop : prop;
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
    this.conf = new SparkConf(true).setMaster(builder.sparkMaster).setAppName(getClass().getName());
    if (builder.jars != null) conf.setJars(builder.jars);
    if (builder.username != null) conf.set("es.net.http.auth.user", builder.username);
    if (builder.password != null) conf.set("es.net.http.auth.pass", builder.password);
    conf.set("es.nodes", parseHosts(builder.hosts));
    if (builder.hosts.contains("https")) conf.set("es.net.ssl", "true");
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
    }
    this.logInitializer = builder.logInitializer;
  }

  public void run() {
    run( // multi-type index
        index + "-" + dateStamp + "/span",
        index + "-" + dateStamp + "/dependencylink",
        SpanBytesDecoder.JSON_V1);

    run( // single-type index
        index + ":span-" + dateStamp + "/span",
        index + ":dependency-" + dateStamp + "/dependency",
        SpanBytesDecoder.JSON_V2);
    log.info("Done");
  }

  void run(String spanResource, String dependencyLinkResource, SpanBytesDecoder decoder) {
    log.info("Processing spans from {}", spanResource);
    JavaSparkContext sc = new JavaSparkContext(conf);
    try {
      JavaRDD<Map<String, Object>> links =
          JavaEsSpark.esJsonRDD(sc, spanResource)
              .groupBy(JSON_TRACE_ID)
              .flatMapValues(new TraceIdAndJsonToDependencyLinks(logInitializer, decoder))
              .values()
              .mapToPair(l -> Tuple2.apply(Tuple2.apply(l.parent(), l.child()), l))
              .reduceByKey((l, r) -> DependencyLink.newBuilder()
                .parent(l.parent())
                .child(l.child())
                .callCount(l.callCount() + r.callCount())
                .errorCount(l.errorCount() + r.errorCount())
                .build())
              .values()
              .map(DEPENDENCY_LINK_JSON);

      if (links.isEmpty()) {
        log.info("No dependency links could be processed from spans in index {}", spanResource);
      } else {
        log.info("Saving dependency links to {}", dependencyLinkResource);
        JavaEsSpark.saveToEs(
            links,
            dependencyLinkResource,
            Collections.singletonMap("es.mapping.id", "id")); // allows overwriting the link
      }
    } finally {
      sc.stop();
    }
  }

  /**
   * Same as {@linkplain DependencyLink}, except it adds an ID field so the job can be re-run,
   * overwriting a prior run's value for the link.
   */
  static final Function<DependencyLink, Map<String, Object>> DEPENDENCY_LINK_JSON = l -> {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", l.parent() + "|" + l.child());
    result.put("parent", l.parent());
    result.put("child", l.child());
    result.put("callCount", l.callCount());
    result.put("errorCount", l.errorCount());
    return result;
  };

  private static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null && !result.isEmpty() ? result : defaultValue;
  }

  static String parseHosts(String hosts) {
    StringBuilder to = new StringBuilder();
    String[] hostParts = hosts.split(",");
    for (int i = 0; i < hostParts.length; i++) {
      String host = hostParts[i];
      if (host.startsWith("http")) {
        URI httpUri = URI.create(host);
        int port = httpUri.getPort();
        if (port == -1) {
          port = host.startsWith("https") ? 443 : 80;
        }
        to.append(httpUri.getHost()).append(":").append(port);
      } else {
        to.append(host);
      }
      if (i + 1 < hostParts.length) {
        to.append(',');
      }
    }
    return to.toString();
  }

  // defining what could be lambdas here until we update to minimum JRE 8 or retrolambda works.
  static final Function<Tuple2<String, String>, String> JSON_TRACE_ID = new Function<Tuple2<String, String>, String>() {
    /** returns the lower 64 bits of the trace ID */
    @Override public String call(Tuple2<String, String> pair) throws IOException {
      JsonReader reader = new JsonReader(new StringReader(pair._2));
      reader.beginObject();
      while (reader.hasNext()) {
        String nextName = reader.nextName();
        if (nextName.equals("traceId")) {
          String traceId = reader.nextString();
          return traceId.length() > 16 ? traceId.substring(traceId.length() - 16) : traceId;
        } else {
          reader.skipValue();
        }
      }
      throw new MalformedJsonException("no traceId in " + pair);
    }

    @Override public String toString() {
      return "pair._2.traceId";
    }
  };
}
