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
@Tag("elasticsearch8")
@Testcontainers(disabledWithoutDocker = true)
class ITElasticsearchDependenciesHeavyV8 extends ITDependenciesHeavy<ElasticsearchStorage> {
  @Container static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(8);

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
