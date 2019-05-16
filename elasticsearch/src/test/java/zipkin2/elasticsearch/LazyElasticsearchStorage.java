/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zipkin2.elasticsearch;

import java.util.Arrays;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.HttpWaitStrategy;
import zipkin2.CheckResult;

class LazyElasticsearchStorage implements TestRule {
  static final Logger LOGGER = LoggerFactory.getLogger(LazyElasticsearchStorage.class);

  /** Need to watch index pattern from 1970 doesn't result in a request line longer than 4096 */
  static final String INDEX = "test_zipkin";

  final String image;

  GenericContainer container;
  volatile ElasticsearchStorage storage;

  LazyElasticsearchStorage(String image) {
    this.image = image;
  }

  ElasticsearchStorage get() {
    if (storage == null) {
      synchronized (this) {
        if (storage == null) {
          storage = compute();
        }
      }
    }
    return storage;
  }

  ElasticsearchStorage compute() {
    if (!"true".equals(System.getProperty("docker.skip"))) {
      try {
        container =
            new GenericContainer(image)
                .withExposedPorts(9200)
                .withEnv("ES_JAVA_OPTS", "-Dmapper.allow_dots_in_name=true -Xms512m -Xmx512m")
                .waitingFor(new HttpWaitStrategy().forPath("/"));
        container.start();
        if (Boolean.valueOf(System.getenv("ES_DEBUG"))) {
          container.followOutput(new Slf4jLogConsumer(LoggerFactory.getLogger(image)));
        }
        System.out.println("Starting docker image " + image);
      } catch (RuntimeException e) {
        // Ignore
      }
    } else {
      LOGGER.info("Skipping startup of docker " + image);
    }

    ElasticsearchStorage result = computeStorageBuilder().build();
    CheckResult check = result.check();
    if (check.ok()) {
      return result;
    } else {
      throw new AssumptionViolatedException(check.error().getMessage(), check.error());
    }
  }

  ElasticsearchStorage.Builder computeStorageBuilder() {
    OkHttpClient ok =
        Boolean.valueOf(System.getenv("ES_DEBUG"))
            ? new OkHttpClient.Builder()
                .addInterceptor(
                    new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                .addNetworkInterceptor(
                    chain ->
                        chain.proceed( // logging interceptor doesn't gunzip
                            chain.request().newBuilder().removeHeader("Accept-Encoding").build()))
                .build()
            : new OkHttpClient();
    ElasticsearchStorage.Builder builder = ElasticsearchStorage.newBuilder(ok).index(INDEX);
    builder.flushOnWrites(true);
    return builder.hosts(Arrays.asList("http://" + esNodes()));
  }

  String esNodes() {
    if (container != null && container.isRunning()) {
      return container.getContainerIpAddress() + ":" + container.getMappedPort(9200);
    } else {
      // Use localhost if we failed to start a container (i.e. Docker is not available)
      return "localhost:9200";
    }
  }

  void close() {
    try {
      ElasticsearchStorage maybeStorage = storage;
      if (maybeStorage == null) return;
      maybeStorage.close();
    } finally {
      if (container != null) {
        System.out.println("Stopping docker image " + image);
        container.stop();
      }
    }
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        get();
        try {
          base.evaluate();
        } finally {
          close();
        }
      }
    };
  }
}
