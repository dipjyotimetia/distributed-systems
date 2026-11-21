#!/usr/bin/env bash
# Generate the Temporal journey OpenAPI clients from contracts/openapi.
set -euo pipefail
cd "$(dirname "$0")/.."
root="${1:-tests/integration/temporal/openapi}"
export PATH="$(go env GOPATH)/bin:$PATH"

for spec in users accounts transactions payments ai; do
  mkdir -p "$root/$spec"
  oapi-codegen -generate types,client -package "$spec" -response-type-suffix HTTP \
    -o "$root/$spec/client.gen.go" "contracts/openapi/$spec.yaml"
done
