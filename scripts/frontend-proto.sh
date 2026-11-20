#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

[[ "$(buf --version)" == 1.75.0 ]]
export PATH="$PWD/frontend/node_modules/.bin:$PATH"
[[ "$(protoc-gen-es --version)" == "protoc-gen-es v2.16.0" ]]
buf generate --template proto/buf.gen.yaml --output "${1:-.}"
