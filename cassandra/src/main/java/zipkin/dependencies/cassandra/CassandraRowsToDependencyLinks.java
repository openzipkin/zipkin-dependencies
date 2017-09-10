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
package zipkin.dependencies.cassandra;

import com.datastax.spark.connector.japi.CassandraRow;
import java.util.LinkedList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import zipkin.Codec;
import zipkin.DependencyLink;
import zipkin.Span;
import zipkin.internal.DependencyLinker;
import zipkin.internal.GroupByTraceId;

import static zipkin.internal.ApplyTimestampAndDuration.guessTimestamp;

final class CassandraRowsToDependencyLinks implements Serializable,
    Function<Iterable<CassandraRow>, Iterable<DependencyLink>> {
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

  @Override public Iterable<DependencyLink> call(Iterable<CassandraRow> rows) {
    if (logInitializer != null) logInitializer.run();
    List<Span> sameTraceId = new LinkedList<>();
    for (CassandraRow row : rows) {
      try {
        sameTraceId.add(Codec.THRIFT.readSpan(row.getBytes("span")));
      } catch (RuntimeException e) {
        log.warn(String.format(
            "Unable to decode span from traces where trace_id=%s and ts=%s and span_name='%s'",
            row.getLong("trace_id"), row.getDate("ts").getTime(), row.getString("span_name")), e);
      }
    }
    DependencyLinker linker = new DependencyLinker();
    for (List<Span> trace : GroupByTraceId.apply(sameTraceId, false, true)) {
      // check to see if the trace is within the interval
      Long timestamp = guessTimestamp(trace.get(0));
      if (timestamp == null ||
          timestamp < startTs ||
          timestamp > endTs) {
        continue;
      }
      linker.putTrace(trace);
    }
    return linker.link();
  }
}
