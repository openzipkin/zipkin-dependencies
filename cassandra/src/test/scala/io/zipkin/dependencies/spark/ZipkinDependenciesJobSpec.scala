package io.zipkin.dependencies.spark

import com.twitter.util.Await._
import com.twitter.zipkin.Constants
import com.twitter.zipkin.adjuster.ApplyTimestampAndDuration
import com.twitter.zipkin.common.{DependencyLink, Span, Trace, _}
import com.twitter.zipkin.storage.DependencyStoreSpec
import com.twitter.zipkin.storage.cassandra.{CassandraDependencyStore, CassandraSpanStore}
import io.zipkin.dependencies.spark.cassandra.ZipkinDependenciesJob
import org.junit.{AssumptionViolatedException, BeforeClass, Test}

object ZipkinDependenciesJobSpec {

  /** This intentionally silently aborts when cassandra is not running on localhost. */
  @BeforeClass def ensureCassandra: Unit = {
    try {
      CassandraFixture.repository
    } catch {
      case e: Exception => throw new AssumptionViolatedException("Cassandra not running", e)
    }
  }
}
/**
 * Micro-integration test that shows [[ZipkinDependenciesJob]] is compatible
 * with other dependency store implementations, such as SQL.
 */
class ZipkinDependenciesJobSpec extends DependencyStoreSpec {

  val spanStore = new CassandraSpanStore {
    /** Deferred as repository creates network connections */
    override lazy val repository = CassandraFixture.repository
  }

  override val store = new CassandraDependencyStore {
    /** Deferred as repository creates network connections */
    override lazy val repository = CassandraFixture.repository
  }

  /**
   * Unlike [[ZipkinDependenciesJobSpec]] in upstream, this processes
   * dependencies in the span store via [[ZipkinDependenciesJob]].
   */
  override def processDependencies(spans: List[Span]) = {
    val traceDuration = Trace.duration(spans).get
    val ts = spans.head.timestamp.get
    val endTs = (ts + traceDuration) / 1000
    val lookback = traceDuration / 1000
    processDependencies(spans, endTs = endTs, lookback = lookback)
  }

  private def processDependencies(spans: List[Span], endTs: Long, lookback: Long) = {
    result(spanStore.apply(spans))

    new ZipkinDependenciesJob(
      cassandraProperties = Map(
        "spark.ui.enabled" -> "false",
        "spark.cassandra.connection.host" -> "127.0.0.1",
        "spark.cassandra.connection.port" -> "9042"
      ),
      keyspace = CassandraFixture.keyspace,
      endTs = endTs,
      lookback = lookback
    ).run()
  }

  override def clear = CassandraFixture.truncate

  @Test def testDefaultTimeWindow(): Unit = {
    import ZipkinDependenciesJob.{defaultEndTs, defaultLookback}
    // verify we have the right data
    dep.endTs shouldBe >(today)
    dep.startTs shouldBe >=(today)

    // Let's pretend we have two days of data processed, yesterday, and the day before.
    // We run the job with the default time window,
    processDependencies(subtractDay(subtractDay(trace)) ++ subtractDay(trace),
      endTs = defaultEndTs, lookback = defaultLookback)

    // A user looks at today's links => we should not see double counts
    result(store.getDependencies(defaultEndTs, Some(defaultLookback))) should be(dep.links)

    // Now try the same but with 2 days lookback (note that it will override previous record)
    processDependencies(subtractDay(subtractDay(trace)) ++ subtractDay(trace),
      endTs = defaultEndTs, lookback = defaultLookback * 2)

    result(store.getDependencies(dep.endTs)) should be(
      List(
        new DependencyLink("zipkin-web", "zipkin-query", 2),
        new DependencyLink("zipkin-query", "zipkin-jdbc", 2)
      )
    )
  }

  /** rebases a trace backwards a day. */
  private def subtractDay(trace: List[Span]) = trace.map(s =>
    s.copy(
      traceId = s.traceId + 1,
      timestamp = s.timestamp.map(_ - (day * 1000)),
      annotations = s.annotations.map(a => a.copy(timestamp = a.timestamp - (day * 1000)))
    )
  )

  // TODO remove once this test is moved into DependencyStoreSpec in Zipkin
  /**
   * In some cases an RPC call is made where one of the two services is not instrumented.
   * However, if the other service is able to emit "sa" or "ca" annotation with a service
   * name, the link can still be constructed.
   */
  @Test def testGetDependenciesOnlyServerInstrumented(): Unit = {
    val client = Endpoint(127 << 24 | 1, 9410, "not-instrumented-client")
    val server = Endpoint(127 << 24 | 2, 9410, "instrumented-server")

    val trace = ApplyTimestampAndDuration(List(
      Span(10L, "get", 10L, annotations = List(
        Annotation((today + 100) * 1000, Constants.ServerRecv, Some(server)),
        Annotation((today + 350) * 1000, Constants.ServerSend, Some(server))),
        binaryAnnotations = List(
          BinaryAnnotation(Constants.ClientAddr, true, Some(client))))
    ))
    processDependencies(trace)

    result(store.getDependencies(today + 1000)).sortBy(_.parent) should be(
      List(
        new DependencyLink("not-instrumented-client", "instrumented-server", 1)
      )
    )
  }

  // TODO remove once this test is moved into DependencyStoreSpec in Zipkin
  /**
   * In some cases an RPC call is made where one of the two services is not instrumented.
   * However, if the other service is able to emit "sa" or "ca" annotation with a service
   * name, the link can still be constructed.
   */
  @Test def testGetDependenciesOnlyClientInstrumented(): Unit = {
    val client = Endpoint(127 << 24 | 1, 9410, "instrumented-client")
    val server = Endpoint(127 << 24 | 2, 9410, "not-instrumented-server")

    val trace = ApplyTimestampAndDuration(List(
      Span(10L, "get", 10L, annotations = List(
        Annotation((today + 100) * 1000, Constants.ClientSend, Some(client)),
        Annotation((today + 350) * 1000, Constants.ClientRecv, Some(client))),
        binaryAnnotations = List(
          BinaryAnnotation(Constants.ServerAddr, true, Some(server))))
    ))
    processDependencies(trace)

    result(store.getDependencies(today + 1000)).sortBy(_.parent) should be(
      List(
        new DependencyLink("instrumented-client", "not-instrumented-server", 1)
      )
    )
  }

  // TODO remove once fixed in zipkin: https://github.com/openzipkin/zipkin/issues/917
  // Temporarily override this test from the main suite, because we can do a better job than what it expects.
  @Test override def dependencies_headlessTrace {
    processDependencies(List(trace(1), trace(2)))

    result(store.getDependencies(today + 1000)) should be(dep.links)
  }
}
