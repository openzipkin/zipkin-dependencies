#!/bin/bash
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

# This script relies on variables defined by Docker Hub Autobuild
# https://docs.docker.com/docker-hub/builds/advanced/#environment-variables-for-building-and-testing
#
# This script uses /bin/bash, not /bin/sh, because /bin/sh on Docker Hub image uses doesn't allow
# substitutions like: ${DOCKER_TAG//,/ }
set -eux

# This hook is called with the current directory set to the same as the Dockerfile, so we go back
# to top level.
cd ..

echo "Building images for ${SOURCE_BRANCH}"

# $SOURCE_BRANCH is something like
#
#   master       - Building the master branch. This cut command will return 'master', and Dockerfiles will ignore it
#   1.0.1        - Building a release image along with a new Zipkin server version. This cut command will return
#                  '1.0.1' and Dockerfiles will use it to fetch the correct version of Zipkin.
#   docker-1.0.1 - Building a release image, but not a new Zipkin server. This cut command will return
#                  '1.0.1' and Dockerfiles will use it to fetch the correct version of Zipkin.
RELEASE_VERSION="$(echo "${SOURCE_BRANCH}" | cut -d '-' -f 2)"

docker build --build-arg release_version="${RELEASE_VERSION}" -f "${DOCKERFILE_PATH}" -t "${IMAGE_NAME}" .

for tag in ${DOCKER_TAG//,/ }; do
  docker tag "$IMAGE_NAME" "${DOCKER_REPO}:${tag}"
done
