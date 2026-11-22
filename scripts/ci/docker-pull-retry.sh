#!/usr/bin/env bash
# Retry docker pull for registry TLS / auth flakes (common on Docker Hub in CI).
set -euo pipefail

max="${DOCKER_PULL_RETRIES:-5}"
if [[ "$#" -eq 0 ]]; then
  echo "usage: $0 image [image...]" >&2
  exit 2
fi

for image in "$@"; do
  attempt=1
  until docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"; do
    if (( attempt >= max )); then
      echo "docker pull failed for $image after $max attempts" >&2
      exit 1
    fi
    echo "docker pull $image failed (attempt $attempt/$max); retrying..." >&2
    sleep $((attempt * 5))
    attempt=$((attempt + 1))
  done
done
