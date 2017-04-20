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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.spark.api.java.function.Function;
import scala.Serializable;
import scala.Tuple2;
import zipkin.Span;
import zipkin.internal.Nullable;

final class TraceIdAndJsonToServiceSpan implements Serializable,
    Function<Iterable<Tuple2<String, String>>, Iterable<Tuple2<String, String>>> {
  private static final long serialVersionUID = 0L;

  final TraceIdAndJsonToTrace getTraces;

  TraceIdAndJsonToServiceSpan(@Nullable Runnable logInitializer) {
    this.getTraces = new TraceIdAndJsonToTrace(logInitializer);
  }

  @Override
  public Iterable<Tuple2<String, String>> call(Iterable<Tuple2<String, String>> traceIdJson) {
    Set<Tuple2<String, String>> result = new LinkedHashSet<>();
    for (List<Span> trace : getTraces.call(traceIdJson)) {
      for (Span span : trace) {
        if (span.name.isEmpty()) continue;
        for (String serviceName : span.serviceNames()) {
          result.add(ElasticsearchDependenciesJob.tuple2(serviceName, span.name));
        }
      }
    }
    return result;
  }
}
