/*
 * Copyright 2016-2019 The OpenZipkin Authors
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
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.containers.MySQLContainer;

final class ZipkinMySQLContainer extends MySQLContainer {
  ZipkinMySQLContainer(String image) {
    super(image);
  }

  @Override public String getDatabaseName() {
    return "zipkin";
  }

  @Override public String getUsername() {
    return "zipkin";
  }

  @Override public String getPassword() {
    return "zipkin";
  }

  @Override public String getDriverClassName() {
    return "org.mariadb.jdbc.Driver";
  }

  MariaDbDataSource getDataSource() throws SQLException {
    MariaDbDataSource dataSource = new MariaDbDataSource(
      getContainerIpAddress(),
      getMappedPort(MYSQL_PORT),
      getDatabaseName()
    );
    dataSource.setUser(getUsername());
    dataSource.setPassword(getPassword());
    return dataSource;
  }
}
