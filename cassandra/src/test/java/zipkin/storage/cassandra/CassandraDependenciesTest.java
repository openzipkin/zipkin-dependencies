/**
 * Copyright 2015-2016 The OpenZipkin Authors
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
package zipkin.storage.cassandra;

import com.google.common.util.concurrent.Futures;
import io.zipkin.dependencies.spark.cassandra.ZipkinDependenciesJob;
import io.zipkin.dependencies.spark.cassandra.ZipkinDependenciesJob$;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import zipkin.Span;
import zipkin.internal.Util;
import zipkin.storage.DependenciesTest;
import zipkin.storage.QueryRequest;

public class CassandraDependenciesTest extends DependenciesTest {
  private final CassandraStorage storage;

  public CassandraDependenciesTest() {
    this.storage = CassandraTestGraph.INSTANCE.storage.get();
  }

  @Override protected CassandraStorage storage() {
    return storage;
  }

  @Override public void clear() {
    storage.clear();
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  @Override
  public void processDependencies(List<Span> spans) {
    Futures.getUnchecked(storage.guavaSpanConsumer().accept(spans));

    Set<Long> endTimestamps = new LinkedHashSet<Long>();
    long lookback = TimeUnit.DAYS.toMillis(1);
    for (List<Span> trace : storage.spanStore().getTraces(QueryRequest.builder().build())) {
      long startedAfter = Util.midnightUTC(trace.get(0).timestamp / 1000);
      endTimestamps.add(startedAfter + lookback);
    }
    for (long endTs : endTimestamps) {
      new ZipkinDependenciesJob(
          ZipkinDependenciesJob$.MODULE$.sparkMaster(),
          ZipkinDependenciesJob$.MODULE$.cassandraProperties(),
          storage.keyspace,
          // TODO: This is a daily job, and c* impl expects daily buckets, too!
          // We could make this more intuitive and less error-prone by accepting
          // a parameter of which day to process
          endTs,
          lookback
      ).run();
    }
  }

  // TODO: Tests below will pass once we change to DependencyLinker

  @Test(expected = AssertionError.class)
  @Override public void intermediateSpans() {
    super.intermediateSpans();
  }

  @Test(expected = AssertionError.class)
  @Override public void unmergedSpans() {
    super.unmergedSpans();
  }

  @Test(expected = AssertionError.class)
  @Override public void duplicateAddress() {
    super.duplicateAddress();
  }

  @Test(expected = AssertionError.class)
  @Override public void dependencies_headlessTrace() {
    super.dependencies_headlessTrace();
  }

  @Test(expected = AssertionError.class)
  @Override public void noCoreAnnotations() {
    super.noCoreAnnotations();
  }

  @Test(expected = AssertionError.class)
  @Override public void notInstrumentedClientAndServer() {
    super.notInstrumentedClientAndServer();
  }
}
