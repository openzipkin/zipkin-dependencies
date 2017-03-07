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
import java.io.ObjectInputStream;
import java.util.LinkedList;
import java.util.List;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.function.Function;
import scala.Serializable;
import scala.Tuple2;
import zipkin.Codec;
import zipkin.DependencyLink;
import zipkin.Span;
import zipkin.internal.DependencyLinker;
import zipkin.internal.GroupByTraceId;
import zipkin.internal.Nullable;

final class TraceIdAndJsonToDependencyLinks implements Serializable,
    Function<Iterable<Tuple2<String, String>>, Iterable<DependencyLink>> {
  transient Logger logger = LogManager.getLogger(TraceIdAndJsonToDependencyLinks.class);

  private static final long serialVersionUID = 0L;

  @Nullable final Runnable logInitializer;

  TraceIdAndJsonToDependencyLinks(Runnable logInitializer) {
    this.logInitializer = logInitializer;
  }

  @Override public Iterable<DependencyLink> call(Iterable<Tuple2<String, String>> traceIdJson) {
    if (logInitializer != null) logInitializer.run();
    List<Span> sameTraceId = new LinkedList<>();
    for (Tuple2<String, String> row : traceIdJson) {
      try {
        sameTraceId.add(Codec.JSON.readSpan(row._2.getBytes()));
      } catch (RuntimeException e) {
        logger.warn("Unable to decode span from traces where trace_id=" + row._1, e);
      }
      sameTraceId.add(Codec.JSON.readSpan(row._2.getBytes()));
    }
    DependencyLinker linker = new DependencyLinker();
    for (List<Span> trace : GroupByTraceId.apply(sameTraceId, true, true)) {
      linker.putTrace(trace);
    }
    return linker.link();
  }

  // loggers aren't serializable
  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    logger = LogManager.getLogger(TraceIdAndJsonToDependencyLinks.class);
    in.defaultReadObject();
  }
}