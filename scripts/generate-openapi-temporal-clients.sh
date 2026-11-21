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

python3 - "$root/transactions/client.gen.go" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
old = """\t\tvar dest Rejection
\t\tif err := json.Unmarshal(bodyBytes, &dest); err != nil {
\t\t\treturn nil, err
\t\t}
\t\tresponse.JSON400 = &dest"""
new = """\t\tif parsed, ok := modeledRejection(bodyBytes); ok {
\t\t\tresponse.JSON400 = parsed
\t\t}"""
text = path.read_text()
count = text.count(old)
if count == 0 and new not in text:
    raise SystemExit("transactions client no longer unmarshals HTTP 400 into Rejection")
path.write_text(text.replace(old, new))
PY

companion="tests/integration/temporal/openapi/transactions/rejection.go"
dest="$root/transactions/rejection.go"
if [[ ! -e "$dest" || ! "$companion" -ef "$dest" ]]; then
  cp "$companion" "$dest"
fi
