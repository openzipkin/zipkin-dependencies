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
package zipkin.storage.cassandra;

import com.google.common.util.concurrent.Futures;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import zipkin.Annotation;
import zipkin.DependencyLink;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.dependencies.cassandra.CassandraDependenciesJob;
import zipkin.internal.ApplyTimestampAndDuration;
import zipkin.internal.MergeById;
import zipkin.internal.Util;
import zipkin.storage.DependenciesTest;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.Constants.CLIENT_RECV;
import static zipkin.Constants.CLIENT_SEND;
import static zipkin.Constants.SERVER_RECV;
import static zipkin.Constants.SERVER_SEND;

public class CassandraDependenciesTest extends DependenciesTest {
  private final CassandraStorage storage;

  public CassandraDependenciesTest() {
    this.storage = CassandraTestGraph.INSTANCE.storage.get();
  }

  @Override protected CassandraStorage storage() {
    return storage;
  }

  @Override public void clear() {
    storage.clear();
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  @Override
  public void processDependencies(List<Span> spans) {
    // This gets or derives a timestamp from the spans
    spans = MergeById.apply(spans);

    Futures.getUnchecked(storage.guavaSpanConsumer().accept(spans));

    Set<Long> days = new LinkedHashSet<>();
    for (Span span : spans) {
      days.add(Util.midnightUTC(span.timestamp / 1000));
    }
    for (long day : days) {
      CassandraDependenciesJob.builder().keyspace(storage.keyspace).day(day).build().run();
    }
  }

  // TODO: remove when zipkin 1.6.1 is out
  /**
   * Legacy instrumentation don't set Span.timestamp or duration. Make sure dependencies still
   * work.
   */
  @Test
  public void getDependencies_noTimestamps() {
    Endpoint one = Endpoint.create("trace-producer-one", 127 << 24 | 1, 9410);
    Endpoint onePort3001 = one.toBuilder().port((short) 3001).build();
    Endpoint two = Endpoint.create("trace-producer-two", 127 << 24 | 2, 9410);
    Endpoint twoPort3002 = two.toBuilder().port((short) 3002).build();
    Endpoint three = Endpoint.create("trace-producer-three", 127 << 24 | 3, 9410);

    List<Span> trace = asList(
        Span.builder().traceId(10L).id(10L).name("get")
            .addAnnotation(Annotation.create(1445136539256150L, SERVER_RECV, one))
            .addAnnotation(Annotation.create(1445136540408729L, SERVER_SEND, one))
            .build(),
        Span.builder().traceId(10L).parentId(10L).id(20L).name("get")
            .addAnnotation(Annotation.create(1445136539764798L, CLIENT_SEND, onePort3001))
            .addAnnotation(Annotation.create(1445136539816432L, SERVER_RECV, two))
            .addAnnotation(Annotation.create(1445136540401414L, SERVER_SEND, two))
            .addAnnotation(Annotation.create(1445136540404135L, CLIENT_RECV, onePort3001))
            .build(),
        Span.builder().traceId(10L).parentId(20L).id(30L).name("get")
            .addAnnotation(Annotation.create(1445136540025751L, CLIENT_SEND, twoPort3002))
            .addAnnotation(Annotation.create(1445136540072846L, SERVER_RECV, three))
            .addAnnotation(Annotation.create(1445136540394644L, SERVER_SEND, three))
            .addAnnotation(Annotation.create(1445136540397049L, CLIENT_RECV, twoPort3002))
            .build()
    );
    processDependencies(trace);

    long traceDuration = ApplyTimestampAndDuration.apply(trace.get(0)).duration;

    assertThat(
        storage.spanStore()
            .getDependencies((trace.get(0).annotations.get(0).timestamp + traceDuration) / 1000,
                traceDuration / 1000)
    ).containsOnly(
        DependencyLink.create("trace-producer-one", "trace-producer-two", 1),
        DependencyLink.create("trace-producer-two", "trace-producer-three", 1)
    );
  }
}
