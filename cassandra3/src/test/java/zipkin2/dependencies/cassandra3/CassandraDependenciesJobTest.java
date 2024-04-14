/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies.cassandra3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.dependencies.cassandra3.CassandraDependenciesJob.parseHosts;
import static zipkin2.dependencies.cassandra3.CassandraDependenciesJob.parsePort;

class CassandraDependenciesJobTest {
  @Test void parseHosts_ignoresPortSection() {
    assertThat(parseHosts("1.1.1.1:9142"))
      .isEqualTo("1.1.1.1");
  }

  @Test void parseHosts_commaDelimits() {
    assertThat(parseHosts("1.1.1.1:9143,2.2.2.2:9143"))
      .isEqualTo("1.1.1.1,2.2.2.2");
  }

  @Test void parsePort_ignoresHostSection() {
    assertThat(parsePort("1.1.1.1:9142"))
      .isEqualTo("9142");
  }

  @Test void parsePort_multiple_consistent() {
    assertThat(parsePort("1.1.1.1:9143,2.2.2.2:9143"))
      .isEqualTo("9143");
  }

  @Test void parsePort_defaultsTo9042() {
    assertThat(parsePort("1.1.1.1"))
      .isEqualTo("9042");
  }

  @Test void parsePort_defaultsTo9042_multi() {
    assertThat(parsePort("1.1.1.1:9143,2.2.2.2"))
      .isEqualTo("9042");
  }
}
