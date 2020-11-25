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
package zipkin2.elasticsearch;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.testcontainers.utility.DockerImageName.parse;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITElasticsearchV6Dependencies extends ITElasticsearchDependencies {
  @RegisterExtension ElasticsearchStorageExtension backend = new ElasticsearchStorageExtension(
    parse("ghcr.io/openzipkin/zipkin-elasticsearch6:2.23.0"));

  @Override ElasticsearchStorageExtension backend() {
    return backend;
  }
}
