#!/usr/bin/env python3
"""Property-test observed responses against declarative business invariants."""
from __future__ import annotations

import argparse
import json
import re
import sys

from schema_validate import _type_tag, url_pattern


def _match(rule_match, method, url, status):
    if "method" in rule_match and rule_match["method"] != method:
        return False
    if "status" in rule_match and str(rule_match["status"]) != str(status):
        return False
    if "url" in rule_match and url_pattern(rule_match["url"]) != url_pattern(url):
        return False
    return True


def _resolve(value, field):
    body = field[1:] if field.startswith("$") else field
    tokens = []
    for part in body.split("."):
        if not part:
            continue
        while part.endswith("[]"):
            part = part[:-2]
            if part:
                tokens.append(part)
                part = ""
            tokens.append("[]")
        if part:
            tokens.append(part)

    def walk(node, idx):
        if idx == len(tokens):
            return [(True, node)]
        tok = tokens[idx]
        if tok == "[]":
            if not isinstance(node, list):
                return [(False, None)]
            out = []
            for item in node:
                out.extend(walk(item, idx + 1))
            return out
        if isinstance(node, dict):
            if tok not in node:
                return [(False, None)]
            return walk(node[tok], idx + 1)
        return [(False, None)]

    return walk(value, 0)


def _check(assert_rule, present, value, path, errors):
    if assert_rule.get("required") and not present:
        errors.append(f"{path}: required field missing")
        return
    if not present:
        return
    expected_type = assert_rule.get("type")
    if expected_type and _type_tag(value) != expected_type:
        errors.append(f"{path}: expected type {expected_type}, got {_type_tag(value)}")
        return
    if "min" in assert_rule and isinstance(value, (int, float)) and value < assert_rule["min"]:
        errors.append(f"{path}: {value} < min {assert_rule['min']}")
    if "max" in assert_rule and isinstance(value, (int, float)) and value > assert_rule["max"]:
        errors.append(f"{path}: {value} > max {assert_rule['max']}")
    if "enum" in assert_rule and value not in assert_rule["enum"]:
        errors.append(f"{path}: {value!r} not in {assert_rule['enum']}")
    if "pattern" in assert_rule and isinstance(value, str):
        if not re.fullmatch(assert_rule["pattern"], value):
            errors.append(f"{path}: {value!r} does not match {assert_rule['pattern']}")
    if assert_rule.get("non_empty"):
        if value in ("", [], {}, None):
            errors.append(f"{path}: expected non-empty")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules", required=True)
    ap.add_argument("--observed")
    ap.add_argument("--lint", action="store_true")
    args = ap.parse_args()

    rules = json.loads(open(args.rules, encoding="utf-8").read())
    if args.lint:
        assert isinstance(rules, list)
        print(f"ok: {len(rules)} rules")
        return

    if not args.observed:
        print("error: --observed is required unless --lint", file=sys.stderr)
        return 2

    observed = json.loads(open(args.observed, encoding="utf-8").read())
    errors = []
    matched = 0
    for item in observed:
        body = item.get("body")
        if body is None:
            continue
        for rule in rules:
            if rule.get("match") and not _match(rule["match"], item["method"], item["url"], str(item["status"])):
                continue
            matched += 1
            for assertion in rule.get("assert", []):
                field = assertion["field"]
                for present, value in _resolve(body, field):
                    _check(assertion, present, value, f"{item['method']} {item['url']} {field}", errors)

    print(f"property validation: {matched} rule match(es), {len(errors)} error(s)")
    for err in errors:
        print(f"  FAIL {err}")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
