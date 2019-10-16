#
# Copyright 2016-2019 The OpenZipkin Authors
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

FROM maven:3-jdk-8-slim

WORKDIR /code
COPY . /code
RUN mvn -B --no-transfer-progress package -DskipTests=true -pl main -am

FROM openzipkin/jre-full:1.8.0_212
MAINTAINER OpenZipkin "https://zipkin.io/"

# Use to set heap, trust store or other system properties.
ENV JAVA_OPTS -Djava.security.egd=file:/dev/./urandom

WORKDIR /zipkin-dependencies

COPY --from=0 /code/main/target/zipkin-dependencies-*.jar /zipkin-dependencies/

# Enable cron by running with entrypoint: crond -f -d 8
# * Bundling this configuration is a convenience, noting not everyone will use cron
# * Cron invokes this job hourly to process today's spans and daily to process yesterday's
COPY docker/periodic/ /etc/periodic/

# Adds coreutils to allow date formatting of 'yesterday'
RUN apk add --no-cache coreutils

# Default entrypoint is to run the dependencies job on-demand, processing today's spans.
CMD java ${JAVA_OPTS} -jar zipkin-dependencies-*.jar
