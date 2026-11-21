#!/usr/bin/env bash
# Regenerate HTTP models and clients from the OpenAPI specs.
# Usage: scripts/openapi-gen.sh [output-root]
# With no argument, files are written into the repository.
# With an output root, the same relative paths are written there for a diff.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="${1:-$root}"
openapi_generator_version="7.14.0"
oapi_codegen_version="v2.4.1"
datamodel_codegen_version="0.28.5"
redocly_version="1.28.5"

jar="${TMPDIR:-/tmp}/openapi-generator-cli-${openapi_generator_version}.jar"
if [[ ! -f "$jar" ]]; then
  urls=(
    "https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/${openapi_generator_version}/openapi-generator-cli-${openapi_generator_version}.jar"
    "https://repo.maven.apache.org/maven2/org/openapitools/openapi-generator-cli/${openapi_generator_version}/openapi-generator-cli-${openapi_generator_version}.jar"
  )
  tmp_jar="$(mktemp)"
  downloaded=0
  for url in "${urls[@]}"; do
    if curl -fsSL --retry 3 --retry-all-errors "$url" -o "$tmp_jar"; then
      downloaded=1
      break
    fi
  done
  if [[ "$downloaded" -ne 1 ]]; then
    echo "failed to download openapi-generator-cli ${openapi_generator_version}" >&2
    exit 1
  fi
  mv "$tmp_jar" "$jar"
fi

generate_java() {
  local spec="$1"
  local package="$2"
  local relative="$3"
  local out
  out="$(mktemp -d)"
  java -jar "$jar" generate \
    -g spring \
    -i "$spec" \
    -o "$out" \
    --global-property models,modelDocs=false,modelTests=false \
    --additional-properties "useJakartaEe=true,useBeanValidation=true,dateLibrary=java8-localdatetime,openApiNullable=false,hideGenerationTimestamp=true,interfaceOnly=true,useSwaggerUI=false,documentationProvider=none,bigDecimalAsString=false,serializationLibrary=jackson,modelPackage=${package}" \
    >/dev/null
  local src="$out/src/main/java/${package//.//}"
  local target="$dest/$relative"
  rm -rf "$target"
  mkdir -p "$target"
  find "$src" -name '*.java' -exec cp {} "$target/" \;
  rm -rf "$out"
}

generate_csharp() {
  local out
  out="$(mktemp -d)"
  java -jar "$jar" generate \
    -g csharp \
    -i "$root/backend/user-service/openapi.yml" \
    -o "$out" \
    --global-property models,modelDocs=false,modelTests=false \
    --additional-properties "packageName=UserServiceDotnet.Generated,targetFramework=net8.0,nullableReferenceTypes=true,validatable=false,hideGenerationTimestamp=true,useDateTimeOffset=false,library=httpclient" \
    >/dev/null
  local target="$dest/backend/user-service/Generated"
  rm -rf "$target"
  mkdir -p "$target"
  find "$out" -path '*/Model/*.cs' -exec cp {} "$target/" \;
  python3 - "$target" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
for path in root.glob("*.cs"):
    lines = []
    for line in path.read_text().splitlines(True):
        if "FileParameter" in line or "OpenAPIDateConverter" in line:
            continue
        lines.append(line)
    path.write_text("".join(lines))
PY
  rm -rf "$out"
}

generate_python() {
  local target="$dest/backend/ai-service/generated_models.py"
  mkdir -p "$(dirname "$target")"
  if ! command -v datamodel-codegen >/dev/null 2>&1 || [[ "$(datamodel-codegen --version 2>/dev/null || true)" != *"$datamodel_codegen_version"* ]]; then
    python3 -m pip install --user --disable-pip-version-check "datamodel-code-generator==${datamodel_codegen_version}" >/dev/null
  fi
  local bin="datamodel-codegen"
  if ! command -v datamodel-codegen >/dev/null 2>&1; then
    bin="${HOME}/.local/bin/datamodel-codegen"
  fi
  "$bin" \
    --input "$root/backend/ai-service/openapi.yml" \
    --input-file-type openapi \
    --output "$target" \
    --output-model-type pydantic_v2.BaseModel \
    --use-schema-description \
    --target-python-version 3.11 \
    --disable-timestamp
}

generate_typescript() {
  local bundled="$1"
  local target="$dest/frontend/src/gen/http"
  local out
  out="$(mktemp -d)"
  java -jar "$jar" generate \
    -g typescript \
    -i "$bundled" \
    -o "$out" \
    --global-property models,modelDocs=false,modelTests=false \
    --additional-properties "modelPropertyNaming=original,supportsES6=true,enumPropertyNaming=original,stringEnums=true,hideGenerationTimestamp=true" \
    >/dev/null
  rm -rf "$target"
  mkdir -p "$target"
  find "$out/models" -name '*.ts' -exec cp {} "$target/" \;
  python3 - "$target" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
for path in root.glob("*.ts"):
    text = path.read_text()
    text = text.replace("from '../models/", "from './")
    text = text.replace("import { HttpFile } from '../http/http';\n", "")
    lines = text.splitlines(keepends=True)
    last_import = max((index for index, line in enumerate(lines) if line.startswith("import ")), default=-1)
    missing = []
    for name in ("AccountResponse", "TransactionResponse"):
        if name in text and f"import {{ {name} }}" not in text and f"export class {name}" not in text and f"export type {name}" not in text:
            missing.append(f"import {{ {name} }} from './{name}';\n")
    if missing:
        lines[last_import + 1:last_import + 1] = missing
        text = "".join(lines)
    path.write_text(text)
PY
  rm -rf "$out"
}

generate_go() {
  local bundled="$1"
  local target="$dest/tests/integration/temporal/gen"
  mkdir -p "$target"
  local gopath_bin
  gopath_bin="$(go env GOPATH)/bin"
  if [[ ! -x "$gopath_bin/oapi-codegen" ]] || [[ "$("$gopath_bin/oapi-codegen" -version 2>&1 || true)" != *"${oapi_codegen_version#v}"* ]]; then
    GOBIN="$gopath_bin" go install "github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen@${oapi_codegen_version}"
  fi
  "$gopath_bin/oapi-codegen" \
    -generate models,client \
    -package banking \
    -o "$target/banking.gen.go" \
    "$bundled"
  python3 - "$target" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
generated = root / "banking.gen.go"
text = generated.read_text()
text = text.replace("time.Time", "LocalTime")
text = text.replace('\t"time"\n', "")
generated.write_text(text)
(root / "localtime.go").write_text('''package banking

import (
	"encoding/json"
	"time"
)

// LocalTime accepts the offset local date-time the Java services emit and RFC3339.
type LocalTime time.Time

func (t *LocalTime) UnmarshalJSON(data []byte) error {
	var raw string
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	if raw == "" {
		return nil
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.999999999", "2006-01-02T15:04:05"} {
		parsed, err := time.Parse(layout, raw)
		if err == nil {
			*t = LocalTime(parsed)
			return nil
		}
	}
	return &time.ParseError{Layout: time.RFC3339Nano, Value: raw, Message: "not a local or RFC3339 date-time"}
}

func (t LocalTime) MarshalJSON() ([]byte, error) {
	return json.Marshal(time.Time(t).Format("2006-01-02T15:04:05.999999999"))
}

func (t LocalTime) Time() time.Time { return time.Time(t) }
''')
PY
}

bundled="$(mktemp --suffix=.yml)"
npx --yes "@redocly/cli@${redocly_version}" bundle "$root/contracts/frontend-api.openapi.yml" -o "$bundled" >/dev/null

generate_java "$root/backend/accounts-service/openapi.yml" com.banking.accountsservice.generated \
  backend/accounts-service/src/main/java/com/banking/accountsservice/generated
generate_java "$root/backend/transactions-service/openapi.yml" com.banking.transactionsservice.generated \
  backend/transactions-service/src/main/java/com/banking/transactionsservice/generated
generate_csharp
generate_python
generate_typescript "$bundled"
generate_go "$bundled"
rm -f "$bundled"
echo "generated HTTP models into $dest"
