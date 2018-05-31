/*
 * Copyright 2016-2018 The OpenZipkin Authors
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

import com.datastax.spark.connector.japi.CassandraRow;
import com.datastax.spark.connector.japi.UDTValue;
import com.datastax.spark.connector.types.TypeConverter;
import java.lang.reflect.Field;
import java.util.Map;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import zipkin2.Endpoint;
import zipkin2.Span;

import static zipkin.dependencies.cassandra3.CassandraDependenciesJob.traceId;

final class CassandraRowToSpan implements Serializable, Function<CassandraRow, Span> {
  static final Logger log = LoggerFactory.getLogger(CassandraRowToSpan.class);

  final boolean inTest;

  CassandraRowToSpan(boolean inTest) {
    this.inTest = inTest;
  }

  @Override public Span call(CassandraRow row) {
    String traceId = traceId(row), spanId = row.getString("id");
    Span.Builder builder = Span.newBuilder()
        .traceId(traceId)
        .parentId(row.getString("parent_id"))
        .id(spanId)
        .timestamp(row.getLong("ts"))
        .shared(row.getBoolean("shared"));

    Map<String, String> tags = row.getMap(
        "tags", TypeConverter.StringConverter$.MODULE$, TypeConverter.StringConverter$.MODULE$);
    String error = tags.get("error");
    if (error != null) builder.putTag("error", error);
    String kind = row.getString("kind");
    if (kind != null) {
      try {
        builder.kind(Span.Kind.valueOf(kind));
      } catch (IllegalArgumentException ignored) {
        log.debug("couldn't parse kind {} in span {}/{}", kind, traceId, spanId);
      }
    }
    Endpoint localEndpoint = readEndpoint(row, "l_ep");
    if (localEndpoint != null) {
      builder.localEndpoint(localEndpoint);
    }
    Endpoint remoteEndpoint = readEndpoint(row, "r_ep");
    if (remoteEndpoint != null) {
      builder.remoteEndpoint(remoteEndpoint);
    }
    return builder.build();
  }

  private Endpoint readEndpoint(CassandraRow row, String name) {
    if (!inTest) {
      return readEndpoint(row.getUDTValue(name));
    }
    // UDT type doesn't work in tests
    // Caused by: com.datastax.spark.connector.types.TypeConversionException: Cannot convert object zipkin2.storage.cassandra.Schema$EndpointUDT@67a3fdf8 of type class zipkin2.storage.cassandra.Schema$EndpointUDT to com.datastax.spark.connector.japi.UDTValue.
    return readEndpoint(row.getObject(name));
  }

  private static Endpoint readEndpoint(UDTValue endpoint) {
    if (endpoint == null) return null;
    String serviceName = endpoint.getString("service");
    if (serviceName != null && !"".equals(serviceName)) { // not possible if written via zipkin
      return Endpoint.newBuilder().serviceName(serviceName).build();
    }
    return null;
  }

  private static Endpoint readEndpoint(Object endpoint) {
    if (endpoint == null) return null;
    String serviceName = null;
    try {
      Field field = endpoint.getClass().getDeclaredField("service");
      field.setAccessible(true);
      serviceName = field.get(endpoint).toString();
    } catch (Exception e) {
      log.debug("couldn't lookup service field of {}", endpoint.getClass(), e);
    }
    if (serviceName != null && !"".equals(serviceName)) { // not possible if written via zipkin
      return Endpoint.newBuilder().serviceName(serviceName).build();
    }
    return null;
  }
}
