package io.zipkin.dependencies.spark.cassandra

import java.util.concurrent.TimeUnit._

import com.datastax.spark.connector._
import com.datastax.spark.connector.rdd.CassandraRDD
import com.twitter.util.{Duration, Time}
import com.twitter.zipkin.Constants
import com.twitter.zipkin.conversions.thrift._
import com.twitter.zipkin.storage.cassandra.{CassandraSpanStoreDefaults, ScroogeThriftCodec}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

object ZipkinDependenciesJob {

  val keyspace = sys.env.getOrElse("CASSANDRA_KEYSPACE", "zipkin")

  val cassandraProperties = Map(
    "spark.cassandra.connection.host" -> sys.env.getOrElse("CASSANDRA_HOST", "127.0.0.1"),
    "spark.cassandra.connection.port" -> sys.env.getOrElse("CASSANDRA_PORT", "9042"),
    "spark.cassandra.auth.username" -> sys.env.getOrElse("CASSANDRA_USERNAME", ""),
    "spark.cassandra.auth.password" -> sys.env.getOrElse("CASSANDRA_PASSWORD", "")
  )

  // local[*] master lets us run & test the job locally without setting a Spark cluster
  val sparkMaster = sys.env.getOrElse("SPARK_MASTER", "local[*]")

  // By default the job only considers spans with timestamps up to previous midnight
  val defaultEndTs: Long = Time.now.floor(Duration.fromTimeUnit(1, DAYS)).inMilliseconds

  // By default the job only accounts for spans in the 24hrs prior to previous midnight
  val defaultLookback: Long = Duration.fromTimeUnit(1, DAYS).inMilliseconds

  def main(args: Array[String]) = {
    new ZipkinDependenciesJob(sparkMaster, cassandraProperties, keyspace).run()
  }
}

case class ZipkinDependenciesJob(sparkMaster: String = ZipkinDependenciesJob.sparkMaster,
                                 cassandraProperties: Map[String, String] = ZipkinDependenciesJob.cassandraProperties,
                                 keyspace: String = ZipkinDependenciesJob.keyspace,
                                 endTs: Long = ZipkinDependenciesJob.defaultEndTs,
                                 lookback: Long = ZipkinDependenciesJob.defaultLookback) {

  val startTs = endTs - lookback
  val microsUpper = endTs * 1000
  val microsLower = startTs * 1000

  /**
   * RDD needs the data objects to be serializable, at least at some points. The zipkin.common.Span is not serializable,
   * and has a lot of extra stuff not needed for pure dependency graph job. This class captures only what's needed.
   */
  case class Span(id: Long, traceId: Long, parentId: Option[Long], timestamp: Option[Long], serviceName: Option[String], annotations: Seq[String]) {
    def mergeSpan(s: Span): Span = {
      val minTimestamp = Seq(timestamp, s.timestamp).flatten.reduceOption(_ min _)
      Span(id, traceId, parentId, minTimestamp, (serviceName ++ s.serviceName).headOption, this.annotations ++ s.annotations)
    }

    /**
     * @return true if Span contains at most one of each core annotation, false otherwise
     */
    def isValid: Boolean = {
      // TODO this has the potential to count the same span twice in runs with adjacent time windows.
      // Ideally, one of `<=` should be strict `<`. Depends on https://github.com/openzipkin/zipkin/issues/924.
      val inTimeRange: Boolean = timestamp.exists(t => microsLower <= t && t <= microsUpper)
      inTimeRange && serviceName.isDefined && !Constants.CoreAnnotations.map { c =>
        annotations.count(_ == c) // how many times this core annotation 'c' is mentioned
      }.exists(_ > 1)
    }
  }

  case class Trace(id: Long, spans: Map[Long, Span]) {
    def mergeTrace(t: Trace): Trace = {
      Trace(id, spans ++ t.spans)
    }
    def getLinks: Iterable[(String, String)] = {
      spans.values
        .filter(_.parentId.isDefined)
        .flatMap(span => spans.get(span.parentId.get).map(parentSpan => (parentSpan.serviceName.get, span.serviceName.get)))
    }
  }

  private[this] def rowToSpan(row: CassandraRow): Span = {
    val thriftSpan = Codecs.spanCodec.decode(row.getBytes("span"))
    val span = thriftSpan.toSpan
    val annotations = span.annotations.map(_.value)
    val timestamp: Option[Long] = span.timestamp orElse span.annotations.map(_.timestamp).sorted.headOption
    Span(id = span.id, traceId = span.traceId, parentId = span.parentId, timestamp = timestamp, serviceName = span.serviceName, annotations)
  }

  def run() {
    val conf = new SparkConf(true)
      .setAll(cassandraProperties)
      .setMaster(sparkMaster)
      .setAppName(getClass.getName)

    println(s"Running Dependencies job with startTs=$startTs (${Time.fromMilliseconds(startTs)}) and endTs=$endTs (${Time.fromMilliseconds(endTs)})")

    val sc = new SparkContext(conf)

    val table: CassandraRDD[CassandraRow] = sc.cassandraTable(keyspace, "traces")
    // ^^^ If need to drill further into the data, add this: .where("wsid='725030:14732'")

    val spans: RDD[Span] = table
      .map(rowToSpan)
      .map(span => ((span.id, span.traceId), span))
      .reduceByKey { (s1, s2) => s1.mergeSpan(s2) }
      .filter { case (key, span) => span.isValid }
      .values

    val traces: RDD[Trace] = spans // guaranteed no duplicates of (trace id, span id)
      .map(span => (span.traceId, Trace(span.traceId, Map(span.id -> span)))) // (traceId -> Trace)
      .reduceByKey((t1, t2) => t1.mergeTrace(t2))
      .values

    val aggregates: RDD[((String, String), Long)] = traces
      .flatMap(_.getLinks)
      .map { case (parent, child) => ((parent, child), 1L) } // start the count
      .reduceByKey(_ + _) // add up the counts

    val toDepInfo: PartialFunction[Any, DependenciesInfo] = {
      case ((parent: String, child: String), callCount: Long) =>
        DependenciesInfo(
          Seq(DependencyLinkInfo(parent = parent, child = child, callCount = callCount)))
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
    val thrift = dependencies.toDependencies(startTs = startTs, endTs = endTs).toThrift
    val blob: Array[Byte] = Codecs.dependenciesCodec.encode(thrift).array()

    val day = Time.fromMilliseconds(endTs).floor(Duration.fromTimeUnit(1, DAYS)).inMilliseconds
    val output = (day, blob)

    sc.parallelize(Seq(output)).saveToCassandra(keyspace, "dependencies", SomeColumns("day" as "_1", "dependencies" as "_2"))
    println(s"Saved with day=$day (${Time.fromMilliseconds(day)})")
  }

  def floorToDay(ts: Long): Long = {
    Time.fromMilliseconds(ts).floor(Duration.fromTimeUnit(1, DAYS)).inMilliseconds
  }
}

object Codecs {
  import com.twitter.zipkin.thriftscala.{Dependencies => ThriftDependencies}

  def spanCodec = CassandraSpanStoreDefaults.SpanCodec

  val dependenciesCodec = new ScroogeThriftCodec[ThriftDependencies](ThriftDependencies)
}
