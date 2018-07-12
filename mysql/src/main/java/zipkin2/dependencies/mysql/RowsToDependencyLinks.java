/*
 * Copyright 2016-2018 The OpenZipkin Authors
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
package zipkin2.dependencies.mysql;

import java.util.Collections;
import java.util.Iterator;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.internal.DependencyLinker;
import zipkin2.Span;

final class RowsToDependencyLinks
    implements Serializable, Function<Iterable<Row>, Iterable<DependencyLink>> {
  private static final long serialVersionUID = 0L;
  private static final Logger log = LoggerFactory.getLogger(RowsToDependencyLinks.class);

  @Nullable final Runnable logInitializer;
  final boolean hasTraceIdHigh;

  RowsToDependencyLinks(Runnable logInitializer, boolean hasTraceIdHigh) {
    this.logInitializer = logInitializer;
    this.hasTraceIdHigh = hasTraceIdHigh;
  }

  @Override public Iterable<DependencyLink> call(Iterable<Row> rows) {
    if (logInitializer != null) logInitializer.run();
    Iterator<Iterator<Span>> traces =
        new DependencyLinkSpanIterator.ByTraceId(rows.iterator(), hasTraceIdHigh);

    if (!traces.hasNext()) return Collections.emptyList();

    DependencyLinker linker = new DependencyLinker();
    while (traces.hasNext()) {
      linker.putTrace(traces.next());
    }
    return linker.link();
  }
}
