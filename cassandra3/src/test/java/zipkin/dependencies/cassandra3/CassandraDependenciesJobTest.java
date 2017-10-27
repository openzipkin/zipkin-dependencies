/**
 * Copyright 2016-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.dependencies.cassandra3;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.dependencies.cassandra3.CassandraDependenciesJob.parseHosts;
import static zipkin.dependencies.cassandra3.CassandraDependenciesJob.parsePort;

public class CassandraDependenciesJobTest {
  @Test
  public void parseHosts_ignoresPortSection() {
    assertThat(parseHosts("1.1.1.1:9142"))
        .isEqualTo("1.1.1.1");
  }

  @Test
  public void parseHosts_commaDelimits() {
    assertThat(parseHosts("1.1.1.1:9143,2.2.2.2:9143"))
        .isEqualTo("1.1.1.1,2.2.2.2");
  }

  @Test
  public void parsePort_ignoresHostSection() {
    assertThat(parsePort("1.1.1.1:9142"))
        .isEqualTo("9142");
  }

  @Test
  public void parsePort_multiple_consistent() {
    assertThat(parsePort("1.1.1.1:9143,2.2.2.2:9143"))
        .isEqualTo("9143");
  }

  @Test
  public void parsePort_defaultsTo9042() {
    assertThat(parsePort("1.1.1.1"))
        .isEqualTo("9042");
  }

  @Test
  public void parsePort_defaultsTo9042_multi() {
    assertThat(parsePort("1.1.1.1:9143,2.2.2.2"))
        .isEqualTo("9042");
  }
}
