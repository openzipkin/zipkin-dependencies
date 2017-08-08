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
package zipkin.dependencies.elasticsearch;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.dependencies.elasticsearch.ElasticsearchDependenciesJob.parseHosts;

public class ElasticsearchDependenciesJobTest {

  @Test public void buildHttps() {
    ElasticsearchDependenciesJob job  = ElasticsearchDependenciesJob.builder()
        .hosts("https://foobar")
        .build();
    assertThat(job.conf.get("es.nodes"))
        .isEqualTo("foobar:443");
    assertThat(job.conf.get("es.net.ssl"))
        .isEqualTo("true");
  }

  @Test public void buildAuth() {
    ElasticsearchDependenciesJob job  = ElasticsearchDependenciesJob.builder()
        .username("foo")
        .password("bar")
        .build();
    assertThat(job.conf.get("es.net.http.auth.user"))
        .isEqualTo("foo");
    assertThat(job.conf.get("es.net.http.auth.pass"))
        .isEqualTo("bar");
  }

  @Test public void parseHosts_default() {
    assertThat(parseHosts("1.1.1.1"))
        .isEqualTo("1.1.1.1");
  }

  @Test public void parseHosts_commaDelimits() {
    assertThat(parseHosts("1.1.1.1:9200,2.2.2.2:9200"))
        .isEqualTo("1.1.1.1:9200,2.2.2.2:9200");
  }

  @Test public void parseHosts_http_defaultPort() {
    assertThat(parseHosts("http://1.1.1.1"))
        .isEqualTo("1.1.1.1:80");
  }

  @Test public void parseHosts_https_defaultPort() {
    assertThat(parseHosts("https://1.1.1.1"))
        .isEqualTo("1.1.1.1:443");
  }
}
