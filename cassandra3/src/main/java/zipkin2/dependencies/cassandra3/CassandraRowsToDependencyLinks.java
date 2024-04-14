/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies.cassandra3;

import com.datastax.spark.connector.japi.CassandraRow;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.FlatMapFunction;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.Span;

final class CassandraRowsToDependencyLinks
  implements Serializable, FlatMapFunction<Iterable<CassandraRow>, DependencyLink> {
  private static final long serialVersionUID = 0L;

  @Nullable final Runnable logInitializer;
  final SpansToDependencyLinks spansToDependencyLinks;

  CassandraRowsToDependencyLinks(@Nullable Runnable logInitializer, long startTs, long endTs) {
    this.logInitializer = logInitializer;
    this.spansToDependencyLinks = new SpansToDependencyLinks(logInitializer, startTs, endTs);
  }

  @Override public Iterator<DependencyLink> call(Iterable<CassandraRow> rows) {
    if (logInitializer != null) logInitializer.run();
    // use a hash set to dedupe any redundantly accepted spans
    Set<Span> sameTraceId = new LinkedHashSet<>();
    for (CassandraRow row : rows) {
      Span span = CassandraRowToSpan.INSTANCE.call(row);
      sameTraceId.add(span);
    }

    return spansToDependencyLinks.call(sameTraceId);
  }
}
