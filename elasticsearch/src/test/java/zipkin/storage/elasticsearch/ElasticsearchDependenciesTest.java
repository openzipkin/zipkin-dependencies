/**
 * Copyright 2016-2017 The OpenZipkin Authors
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
package zipkin.storage.elasticsearch;

import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import zipkin.Span;
import zipkin.dependencies.elasticsearch.ElasticsearchDependenciesJob;
import zipkin.internal.MergeById;
import zipkin.storage.DependenciesTest;

import static zipkin.internal.ApplyTimestampAndDuration.guessTimestamp;

public class ElasticsearchDependenciesTest extends DependenciesTest {
  private final ElasticsearchStorage storage;
  private final String index;

  public ElasticsearchDependenciesTest() {
    this.storage = ElasticsearchTestGraph.INSTANCE.storage.get();
    this.index = ElasticsearchTestGraph.INSTANCE.index;
  }

  @Override protected ElasticsearchStorage storage() {
    return storage;
  }

  @Override public void clear() throws IOException {
    storage.clear();
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  @Override
  public void processDependencies(List<Span> spans) {
    // This gets or derives a timestamp from the spans
    spans = MergeById.apply(spans);

    Futures.getUnchecked(storage.guavaSpanConsumer().accept(spans));

    Set<Long> days = new LinkedHashSet<>();
    for (Span span : spans) {
      days.add(guessTimestamp(span) / 1000);
    }
    for (long day : days) {
      ElasticsearchDependenciesJob.builder().index(index).day(day).build().run();
    }
  }
}
