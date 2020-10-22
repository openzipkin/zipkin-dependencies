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

import com.datastax.spark.connector.japi.CassandraRow;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.Function;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.Span;

final class CassandraRowsToDependencyLinks
  implements Serializable, Function<Iterable<CassandraRow>, Iterable<DependencyLink>> {
  private static final long serialVersionUID = 0L;

  @Nullable final Runnable logInitializer;
  final SpansToDependencyLinks spansToDependencyLinks;

  CassandraRowsToDependencyLinks(@Nullable Runnable logInitializer, long startTs, long endTs) {
    this.logInitializer = logInitializer;
    this.spansToDependencyLinks = new SpansToDependencyLinks(logInitializer, startTs, endTs);
  }

  @Override public Iterable<DependencyLink> call(Iterable<CassandraRow> rows) {
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
