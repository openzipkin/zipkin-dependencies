package io.zipkin.dependencies.spark.cassandra

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import com.datastax.spark.connector._
import com.datastax.spark.connector.rdd.CassandraRDD
import com.google.common.base.Predicate
import com.google.common.collect.Collections2
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import zipkin.internal.Util._
import zipkin.internal.{Dependencies, DependencyLinkSpan, DependencyLinker, MergeById}
import zipkin.{Annotation, BinaryAnnotation, Constants, DependencyLink, Span}

import scala.collection.JavaConverters._

object ZipkinDependenciesJob {

  val keyspace = sys.env.getOrElse("CASSANDRA_KEYSPACE", "zipkin")

  val cassandraProperties = Map(
    "spark.ui.enabled" -> "false",
    "spark.cassandra.connection.host" -> sys.env.getOrElse("CASSANDRA_HOST", "127.0.0.1"),
    "spark.cassandra.connection.port" -> sys.env.getOrElse("CASSANDRA_PORT", "9042"),
    "spark.cassandra.auth.username" -> sys.env.getOrElse("CASSANDRA_USERNAME", ""),
    "spark.cassandra.auth.password" -> sys.env.getOrElse("CASSANDRA_PASSWORD", "")
  )

  // local[*] master lets us run & test the job locally without setting a Spark cluster
  val sparkMaster = sys.env.getOrElse("SPARK_MASTER", "local[*]")

  // By default the job only works on traces whose first timestamp is today
  val day: Long = midnightUTC(System.currentTimeMillis)

  def main(args: Array[String]) = {
    new ZipkinDependenciesJob(sparkMaster, cassandraProperties, keyspace).run()
  }
}

case class ZipkinDependenciesJob(sparkMaster: String = ZipkinDependenciesJob.sparkMaster,
                                 cassandraProperties: Map[String, String] = ZipkinDependenciesJob.cassandraProperties,
                                 keyspace: String = ZipkinDependenciesJob.keyspace,
                                 day: Long = ZipkinDependenciesJob.day) {

  val dateFormat = new SimpleDateFormat("yyyy-MM-dd")

  val startTs = midnightUTC(day)
  val endTs = startTs + TimeUnit.DAYS.toMillis(1) - 1
  val microsLower = startTs * 1000
  val microsUpper = (endTs * 1000) + 999

  def inTimeRange(span: Span): Boolean = {
    span.timestamp != null && microsLower <= span.timestamp && span.timestamp <= microsUpper
  }

  def run() {
    val conf = new SparkConf(true)
      .setAll(cassandraProperties)
      .setMaster(sparkMaster)
      .setAppName(getClass.getName)

    println(s"Running Dependencies job for ${dateFormat.format(new Date(day))}: $microsLower ≤ Span.timestamp ≤ $microsUpper")

    val sc = new SparkContext(conf)

    val table: CassandraRDD[CassandraRow] = sc.cassandraTable(keyspace, "traces")
    // ^^^ If need to drill further into the data, add this: .where("wsid='725030:14732'")

    def makeLinks(traceId: Long, rows: Iterable[CassandraRow]): Iterable[DependencyLink] = {
      val spans = MergeById.apply(
        rows.map(row => zipkin.Codec.THRIFT.readSpan(row.getBytes("span"))).toList.asJava
      )
      if (inTimeRange(spans.get(0))) {
        val linkSpans = spans.asScala.map(DependencyLinkSpan.from).iterator.asJava
        new DependencyLinker().putTrace(linkSpans).link().asScala
      } else {
        Iterable.empty[DependencyLink]
      }
    }

    val aggregates: RDD[((String, String), Long)] = table
      .spanBy(row => row.getLong("trace_id"))    // read the whole partition
      .flatMap(pair => makeLinks(pair._1, pair._2))  // and reduce it all at once, in memory
      .map(link => ((link.parent, link.child), link.callCount))
      .reduceByKey(_ + _) // add up the counts

    val toDepInfo: PartialFunction[Any, DependenciesInfo] = {
      case ((parent: String, child: String), callCount: Long) =>
        DependenciesInfo(Seq(DependencyLink.create(parent, child, callCount)))
    }

    // reduce does not work on empty collections, so add an empty sentinel just in case
    val dependencies: DependenciesInfo =
      (aggregates.map(toDepInfo) ++ sc.parallelize(Seq(DependenciesInfo(Seq()))))
        .reduce(_ + _) // merge under one Dependencies object, which overrides +

    saveToCassandra(sc, keyspace, dependencies)

    println(s"Dependencies: $dependencies")

    sc.stop()
  }

  def saveToCassandra(sc: SparkContext, keyspace: String, dependencies: DependenciesInfo): Unit = {
    val thrift = Dependencies.create(startTs,  endTs, dependencies.links.asJava)
    val blob: Array[Byte] = thrift.toThrift.array()
    val output = (day, blob)

    sc.parallelize(Seq(output)).saveToCassandra(keyspace, "dependencies", SomeColumns("day" as "_1", "dependencies" as "_2"))
    println(s"Saved with day=$day ${dateFormat.format(new Date(day))}")
  }
}
