/**
 * Copyright 2016 The OpenZipkin Authors
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
package zipkin.dependencies.mysql;

import java.util.Iterator;
import org.apache.spark.sql.Row;
import zipkin.internal.DependencyLinkSpan;
import zipkin.internal.PeekingIterator;

import static zipkin.Constants.CLIENT_ADDR;
import static zipkin.Constants.SERVER_ADDR;
import static zipkin.Constants.SERVER_RECV;

/** Processes rows are in the same trace and ordered by span id */
final class DependencyLinkSpanIterator implements Iterator<DependencyLinkSpan> {

  final PeekingIterator<Row> delegate;

  DependencyLinkSpanIterator(Iterator<Row> delegate) {
    this.delegate = new PeekingIterator<>(delegate);
  }

  @Override
  public boolean hasNext() {
    return delegate.hasNext();
  }

  @Override
  public DependencyLinkSpan next() {
    Row row = delegate.next();

    DependencyLinkSpan.Builder result = DependencyLinkSpan.builder(
        row.getLong(0), // trace_id
        row.isNullAt(1) ? null : row.getLong(1), // parent_id
        row.getLong(2) // id
    );
    parseClientAndServerNames(result, row);

    while (hasNext()) {
      Row next = delegate.peek();
      if (next == null) {
        continue;
      }
      if (row.getLong(2) == next.getLong(2)) { // id
        delegate.next(); // advance the iterator since we are in the same span id
        parseClientAndServerNames(result, next);
      } else {
        break;
      }
    }
    return result.build();
  }

  void parseClientAndServerNames(DependencyLinkSpan.Builder span, Row row) {
    String key = row.getString(3); // a_key
    String serviceName = row.getString(4); // a_service_name

    if (key == null) return; // neither client nor server (usually local)
    switch (key) {
      case CLIENT_ADDR:
        span.caService(serviceName);
        break;
      case SERVER_ADDR:
        span.saService(serviceName);
        break;
      case SERVER_RECV:
        span.srService(serviceName);
    }
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }
}