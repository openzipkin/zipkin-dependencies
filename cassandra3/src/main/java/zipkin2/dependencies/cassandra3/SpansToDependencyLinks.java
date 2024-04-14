/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies.cassandra3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.FlatMapFunction;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;

final class SpansToDependencyLinks
  implements Serializable, FlatMapFunction<Iterable<Span>, DependencyLink> {
  private static final long serialVersionUID = 0L;

  @Nullable final Runnable logInitializer;
  final long startTs;
  final long endTs;

  SpansToDependencyLinks(Runnable logInitializer, long startTs, long endTs) {
    this.logInitializer = logInitializer;
    this.startTs = startTs;
    this.endTs = endTs;
  }

  @Override public Iterator<DependencyLink> call(Iterable<Span> spans) {
    if (logInitializer != null) logInitializer.run();
    List<Span> sameTraceId = new ArrayList<>();
    for (Span span : spans) {
      // check to see if the trace is within the interval
      if (span.parentId() == null) {
        long timestamp = span.timestampAsLong();
        if (timestamp == 0 || timestamp < startTs || timestamp > endTs) {
          return Collections.emptyIterator();
        }
      }
      sameTraceId.add(span);
    }
    return new DependencyLinker().putTrace(sameTraceId).link().iterator();
  }
}
