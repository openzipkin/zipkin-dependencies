/*
 * Copyright 2016-2023 The OpenZipkin Authors
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
package zipkin2.dependencies.elasticsearch;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import scala.Tuple2;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.codec.SpanBytesDecoder;
import zipkin2.internal.DependencyLinker;

final class TraceIdAndJsonToDependencyLinks
    implements Serializable, Function<Iterable<Tuple2<String, String>>, Iterable<DependencyLink>> {
  private static final long serialVersionUID = 0L;
  private static final Logger log = LoggerFactory.getLogger(TraceIdAndJsonToDependencyLinks.class);
  private static final Gson GSON = new Gson();

  @Nullable final Runnable logInitializer;
  final SpanBytesDecoder decoder;

  TraceIdAndJsonToDependencyLinks(Runnable logInitializer, SpanBytesDecoder decoder) {
    this.logInitializer = logInitializer;
    this.decoder = decoder;
  }

  @Override
  public Iterable<DependencyLink> call(Iterable<Tuple2<String, String>> traceIdJson) {
    if (logInitializer != null) logInitializer.run();
    List<Span> sameTraceId = new ArrayList<>();
    for (Tuple2<String, String> row : traceIdJson) {
      try {
        final JsonObject json = GSON.fromJson(row._2, JsonObject.class);
        final Span.Builder spanBuilder = Span
            .newBuilder()
            .traceId(json.get("trace_id").getAsString())
            .id(json.get("span_id").getAsString())
            .parentId(json.has("parent_id") ? json.get("parent_id").getAsString() : null)
            .name(json.get("name").getAsString())
            .kind(Span.Kind.valueOf(json.get("kind").getAsString()))
            .localEndpoint(ep(json, "local_endpoint_service_name"))
            .remoteEndpoint(ep(json, "remote_endpoint_service_name"));

        if (json.has("tags") && !json.get("tags").getAsString().isEmpty()) {
          final String tags = json.get("tags").getAsString();
          GSON.fromJson(tags, JsonObject.class).entrySet().forEach(entry -> {
            spanBuilder.putTag(entry.getKey(), entry.getValue().getAsString());
          });
        }
        sameTraceId.add(spanBuilder.build());
      } catch (Exception e) {
        log.warn("Unable to decode span from traces where trace_id=" + row._1, e);
      }
    }
    DependencyLinker linker = new DependencyLinker();
    linker.putTrace(sameTraceId);
    return linker.link();
  }

  static Endpoint ep(JsonObject json, String key) {
    if (json.has(key)) {
      return Endpoint.newBuilder().serviceName((json.get(key).getAsString())).build();
    }
    return null;
  }
}
