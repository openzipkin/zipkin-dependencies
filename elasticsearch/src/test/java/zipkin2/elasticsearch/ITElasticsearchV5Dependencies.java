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
package zipkin2.elasticsearch;

import org.junit.ClassRule;

public class ITElasticsearchV5Dependencies extends ITElasticsearchDependencies {

  @ClassRule
  public static LazyElasticsearchStorage storage =
      new LazyElasticsearchStorage("openzipkin/zipkin-elasticsearch5:2.11.9");

  @Override
  protected ElasticsearchStorage esStorage() {
    return storage.get();
  }

  @Override
  protected String esNodes() {
    return storage.esNodes();
  }
}
