/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.storage.elasticsearch;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin2.Span;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.storage.ITDependenciesHeavy;

@Tag("docker")
@Tag("opensearch2")
@Testcontainers(disabledWithoutDocker = true)
class ITOpensearchDependenciesHeavyV2 extends ITDependenciesHeavy<ElasticsearchStorage> {
  @Container static OpensearchContainer opensearch = new OpensearchContainer(2);

  @Override protected ElasticsearchStorage.Builder newStorageBuilder(TestInfo testInfo) {
    return opensearch.newStorageBuilder();
  }

  @Override public void clear() throws IOException {
    storage.clear();
  }

  @Override protected void processDependencies(List<Span> spans) throws Exception {
    opensearch.processDependencies(storage, spans);
  }
}
