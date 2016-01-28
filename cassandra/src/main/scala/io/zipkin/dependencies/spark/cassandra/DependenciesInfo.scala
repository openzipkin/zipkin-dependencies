package io.zipkin.dependencies.spark.cassandra

import com.twitter.zipkin.common.{DependencyLink, Dependencies}

/*
 * A workaround for https://github.com/openzipkin/zipkin/issues/867
 * The DependencyLink is not serializable so we must wrap it here.
 * */

case class DependenciesInfo(links: Seq[DependencyLinkInfo]) {

  def toDependencies(startTs: Long, endTs: Long) = Dependencies(startTs, endTs, links.map(_.toDependencyLink))

  def +(that: DependenciesInfo): DependenciesInfo = {
    // links are merged by mapping to parent/child and summing corresponding links
    val newLinks = (this.links ++ that.links)
        .groupBy(link => (link.parent, link.child))
        .map {
          case ((parent, child), links) => DependencyLinkInfo(parent, child, links.map(_.callCount).sum)
        }.toSeq
    DependenciesInfo(newLinks)
  }
}

case class DependencyLinkInfo(parent: String, child: String, val callCount: Long) {
  def toDependencyLink = DependencyLink(parent, child, callCount)
}
