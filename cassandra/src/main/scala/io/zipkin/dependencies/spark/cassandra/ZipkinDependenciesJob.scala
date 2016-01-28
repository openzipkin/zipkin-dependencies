package io.zipkin.dependencies.spark.cassandra

import java.util.concurrent.TimeUnit._

import com.datastax.spark.connector._
import com.datastax.spark.connector.rdd.CassandraRDD
import com.twitter.util.{Duration, Time}
import com.twitter.zipkin.Constants
import com.twitter.zipkin.common.{Span => CommonSpan}
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
  case class Span(id: Long, traceId: Long, parentId: Option[Long], timestamp: Option[Long],
                  annotations: Map[String, String], clientName: Option[String], serverName: Option[String]) {
    def mergeSpan(s: Span): Span = {
      val minTimestamp = Seq(timestamp, s.timestamp).flatten.reduceOption(_ min _)
      Span(id, traceId, parentId, minTimestamp,
        annotations = this.annotations ++ s.annotations,
        clientName = (this.clientName ++ s.clientName).headOption,
        serverName = (this.serverName ++ s.serverName).headOption
      )
    }

    /**
     * Tries to extract the best name of the service in this span. Depends on logged annotations
     * and prioritizes names logged by the service itself (via Core annotations) over names logged
     * by the "other" service (via CoreAddress binary annotations).
     */
    lazy val serviceName: Option[String] = {
      // Most authoritative is the label of any server annotation, logged by an instrumented server itself
      Constants.CoreServer.flatMap(annotations.get).headOption orElse
        // Next is the label of the server's endpoint
        serverName orElse
        // Next is the label of any client annotation, logged by an instrumented client
        Constants.CoreClient.flatMap(annotations.get).headOption orElse
        // Next is the label of the client's endpoint
        clientName
    }

    /**
     * Used to filter out spans from the final result. A span is considered valid if all of the following
     * conditions are satisfied:
     *
     *   - it has a timestamp in the range [microsLower, microsUpper]  (atm this range is closed on both sides)
     *   - it can provide a non-empty service name
     *   - if it has Core annotations, each Core annotation is not repeated more than once.
     *
     * The last condition is legacy, (apparently) to protect against malformed spans. Multiple copies of the
     * same core annotation indicate that something has probably gone wrong with the instrumentation.
     */
    def isValid: Boolean = {
      // TODO this has the potential to count the same span twice in runs with adjacent time windows.
      // Ideally, one of `<=` should be strict `<`. Depends on https://github.com/openzipkin/zipkin/issues/924.
      val inTimeRange: Boolean = timestamp.exists(t => microsLower <= t && t <= microsUpper)
      inTimeRange &&
        !serviceName.forall(_.isEmpty) &&
        !Constants.CoreAnnotations.map { c =>
          annotations.count(_ == c) // how many times this core annotation 'c' is mentioned
        }.exists(_ > 1)
    }
  }

  // TODO these should be moved into common data model in Zipkin
  sealed trait Instrumentation {
    def +(other: Instrumentation): Instrumentation
  }
  case object ParentAndChild extends Instrumentation {
    override def +(other: Instrumentation): Instrumentation = this
  }
  case object ParentOnly extends Instrumentation {
    override def +(other: Instrumentation): Instrumentation = other match {
      case ParentAndChild => other
      case _ => this
    }
  }
  case object ChildOnly extends Instrumentation {
    override def +(other: Instrumentation): Instrumentation = other match {
      case ParentAndChild => other
      case _ => this
    }
  }

  case class Trace(id: Long, spans: Map[Long, Span]) {
    def mergeTrace(t: Trace): Trace = {
      Trace(id, spans ++ t.spans)
    }

    /**
     * Extracts parent->child links from spans within this trace.
     */
    def getLinks: Iterable[(String, String, Instrumentation)] = {
      val parentChildren: Map[Long, Set[Long]] = spans.values
        .flatMap(span => span.parentId.flatMap(spans.get).map(parentSpan => (parentSpan.id, span.id)))
        .groupBy(_._1).mapValues(_ map (_._2)).mapValues(_.toSet)

      // for spans that have parents, collect links as (parent, child, instr, parentId, childId)
      val parentAndChildInstrumented: Iterable[(String, String, Instrumentation, Long, Long)] = spans.values
        .flatMap(span => span.parentId.flatMap(spans.get).map(
        parentSpan => (parentSpan.serviceName.get, span.serviceName.get, ParentAndChild, parentSpan.id, span.id))
        )

      // we check these maps to ensure we don't double-count links already found as parent->child spans
      val parentChildByParentId: Set[(Long, String, String)] =
        parentAndChildInstrumented.map(t => (t._4, t._1, t._2)).toSet
      val parentChildByChildId: Set[(Long, String, String)] =
        parentAndChildInstrumented.map(t => (t._5, t._1, t._2)).toSet

      // spans without children may know the server via ServerAddress
      val clientOnlyInstrumentedMap: Map[Long, (String, String, Instrumentation)] = spans.values
        .filter(span => parentChildren.get(span.id).isEmpty)
        .flatMap(span => span.serverName.flatMap(serverName => span.clientName.flatMap(clientName =>
          if (parentChildByChildId.contains((span.id, clientName, serverName))) {
            None
          } else {
            Some(span.id ->(clientName, serverName, ChildOnly))
          }
        ))).toMap

      // spans without parent may know the client via ClientAddress
      val serverOnlyInstrumented: Iterable[(String, String, Instrumentation)] = spans.values
        .filter(span => span.parentId.flatMap(spans.get).isEmpty && !clientOnlyInstrumentedMap.contains(span.id))
        .flatMap(span => span.clientName.flatMap(
          clientName => span.serverName.flatMap(serverName =>
            if (parentChildByParentId.contains((span.id, clientName, serverName))) {
              None
            } else {
              Some((clientName, serverName, ParentOnly))
            }
        )))

      (parentAndChildInstrumented.map(t => (t._1, t._2, t._3)) ++
        clientOnlyInstrumentedMap.values ++
        serverOnlyInstrumented
        ).filterNot(t => t._1.isEmpty || t._2.isEmpty)
    }
  }

  val InterestingAnnotations: Set[String] = Constants.CoreClient ++ Constants.CoreServer

  private[this] def rowToSpan(row: CassandraRow): Span = {
    val thriftSpan = Codecs.spanCodec.decode(row.getBytes("span"))
    val span = thriftSpan.toSpan
    val annotations: Map[String, String] = span.annotations
      .filter(a => InterestingAnnotations.contains(a.value))
      .flatMap(a => a.host.map(_.serviceName).filterNot(_.isEmpty).map(name => a.value -> name))
      .toMap
    val timestamp: Option[Long] = span.timestamp orElse span.annotations.map(_.timestamp).sorted.headOption

    def serviceNameCore(span: CommonSpan, keys: Set[String]): Option[String] = {
      span.annotations.find(a => keys.contains(a.value)).flatMap(_.host).map(_.serviceName).filterNot(_.isEmpty)
    }

    def serviceNameBin(span: CommonSpan, key: String): Option[String] = {
      span.binaryAnnotations.find(_.key == key).flatMap(_.host).map(_.serviceName).filterNot(_.isEmpty)
    }

    val clientName = (serviceNameCore(span, Constants.CoreClient) ++ serviceNameBin(span, Constants.ClientAddr)).headOption
    val serverName = (serviceNameCore(span, Constants.CoreServer) ++ serviceNameBin(span, Constants.ServerAddr)).headOption
    Span(id = span.id, traceId = span.traceId, parentId = span.parentId, timestamp = timestamp,
      annotations = annotations, clientName, serverName)
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

    if (spans.isEmpty()) {
      println(s"NO VALID SPANS FOUND IN THE TIME RANGE")
    }

    val traces: RDD[Trace] = spans // guaranteed no duplicates of (trace id, span id)
      .map(span => (span.traceId, Trace(span.traceId, Map(span.id -> span)))) // (traceId -> Trace)
      .reduceByKey((t1, t2) => t1.mergeTrace(t2))
      .values

    case class Link(callCount: Long, instrumentation: Instrumentation) {
      def +(link: Link): Link = {
        Link(this.callCount + link.callCount, this.instrumentation + link.instrumentation)
      }
    }

    val aggregates: RDD[((String, String), Link)] = traces
      .flatMap(_.getLinks)
      .map { case (parent, child, instrumentation) => ((parent, child), Link(1L, instrumentation)) } // start the count
      .reduceByKey(_ + _) // add up the counts

    if (aggregates.isEmpty()) {
      println(s"NO LINKS WERE GENERATED FROM SPANS")
    } else {
      val dependencies: DependenciesInfo = aggregates
        .map { case ((parent: String, child: String), link: Link) =>
        // TODO signal loss here - `instrumentation` is ignored
        DependenciesInfo(Seq(DependencyLinkInfo(parent = parent, child = child, callCount = link.callCount)))
      }.reduce(_ + _) // merge DLs under one Dependencies object, which overrides +

      saveToCassandra(sc, keyspace, dependencies)
    }
    sc.stop()
  }

  def saveToCassandra(sc: SparkContext, keyspace: String, dependencies: DependenciesInfo): Unit = {
    val thrift = dependencies.toDependencies(startTs = startTs, endTs = endTs).toThrift
    val blob: Array[Byte] = Codecs.dependenciesCodec.encode(thrift).array()
    val day = Time.fromMilliseconds(endTs).floor(Duration.fromTimeUnit(1, DAYS)).inMilliseconds
    val output = (day, blob)

    sc.parallelize(Seq(output)).saveToCassandra(
      keyspace, "dependencies", SomeColumns("day" as "_1", "dependencies" as "_2"))

    println(s"Saved dependencies as of $day (${Time.fromMilliseconds(day)}}): $dependencies")
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
