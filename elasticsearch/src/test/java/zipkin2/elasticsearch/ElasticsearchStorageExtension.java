/*
 * Copyright 2016-2020 The OpenZipkin Authors
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
package zipkin2.elasticsearch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.logging.LogLevel;
import java.io.IOException;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import zipkin2.CheckResult;
import zipkin2.elasticsearch.ElasticsearchStorage.Builder;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ElasticsearchStorageExtension implements BeforeAllCallback, AfterAllCallback {
  static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchStorageExtension.class);
  static final int ELASTICSEARCH_PORT = 9200;
  final String image;
  GenericContainer container;

  ElasticsearchStorageExtension(String image) {
    this.image = image;
  }

  @Override public void beforeAll(ExtensionContext context) throws IOException {
    if (!"true".equals(System.getProperty("docker.skip"))) {
      try {
        LOGGER.info("Starting docker image " + image);
        container = new GenericContainer(image)
          .withExposedPorts(ELASTICSEARCH_PORT)
          .waitingFor(new HttpWaitStrategy().forPath("/"));
        container.start();
        if (Boolean.parseBoolean(System.getenv("ES_DEBUG"))) {
          container.followOutput(new Slf4jLogConsumer(LoggerFactory.getLogger(image)));
        }
        LOGGER.info("Starting docker image " + image);
      } catch (RuntimeException e) {
        LOGGER.warn("Couldn't start docker image " + image + ": " + e.getMessage(), e);
      }
    } else {
      LOGGER.info("Skipping startup of docker " + image);
    }

    assumeTrue(container != null, "Docker not available");

    tryToInitializeSession();
  }

  @Override public void afterAll(ExtensionContext context) {
    if (container != null) {
      LOGGER.info("Stopping docker image " + image);
      container.stop();
    }
  }

  void tryToInitializeSession() {
    try (ElasticsearchStorage result = computeStorageBuilder().build()) {
      CheckResult check = result.check();
      assumeTrue(check.ok(), () -> "Could not connect to storage, skipping test: "
        + check.error().getMessage());
    }
  }

  Builder computeStorageBuilder() {
    WebClientBuilder builder = WebClient.builder("http://" + hostPort())
      // Elasticsearch 7 never returns a response when receiving an HTTP/2 preface instead of the
      // more valid behavior of returning a bad request response, so we can't use the preface.
      //
      // TODO: find or raise a bug with Elastic
      .factory(ClientFactory.builder().useHttp2Preface(false).build());

    if (Boolean.parseBoolean(System.getenv("ES_DEBUG"))) {
      builder.decorator(c -> LoggingClient.builder()
        .requestLogLevel(LogLevel.INFO)
        .successfulResponseLogLevel(LogLevel.INFO).build(c));
    }
    WebClient client = builder.build();
    return ElasticsearchStorage.newBuilder(() -> client).index("zipkin-test").flushOnWrites(true);
  }

  String hostPort() {
    return container.getContainerIpAddress() + ":" + container.getMappedPort(ELASTICSEARCH_PORT);
  }
}
