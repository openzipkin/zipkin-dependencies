package org.openzipkin.dependencies.spark.cassandra

import java.util.concurrent.TimeUnit._

import com.datastax.spark.connector._
import com.datastax.spark.connector.rdd.CassandraRDD
import com.twitter.util.{Duration, Time}
import com.twitter.zipkin.Constants
import com.twitter.zipkin.conversions.thrift._
import com.twitter.zipkin.storage.cassandra.{ScroogeThriftCodec, CassandraSpanStoreDefaults}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

object ZipkinDependenciesJob {

  /**
   * RDD needs the data objects to be serializable, at least at some points. The zipkin.common.Span is not serializable,
   * and has a lot of extra stuff not needed for pure dependency graph job. This class captures only what's needed.
   */
  case class Span(id: Long, traceId: Long, parentId: Option[Long], serviceName: Option[String], annotations: Seq[String]) {
    def mergeSpan(s: Span): Span = {
      Span(id, traceId, parentId, (serviceName ++ s.serviceName).headOption, this.annotations ++ s.annotations)
    }

    /**
     * @return true if Span contains at most one of each core annotation, false otherwise
     */
    def isValid: Boolean = {
      serviceName.isDefined && !Constants.CoreAnnotations.map { c =>
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

  object Codecs {
    import com.twitter.zipkin.thriftscala.{ Dependencies => ThriftDependencies }

    def spanCodec = CassandraSpanStoreDefaults.SpanCodec

    val dependenciesCodec = new ScroogeThriftCodec[ThriftDependencies](ThriftDependencies)
  }

  private[this] def rowToSpan(row: CassandraRow): Span = {
    val thriftSpan = Codecs.spanCodec.decode(row.getBytes("span"))
    val span = thriftSpan.toSpan
    val annotations = span.annotations.map(_.value)
    Span(id = span.id, traceId = span.traceId, parentId = span.parentId, serviceName = span.serviceName, annotations)
  }

  val keyspace = sys.env.getOrElse("ZIPKIN_KEYSPACE", "zipkin")
  val cassandraHost = sys.env.getOrElse("CASSANDRA_HOST", "127.0.0.1")
  val cassandraUser = sys.env.getOrElse("CASSANDRA_USERNAME", "")
  val cassandraPass = sys.env.getOrElse("CASSANDRA_PASSWORD", "")
  // local[*] master lets us run & test the job right in our IDE, meaning we don't explicitly have a spark master set up
  val sparkMaster = sys.env.getOrElse("SPARK_MASTER", "local[*]")

  def main(args: Array[String]): Unit = {

    val conf = new SparkConf(true)
        .set("spark.cassandra.connection.host", cassandraHost)
        .set("spark.cassandra.auth.username", cassandraUser)
        .set("spark.cassandra.auth.password", cassandraPass)
        .setMaster(sparkMaster).setAppName(getClass.getName)

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

    val dependencies: DependenciesInfo = aggregates
      .map { case ((parent: String, child: String), callCount: Long) =>
        DependenciesInfo(0, 0, Seq(DependencyLinkInfo(parent = parent, child = child, callCount = callCount)))
      }
      .reduce(_ + _) // merge DLs under one Dependencies object, which overrides +

    saveToCassandra(sc, dependencies)

    println(s"Dependencies: $dependencies")
    sc.stop()
  }

  def saveToCassandra(sc: SparkContext, dependencies: DependenciesInfo): Unit = {
    val day: Long = Time.now.floor(Duration.fromTimeUnit(1, DAYS)).inMilliseconds

    val thrift = dependencies.toDependencies.toThrift
    val blob: Array[Byte] = Codecs.dependenciesCodec.encode(thrift).array()

    val output = (day, blob)

    sc.parallelize(Seq(output)).saveToCassandra(keyspace, "dependencies", SomeColumns("day" as "_1", "dependencies" as "_2"))
  }

}
