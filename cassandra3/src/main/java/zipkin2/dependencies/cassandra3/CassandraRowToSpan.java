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
package zipkin2.dependencies.cassandra3;

import com.datastax.spark.connector.japi.CassandraRow;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.lang.StringUtils;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import zipkin2.Endpoint;
import zipkin2.Span;

import java.util.Objects;

import static zipkin2.Span.normalizeTraceId;

enum CassandraRowToSpan implements Serializable, Function<CassandraRow, Span> {
  INSTANCE;
  final Logger log = LoggerFactory.getLogger(CassandraRowToSpan.class);
  final Gson gson = new Gson();

  @Override public Span call(CassandraRow row) {
    String traceId = normalizeTraceId(row.getString("trace_id"));
    if (traceId.length() == 32) traceId = traceId.substring(16);
    String spanId = row.getString("span_id");

    Span.Builder builder = Span.newBuilder()
      .traceId(traceId)
      .parentId(row.getString("parent_id"))
      .id(spanId)
      .timestamp(row.getLong("timestamp") / 1000)
      .shared(Objects.equals(row.getInt("shared"), 1));

    final String tagJsonString = row.getString("tags");
    if (StringUtils.isNotEmpty(tagJsonString)) {
      final JsonObject tagJson = gson.fromJson(tagJsonString, JsonObject.class);
      if (tagJson.has("error")) {
        builder.putTag("error", tagJson.get("error").getAsString());
      }
    }
    String kind = row.getString("kind");
    if (kind != null) {
      try {
        builder.kind(Span.Kind.valueOf(kind));
      } catch (IllegalArgumentException ignored) {
        log.debug("couldn't parse kind {} in span {}/{}", kind, traceId, spanId);
      }
    }
    Endpoint localEndpoint = readEndpoint(row.getString("local_endpoint_service_name"));
    if (localEndpoint != null) builder.localEndpoint(localEndpoint);
    Endpoint remoteEndpoint = readEndpoint(row.getString("remote_endpoint_service_name"));
    if (remoteEndpoint != null) builder.remoteEndpoint(remoteEndpoint);
    return builder.build();
  }

  @Nullable static Endpoint readEndpoint(String serviceName) {
    if (StringUtils.isEmpty(serviceName)) return null;
    return Endpoint.newBuilder().serviceName(serviceName).build();
  }
}
