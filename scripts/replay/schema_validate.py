#!/usr/bin/env python3
"""Schema-validate observed replay responses against a request suite's expected bodies."""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

_NUM_SEG = re.compile(r"^\d+$")


def url_pattern(url: str) -> str:
    path = url.split("?", 1)[0]
    segs = [("{id}" if _NUM_SEG.match(s) else s) for s in path.split("/")]
    return "/".join(segs)


def endpoint_key(method, url, status):
    return f"{method} {url_pattern(url)} [{status}]"


def _type_tag(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "number"
    if isinstance(v, str):
        return "string"
    if isinstance(v, list):
        return "array"
    if isinstance(v, dict):
        return "object"
    return "unknown"


def infer(value):
    tag = _type_tag(value)
    node = {"types": {tag}}
    if tag == "object":
        node["props"] = {k: infer(v) for k, v in value.items()}
    elif tag == "array":
        elem = None
        for item in value:
            elem = merge(elem, infer(item)) if elem is not None else infer(item)
        node["elem"] = elem
    return node


def merge(a, b):
    if a is None:
        return b
    if b is None:
        return a
    out = {"types": a["types"] | b["types"]}
    if "object" in out["types"]:
        pa, pb = a.get("props", {}), b.get("props", {})
        props = {}
        for k in set(pa) | set(pb):
            if k in pa and k in pb:
                props[k] = merge(pa[k], pb[k])
                props[k]["_required"] = pa[k].get("_required", True) and pb[k].get("_required", True)
            else:
                props[k] = dict(pa.get(k) or pb.get(k))
                props[k]["_required"] = False
        out["props"] = props
    if "array" in out["types"]:
        out["elem"] = merge(a.get("elem"), b.get("elem"))
    return out


def _compatible(expected_types, observed_tag):
    if expected_types == {"null"}:
        return True
    return observed_tag in expected_types


def validate(node, value, path, errors):
    tag = _type_tag(value)
    if not _compatible(node["types"], tag):
        errors.append(f"{path}: expected {'|'.join(sorted(node['types']))}, got {tag}")
        return
    if tag == "object" and "object" in node["types"] and node.get("props"):
        for k, child in node["props"].items():
            if k not in value:
                if child.get("_required", True):
                    errors.append(f"{path}.{k}: required field missing")
                continue
            validate(child, value[k], f"{path}.{k}", errors)
    if tag == "array" and "array" in node["types"] and node.get("elem"):
        for i, item in enumerate(value):
            validate(node["elem"], item, f"{path}[{i}]", errors)


def schema_field_paths(node, prefix="$"):
    paths = set()
    if "object" in node["types"] and node.get("props"):
        for k, child in node["props"].items():
            cp = f"{prefix}.{k}"
            if child.get("_required", True):
                paths.add(cp)
            paths |= schema_field_paths(child, cp)
    if "array" in node["types"] and node.get("elem"):
        paths |= schema_field_paths(node["elem"], f"{prefix}[]")
    return paths


def observed_field_paths(value, prefix="$"):
    paths = {prefix}
    if isinstance(value, dict):
        for k, v in value.items():
            paths |= observed_field_paths(v, f"{prefix}.{k}")
    elif isinstance(value, list):
        for item in value:
            paths |= observed_field_paths(item, f"{prefix}[]")
    return paths


def build_schemas(suite):
    schemas = {}
    for req in suite:
        body = req.get("expectedBody")
        if body is None:
            continue
        key = endpoint_key(req["method"], req["url"], req["expectedStatus"])
        schemas[key] = merge(schemas.get(key), infer(body))
    return schemas


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", required=True, help="requests.json suite with expectedBody fields")
    ap.add_argument("--observed", required=True, help="observed.json from drive_suite.py")
    ap.add_argument("--require-coverage", action="store_true")
    ap.add_argument("--strict-field-coverage", action="store_true")
    args = ap.parse_args()

    suite = json.loads(open(args.suite, encoding="utf-8").read())
    observed = json.loads(open(args.observed, encoding="utf-8").read())
    schemas = build_schemas(suite)
    if not schemas:
        print("schema validation: no expectedBody schemas in suite; skipping shape checks")
        # Still enforce status coverage from suite.
        golden_ops = {endpoint_key(r["method"], r["url"], r["expectedStatus"]) for r in suite}
    else:
        golden_ops = set(schemas)

    errors = []
    observed_ops = set()
    observed_fields = {}
    no_schema = 0
    for item in observed:
        key = endpoint_key(item["method"], item["url"], str(item["status"]))
        observed_ops.add(key)
        body = item.get("body")
        if key not in schemas:
            no_schema += 1
            continue
        if body is None:
            continue
        observed_fields.setdefault(key, set()).update(observed_field_paths(body))
        local = []
        validate(schemas[key], body, "$", local)
        for err in local:
            errors.append(f"{key}: {err}")

    print(f"schema validation: {len(errors)} error(s)")
    for err in errors:
        print(f"  FAIL {err}")
    if no_schema:
        print(f"  ({no_schema} observed response(s) had no suite schema and were skipped)")

    missing_ops = sorted(golden_ops - observed_ops)
    missing_fields = []
    for key in sorted(observed_ops & set(schemas)):
        want = schema_field_paths(schemas[key])
        got = observed_fields.get(key, set())
        for field in sorted(want - got):
            missing_fields.append(f"{key}: {field}")

    print(f"\ncontract coverage: {len(observed_ops & golden_ops)}/{len(golden_ops)} suite operation(s) exercised")
    if missing_ops:
        print("MISSING OPERATIONS:")
        for op in missing_ops:
            print(f"  - {op}")
    if missing_fields:
        print("MISSING FIELDS:")
        for f in missing_fields:
            print(f"  - {f}")

    failed = bool(errors)
    if args.require_coverage and missing_ops:
        failed = True
    if args.require_coverage and args.strict_field_coverage and missing_fields:
        failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
