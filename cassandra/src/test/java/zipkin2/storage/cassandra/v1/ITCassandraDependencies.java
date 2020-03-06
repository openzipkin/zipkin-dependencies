/*
 * Copyright 2016-2020 The OpenZipkin Authors
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
package zipkin2.storage.cassandra.v1;

import com.datastax.driver.core.Host;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Uninterruptibles;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import zipkin2.Span;
import zipkin2.dependencies.cassandra.CassandraDependenciesJob;
import zipkin2.storage.ITDependencies;
import zipkin2.storage.StorageComponent;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITCassandraDependencies extends ITDependencies<CassandraStorage> {
  @RegisterExtension CassandraStorageExtension backend = new CassandraStorageExtension(
    "openzipkin/zipkin-cassandra:2.20.0");

  String keyspace;

  @Override protected boolean initializeStoragePerTest() {
    return true;
  }

  @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
    keyspace = testInfo.getTestMethod().get().getName().toLowerCase();
    if (keyspace.length() > 48) keyspace = keyspace.substring(keyspace.length() - 48);
    return backend.computeStorageBuilder().keyspace(keyspace);
  }

  @Override public void clear() {
    // Just let the data pile up to prevent warnings and slowness.
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  @Override protected void processDependencies(List<Span> spans) throws Exception {
    // TODO: this avoids overrunning the cluster with BusyPoolException
    for (List<Span> nextChunk : Lists.partition(spans, 100)) {
      storage.spanConsumer().accept(nextChunk).execute();
      // Now, block until writes complete, notably so we can read them.
      blockWhileInFlight(storage);
    }

    // aggregate links in memory to determine which days they are in
    Set<Long> days = aggregateLinks(spans).keySet();

    // process the job for each day of links.
    for (long day : days) {
      CassandraDependenciesJob.builder()
        .keyspace(keyspace)
        .localDc(storage.localDc)
        .contactPoints(storage.contactPoints)
        .day(day)
        .build()
        .run();
    }
  }

  static void blockWhileInFlight(CassandraStorage storage) {
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
  }
}
