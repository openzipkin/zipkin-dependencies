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

package zipkin2.dependencies.jdbc;

import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import zipkin2.DependencyLink;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JDBCDependenciesJob {
  protected final Logger log = LoggerFactory.getLogger(this.getClass());

  protected void run(long day, Map<String, String> sparkOpts, JavaSparkContext ctx, Runnable logInitializer) {
    Function<Row, String> rowTraceId = r -> r.getString(0); // trace_id

    final String mysqlDateTableSuffix = DateFormatUtils.format(day, "yyyyMMdd");
    final String dateStampShow = DateFormatUtils.format(day, "yyyy-MM-dd");

    long microsLower = day * 1000;
    long microsUpper = (day * 1000) + TimeUnit.DAYS.toMicros(1) - 1;

    String linksQuery = String.format(
        "select trace_id, parent_id, span_id, kind, local_endpoint_service_name, remote_endpoint_service_name, tags "+
            "from zipkin_span_%s",
        mysqlDateTableSuffix);

    sparkOpts.put("dbtable", "(" + linksQuery + ") as link_spans");

    log.info("Running Dependencies job for {}: start_ts between {} and {}", dateStampShow, microsLower,
        microsUpper);

    List<DependencyLink> links = new SQLContext(ctx).read()
        .format("org.apache.spark.sql.execution.datasources.jdbc.JdbcRelationProvider")
        .options(sparkOpts)
        .load()
        .toJavaRDD()
        .groupBy(rowTraceId)
        .flatMapValues(new RowsToDependencyLinks(logInitializer))
        .values()
        .mapToPair(l -> Tuple2.apply(Tuple2.apply(l.parent(), l.child()), l))
        .reduceByKey((l, r) -> DependencyLink.newBuilder()
            .parent(l.parent())
            .child(l.child())
            .callCount(l.callCount() + r.callCount())
            .errorCount(l.errorCount() + r.errorCount())
            .build())
        .values().collect();

    ctx.stop();

    log.info("Saving with day=" + dateStampShow);
    saveLinks(day, links, mysqlDateTableSuffix, sparkOpts);
    log.info("Done");
  }

  protected void saveLinks(long day, List<DependencyLink> links, String tableDate, Map<String, String> sparkOpts) {
    try (Connection con = DriverManager.getConnection(sparkOpts.get("url"), sparkOpts.get("user"), sparkOpts.get("password"))) {
      PreparedStatement replace = con.prepareStatement(buildSaveLinkSql(tableDate));
      for (DependencyLink link : links) {
        replace.setString(1, tableDate + "_" + link.parent() + "_" + link.child());
        replace.setString(2, "zipkin_dependency");
        replace.setLong(3, day);
        replace.setString(4, link.parent());
        replace.setString(5, link.child());
        replace.setLong(6, link.callCount());
        replace.setLong(7, link.errorCount());
        replace.executeUpdate();
      }
    } catch (SQLException e) {
      throw new RuntimeException("Could not save links " + links, e);
    }
  }

  protected String buildSaveLinkSql(String tableDate) {
    return "REPLACE INTO zipkin_dependency_" + tableDate
        + " (id, table_name, analyze_day, parent, child, call_count, error_count) VALUES (?,?,?,?,?,?,?)";
  }

  protected static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null ? result : defaultValue;
  }
}
