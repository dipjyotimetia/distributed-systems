#!/usr/bin/env bash
# Build microsvc-journeys images in parallel (Buildx + optional GHA cache) and
# load them into a Kind cluster.
#
# Usage:
#   scripts/ci/build-and-load-kind-images.sh <cluster-name> <smoke|payments>
#
# Env:
#   GRADLE_CACHE_SUFFIX   BuildKit gradle cache id suffix (default 0)
#   KIND_IMAGE_PARALLEL   Max concurrent builds (default 4)
set -euo pipefail

cluster="${1:?kind cluster name required}"
profile="${2:?profile required: smoke|payments}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

GRADLE_CACHE_SUFFIX="${GRADLE_CACHE_SUFFIX:-0}"
KIND_IMAGE_PARALLEL="${KIND_IMAGE_PARALLEL:-4}"
use_gha_cache=0
if [[ -n "${ACTIONS_CACHE_URL:-}" && -n "${ACTIONS_RUNTIME_TOKEN:-}" ]]; then
  use_gha_cache=1
fi

smoke_services=(
  api-gateway accounts-service user-service transactions-service
  fraud-service notification-service ai-service disputes-service
)
payments_extra=(payments-service)

case "$profile" in
  smoke) services=("${smoke_services[@]}") ;;
  payments) services=("${smoke_services[@]}" "${payments_extra[@]}") ;;
  *)
    echo "unknown profile: $profile (expected smoke|payments)" >&2
    exit 1
    ;;
esac

build_one() {
  local service="$1"
  local image="microsvc-journeys/${service}:checkout"
  local context="" file=""
  local attempt=1
  local suffix="${GRADLE_CACHE_SUFFIX}"

  case "$service" in
    accounts-service|api-gateway|payments-service|transactions-service)
      file="backend/${service}/Dockerfile"
      context="."
      ;;
    frontend)
      context="frontend"
      ;;
    provider-fixtures)
      file="tests/integration/temporal/stack/Dockerfile"
      context="."
      ;;
    *)
      context="backend/${service}"
      ;;
  esac

  echo "::group::build ${image}"
  while (( attempt <= 5 )); do
    local -a args=()
    if [[ "$use_gha_cache" -eq 1 ]]; then
      args=(buildx build --load
        --build-arg "GRADLE_CACHE_SUFFIX=${suffix}"
        --cache-from "type=gha,scope=kind-${service}"
        --cache-to "type=gha,mode=max,scope=kind-${service}"
        -t "$image")
    else
      args=(build -t "$image" --build-arg "GRADLE_CACHE_SUFFIX=${suffix}")
    fi
    [[ -n "$file" ]] && args+=(-f "$file")
    args+=("$context")
    if docker "${args[@]}"; then
      echo "::endgroup::"
      return 0
    fi
    echo "build failed for $image (attempt $attempt)" >&2
    if (( attempt == 5 )); then
      echo "::endgroup::"
      return 1
    fi
    sleep $((attempt * 10))
    attempt=$((attempt + 1))
    suffix="r${attempt}"
  done
}

images=()
batch=()
fail=0
flush_batch() {
  local pid
  for pid in "${batch[@]+"${batch[@]}"}"; do
    if ! wait "$pid"; then
      fail=1
    fi
  done
  batch=()
}

for service in "${services[@]}" frontend provider-fixtures; do
  build_one "$service" &
  batch+=("$!")
  images+=("microsvc-journeys/${service}:checkout")
  if (( ${#batch[@]} >= KIND_IMAGE_PARALLEL )); then
    flush_batch
  fi
done
flush_batch

if (( fail != 0 )); then
  echo "one or more image builds failed" >&2
  exit 1
fi

kind load docker-image --name "$cluster" "${images[@]}"
echo "Loaded ${#images[@]} images into kind cluster $cluster"
