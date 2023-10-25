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
package zipkin2.dependencies;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.TimeZone;
import zipkin2.dependencies.elasticsearch.ElasticsearchDependenciesJob;
import zipkin2.dependencies.h2.H2DependenciesJob;
import zipkin2.dependencies.mysql.MySQLDependenciesJob;
import zipkin2.dependencies.postgres.PostgresDependenciesJob;
import zipkin2.dependencies.banyandb.BanyanDBDependenciesJob;

public final class ZipkinDependenciesJob {
  /** Runs with defaults, starting today */
  public static void main(String[] args) throws UnsupportedEncodingException {
    String[] jarPath = pathToUberJar();
    long day = args.length == 1 ? parseDay(args[0]) : System.currentTimeMillis();
    String storageType = System.getenv("STORAGE_TYPE");
    if (storageType == null) {
      throw new IllegalArgumentException("STORAGE_TYPE not set");
    }

    String zipkinLogLevel = System.getenv("ZIPKIN_LOG_LEVEL");
    if (zipkinLogLevel == null) zipkinLogLevel = "INFO";
    Runnable logInitializer = LogInitializer.create(zipkinLogLevel);
    logInitializer.run(); // Ensures local log commands emit

    final LinkedHashMap<String, String> sparkConf = new LinkedHashMap<>();
    String sparkConfRaw = System.getenv("SPARK_CONF");
    if (sparkConfRaw != null && !sparkConfRaw.isEmpty() && sparkConfRaw.indexOf("=") > -1) {
      for (String pair : sparkConfRaw.split(",", -1)) {
        final String[] splits = pair.split("=", -1);
        if (splits.length == 2) {
          sparkConf.put(splits[0], splits[1]);
        }
      }
    }

    switch (storageType) {
      case "cassandra3":
        zipkin2.dependencies.cassandra3.CassandraDependenciesJob.builder()
          .logInitializer(logInitializer)
          .jars(jarPath)
          .day(day)
          .conf(sparkConf)
          .build()
          .run();
        break;
      case "mysql":
        MySQLDependenciesJob.builder()
          .logInitializer(logInitializer)
          .jars(jarPath)
          .day(day)
          .conf(sparkConf)
          .build()
          .run();
        break;
      case "h2":
        H2DependenciesJob.builder()
            .logInitializer(logInitializer)
            .jars(jarPath)
            .day(day)
            .conf(sparkConf)
            .build()
            .run();
        break;
      case "postgresql":
        PostgresDependenciesJob.builder()
            .logInitializer(logInitializer)
            .jars(jarPath)
            .day(day)
            .conf(sparkConf)
            .build()
            .run();
        break;
      case "elasticsearch":
        ElasticsearchDependenciesJob.builder()
          .logInitializer(logInitializer)
          .jars(jarPath)
          .day(day)
          .conf(sparkConf)
          .build()
          .run();
        break;
      case "banyandb":
        BanyanDBDependenciesJob.builder()
          .logInitializer(logInitializer)
          .jars(jarPath)
          .day(day)
          .conf(sparkConf)
          .build()
          .run();
        break;
      default:
        throw new UnsupportedOperationException("Unsupported STORAGE_TYPE: " + storageType + "\n"
          + "Options are: cassandra3, mysql, elasticsearch");
    }
  }

  static String[] pathToUberJar() throws UnsupportedEncodingException {
    URL jarFile = ZipkinDependenciesJob.class.getProtectionDomain().getCodeSource().getLocation();
    return new File(jarFile.getPath()).isDirectory() ? null
      : new String[] {URLDecoder.decode(jarFile.getPath(), "UTF-8")};
  }

  static long parseDay(String formattedDate) {
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    try {
      return df.parse(formattedDate).getTime();
    } catch (ParseException e) {
      throw new IllegalArgumentException(
        "First argument must be a yyyy-MM-dd formatted date. Ex. 2016-07-16");
    }
  }
}
