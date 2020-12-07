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
package zipkin2.elasticsearch;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Span;
import zipkin2.dependencies.elasticsearch.ElasticsearchDependenciesJob;

import static zipkin2.storage.ITDependencies.aggregateLinks;

abstract class ITElasticsearchDependencies {
  abstract ElasticsearchExtension elasticsearch();

  String index;

  ElasticsearchStorage.Builder newStorageBuilder(TestInfo testInfo) {
    index = testInfo.getTestClass().get().getName().toLowerCase();
    if (index.length() > 48) index = index.substring(index.length() - 48);
    return elasticsearch().computeStorageBuilder().index(index);
  }

  @Nested
  class ITDependencies extends zipkin2.storage.ITDependencies<ElasticsearchStorage> {

    @Override protected ElasticsearchStorage.Builder newStorageBuilder(TestInfo testInfo) {
      return ITElasticsearchDependencies.this.newStorageBuilder(testInfo);
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }

    @Override protected void processDependencies(List<Span> spans) throws Exception {
      ITElasticsearchDependencies.this.processDependencies(storage, spans);
    }
  }

  @Nested
  class ITDependenciesHeavy extends zipkin2.storage.ITDependenciesHeavy<ElasticsearchStorage> {

    @Override protected ElasticsearchStorage.Builder newStorageBuilder(TestInfo testInfo) {
      return ITElasticsearchDependencies.this.newStorageBuilder(testInfo);
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }

    @Override protected void processDependencies(List<Span> spans) throws Exception {
      ITElasticsearchDependencies.this.processDependencies(storage, spans);
    }
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  void processDependencies(ElasticsearchStorage storage, List<Span> spans) throws IOException {
    storage.spanConsumer().accept(spans).execute();

    // aggregate links in memory to determine which days they are in
    Set<Long> days = aggregateLinks(spans).keySet();

    // process the job for each day of links.
    for (long day : days) {
      ElasticsearchDependenciesJob.builder()
        .index(index)
        .hosts(elasticsearch().hostPort())
        .day(day)
        .build()
        .run();
    }
  }
}
