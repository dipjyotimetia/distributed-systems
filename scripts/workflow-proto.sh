#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

[[ "$(buf --version)" == 1.57.0 ]]
[[ "$(protoc --version)" == "libprotoc 31.1" ]]
[[ "$(protoc-gen-go --version)" == "protoc-gen-go v1.36.9" ]]
buf build proto/workflows
buf lint proto/workflows
buf generate proto/workflows --template proto/workflows/buf.gen.yaml --output "${1:-.}"
