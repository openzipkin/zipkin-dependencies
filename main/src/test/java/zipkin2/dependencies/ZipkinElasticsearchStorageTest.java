/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.testing.junit5.server.mock.MockWebServerExtension;

class ZipkinElasticsearchStorageTest {
  static final AggregatedHttpResponse ELASTICSEARCH_RESPONSE = AggregatedHttpResponse.of(
    HttpStatus.OK, MediaType.JSON_UTF_8, """
      {
        "name" : "zipkin-elasticsearch",
        "cluster_name" : "docker-cluster",
        "cluster_uuid" : "wByRPgSgTryYl0TZXW4MsA",
        "version" : {
          "number" : "7.0.1",
          "build_flavor" : "default",
          "build_type" : "tar",
          "build_hash" : "e4efcb5",
          "build_date" : "2019-04-29T12:56:03.145736Z",
          "build_snapshot" : false,
          "lucene_version" : "8.0.0",
          "minimum_wire_compatibility_version" : "6.7.0",
          "minimum_index_compatibility_version" : "6.0.0-beta1"
        },
        "tagline" : "You Know, for Search"
      }
      """);
  static final AggregatedHttpResponse OPENSEARCH_RESPONSE = AggregatedHttpResponse.of(
    HttpStatus.OK, MediaType.JSON_UTF_8, """
      {
        "name" : "PV-NhJd",
        "cluster_name" : "CollectorDBCluster",
        "cluster_uuid" : "UjZaM0fQRC6tkHINCg9y8w",
         "version" : {
          "distribution" : "opensearch",
          "number" : "2.11.1",
          "build_type" : "tar",
          "build_hash" : "6b1986e964d440be9137eba1413015c31c5a7752",
          "build_date" : "2023-11-29T21:43:10.135035992Z",
          "build_snapshot" : false,
          "lucene_version" : "9.7.0",
          "minimum_wire_compatibility_version" : "7.10.0",
          "minimum_index_compatibility_version" : "7.0.0"
        },
        "tagline" : "The OpenSearch Project: https://opensearch.org/"
      }
      """);

  @RegisterExtension static MockWebServerExtension server = new MockWebServerExtension();

  @Test void opensearch_http() throws Exception {
    server.enqueue(OPENSEARCH_RESPONSE);

    assertThat(ZipkinElasticsearchStorage.flavor(server.httpUri().toString(), null, null))
      .isEqualTo("opensearch");
  }

  @Test void opensearch_https() throws Exception {
    server.enqueue(OPENSEARCH_RESPONSE);

    assertThat(ZipkinElasticsearchStorage.flavor(server.httpsUri().toString(), null, null))
      .isEqualTo("opensearch");
  }

  @Test void elasticsearch_http() throws Exception {
    server.enqueue(ELASTICSEARCH_RESPONSE);

    assertThat(ZipkinElasticsearchStorage.flavor(server.httpUri().toString(), null, null))
      .isEqualTo("elasticsearch");
  }

  @Test void elasticsearch_https() throws Exception {
    server.enqueue(ELASTICSEARCH_RESPONSE);

    assertThat(ZipkinElasticsearchStorage.flavor(server.httpsUri().toString(), null, null))
      .isEqualTo("elasticsearch");
  }
}
