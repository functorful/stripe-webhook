#!/usr/bin/env sh

set -e

if [ -f ".env" ]; then
  source .env
fi

set -v

if [ -z "${CONTAINER_TEST_IMAGE}" ]; then
  CONTAINER_TEST_IMAGE=test-image
fi

if [ -z "${CONTAINER_RELEASE_IMAGE}" ]; then
  CONTAINER_RELEASE_IMAGE=release-image
fi

VERSION_FILE="version.txt"

VERSION="$( cat ${VERSION_FILE} )"

docker pull "${CONTAINER_TEST_IMAGE}"

docker tag "${CONTAINER_TEST_IMAGE}" "${CONTAINER_RELEASE_IMAGE}:v${VERSION}"
docker push "${CONTAINER_RELEASE_IMAGE}:${VERSION}"

docker tag "${CONTAINER_TEST_IMAGE}" "${CONTAINER_RELEASE_IMAGE}:latest"
docker push "${CONTAINER_RELEASE_IMAGE}:latest"
