#!/bin/sh
# Retry Gradle bootJar inside Docker builds. Maven Central can return a one-off
# miss that Gradle caches in the BuildKit gradle cache mount; outer docker-build
# retries then fail instantly unless that metadata is cleared.
set -eu

task="${1:?gradle task required, e.g. :api-gateway:bootJar}"
attempt=1
max_attempts=3

while [ "$attempt" -le "$max_attempts" ]; do
  refresh=""
  if [ "$attempt" -gt 1 ]; then
    refresh="--refresh-dependencies"
  fi
  if ./gradlew --no-daemon $refresh "$task"; then
    exit 0
  fi
  echo "Gradle $task failed (attempt $attempt/$max_attempts); clearing module metadata" >&2
  rm -rf \
    /root/.gradle/caches/modules-2/metadata-* \
    /root/.gradle/caches/*/plugin-resolution \
    /root/.gradle/caches/journal-1 || true
  if [ "$attempt" -eq "$max_attempts" ]; then
    exit 1
  fi
  sleep $((attempt * 5))
  attempt=$((attempt + 1))
done
exit 1
