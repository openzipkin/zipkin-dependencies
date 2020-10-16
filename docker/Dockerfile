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

# Until we can upgrade to Spark 3, we are pinned to JRE 1.8
ARG java_version=1.8.0_252-b09

# We copy files from the context into a scratch container first to avoid a problem where docker and
# docker-compose don't share layer hashes https://github.com/docker/compose/issues/883 normally.
# COPY --from= works around the issue.
FROM scratch as scratch

COPY . /code/

#####
# zipkin-builder - An image that caches build repositories (.m2, .npm) to speed up subsequent builds.
#####

FROM openzipkin/zipkin-builder as install

WORKDIR /install

# Conditions aren't supported in Dockerfile instructions, so we copy source even if it isn't used.
COPY --from=scratch /code /code

# This will either be "master" or a real version ex. "2.4.5"
ARG release_version=master
COPY docker/bin/install.sh /tmp/
RUN RELEASE_VERSION=${release_version} /tmp/install.sh && rm /tmp/install.sh

FROM openzipkin/java:${java_version}-jre as zipkin-dependencies

# Adds coreutils to allow date formatting of 'yesterday'
RUN apk add --no-cache coreutils
# Enable cron by running with entrypoint: crond -f -d 8
# * Bundling this configuration is a convenience, noting not everyone will use cron
# * Cron invokes this job hourly to process today's spans and daily to process yesterday's
COPY docker/periodic/ /etc/periodic/

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
CMD java ${JAVA_OPTS} -Djava.io.tmpdir=/tmp -jar zipkin-dependencies.jar
