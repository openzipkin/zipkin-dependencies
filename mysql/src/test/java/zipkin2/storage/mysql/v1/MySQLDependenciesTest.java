/*
 * Copyright 2016-2018 The OpenZipkin Authors
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
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import zipkin.Span;
import zipkin.dependencies.mysql.MySQLDependenciesJob;
import zipkin.internal.MergeById;
import zipkin.internal.V2SpanConverter;
import zipkin.internal.V2StorageComponent;
import zipkin.storage.DependenciesTest;
import zipkin.storage.QueryRequest;
import zipkin.storage.StorageComponent;

import static zipkin.internal.ApplyTimestampAndDuration.guessTimestamp;
import static zipkin.internal.Util.midnightUTC;

public class MySQLDependenciesTest extends DependenciesTest {
  private final MySQLStorage storage;

  public MySQLDependenciesTest() {
    this.storage = MySQLTestGraph.INSTANCE.storage.get();
  }

  @Override
  protected StorageComponent storage() {
    return V2StorageComponent.create(storage);
  }

  @Override
  public void clear() {
    storage.clear();
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  @Override
  public void processDependencies(List<Span> spans) {
    accept(spans);

    Set<Long> days = new LinkedHashSet<>();
    for (List<Span> trace :
        storage().spanStore().getTraces(QueryRequest.builder().limit(10000).build())) {
      days.add(midnightUTC(guessTimestamp(MergeById.apply(trace).get(0)) / 1000));
    }

    for (long day : days) {
      MySQLDependenciesJob.builder().day(day).build().run();
    }
  }

  void accept(List<Span> page) {
    try {
      storage.spanConsumer().accept(V2SpanConverter.fromSpans(page)).execute();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
