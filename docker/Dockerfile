#
# Copyright 2016-2023 The OpenZipkin Authors
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

# java_version is used during the installation process to build or download the module jar.
#
# Use latest version here: https://github.com/orgs/openzipkin/packages/container/package/java
# This is defined in many places because Docker has no "env" script functionality unless you use
# docker-compose: When updating, update everywhere.
ARG java_version=21.0.1_p12

# We copy files from the context into a scratch container first to avoid a problem where docker and
# docker-compose don't share layer hashes https://github.com/docker/compose/issues/883 normally.
# COPY --from= works around the issue.
FROM scratch as scratch

COPY . /code/

FROM ghcr.io/openzipkin/java:$java_version as install

WORKDIR /code
# Conditions aren't supported in Dockerfile instructions, so we copy source even if it isn't used.
COPY --from=scratch /code/ .

WORKDIR /install

# When true, build-bin/maven/unjar searches /code for the artifact instead of resolving remotely.
# /code contains what is allowed in .dockerignore. On problem, ensure .dockerignore is correct.
ARG release_from_maven_build=false
ENV RELEASE_FROM_MAVEN_BUILD=$release_from_maven_build
# Version of the artifact to unjar. Ex. "2.4.5" or "2.4.5-SNAPSHOT" "master" to use the pom version.
ARG version=master
ENV VERSION=$version
ENV MAVEN_PROJECT_BASEDIR=/code
RUN /code/build-bin/maven/maven_build_or_unjar io.zipkin.dependencies zipkin-dependencies ${VERSION}

FROM ghcr.io/openzipkin/java:11.0.21_p9-jre as zipkin-dependencies
LABEL org.opencontainers.image.description="Zipkin Dependencies Aggregator on OpenJDK and Alpine Linux"
LABEL org.opencontainers.image.source=https://github.com/openzipkin/zipkin-dependencies

# Adds coreutils to allow date formatting of 'yesterday'
RUN apk add --no-cache coreutils

# Create custom user and ensure the process doesn't run as root
ARG USER=zipkin-dependencies
RUN adduser -g '' -h ${PWD} -D ${USER}
USER ${USER}

# Enable cron by running with entrypoint: crond -f -d 8 (change --user root)
# * Bundling this configuration is a convenience, noting not everyone will use cron
# * Cron invokes this job hourly to process today's spans and daily to process yesterday's
COPY --chown=${USER} docker/periodic/ /etc/periodic/

# Adds ENTRYPOINT script into the default search path
COPY --from=install --chown=${USER} /code/docker/bin/start-zipkin-dependencies /usr/local/bin/

# All content including binaries and logs write under WORKDIR
WORKDIR /${USER}

# Copy binaries and config we installed earlier
COPY --from=install --chown=${USER} /install/* ./classes/

ENV STORAGE_TYPE=""
# Use to set heap, trust store or other system properties.
ENV JAVA_OPTS="-Xms512m -Xmx512m -XX:+ExitOnOutOfMemoryError"

# Default entrypoint is to run the dependencies job on-demand, processing today's spans.
ENTRYPOINT ["start-zipkin-dependencies"]
