#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

[[ "$(buf --version)" == 1.75.0 ]]
[[ "$(protoc --version)" == "libprotoc 36.2" ]]
[[ "$(protoc-gen-go --version)" == "protoc-gen-go v1.36.12" ]]
buf build proto/workflows
buf lint proto/workflows
buf generate proto/workflows --template proto/workflows/buf.gen.yaml --output "${1:-.}"
