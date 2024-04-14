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
import zipkin2.storage.ITDependencies;

@Tag("docker")
@Tag("elasticsearch7")
@Testcontainers(disabledWithoutDocker = true)
class ITElasticsearchDependenciesV7 extends ITDependencies<ElasticsearchStorage> {
  @Container static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(7);

  @Override protected ElasticsearchStorage.Builder newStorageBuilder(TestInfo testInfo) {
    return elasticsearch.newStorageBuilder();
  }

  @Override public void clear() throws IOException {
    storage.clear();
  }

  @Override protected void processDependencies(List<Span> spans) throws Exception {
    elasticsearch.processDependencies(storage, spans);
  }
}
