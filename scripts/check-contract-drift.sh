#!/usr/bin/env bash
# Regenerate checked-in internal gRPC stubs and Go OpenAPI servers, then diff.
set -euo pipefail
cd "$(dirname "$0")/.."
generated="$(mktemp -d)"
mkdir -p "$generated/cards" "$generated/disputes" "$generated/ai"
export PATH="$(go env GOPATH)/bin:$PATH"

protoc -I contracts/proto/ledger \
  --go_out="$generated/cards" --go_opt=module=github.com/dipjyotimetia/distributed-systems/backend/cards-service \
  --go-grpc_out="$generated/cards" --go-grpc_opt=module=github.com/dipjyotimetia/distributed-systems/backend/cards-service \
  contracts/proto/ledger/ledger.proto
diff -ru backend/cards-service/gen/ledger "$generated/cards/gen/ledger"

protoc -I contracts/proto/transactions \
  --go_out="$generated/disputes" --go_opt=module=github.com/dipjyotimetia/distributed-systems/backend/disputes-service \
  --go-grpc_out="$generated/disputes" --go-grpc_opt=module=github.com/dipjyotimetia/distributed-systems/backend/disputes-service \
  contracts/proto/transactions/query.proto
protoc -I contracts/proto/disputes \
  --go_out="$generated/disputes" --go_opt=module=github.com/dipjyotimetia/distributed-systems/backend/disputes-service \
  --go-grpc_out="$generated/disputes" --go-grpc_opt=module=github.com/dipjyotimetia/distributed-systems/backend/disputes-service \
  contracts/proto/disputes/research.proto
diff -ru backend/disputes-service/gen "$generated/disputes/gen"

# Prefer uv for Python tooling (matches ai-service / knowledge-service).
if command -v uv >/dev/null 2>&1; then
  PY=(uv run --with 'grpcio-tools==1.83.2' python)
else
  PY=(python3)
fi
"${PY[@]}" -m grpc_tools.protoc -I contracts/proto/disputes \
  --python_out="$generated/ai" --grpc_python_out="$generated/ai" contracts/proto/disputes/research.proto
"${PY[@]}" - <<PY
from pathlib import Path
path = Path("$generated/ai/research_pb2_grpc.py")
path.write_text(path.read_text().replace("import research_pb2 as research__pb2", "from gen import research_pb2 as research__pb2"))
PY
diff -ru backend/ai-service/gen/research_pb2.py "$generated/ai/research_pb2.py"
diff -ru backend/ai-service/gen/research_pb2_grpc.py "$generated/ai/research_pb2_grpc.py"

oapi-codegen -generate types,std-http-server -package api -o "$generated/notifications.go" contracts/openapi/notifications.yaml
"${PY[@]}" - <<PY
from pathlib import Path
path = Path("$generated/notifications.go")
text = path.read_text()
for old, new in {
    "func Handler(": "func OpenAPIHandler(",
    "return HandlerWithOptions": "return OpenAPIHandlerWithOptions",
    "func HandlerFromMux(": "func OpenAPIHandlerFromMux(",
    "func HandlerFromMuxWithBaseURL(": "func OpenAPIHandlerFromMuxWithBaseURL(",
    "func HandlerWithOptions(": "func OpenAPIHandlerWithOptions(",
}.items():
    text = text.replace(old, new)
path.write_text(text)
PY
diff -ru backend/notification-service/internal/api/openapi.gen.go "$generated/notifications.go"
oapi-codegen -generate types,std-http-server -package httpapi -o "$generated/disputes.go" contracts/openapi/disputes.yaml
diff -ru backend/disputes-service/internal/httpapi/openapi.gen.go "$generated/disputes.go"
bash scripts/generate-openapi-temporal-clients.sh "$generated/temporal"
diff -ru tests/integration/temporal/openapi "$generated/temporal"
echo "contract stubs match"
