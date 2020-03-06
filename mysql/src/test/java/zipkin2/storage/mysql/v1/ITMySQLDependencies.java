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
package zipkin2.storage.mysql.v1;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import zipkin2.Span;
import zipkin2.dependencies.mysql.MySQLDependenciesJob;
import zipkin2.storage.ITDependencies;
import zipkin2.storage.StorageComponent;

import static org.testcontainers.containers.MySQLContainer.MYSQL_PORT;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITMySQLDependencies extends ITDependencies<MySQLStorage> {
  @RegisterExtension MySQLStorageExtension backend = new MySQLStorageExtension(
    "openzipkin/zipkin-mysql:2.20.0");

  @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
    return backend.computeStorageBuilder();
  }

  @Override public void clear() {
    storage.clear();
  }

  /** This processes the job as if it were a batch. For each day we had traces, run the job again. */
  @Override public void processDependencies(List<Span> spans) throws IOException {
    storage.spanConsumer().accept(spans).execute();

    // aggregate links in memory to determine which days they are in
    Set<Long> days = aggregateLinks(spans).keySet();

    // process the job for each day of links.
    for (long day : days) {
      MySQLDependenciesJob.builder()
        .user(backend.container.getUsername())
        .password(backend.container.getPassword())
        .port(backend.container.getMappedPort(MYSQL_PORT))
        .db(backend.container.getDatabaseName())
        .day(day).build().run();
    }
  }
}
