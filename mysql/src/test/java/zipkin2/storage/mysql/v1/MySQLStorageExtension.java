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
package zipkin2.storage.mysql.v1;

import java.sql.SQLException;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mariadb.jdbc.MariaDbDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.CheckResult;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MySQLStorageExtension implements BeforeAllCallback, AfterAllCallback {
  static final Logger LOGGER = LoggerFactory.getLogger(MySQLStorageExtension.class);

  final String image;

  ZipkinMySQLContainer container;

  MySQLStorageExtension(String image) {
    this.image = image;
  }

  @Override public void beforeAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }

    if (!"true".equals(System.getProperty("docker.skip"))) {
      try {
        container = new ZipkinMySQLContainer(image);
        container.start();
        LOGGER.info("Starting docker image " + image);
      } catch (RuntimeException e) {
        LOGGER.warn("Couldn't start docker image " + image + ": " + e.getMessage(), e);
      }
    } else {
      LOGGER.info("Skipping startup of docker " + image);
    }

    assumeTrue(container != null, "Docker not available");

    try (MySQLStorage result = computeStorageBuilder().build()) {
      CheckResult check = result.check();
      assumeTrue(check.ok(), () -> "Could not connect to storage, skipping test: "
        + check.error().getMessage());
    }
  }

  @Override public void afterAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }
    if (container != null) container.stop();
  }

  MySQLStorage.Builder computeStorageBuilder() {
    final MariaDbDataSource dataSource;

    try {
      assumeTrue(container != null, "Docker not available");
      dataSource = container.getDataSource();
      dataSource.setProperties("autoReconnect=true&useUnicode=yes&characterEncoding=UTF-8");
    } catch (SQLException e) {
      throw new AssertionError(e);
    }

    return new MySQLStorage.Builder()
      .datasource(dataSource)
      .executor(Runnable::run);
  }
}
