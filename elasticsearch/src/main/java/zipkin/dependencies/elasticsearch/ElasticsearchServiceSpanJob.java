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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.elasticsearch.spark.rdd.api.java.JavaEsSpark;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import static zipkin.dependencies.elasticsearch.ElasticsearchDependenciesJob.traceId;

public final class ElasticsearchServiceSpanJob implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(ElasticsearchDependenciesJob.class);

  final ElasticsearchDependenciesJob job;

  ElasticsearchServiceSpanJob(ElasticsearchDependenciesJob job) {
    this.job = job;
  }

  @Override public void run() {
    String bucket = job.index + "-" + job.dateStamp;

    log.info("Processing spans from {}/span", bucket);

    JavaSparkContext sc = new JavaSparkContext(job.conf);

    JavaRDD<Map<String, Object>> links = JavaEsSpark.esJsonRDD(sc, bucket + "/span")
        .groupBy(pair -> traceId(pair._2))
        .flatMapValues(new TraceIdAndJsonToServiceSpan(job.logInitializer))
        .values()
        .distinct()
        .map(ElasticsearchServiceSpanJob::servicespanJson);

    log.info("Saving service and span names to {}/servicespan", bucket);
    JavaEsSpark.saveToEs(links, bucket + "/servicespan",
        Collections.singletonMap("es.mapping.id", "id")); // allows overwriting
    log.info("Done");
    sc.stop();
  }

  static Map<String, Object> servicespanJson(Tuple2<String, String> serviceSpan) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", serviceSpan._1() + "|" + serviceSpan._2());
    result.put("serviceName", serviceSpan._1());
    result.put("spanName", serviceSpan._2());
    return result;
  }
}
