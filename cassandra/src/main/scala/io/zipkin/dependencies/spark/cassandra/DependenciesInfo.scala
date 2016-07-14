package io.zipkin.dependencies.spark.cassandra

import zipkin.DependencyLink

case class DependenciesInfo(links: Seq[DependencyLink]) {

  def +(that: DependenciesInfo): DependenciesInfo = {
    // links are merged by mapping to parent/child and summing corresponding links
    val newLinks = (this.links ++ that.links)
        .groupBy(link => (link.parent, link.child))
        .map {
          case ((parent, child), links) => DependencyLink.create(parent, child, links.map(_.callCount).sum)
        }.toSeq
    DependenciesInfo(newLinks)
  }
}
