/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.storage.mysql.v1;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.mariadb.jdbc.MariaDbDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import zipkin2.Span;
import zipkin2.dependencies.mysql.MySQLDependenciesJob;

import static org.testcontainers.utility.DockerImageName.parse;
import static zipkin2.storage.ITDependencies.aggregateLinks;

final class MySQLContainer extends GenericContainer<MySQLContainer> {
  static final Logger LOGGER = LoggerFactory.getLogger(MySQLContainer.class);

  MySQLContainer() {
    super(parse("ghcr.io/openzipkin/zipkin-mysql:3.2.1"));
    addExposedPort(3306);
    waitStrategy = Wait.forHealthcheck();
    withLogConsumer(new Slf4jLogConsumer(LOGGER));
  }

  @Override public void start() {
    super.start();
    LOGGER.info("Using hostPort " + host() + ":" + port());
  }

  MySQLStorage.Builder newStorageBuilder() {
    final MariaDbDataSource dataSource;

    try {
      dataSource = new MariaDbDataSource(String.format(
        "jdbc:mysql://%s:%s/zipkin?permitMysqlScheme&autoReconnect=true&useUnicode=yes&characterEncoding=UTF-8",
        host(), port()));
      dataSource.setUser("zipkin");
      dataSource.setPassword("zipkin");
    } catch (SQLException e) {
      throw new AssertionError(e);
    }

    return new MySQLStorage.Builder()
      .datasource(dataSource)
      .executor(Runnable::run);
  }

  String host() {
    return getHost();
  }

  int port() {
    return getMappedPort(3306);
  }

  /** This processes the job as if it were a batch. For each day we had traces, run the job again. */
  void processDependencies(MySQLStorage storage, List<Span> spans) throws IOException {
    storage.spanConsumer().accept(spans).execute();

    // aggregate links in memory to determine which days they are in
    Set<Long> days = aggregateLinks(spans).keySet();

    // process the job for each day of links.
    for (long day : days) {
      MySQLDependenciesJob.builder()
        .user("zipkin")
        .password("zipkin")
        .host(host())
        .port(port())
        .db("zipkin")
        .day(day).build().run();
    }
  }
}
