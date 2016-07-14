package io.zipkin.dependencies.spark.cassandra

import java.util.Date
import java.util.concurrent.TimeUnit

import com.datastax.spark.connector._
import com.datastax.spark.connector.rdd.CassandraRDD
import com.google.common.base.Predicate
import com.google.common.collect.Collections2
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import zipkin.internal.Dependencies
import zipkin.internal.Util._
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

  // By default the job only considers spans with timestamps up to previous midnight
  val defaultEndTs: Long = midnightUTC(System.currentTimeMillis)

  // By default the job only accounts for spans in the 24hrs prior to previous midnight
  val defaultLookback: Long = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)

  def main(args: Array[String]) = {
    new ZipkinDependenciesJob(sparkMaster, cassandraProperties, keyspace).run()
  }
}

case class ZipkinDependenciesJob(sparkMaster: String = ZipkinDependenciesJob.sparkMaster,
                                 cassandraProperties: Map[String, String] = ZipkinDependenciesJob.cassandraProperties,
                                 keyspace: String = ZipkinDependenciesJob.keyspace,
                                 endTs: Long = ZipkinDependenciesJob.defaultEndTs,
                                 lookback: Long = ZipkinDependenciesJob.defaultLookback) {

  val annotationsToConsider = Set(Constants.CLIENT_SEND, Constants.CLIENT_RECV, Constants.SERVER_SEND, Constants.SERVER_RECV)
  val binaryAnnotationsToConsider = Set(Constants.CLIENT_ADDR, Constants.SERVER_ADDR, Constants.LOCAL_COMPONENT)

  val startTs = endTs - lookback
  val microsUpper = endTs * 1000
  val microsLower = startTs * 1000

  /**
   * @return true if Span contains at most one of each core annotation, false otherwise
   */
  def isValid(span: Span): Boolean = {
    // TODO this has the potential to count the same span twice in runs with adjacent time windows.
    // Ideally, one of `<=` should be strict `<`. Depends on https://github.com/openzipkin/zipkin/issues/924.
    val inTimeRange: Boolean = span.timestamp != null && microsLower <= span.timestamp && span.timestamp <= microsUpper
    val serviceNameDefined = serviceName(span).isDefined
    val isValid = inTimeRange && serviceNameDefined
    isValid
  }

  case class Trace(id: Long, spans: Map[Long, Span]) {
    def mergeTrace(t: Trace): Trace = {
      Trace(id, spans ++ t.spans)
    }
    def getLinks: Iterable[(String, String)] = {
      spans.values
        .filter(_.parentId != null)
        .flatMap(span => spans.get(span.parentId).map(parentSpan => (serviceName(parentSpan).get, serviceName(span).get)))
    }
  }

  def run() {
    val conf = new SparkConf(true)
      .setAll(cassandraProperties)
      .setMaster(sparkMaster)
      .setAppName(getClass.getName)

    println(s"Running Dependencies job with startTs=$startTs (${new Date(startTs)}) and endTs=$endTs (${new Date(endTs)})")

    val sc = new SparkContext(conf)

    val table: CassandraRDD[CassandraRow] = sc.cassandraTable(keyspace, "traces")
    // ^^^ If need to drill further into the data, add this: .where("wsid='725030:14732'")

    def makeTrace(traceId: Long, rows: Iterable[CassandraRow]): Trace = {
      val spans: Map[Long, Span] = rows
        .map(rowToSpan)
        .groupBy(span => span.id)
        .mapValues(spans => spans.toList match {
          case span :: nil => span
          case head :: tail => tail.fold(head)((s1, s2) => s1.toBuilder().merge(s2).build())
          case _ => null // this can never happen
        })
        .filter(kv => isValid(kv._2))
      Trace(traceId, spans)
    }

    val aggregates: RDD[((String, String), Long)] = table
      .spanBy(row => row.getLong("trace_id"))    // read the whole partition
      .map(pair => makeTrace(pair._1, pair._2))  // and reduce it all at once, in memory
      .flatMap(_.getLinks)
      .map { case (parent, child) => ((parent, child), 1L) } // start the count
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

  /**
    * Filters out span data irrelevant to dependency linking, which makes intermediate
    * stages require less data.
    */
  def rowToSpan(row: CassandraRow): Span = {
    val unfiltered = zipkin.Codec.THRIFT.readSpan(row.getBytes("span"))
    return unfiltered.toBuilder
      .annotations(Collections2.filter(unfiltered.annotations, new Predicate[Annotation] {
        override def apply(input: Annotation) = annotationsToConsider.contains(input.value)
      }))
      .binaryAnnotations(Collections2.filter(unfiltered.binaryAnnotations, new Predicate[BinaryAnnotation] {
        override def apply(input: BinaryAnnotation) = binaryAnnotationsToConsider.contains(input.key)
      }))
      .build()
  }

  def saveToCassandra(sc: SparkContext, keyspace: String, dependencies: DependenciesInfo): Unit = {
    val thrift = Dependencies.create(startTs,  endTs, dependencies.links.asJava)
    val blob: Array[Byte] = thrift.toThrift.array()

    // links are stored under the day they are in (startTs), not the day they are before (endTs).
    val day = midnightUTC(startTs)
    val output = (day, blob)

    sc.parallelize(Seq(output)).saveToCassandra(keyspace, "dependencies", SomeColumns("day" as "_1", "dependencies" as "_2"))
    println(s"Saved with day=$day (${new Date(day)})")
  }

  /**
   * Historical service name chooser
   *
   * Tries to extract the best name of the service in this span. This depends on annotations
   * logged and prioritized names logged by the server over those logged by the client.
   */
  def serviceName(span: Span): Option[String] = {
    // Most authoritative is the label of the server's endpoint
    serviceNameOfBinaryAnnotation(span, Constants.SERVER_ADDR) orElse
      // Next, the label of any server annotation, logged by an instrumented server
      serviceNameOfCoreAnnotationStartingWith(span, "s") orElse
      // Next is the label of the client's endpoint
      serviceNameOfBinaryAnnotation(span, Constants.CLIENT_ADDR) orElse
      // Next is the label of any client annotation, logged by an instrumented client
      serviceNameOfCoreAnnotationStartingWith(span, "c") orElse
      // Finally is the label of the local component's endpoint
      serviceNameOfBinaryAnnotation(span, Constants.LOCAL_COMPONENT)
  }

  def serviceNameOfBinaryAnnotation(span: Span, key: String): Option[String] = {
    span.binaryAnnotations.asScala.find(_.key == key)
      .filter(_.endpoint != null)
      .map(_.endpoint.serviceName)
      .filterNot(_.isEmpty)
  }

  def serviceNameOfCoreAnnotationStartingWith(span: Span, prefix: String): Option[String] = {
    span.annotations.asScala
      .find(a => a.value.startsWith(prefix) && annotationsToConsider.contains(a.value))
      .filter(_.endpoint != null)
      .map(_.endpoint.serviceName)
      .filterNot(_.isEmpty)
  }
}
