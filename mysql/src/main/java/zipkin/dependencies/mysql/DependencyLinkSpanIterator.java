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
package zipkin.dependencies.mysql;

import java.util.Iterator;
import org.apache.spark.sql.Row;
import zipkin.internal.DependencyLinkSpan;
import zipkin.internal.Nullable;
import zipkin.internal.PeekingIterator;

import static zipkin.Constants.CLIENT_ADDR;
import static zipkin.Constants.CLIENT_SEND;
import static zipkin.Constants.SERVER_ADDR;
import static zipkin.Constants.SERVER_RECV;

/**
 * Convenience that lazy converts rows into {@linkplain DependencyLinkSpan} objects.
 *
 * <p>Out-of-date schemas may be missing the trace_id_high field. When present, this becomes {@link
 * DependencyLinkSpan.TraceId#hi} used as the left-most 16 characters of the traceId in logging
 * statements.
 */
final class DependencyLinkSpanIterator implements Iterator<DependencyLinkSpan> {

  /** Assumes the input records are sorted by trace id, span id */
  static final class ByTraceId implements Iterator<Iterator<DependencyLinkSpan>> {
    final PeekingIterator<Row> delegate;
    final boolean hasTraceIdHigh;
    final int traceIdIndex;

    @Nullable Long currentTraceIdHi;
    long currentTraceIdLo;

    ByTraceId(Iterator<Row> delegate, boolean hasTraceIdHigh) {
      this.delegate = new PeekingIterator<>(delegate);
      this.hasTraceIdHigh = hasTraceIdHigh;
      this.traceIdIndex = hasTraceIdHigh ? 1 : 0;
    }

    @Override public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override public Iterator<DependencyLinkSpan> next() {
      currentTraceIdHi = hasTraceIdHigh ? delegate.peek().getLong(0) : null;
      currentTraceIdLo = delegate.peek().getLong(traceIdIndex);
      return new DependencyLinkSpanIterator(delegate, currentTraceIdHi, currentTraceIdLo);
    }

    @Override public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  final PeekingIterator<Row> delegate;
  final int traceIdIndex;
  @Nullable final Long traceIdHi;
  final long traceIdLo;

  DependencyLinkSpanIterator(PeekingIterator<Row> delegate, Long traceIdHi, long traceIdLo) {
    this.delegate = delegate;
    this.traceIdIndex = traceIdHi != null ? 1 : 0;
    this.traceIdHi = traceIdHi;
    this.traceIdLo = traceIdLo;
  }

  @Override
  public boolean hasNext() {
    return delegate.hasNext()
        && (traceIdHi == null || traceIdHi.equals(delegate.peek().getLong(0)))
        && delegate.peek().getLong(traceIdIndex) == traceIdLo; // trace_id
  }

  @Override
  public DependencyLinkSpan next() {
    Row row = delegate.next();

    DependencyLinkSpan.Builder result = DependencyLinkSpan.builder(
        traceIdHi != null ? traceIdHi : 0L,
        traceIdLo,
        row.isNullAt(traceIdIndex + 1) ? null : row.getLong(traceIdIndex + 1), // parent_id
        row.getLong(traceIdIndex + 2) // id
    );
    parseClientAndServerNames(result, row);

    while (hasNext()) {
      Row next = delegate.peek();
      if (next == null) {
        continue;
      }
      if (row.getLong(traceIdIndex + 2) == next.getLong(traceIdIndex + 2)) { // id
        delegate.next(); // advance the iterator since we are in the same span id
        parseClientAndServerNames(result, next);
      } else {
        break;
      }
    }
    return result.build();
  }

  void parseClientAndServerNames(DependencyLinkSpan.Builder span, Row row) {
    String key = row.getString(traceIdIndex + 3); // a_key
    String serviceName = row.getString(traceIdIndex + 4); // a_service_name

    if (key == null) return; // neither client nor server (usually local)
    switch (key) {
      case CLIENT_ADDR:
        span.caService(serviceName);
        break;
      case CLIENT_SEND:
        span.csService(serviceName);
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
