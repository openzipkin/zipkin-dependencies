package io.zipkin.dependencies.spark.cassandra

import com.twitter.zipkin.common.{DependencyLink, Dependencies}

/*
 * A workaround for https://github.com/openzipkin/zipkin/issues/867
 * The DependencyLink is not serializable so we must wrap it here.
 * */

case class DependenciesInfo(startTs: Long, endTs: Long, links: Seq[DependencyLinkInfo]) {
  def toDependencies = Dependencies(startTs, endTs, links.map(_.toDependencyLink))
  def +(that: DependenciesInfo): DependenciesInfo = {
    // don't sum against Dependencies.zero
    if (that == DependenciesInfo.zero) {
      return this
    } else if (this == DependenciesInfo.zero) {
      return that
    }

    // new start/end should be the inclusive time span of both items
    val newStart = startTs min that.startTs
    val newEnd = endTs max that.endTs

    // links are merged by mapping to parent/child and summing corresponding links
    val newLinks = (this.links ++ that.links)
        .groupBy(link => (link.parent, link.child))
        .map {
          case ((parent, child), links) => DependencyLinkInfo(parent, child, links.map(_.callCount).sum)
        }.toSeq
    DependenciesInfo(newStart, newEnd, newLinks)
  }
}

case class DependencyLinkInfo(parent: String, child: String, val callCount: Long) {
  def toDependencyLink = DependencyLink(parent, child, callCount)
}

object DependenciesInfo {
  def zero = DependenciesInfo(0, 0, Seq.empty[DependencyLinkInfo])
}