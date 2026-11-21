#!/usr/bin/env bash
# Regenerate the frontend financial API clients.
set -euo pipefail
cd "$(dirname "$0")/.."
output="${1:-frontend/src/gen/openapi}"
generated="$(mktemp -d)"
trap 'rm -rf "$generated"' EXIT
version=7.14.0
artifact="openapi-generator-cli-$version.jar"
curl -fsS --retry 3 "https://repo.maven.apache.org/maven2/org/openapitools/openapi-generator-cli/$version/$artifact" \
  -o "$generated/$artifact"
for spec in transactions accounts payments; do
  java -jar "$generated/$artifact" generate \
    -i "contracts/openapi/$spec.yaml" -g typescript-fetch -o "$generated/$spec" \
    --additional-properties="withoutRuntimeChecks=true,supportsES6=true,npmName=$spec-openapi,npmVersion=1.0.0" \
    --global-property=apiDocs=false,modelDocs=false,apiTests=false,modelTests=false
  find "$generated/$spec/src" -name '*.ts' -exec perl -pi -e 's/[ \t]+$//' {} +
  find "$generated/$spec/src" -name '*.ts' -exec perl -0777 -pi -e 's/\n+\z/\n/' {} +
  mkdir -p "$output/$spec"
  cp -R "$generated/$spec/src" "$output/$spec/"
done
