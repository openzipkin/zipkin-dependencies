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
package zipkin2.storage.cassandra;

import com.datastax.driver.core.Host;
import com.datastax.driver.core.Session;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestName;
import zipkin.Span;
import zipkin.dependencies.cassandra3.CassandraDependenciesJob;
import zipkin.internal.MergeById;
import zipkin.internal.V2SpanConverter;
import zipkin.internal.V2StorageComponent;
import zipkin.storage.DependenciesTest;
import zipkin.storage.QueryRequest;
import zipkin.storage.StorageComponent;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.internal.ApplyTimestampAndDuration.guessTimestamp;
import static zipkin.internal.Util.midnightUTC;

public class CassandraDependenciesTest extends DependenciesTest {
  @ClassRule public static CassandraStorageRule cassandraStorageRule =
      new CassandraStorageRule("openzipkin/zipkin-cassandra:2.4.1");

  @Rule public TestName testName = new TestName();

  String keyspace;
  CassandraStorage storage;

  @Before @Override public void clear() {
    keyspace = testName.getMethodName().toLowerCase();
    if (keyspace.length() > 48) keyspace = keyspace.substring(keyspace.length() - 48);
    Session session = cassandraStorageRule.session();
    session.execute("DROP KEYSPACE IF EXISTS " + keyspace);
    assertThat(session.getCluster().getMetadata().getKeyspace(keyspace)).isNull();

    storage = cassandraStorageRule.computeStorageBuilder().keyspace(keyspace).build();
  }

  @Override protected StorageComponent storage() {
    return V2StorageComponent.create(storage);
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  @Override public void processDependencies(List<Span> spans) {
    accept(spans);

    // Now, block until writes complete, notably so we can read them.
    Session.State state = storage.session().getState();
    refresh:
    while (true) {
      for (Host host : state.getConnectedHosts()) {
        if (state.getInFlightQueries(host) > 0) {
          Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
          state = storage.session().getState();
          continue refresh;
        }
      }
      break;
    }

    /// Now actually process the dependencies
    Set<Long> days = new LinkedHashSet<>();
    for (List<Span> trace : storage().spanStore()
        .getTraces(QueryRequest.builder().limit(10000).build())) {
      days.add(midnightUTC(guessTimestamp(MergeById.apply(trace).get(0)) / 1000));
    }

    for (long day : days) {
      CassandraDependenciesJob.builder()
          .keyspace(keyspace)
          .localDc(storage.localDc())
          .contactPoints(storage.contactPoints())
          // probably a bad test name.. strictTraceId actually tests strictTraceId = false
          .strictTraceId(!"getDependencies_strictTraceId".equals(testName.getMethodName()))
          .internalInTest(true)
          .day(day).build().run();
    }
  }

  void accept(List<Span> page) {
    try {
      storage.spanConsumer().accept(V2SpanConverter.fromSpans(page)).execute();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
