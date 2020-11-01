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
package zipkin2.storage.cassandra;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.utility.DockerImageName;
import zipkin2.Span;
import zipkin2.dependencies.cassandra3.CassandraDependenciesJob;

import static zipkin2.storage.ITDependencies.aggregateLinks;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITCassandraDependencies {
  @RegisterExtension CassandraStorageExtension backend = new CassandraStorageExtension(
    DockerImageName.parse("ghcr.io/openzipkin/zipkin-cassandra:2.22.1"));

  @Nested
  class ITDependencies extends zipkin2.storage.ITDependencies<CassandraStorage> {

    @Override protected CassandraStorage.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override public void clear() {
      backend.clear(storage);
    }

    @Override protected void processDependencies(List<Span> spans) throws Exception {
      ITCassandraDependencies.this.processDependencies(storage, spans);
    }
  }

  @Nested
  class ITDependenciesHeavy extends zipkin2.storage.ITDependenciesHeavy<CassandraStorage> {

    @Override protected CassandraStorage.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override public void clear() {
      backend.clear(storage);
    }

    @Override protected void processDependencies(List<Span> spans) throws Exception {
      ITCassandraDependencies.this.processDependencies(storage, spans);
    }
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  void processDependencies(CassandraStorage storage, List<Span> spans) throws Exception {
    // TODO: this avoids overrunning the cluster with BusyPoolException
    for (List<Span> nextChunk : Lists.partition(spans, 100)) {
      storage.spanConsumer().accept(nextChunk).execute();
      // Now, block until writes complete, notably so we can read them.
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    // aggregate links in memory to determine which days they are in
    Set<Long> days = aggregateLinks(spans).keySet();

    // process the job for each day of links.
    for (long day : days) {
      CassandraDependenciesJob.builder()
        .keyspace(storage.keyspace)
        .localDc(storage.localDc)
        .contactPoints(storage.contactPoints)
        .strictTraceId(false)
        .day(day)
        .build()
        .run();
    }

    CassandraStorageExtension.blockWhileInFlight(storage);
  }
}
