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

package zipkin2.dependencies.banyandb;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.spark.api.java.function.Function;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;

import java.util.ArrayList;
import java.util.List;

public class BanyanDBTraceConvertor implements Function<Iterable<BanyanDBRDD.RowData>, Iterable<DependencyLink>> {
  private static final Gson GSON = new Gson();

  @Override
  public Iterable<DependencyLink> call(Iterable<BanyanDBRDD.RowData> elements) throws Exception {
    List<Span> sameTraceId = new ArrayList<>();
    for (BanyanDBRDD.RowData element : elements) {
      final Span.Builder builder = Span.newBuilder()
          .traceId(element.getTagValue("trace_id"))
          .parentId(element.getTagValue("parent_id"))
          .id(element.getTagValue("span_id"))
          .localEndpoint(ep(element, "local_endpoint_service_name"))
          .remoteEndpoint(ep(element, "remote_endpoint_service_name"));

      final String tagsString = element.getTagValue("tags");
      if (StringUtils.isNotEmpty(tagsString)) {
        GSON.fromJson(tagsString, JsonObject.class).entrySet().forEach(entry -> {
          builder.putTag(entry.getKey(), entry.getValue().getAsString());
        });
      }

      sameTraceId.add(builder.build());
    }
    DependencyLinker linker = new DependencyLinker();
    linker.putTrace(sameTraceId);
    return linker.link();
  }

  private Endpoint ep(BanyanDBRDD.RowData data, String key) {
    final String val = data.getTagValue(key);
    if (StringUtils.isNotEmpty(val)) {
      return Endpoint.newBuilder().serviceName(val).build();
    }
    return null;
  }

}
