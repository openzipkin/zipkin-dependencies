/*
 * Copyright 2016-2022 The OpenZipkin Authors
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import scala.Tuple2;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.codec.SpanBytesDecoder;
import zipkin2.internal.DependencyLinker;

final class TraceIdAndJsonToDependencyLinks
  implements Serializable, FlatMapFunction<Iterable<Tuple2<String, String>>, DependencyLink> {
  private static final long serialVersionUID = 0L;
  private static final Logger log = LoggerFactory.getLogger(TraceIdAndJsonToDependencyLinks.class);

  @Nullable final Runnable logInitializer;
  final SpanBytesDecoder decoder;

  TraceIdAndJsonToDependencyLinks(Runnable logInitializer, SpanBytesDecoder decoder) {
    this.logInitializer = logInitializer;
    this.decoder = decoder;
  }

  @Override
  public Iterator<DependencyLink> call(Iterable<Tuple2<String, String>> traceIdJson) {
    if (logInitializer != null) logInitializer.run();
    List<Span> sameTraceId = new ArrayList<>();
    for (Tuple2<String, String> row : traceIdJson) {
      try {
        decoder.decode(row._2.getBytes(ElasticsearchDependenciesJob.UTF_8), sameTraceId);
      } catch (Exception e) {
        log.warn("Unable to decode span from traces where trace_id=" + row._1, e);
      }
    }
    DependencyLinker linker = new DependencyLinker();
    linker.putTrace(sameTraceId);
    return linker.link().iterator();
  }
}
