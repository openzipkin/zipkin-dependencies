/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies.elasticsearch;

import java.io.IOException;
import java.util.Base64;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.elasticsearch.hadoop.EsHadoopException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchDependenciesJobTest {
  MockWebServer es = new MockWebServer();

  @BeforeEach void start() throws IOException {
    es.start();
  }

  @AfterEach void stop() throws IOException {
    es.close();
  }

  @Test void buildHttps() {
    ElasticsearchDependenciesJob job =
      ElasticsearchDependenciesJob.builder().hosts("https://foobar").build();
    assertThat(job.conf.get("es.nodes")).isEqualTo("foobar:443");
    assertThat(job.conf.get("es.net.ssl")).isEqualTo("true");
  }

  @Test void buildAuth() {
    ElasticsearchDependenciesJob job =
      ElasticsearchDependenciesJob.builder().username("foo").password("bar").build();
    assertThat(job.conf.get("es.net.http.auth.user")).isEqualTo("foo");
    assertThat(job.conf.get("es.net.http.auth.pass")).isEqualTo("bar");
  }

  @Test void authWorks() throws Exception {
    es.enqueue(new MockResponse()); // let the HEAD request pass, so we can trap the header value
    es.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AT_START)); // kill the job
    ElasticsearchDependenciesJob job = ElasticsearchDependenciesJob.builder()
      .username("foo")
      .password("bar")
      .hosts(es.url("").toString())
      .build();

    assertThatThrownBy(job::run)
      .isInstanceOf(EsHadoopException.class);

    String encoded = Base64.getEncoder().encodeToString("foo:bar".getBytes(UTF_8));
    assertThat(es.takeRequest().getHeader("Authorization"))
      .isEqualTo("Basic " + encoded.trim());
  }

  @Test void authWorksWithSsl() throws Exception {
    es.useHttps(localhost().sslSocketFactory(), false);

    es.enqueue(new MockResponse()); // let the HEAD request pass, so we can trap the header value
    es.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AT_START)); // kill the job

    ElasticsearchDependenciesJob.Builder builder = ElasticsearchDependenciesJob.builder()
      .username("foo")
      .password("bar")
      .hosts(es.url("").toString());

    // temporarily hack-in self-signed until https://github.com/openzipkin/zipkin/issues/1683
    builder.sparkProperties.put("es.net.ssl.cert.allow.self.signed", "true");

    ElasticsearchDependenciesJob job = builder.build();

    assertThatThrownBy(job::run)
      .isInstanceOf(EsHadoopException.class);

    String encoded = Base64.getEncoder().encodeToString("foo:bar".getBytes(UTF_8));
    assertThat(es.takeRequest().getHeader("Authorization"))
      .isEqualTo("Basic " + encoded.trim());
  }

  @Test void parseHosts_default() {
    assertThat(ElasticsearchDependenciesJob.parseHosts("1.1.1.1")).isEqualTo("1.1.1.1");
  }

  @Test void parseHosts_commaDelimits() {
    assertThat(ElasticsearchDependenciesJob.parseHosts("1.1.1.1:9200,2.2.2.2:9200")).isEqualTo(
      "1.1.1.1:9200,2.2.2.2:9200");
  }

  @Test void parseHosts_http_defaultPort() {
    assertThat(ElasticsearchDependenciesJob.parseHosts("http://1.1.1.1")).isEqualTo("1.1.1.1:80");
  }

  @Test void parseHosts_https_defaultPort() {
    assertThat(ElasticsearchDependenciesJob.parseHosts("https://1.1.1.1")).isEqualTo("1.1.1.1:443");
  }

  @Test void javaSslOptsRedirected() {
    System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
    System.setProperty("javax.net.ssl.keyStorePassword", "superSecret");
    System.setProperty("javax.net.ssl.trustStore", "truststore.jks");
    System.setProperty("javax.net.ssl.trustStorePassword", "secretSuper");

    ElasticsearchDependenciesJob job = ElasticsearchDependenciesJob.builder().build();

    assertThat(job.conf.get("es.net.ssl.keystore.location")).isEqualTo("file:keystore.jks");
    assertThat(job.conf.get("es.net.ssl.keystore.pass")).isEqualTo("superSecret");
    assertThat(job.conf.get("es.net.ssl.truststore.location")).isEqualTo("file:truststore.jks");
    assertThat(job.conf.get("es.net.ssl.truststore.pass")).isEqualTo("secretSuper");

    System.clearProperty("javax.net.ssl.keyStore");
    System.clearProperty("javax.net.ssl.keyStorePassword");
    System.clearProperty("javax.net.ssl.trustStore");
    System.clearProperty("javax.net.ssl.trustStorePassword");
  }
}
