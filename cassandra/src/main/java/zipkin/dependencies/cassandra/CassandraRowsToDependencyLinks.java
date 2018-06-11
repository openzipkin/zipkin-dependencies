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
package zipkin.dependencies.cassandra;

import com.datastax.spark.connector.japi.CassandraRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;
import zipkin2.internal.V1ThriftSpanReader;
import zipkin2.v1.V1Span;
import zipkin2.v1.V1SpanConverter;

final class CassandraRowsToDependencyLinks
    implements Serializable, Function<Iterable<CassandraRow>, Iterable<DependencyLink>> {
  private static final long serialVersionUID = 0L;
  private static final Logger log = LoggerFactory.getLogger(CassandraRowsToDependencyLinks.class);

  @Nullable final Runnable logInitializer;
  final long startTs;
  final long endTs;

  CassandraRowsToDependencyLinks(Runnable logInitializer, long startTs, long endTs) {
    this.logInitializer = logInitializer;
    this.startTs = startTs;
    this.endTs = endTs;
  }

  @Override
  public Iterable<DependencyLink> call(Iterable<CassandraRow> rows) {
    if (logInitializer != null) logInitializer.run();
    V1ThriftSpanReader reader = V1ThriftSpanReader.create();
    V1SpanConverter converter = V1SpanConverter.create();
    List<Span> sameTraceId = new ArrayList<>();
    for (CassandraRow row : rows) {
      try {
        V1Span v1Span = reader.read(row.getBytes("span"));
        for (Span span : converter.convert(v1Span)) {
          // check to see if the trace is within the interval
          if (span.parentId() == null) {
            long timestamp = span.timestampAsLong();
            if (timestamp == 0 || timestamp < startTs || timestamp > endTs) {
              return Collections.emptyList();
            }
          }
          sameTraceId.add(span);
        }
      } catch (RuntimeException e) {
        log.warn(
            String.format(
                "Unable to decode span from traces where trace_id=%s and ts=%s and span_name='%s'",
                row.getLong("trace_id"), row.getDate("ts").getTime(), row.getString("span_name")),
            e);
      }
    }
    return new DependencyLinker().putTrace(sameTraceId.iterator()).link();
  }
}
