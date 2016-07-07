package io.zipkin.dependencies.spark

import com.twitter.util.Await._
import com.twitter.zipkin.common.{DependencyLink, Span, Trace}
import com.twitter.zipkin.storage.DependencyStoreSpec
import com.twitter.zipkin.storage.cassandra.{CassandraDependencyStore, CassandraSpanStore}
import io.zipkin.dependencies.spark.cassandra.ZipkinDependenciesJob
import org.junit.{AssumptionViolatedException, BeforeClass, Test}
import org.scalatest.TestFailedException

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
    import ZipkinDependenciesJob.{ defaultEndTs, defaultLookback }
    // verify we have the right data
    dep.endTs shouldBe > (today)
    dep.startTs shouldBe >= (today)

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

  // unimplemented dependency link features
  @Test(expected = classOf[TestFailedException])
  override def getDependencies_notInstrumentedClientAndServer(): Unit = {
    super.getDependencies_notInstrumentedClientAndServer()
  }

  @Test(expected = classOf[TestFailedException])
  override def dependencies_headlessTrace: Unit = {
    super.dependencies_headlessTrace
  }

  @Test(expected = classOf[TestFailedException])
  override def getDependencies_intermediateSpans(): Unit = {
    super.getDependencies_intermediateSpans()
  }

  /** rebases a trace backwards a day. */
  private def subtractDay(trace: List[Span]) = trace.map(s =>
    s.copy(
      traceId = s.traceId + 1,
      timestamp = s.timestamp.map(_ - (day * 1000)),
      annotations = s.annotations.map(a => a.copy(timestamp = a.timestamp - (day * 1000)))
    )
  )
}
