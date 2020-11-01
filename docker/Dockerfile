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

ARG java_version

# We copy files from the context into a scratch container first to avoid a problem where docker and
# docker-compose don't share layer hashes https://github.com/docker/compose/issues/883 normally.
# COPY --from= works around the issue.
FROM scratch as scratch

COPY . /code/

FROM ghcr.io/openzipkin/java:${java_version} as install

WORKDIR /install

# Conditions aren't supported in Dockerfile instructions, so we copy source even if it isn't used.
COPY --from=scratch /code /code

# This will either be a snapshot like 2.4.5-SNAPSHOT or a real version ex. "2.4.5"
ARG release_version
ENV RELEASE_VERSION=$release_version
# When true, main images reuse zipkin-exec.jar and zipkin-slim.jar in the context root
ARG release_from_maven_build=false
ENV RELEASE_FROM_MAVEN_BUILD=$release_from_maven_build
RUN /code/docker/bin/install.sh

FROM ghcr.io/openzipkin/java:${java_version}-jre as zipkin-dependencies
LABEL org.opencontainers.image.description="Zipkin Dependencies Aggregator on OpenJDK and Alpine Linux"

# Adds coreutils to allow date formatting of 'yesterday'
RUN apk add --no-cache coreutils
# Enable cron by running with entrypoint: crond -f -d 8
# * Bundling this configuration is a convenience, noting not everyone will use cron
# * Cron invokes this job hourly to process today's spans and daily to process yesterday's
COPY docker/periodic/ /etc/periodic/

# Adds ENTRYPOINT script into the default search path
COPY --from=install /code/docker/bin/ /tmp/docker-bin/
RUN mv /tmp/docker-bin/start-zipkin-dependencies /usr/local/bin/ && \
    rm -rf /tmp/docker-bin

# All content including binaries and logs write under WORKDIR
ARG USER=zipkin-dependencies
WORKDIR /${USER}

# Ensure the process doesn't run as root
RUN adduser -g '' -h ${PWD} -D ${USER}
USER ${USER}

# Copy binaries and config we installed earlier
COPY --from=install --chown=${USER} /install .

ENV STORAGE_TYPE=""
# Use to set heap, trust store or other system properties.
ENV JAVA_OPTS="-Xms512m -Xmx512m -XX:+ExitOnOutOfMemoryError"

# Default entrypoint is to run the dependencies job on-demand, processing today's spans.
ENTRYPOINT ["start-zipkin-dependencies"]
