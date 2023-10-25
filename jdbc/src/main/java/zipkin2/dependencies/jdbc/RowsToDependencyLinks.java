/*
 * Copyright 2016-2023 The OpenZipkin Authors
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
package zipkin2.dependencies.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Row;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;

public final class RowsToDependencyLinks
    implements Serializable, Function<Iterable<Row>, Iterable<DependencyLink>> {
  private static final long serialVersionUID = 0L;

  @Nullable final Runnable logInitializer;

  public RowsToDependencyLinks(Runnable logInitializer) {
    this.logInitializer = logInitializer;
  }

  @Override public Iterable<DependencyLink> call(Iterable<Row> rows) {
    if (logInitializer != null) logInitializer.run();
    Iterator<Iterator<Span>> traces =
        new DependencyLinkSpanIterator.ByTraceId(rows.iterator());

    if (!traces.hasNext()) return Collections.emptyList();

    DependencyLinker linker = new DependencyLinker();
    List<Span> nextTrace = new ArrayList<>();
    while (traces.hasNext()) {
      Iterator<Span> i = traces.next();
      while (i.hasNext()) nextTrace.add(i.next());
      linker.putTrace(nextTrace);
      nextTrace.clear();
    }
    return linker.link();
  }
}
