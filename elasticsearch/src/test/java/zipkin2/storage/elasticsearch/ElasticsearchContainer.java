/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.storage.elasticsearch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.logging.LoggingClientBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import zipkin2.Span;
import zipkin2.dependencies.elasticsearch.ElasticsearchDependenciesJob;
import zipkin2.elasticsearch.ElasticsearchStorage;

import static org.testcontainers.utility.DockerImageName.parse;
import static zipkin2.storage.ITDependencies.aggregateLinks;

class ElasticsearchContainer extends GenericContainer<ElasticsearchContainer> {
  static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchContainer.class);

  ElasticsearchContainer(int majorVersion) {
    super(parse("ghcr.io/openzipkin/zipkin-elasticsearch" + majorVersion + ":3.2.1"));
    addExposedPort(9200);
    waitStrategy = Wait.forHealthcheck();
    withLogConsumer(new Slf4jLogConsumer(LOGGER));
  }

  @Override public void start() {
    super.start();
    LOGGER.info("Using baseUrl http://" + hostPort());
  }

  ElasticsearchStorage.Builder newStorageBuilder() {

    WebClientBuilder builder = WebClient.builder("http://" + hostPort())
      // Elasticsearch 7 never returns a response when receiving an HTTP/2 preface instead of the
      // more valid behavior of returning a bad request response, so we can't use the preface.
      //
      // TODO: find or raise a bug with Elastic
      .factory(ClientFactory.builder().useHttp2Preface(false).build());
    builder.decorator((delegate, ctx, req) -> {
      final HttpResponse response = delegate.execute(ctx, req);
      return HttpResponse.of(response.aggregate().thenApply(r -> {
        // ES will return a 'warning' response header when using deprecated api, detect this and
        // fail early, so we can do something about it.
        // Example usage: https://github.com/elastic/elasticsearch/blob/3049e55f093487bb582a7e49ad624961415ba31c/x-pack/plugin/security/src/internalClusterTest/java/org/elasticsearch/integration/IndexPrivilegeIntegTests.java#L559
        final String warningHeader = r.headers().get("warning");
        if (warningHeader != null) {
          if (IgnoredDeprecationWarnings.IGNORE_THESE_WARNINGS.stream().noneMatch(p -> p.matcher(warningHeader).find())) {
            throw new IllegalArgumentException(
              "Detected usage of deprecated API for request " + req + ":\n" + warningHeader);
          }
        }
        // Convert AggregatedHttpResponse back to HttpResponse.
        return r.toHttpResponse();
      }));
    });

    // When ES_DEBUG=true log full headers, request and response body to the category
    // com.linecorp.armeria.client.logging
    if (Boolean.parseBoolean(System.getenv("ES_DEBUG"))) {
      ClientOptionsBuilder options = ClientOptions.builder();
      LoggingClientBuilder loggingBuilder = LoggingClient.builder()
        .requestLogLevel(LogLevel.INFO)
        .successfulResponseLogLevel(LogLevel.INFO);
      options.decorator(loggingBuilder.newDecorator());
      options.decorator(ContentPreviewingClient.newDecorator(Integer.MAX_VALUE));
      builder.options(options.build());
    }

    WebClient client = builder.build();
    return ElasticsearchStorage.newBuilder(new ElasticsearchStorage.LazyHttpClient() {
      @Override public WebClient get() {
        return client;
      }

      @Override public void close() {
        client.endpointGroup().close();
      }

      @Override public String toString() {
        return client.uri().toString();
      }
    }).flushOnWrites(true);
  }

  String hostPort() {
    return getHost() + ":" + getMappedPort(9200);
  }

  /**
   * This processes the job as if it were a batch. For each day we had traces, run the job again.
   */
  void processDependencies(ElasticsearchStorage storage, List<Span> spans) throws IOException {
    storage.spanConsumer().accept(spans).execute();

    // aggregate links in memory to determine which days they are in
    Set<Long> days = aggregateLinks(spans).keySet();

    // process the job for each day of links.
    for (long day : days) {
      ElasticsearchDependenciesJob.builder().hosts(hostPort()).day(day).build().run();
    }
  }
}
