/*
 * Copyright 2016-2024 The OpenZipkin Authors
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

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin2.Span;
import zipkin2.storage.ITDependencies;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class ITCassandraDependencies extends ITDependencies<CassandraStorage> {
  @Container static CassandraContainer cassandra = new CassandraContainer();

  @Override protected CassandraStorage.Builder newStorageBuilder(TestInfo testInfo) {
    return cassandra.newStorageBuilder();
  }

  @Override public void clear() {
    cassandra.clear(storage);
  }

  @Override protected void processDependencies(List<Span> spans) throws Exception {
    cassandra.processDependencies(storage, spans);
  }
}
