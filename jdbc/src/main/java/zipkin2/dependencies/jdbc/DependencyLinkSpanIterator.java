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

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
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
  private static final Gson GSON = new Gson();

  /** Assumes the input records are sorted by trace id, span id */
  static final class ByTraceId implements Iterator<Iterator<Span>> {
    final PeekingIterator<Row> delegate;

    String currentTraceId;

    ByTraceId(Iterator<Row> delegate) {
      this.delegate = new PeekingIterator<>(delegate);
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public Iterator<Span> next() {
      Row peeked = delegate.peek();
      currentTraceId = peeked.getString(0);
      return new DependencyLinkSpanIterator(delegate, currentTraceId);
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  final PeekingIterator<Row> delegate;
  final String traceId;

  DependencyLinkSpanIterator(
      PeekingIterator<Row> delegate, String traceId) {
    this.delegate = delegate;
    this.traceId = traceId;
  }

  @Override
  public boolean hasNext() {
    return delegate.hasNext()
        // We don't have a query parameter for strictTraceId when fetching dependency links, so we
        // ignore traceIdHigh. Otherwise, a single trace can appear as two, doubling callCount.
        && Objects.equals(delegate.peek().getString(0), traceId); // trace_id
  }

  @Override
  public Span next() {
    Row row = delegate.next();

    final Span.Builder builder = Span.newBuilder()
        .traceId(row.getString(0))
        .parentId(row.isNullAt(1) ? "0" : row.getString(1))
        .id(row.getString(2))
        .kind(Span.Kind.valueOf(row.getString(3)))
        .localEndpoint(ep(row.getString(4)))
        .remoteEndpoint(ep(row.getString(5)));

    String tagsString = row.getString(6);
    if (StringUtils.isNotEmpty(tagsString)) {
      final JsonObject tagObject = GSON.fromJson(tagsString, JsonObject.class);
      for (Map.Entry<String, JsonElement> entry : tagObject.entrySet()) {
        builder.putTag(entry.getKey(), entry.getValue().getAsString());
      }
    }
    return builder.build();
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
