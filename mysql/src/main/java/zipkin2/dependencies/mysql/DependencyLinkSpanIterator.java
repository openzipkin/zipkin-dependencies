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

import java.util.Iterator;
import javax.annotation.Nullable;
import org.apache.spark.sql.Row;
import zipkin2.Endpoint;
import zipkin2.Span;

/**
 * Lazy converts rows into {@linkplain Span} objects suitable for dependency links. This takes
 * short-cuts to require less data. For example, it folds shared RPC spans into one, and doesn't
 * include tags, non-core annotations or time units.
 *
 * <p>Out-of-date schemas may be missing the trace_id_high field. When present, the {@link
 * Span#traceId()} could be 32 characters in logging statements.
 */
final class DependencyLinkSpanIterator implements Iterator<Span> {
  static final int BINARY_ANNOTATION_TYPE_STRING = 6;

  /** Assumes the input records are sorted by trace id, span id */
  static final class ByTraceId implements Iterator<Iterator<Span>> {
    final PeekingIterator<Row> delegate;
    final boolean hasTraceIdHigh;
    final int traceIdIndex;

    long currentTraceIdHi, currentTraceIdLo;

    ByTraceId(Iterator<Row> delegate, boolean hasTraceIdHigh) {
      this.delegate = new PeekingIterator<>(delegate);
      this.hasTraceIdHigh = hasTraceIdHigh;
      this.traceIdIndex = hasTraceIdHigh ? 1 : 0;
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public Iterator<Span> next() {
      Row peeked = delegate.peek();
      currentTraceIdHi = hasTraceIdHigh ? peeked.getLong(0) : 0L;
      currentTraceIdLo = peeked.getLong(traceIdIndex);
      return new DependencyLinkSpanIterator(
          delegate, traceIdIndex, currentTraceIdHi, currentTraceIdLo);
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  final PeekingIterator<Row> delegate;
  final int traceIdIndex;
  final long traceIdHi, traceIdLo;

  DependencyLinkSpanIterator(
      PeekingIterator<Row> delegate, int traceIdIndex, long traceIdHi, long traceIdLo) {
    this.delegate = delegate;
    this.traceIdIndex = traceIdIndex;
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
    String lcService = null, srService = null, csService = null, caService = null, saService = null,
      maService = null, mrService = null, msService = null;
    while (hasNext()) { // there are more values for this trace
      if (spanId != delegate.peek().getLong(traceIdIndex + 2) /* id */) {
        break; // if we are in a new span
      }
      Row next = delegate.next(); // row for the same span

      String key = emptyToNull(row, traceIdIndex + 3); // a_key
      String value = emptyToNull(row, traceIdIndex + 4); // a_service_name
      if (key == null || value == null) continue; // neither client nor server
      switch (key) {
        case "lc":
          lcService = value;
          break;
        case "ca":
          caService = value;
          break;
        case "cs":
          csService = value;
          break;
        case "sa":
          saService = value;
          break;
        case "ma":
          maService = value;
          break;
        case "mr":
          mrService = value;
          break;
        case "ms":
          msService = value;
          break;
        case "sr":
          srService = value;
          break;
        case "error":
          // a span is in error if it has a tag, not an annotation, of name "error"
          error = BINARY_ANNOTATION_TYPE_STRING == next.getInt(traceIdIndex + 5); // a_type
      }
    }

    // The client address is more authoritative than the client send owner.
    if (caService == null) caService = csService;

    // Finagle labels two sides of the same socket ("ca", "sa") with the same name.
    // Skip the client side, so it isn't mistaken for a loopback request
    if (saService != null && saService.equals(caService)) caService = null;

    long parentId = row.isNullAt(traceIdIndex + 1) ? 0L : row.getLong(traceIdIndex + 1);
    Span.Builder result =
        Span.newBuilder().traceId(traceIdHi, traceIdLo).parentId(parentId).id(spanId);

    if (error) {
      result.putTag("error", "" /* actual value doesn't matter */);
    }

    if (srService != null) {
      return result
          .kind(Span.Kind.SERVER)
          .localEndpoint(ep(srService))
          .remoteEndpoint(ep(caService))
          .build();
    } else if (saService != null) {
      Endpoint localEndpoint = ep(caService);
      // When span.kind is missing, the local endpoint is "lc" and the remote endpoint is "sa"
      if (localEndpoint == null) localEndpoint = ep(lcService);
      return result
          .kind(csService != null ? Span.Kind.CLIENT : null)
          .localEndpoint(localEndpoint)
          .remoteEndpoint(ep(saService))
          .build();
    } else if (csService != null) {
      return result.kind(Span.Kind.SERVER).localEndpoint(ep(caService)).build();
    } else if (mrService != null) {
      return result
        .kind(Span.Kind.CONSUMER)
        .localEndpoint(ep(mrService))
        .remoteEndpoint(ep(maService))
        .build();
    } else if (msService != null) {
      return result
        .kind(Span.Kind.PRODUCER)
        .localEndpoint(ep(msService))
        .remoteEndpoint(ep(maService))
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
