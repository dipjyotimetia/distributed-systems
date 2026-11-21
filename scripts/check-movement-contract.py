"""Validate financial contracts and their acceptance examples."""

import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parent.parent / "contracts"
spec = yaml.safe_load((ROOT / "openapi/transactions.yaml").read_text())
validate(spec)


def resolve(node, document):
    if "$ref" in node:
        target = document
        for part in node["$ref"].removeprefix("#/").split("/"):
            target = target[part]
        return target
    return node


def check_content(container, document):
    for media in resolve(container, document).get("content", {}).values():
        validator = OAS30Validator(
            resolve(media["schema"], document) | {"components": document["components"]},
            format_checker=OAS30Validator.FORMAT_CHECKER,
        )
        if "example" in media:
            validator.validate(media["example"])
        for example in media.get("examples", {}).values():
            validator.validate(example["value"])


paths = spec["paths"]
submission = paths["/api/v1/movements"]["post"]
lookup = paths["/api/v1/movements/{id}"]["get"]
check_content(submission["requestBody"], spec)
for operation in (submission, lookup):
    for response in operation["responses"].values():
        check_content(response, spec)

accepted_response = submission["responses"]["202"]
outcomes = accepted_response["content"]["application/json"]["examples"]
accepted = outcomes["accepted"]["value"]
retried = outcomes["lostResponseRetry"]["value"]
assert accepted["status"] == "PENDING"
assert retried["status"] == "POSTED"
assert accepted["id"] == retried["id"]
assert accepted["createdAt"] == retried["createdAt"]
assert accepted_response["headers"]["Location"]["example"] == f"/api/v1/movements/{accepted['id']}"

for name, routes in {
    "accounts": ["/api/v2/accounts/{accountId}/balances", "/api/v2/accounts/{accountId}/statement-jobs"],
    "payments": ["/api/v1/operations/payments/{id}/resolutions"],
}.items():
    document = yaml.safe_load((ROOT / f"openapi/{name}.yaml").read_text())
    validate(document)
    for path in routes:
        for method in ("post", "get"):
            operation = document["paths"][path].get(method)
            if operation:
                check_content(operation.get("requestBody", {}), document)
                for response in operation["responses"].values():
                    check_content(response, document)

events = ROOT / "events"
schema = json.loads((events / "movement-lifecycle-v1.schema.json").read_text())
example = json.loads((events / "movement-lifecycle-v1.example.json").read_text())
Draft202012Validator.check_schema(schema)
Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER).validate(example)
assert example["movementId"] == retried["id"]
assert example["status"] == retried["status"]
assert example["occurredAt"] == retried["updatedAt"]
print("Financial OpenAPI and movement event examples match their schemas")
