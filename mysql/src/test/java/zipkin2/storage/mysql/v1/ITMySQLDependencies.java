/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.storage.mysql.v1;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin2.Span;
import zipkin2.storage.ITDependencies;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class ITMySQLDependencies extends ITDependencies<MySQLStorage> {
  @Container static MySQLContainer mysql = new MySQLContainer();

  @Override protected MySQLStorage.Builder newStorageBuilder(TestInfo testInfo) {
    return mysql.newStorageBuilder();
  }

  @Override public void clear() {
    storage.clear();
  }

  @Override protected void processDependencies(List<Span> spans) throws Exception {
    mysql.processDependencies(storage, spans);
  }
}
