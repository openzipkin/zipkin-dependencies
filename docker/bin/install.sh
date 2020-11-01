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

# This script decides based on $RELEASE_FROM_MAVEN_BUILD and $RELEASE_VERSION whether to reuse or
# download the binaries we need.
if [ "$RELEASE_FROM_MAVEN_BUILD" = "true" ]; then
  echo "*** Reusing zipkin-dependencies jar in the Docker context..."
  cp "/code/main/target/zipkin-dependencies-${RELEASE_VERSION}.jar" zipkin-dependencies.jar
else
  io.zipkin.dependencies:zipkin-dependencies:${RELEASE_VERSION}:jar
  case ${RELEASE_VERSION} in
    *-SNAPSHOT )
      echo "Building from source within Docker is not supported. \
            Build via instructions at the bottom of README.md and set RELEASE_FROM_MAVEN_BUILD=true"
      exit 1
      ;;
    * )
      echo "*** Downloading from Maven..."
      io.zipkin.dependencies:zipkin-dependencies:${RELEASE_VERSION}:jar
      mvn -q --batch-mode --batch-mode org.apache.maven.plugins:maven-dependency-plugin:3.1.2:get \
          -Dtransitive=false -Dartifact=io.zipkin.dependencies:zipkin-dependencies:${RELEASE_VERSION}:jar
      find ~/.m2/repository -name zipkin-dependencies-${RELEASE_VERSION}.jar -exec cp {} zipkin-dependencies.jar \;
      ;;
    esac
fi

# sanity check!
test -f zipkin-dependencies.jar
