#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

[[ "$(buf --version)" == 1.75.0 ]]
[[ "$(protoc --version)" == "libprotoc 36.2" ]]
[[ "$(protoc-gen-go --version)" == "protoc-gen-go v1.36.12" ]]
buf build contracts/proto/workflows
buf lint contracts/proto/workflows
buf generate contracts/proto/workflows --template contracts/proto/workflows/buf.gen.yaml --output "${1:-.}"
