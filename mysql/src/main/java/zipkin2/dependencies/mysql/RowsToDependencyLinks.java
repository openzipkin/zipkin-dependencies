/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies.mysql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.Row;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;

final class RowsToDependencyLinks
    implements Serializable, FlatMapFunction<Iterable<Row>, DependencyLink> {
  private static final long serialVersionUID = 0L;

  @Nullable final Runnable logInitializer;
  final boolean hasTraceIdHigh;

  RowsToDependencyLinks(Runnable logInitializer, boolean hasTraceIdHigh) {
    this.logInitializer = logInitializer;
    this.hasTraceIdHigh = hasTraceIdHigh;
  }

  @Override public Iterator<DependencyLink> call(Iterable<Row> rows) {
    if (logInitializer != null) logInitializer.run();
    Iterator<Iterator<Span>> traces =
        new DependencyLinkSpanIterator.ByTraceId(rows.iterator(), hasTraceIdHigh);

    if (!traces.hasNext()) return Collections.emptyIterator();

    DependencyLinker linker = new DependencyLinker();
    List<Span> nextTrace = new ArrayList<>();
    while (traces.hasNext()) {
      Iterator<Span> i = traces.next();
      while (i.hasNext()) nextTrace.add(i.next());
      linker.putTrace(nextTrace);
      nextTrace.clear();
    }
    return linker.link().iterator();
  }
}
