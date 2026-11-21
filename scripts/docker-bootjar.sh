#!/bin/sh
# Retry Gradle bootJar inside Docker builds. Maven Central can return a one-off
# miss that Gradle caches in the BuildKit gradle cache mount; outer docker-build
# retries then fail instantly unless that metadata is cleared. A 429 is different:
# refreshing every dependency repeats the request burst, so those retries wait
# and resolve only what is still missing.
set -eu

task="${1:?gradle task required, e.g. :api-gateway:bootJar}"
attempt=1
max_attempts=6
refresh_next=0

while [ "$attempt" -le "$max_attempts" ]; do
  refresh=""
  if [ "$refresh_next" -eq 1 ]; then
    refresh="--refresh-dependencies"
  fi
  log=$(mktemp)
  if ./gradlew --no-daemon $refresh "$task" >"$log" 2>&1; then
    cat "$log"
    rm -f "$log"
    exit 0
  fi
  cat "$log"
  rate_limited=0
  if grep -q "status code 429" "$log"; then
    rate_limited=1
  fi
  rm -f "$log"
  echo "Gradle $task failed (attempt $attempt/$max_attempts); clearing module metadata" >&2
  rm -rf \
    /root/.gradle/caches/modules-2/metadata-* \
    /root/.gradle/caches/*/plugin-resolution \
    /root/.gradle/caches/journal-1 || true
  if [ "$attempt" -eq "$max_attempts" ]; then
    exit 1
  fi
  if [ "$rate_limited" -eq 1 ]; then
    refresh_next=0
    sleep $((attempt * 20))
  else
    refresh_next=1
    sleep $((attempt * 5))
  fi
  attempt=$((attempt + 1))
done
exit 1
