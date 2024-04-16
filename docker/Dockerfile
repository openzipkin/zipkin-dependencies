#
# Copyright The OpenZipkin Authors
# SPDX-License-Identifier: Apache-2.0
#

# java_version is used during the installation process to build or download the module jar.
#
# Use latest version here: https://github.com/orgs/openzipkin/packages/container/package/java
# This is defined in many places because Docker has no "env" script functionality unless you use
# docker-compose: When updating, update everywhere.
#
# TODO: temporarily 11 until we can update to spark on scala 2.13
ARG java_version=11.0.22_p7

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

# Try -jre again once Spark dependencies are up to date. Spark 3.0 uses old Hadoop in the
# Elasticsearch driver which needs com.sun.security.auth.module.UnixLoginModule.
FROM ghcr.io/openzipkin/java:11.0.22_p7 as zipkin-dependencies
LABEL org.opencontainers.image.description="Zipkin Dependencies Aggregator on OpenJDK and Alpine Linux"
LABEL org.opencontainers.image.source=https://github.com/openzipkin/zipkin-dependencies

# Adds dateutils to allow date formatting. Don't use coreutils until below:
# https://gitlab.alpinelinux.org/alpine/aports/-/issues/16002
RUN apk add --no-cache dateutils

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
