#!/bin/sh
#
# Copyright 2016-2020 The OpenZipkin Authors
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.
#

set -eux

# This script decides based on $RELEASE_VERSION whether to build or download the binary we need.
if [ "$RELEASE_VERSION" = "master" ]
then
  echo "*** Building from source..."
  # Uses the same command as we suggest in README.md: disables JDK enforcement only needed in tests
  (cd /code; ./mvnw -T1C -q --batch-mode -DskipTests -Denforcer.fail=false --also-make -pl main package)
  cp /code/main/target/zipkin-dependencies*.jar zipkin-dependencies.jar
else
  artifact=io.zipkin.dependencies:zipkin-dependencies:${RELEASE_VERSION}:jar
  echo "*** Downloading ${artifact} using Maven...."
  # This prefers Maven central, but uses our release repository if it isn't yet synced.
  mvn --batch-mode org.apache.maven.plugins:maven-dependency-plugin:get \
      -DremoteRepositories=bintray::::https://dl.bintray.com/openzipkin/maven -Dtransitive=false \
      -Dartifact=${artifact}
    find ~/.m2/repository -name zipkin-dependencies-${RELEASE_VERSION}.jar -exec cp {} zipkin-dependencies.jar \;
fi

# sanity check!
test -f zipkin-dependencies.jar
