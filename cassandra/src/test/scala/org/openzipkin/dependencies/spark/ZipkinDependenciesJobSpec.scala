package org.openzipkin.dependencies.spark

import com.twitter.util.Await._
import com.twitter.zipkin.common.Span
import com.twitter.zipkin.storage.DependencyStoreSpec
import com.twitter.zipkin.storage.cassandra.{CassandraDependencyStore, CassandraSpanStore}
import org.junit.{BeforeClass, Ignore, Test}
import org.openzipkin.dependencies.spark.cassandra.ZipkinDependenciesJob

object ZipkinDependenciesJobSpec {

  @BeforeClass def ensureCassandra = CassandraFixture.cassandra
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
    result(spanStore.apply(spans))

    ZipkinDependenciesJob.run(
      cassandraProperties = Map(
        "spark.ui.enabled" -> "false",
        "spark.cassandra.connection.host" -> "127.0.0.1",
        "spark.cassandra.connection.port" -> "9142"
      ),
      keyspace = CassandraFixture.keyspace
    )
  }

  @Test
  @Ignore
  // FIXME
  override def getDependenciesAllInstrumented() = {}

  override def clear = CassandraFixture.truncate
}
