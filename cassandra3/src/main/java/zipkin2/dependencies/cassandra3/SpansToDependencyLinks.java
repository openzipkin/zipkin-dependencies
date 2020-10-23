/*
 * Copyright 2016-2020 The OpenZipkin Authors
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
package zipkin2.dependencies.cassandra3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.Function;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;

final class SpansToDependencyLinks
  implements Serializable, Function<Iterable<Span>, Iterable<DependencyLink>> {
  private static final long serialVersionUID = 0L;

  @Nullable final Runnable logInitializer;
  final long startTs;
  final long endTs;

  SpansToDependencyLinks(Runnable logInitializer, long startTs, long endTs) {
    this.logInitializer = logInitializer;
    this.startTs = startTs;
    this.endTs = endTs;
  }

  @Override public Iterable<DependencyLink> call(Iterable<Span> spans) {
    if (logInitializer != null) logInitializer.run();
    List<Span> sameTraceId = new ArrayList<>();
    for (Span span : spans) {
      // check to see if the trace is within the interval
      if (span.parentId() == null) {
        long timestamp = span.timestampAsLong();
        if (timestamp == 0 || timestamp < startTs || timestamp > endTs) {
          return Collections.emptyList();
        }
      }
      sameTraceId.add(span);
    }
    return new DependencyLinker().putTrace(sameTraceId).link();
  }
}
