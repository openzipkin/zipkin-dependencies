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
package zipkin.dependencies.mysql;

import java.util.Iterator;
import javax.annotation.Nullable;
import org.apache.spark.sql.Row;
import zipkin.BinaryAnnotation;
import zipkin.Constants;
import zipkin.internal.PeekingIterator;
import zipkin2.Endpoint;
import zipkin2.Span;

import static zipkin.Constants.CLIENT_ADDR;
import static zipkin.Constants.CLIENT_SEND;
import static zipkin.Constants.ERROR;
import static zipkin.Constants.SERVER_ADDR;
import static zipkin.Constants.SERVER_RECV;
import static zipkin.internal.Util.equal;
import static zipkin.internal.Util.toLowerHex;

/**
 * Lazy converts rows into {@linkplain Span} objects suitable for dependency links. This takes
 * short-cuts to require less data. For example, it folds shared RPC spans into one, and doesn't
 * include tags, non-core annotations or time units.
 *
 * <p>Out-of-date schemas may be missing the trace_id_high field. When present, the {@link
 * Span#traceId()} could be 32 characters in logging statements.
 */
final class DependencyLinkSpanIterator implements Iterator<Span> {

  /** Assumes the input records are sorted by trace id, span id */
  static final class ByTraceId implements Iterator<Iterator<Span>> {
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

    @Override public Iterator<Span> next() {
      Row peeked = delegate.peek();
      currentTraceIdHi = hasTraceIdHigh ? peeked.getLong(0) : null;
      currentTraceIdLo = peeked.getLong(traceIdIndex);
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
        // We don't have a query parameter for strictTraceId when fetching dependency links, so we
        // ignore traceIdHigh. Otherwise, a single trace can appear as two, doubling callCount.
        && delegate.peek().getLong(traceIdIndex) == traceIdLo; // trace_id
  }

  @Override
  public Span next() {
    Row row = delegate.peek();

    long spanId = row.getLong(traceIdIndex + 2);
    boolean error = false;
    String srService = null, csService = null, caService = null, saService = null;
    while (hasNext()) { // there are more values for this trace
      if (spanId != delegate.peek().getLong(traceIdIndex + 2) /* id */) {
        break; // if we are in a new span
      }
      Row next = delegate.next(); // row for the same span

      String key = emptyToNull(row, traceIdIndex + 3); // a_key
      String value = emptyToNull(row, traceIdIndex + 4); // a_service_name
      if (key == null || value == null) continue; // neither client nor server
      switch (key) {
        case CLIENT_ADDR:
          caService = value;
          break;
        case CLIENT_SEND:
          csService = value;
          break;
        case SERVER_ADDR:
          saService = value;
          break;
        case SERVER_RECV:
          srService = value;
          break;
        case ERROR:
          // a span is in error if it has a tag, not an annotation, of name "error"
          error = BinaryAnnotation.Type.STRING.value == next.getInt(traceIdIndex + 5); // a_type
      }
    }

    // The client address is more authoritative than the client send owner.
    if (caService == null) caService = csService;

    // Finagle labels two sides of the same socket ("ca", "sa") with the same name.
    // Skip the client side, so it isn't mistaken for a loopback request
    if (equal(saService, caService)) caService = null;

    Long parentId = row.isNullAt(traceIdIndex + 1) ? null : row.getLong(traceIdIndex + 1);
    Span.Builder result = Span.newBuilder()
        .traceId(toLowerHex(traceIdHi != null ? traceIdHi : 0L, traceIdLo))
        .parentId(parentId != null ? toLowerHex(parentId) : null)
        .id(toLowerHex(spanId));

    if (error) {
      result.putTag(Constants.ERROR, "" /* actual value doesn't matter */);
    }

    if (srService != null) {
      return result.kind(Span.Kind.SERVER)
          .localEndpoint(ep(srService))
          .remoteEndpoint(ep(caService))
          .build();
    } else if (saService != null) {
      return result
          .kind(csService != null ? Span.Kind.CLIENT : null)
          .localEndpoint(ep(caService))
          .remoteEndpoint(ep(saService))
          .build();
    } else if (csService != null) {
      return result.kind(Span.Kind.SERVER)
          .localEndpoint(ep(caService))
          .build();
    }
    return result.build();
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }

  static @Nullable String emptyToNull(Row row, int index) {
    String result = row.isNullAt(index) ? null : row.getString(index);
    return result != null && !"".equals(result) ? result : null;
  }

  static Endpoint ep(@Nullable String serviceName) {
    return serviceName != null ? Endpoint.newBuilder().serviceName(serviceName).build() : null;
  }
}
