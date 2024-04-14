/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.storage.cassandra;

import com.codahale.metrics.Gauge;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metrics.DefaultNodeMetric;
import com.datastax.oss.driver.api.core.metrics.Metrics;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import zipkin2.Span;
import zipkin2.dependencies.cassandra3.CassandraDependenciesJob;
import zipkin2.storage.cassandra.CassandraStorage.SessionFactory;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.utility.DockerImageName.parse;
import static zipkin2.Call.propagateIfFatal;
import static zipkin2.storage.ITDependencies.aggregateLinks;
import static zipkin2.storage.cassandra.Schema.TABLE_AUTOCOMPLETE_TAGS;
import static zipkin2.storage.cassandra.Schema.TABLE_DEPENDENCY;
import static zipkin2.storage.cassandra.Schema.TABLE_SERVICE_REMOTE_SERVICES;
import static zipkin2.storage.cassandra.Schema.TABLE_SERVICE_SPANS;
import static zipkin2.storage.cassandra.Schema.TABLE_SPAN;
import static zipkin2.storage.cassandra.Schema.TABLE_TRACE_BY_SERVICE_REMOTE_SERVICE;
import static zipkin2.storage.cassandra.Schema.TABLE_TRACE_BY_SERVICE_SPAN;

final class CassandraContainer extends GenericContainer<CassandraContainer> {
  static final Logger LOGGER = LoggerFactory.getLogger(CassandraContainer.class);
  static final List<String> SEARCH_TABLES = asList(
    TABLE_AUTOCOMPLETE_TAGS,
    TABLE_SERVICE_REMOTE_SERVICES,
    TABLE_SERVICE_SPANS,
    TABLE_TRACE_BY_SERVICE_REMOTE_SERVICE,
    TABLE_TRACE_BY_SERVICE_SPAN
  );

  CassandraContainer() {
    super(parse("ghcr.io/openzipkin/zipkin-cassandra:3.2.1"));
    addExposedPort(9042);
    waitStrategy = Wait.forHealthcheck();
    withLogConsumer(new Slf4jLogConsumer(LOGGER));
  }

  CqlSession globalSession;

  @Override public void start() {
    super.start();
    LOGGER.info("Using contactPoint " + contactPoint());
    globalSession = tryToInitializeSession(contactPoint());
  }

  // Builds a session without trying to use a namespace or init UDTs
  static CqlSession tryToInitializeSession(String contactPoint) {
    CassandraStorage storage = newStorageBuilder(contactPoint).build();
    CqlSession session = null;
    try {
      session = SessionFactory.DEFAULT.create(storage);
      session.execute("SELECT now() FROM system.local");
    } catch (Throwable e) {
      propagateIfFatal(e);
      if (session != null) {
        session.close();
      }
      assumeTrue(false, e.getMessage());
    }
    return session;
  }

  CassandraStorage.Builder newStorageBuilder() {
    return newStorageBuilder(contactPoint());
  }

  static CassandraStorage.Builder newStorageBuilder(String contactPoint) {
    return CassandraStorage.newBuilder().contactPoints(contactPoint).maxConnections(1);
  }

  String contactPoint() {
    return getHost() + ":" + getMappedPort(9042);
  }

  void clear(CassandraStorage storage) {
    // Clear any key cache
    CassandraSpanConsumer spanConsumer = storage.spanConsumer;
    if (spanConsumer != null) {
      spanConsumer.clear();
    }

    CqlSession session = storage.session.session;
    if (session == null) {
      session = globalSession;
    }

    List<String> toTruncate = new ArrayList<>(SEARCH_TABLES);
    toTruncate.add(TABLE_DEPENDENCY);
    toTruncate.add(TABLE_SPAN);

    for (String table : toTruncate) {
      try {
        session.execute("TRUNCATE " + storage.keyspace + "." + table);
      } catch (InvalidQueryException e) {
        assertThat(e).hasMessage("unconfigured table " + table);
      }
    }

    blockWhileInFlight(storage);
  }

  @Override
  public void stop() {
    if (globalSession != null) {
      globalSession.close();
    }
    super.stop();
  }

  static void blockWhileInFlight(CassandraStorage storage) {
    CqlSession session = storage.session.get();
    // Now, block until writes complete, notably so we can read them.
    boolean wasInFlight = false;
    while (true) {
      if (!poolInFlight(session)) {
        if (wasInFlight) {
          sleep(100); // give a little more to avoid flakey tests
        }
        return;
      }
      wasInFlight = true;
      sleep(100);
    }
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  // Use metrics to wait for in-flight requests to settle per
  // https://groups.google.com/a/lists.datastax.com/g/java-driver-user/c/5um_yGNynow/m/cInH5I5jBgAJ
  static boolean poolInFlight(CqlSession session) {
    Collection<Node> nodes = session.getMetadata().getNodes().values();
    Optional<Metrics> metrics = session.getMetrics();
    for (Node node : nodes) {
      int inFlight = metrics.flatMap(m -> m.getNodeMetric(node, DefaultNodeMetric.IN_FLIGHT))
        .map(m -> ((Gauge<Integer>) m).getValue())
        .orElse(0);
      if (inFlight > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  void processDependencies(CassandraStorage storage, List<Span> spans) throws Exception {
    // TODO: this avoids overrunning the cluster with BusyPoolException
    for (List<Span> nextChunk : Lists.partition(spans, 100)) {
      storage.spanConsumer().accept(nextChunk).execute();
      // Now, block until writes complete, notably so we can read them.
      blockWhileInFlight(storage);
    }

    // aggregate links in memory to determine which days they are in
    Set<Long> days = aggregateLinks(spans).keySet();

    // process the job for each day of links.
    for (long day : days) {
      CassandraDependenciesJob.builder()
        .keyspace(storage.keyspace)
        .localDc(storage.localDc)
        .contactPoints(storage.contactPoints)
        .strictTraceId(false)
        .day(day)
        .build()
        .run();
    }

    blockWhileInFlight(storage);
  }
}
