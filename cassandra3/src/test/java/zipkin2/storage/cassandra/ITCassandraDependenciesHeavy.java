/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.storage.cassandra;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin2.Span;
import zipkin2.storage.ITDependenciesHeavy;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class ITCassandraDependenciesHeavy extends ITDependenciesHeavy<CassandraStorage> {
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
