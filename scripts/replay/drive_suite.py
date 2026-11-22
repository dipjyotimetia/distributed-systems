#!/usr/bin/env python3
"""Drive an HTTP request suite against a running service; write observed.json.

Supports optional response capture for URL templating:
  "capture": {"accountId": "$.id"}
  "url": "/api/v1/accounts/${accountId}/balance"
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

_CAPTURE_RE = re.compile(r"\$\{([^}]+)\}")


def _json_path(value, path: str):
    if not path.startswith("$"):
        raise ValueError(f"capture path must start with $: {path}")
    cur = value
    for part in path[1:].split("."):
        if part == "":
            continue
        if part.startswith("[") and part.endswith("]"):
            cur = cur[int(part[1:-1])]
            continue
        if not isinstance(cur, dict) or part not in cur:
            raise KeyError(f"path {path} not found (at {part})")
        cur = cur[part]
    return cur


def _resolve(template: str, captures: dict) -> str:
    def repl(match):
        key = match.group(1)
        if key not in captures:
            raise KeyError(f"capture ${{{key}}} not set")
        return str(captures[key])

    return _CAPTURE_RE.sub(repl, template)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", required=True)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument(
        "--ignore-body-status",
        action="store_true",
        help="only assert status codes match expectedStatus",
    )
    args = ap.parse_args()

    suite = json.loads(open(args.suite, encoding="utf-8").read())
    base = args.base_url.rstrip("/")
    observed = []
    failures = 0
    captures: dict = {}

    for req in suite:
        try:
            url_path = _resolve(req["url"], captures)
        except KeyError as e:
            print(f"FAIL {req['method']} {req['url']}: {e}", file=sys.stderr)
            failures += 1
            observed.append(
                {
                    "id": req.get("id"),
                    "method": req["method"],
                    "url": req["url"],
                    "status": 0,
                    "body": None,
                    "error": str(e),
                }
            )
            continue

        url = base + url_path
        data = None
        headers = dict(req.get("headers") or {})
        body = req.get("body")
        if body is not None:
            if isinstance(body, (dict, list)):
                data = json.dumps(body).encode()
                headers.setdefault("Content-Type", "application/json")
            else:
                data = str(body).encode()
        request = urllib.request.Request(url, data=data, headers=headers, method=req["method"])
        status = 0
        resp_body = None
        raw = b""
        try:
            with urllib.request.urlopen(request, timeout=30) as resp:
                status = resp.getcode()
                raw = resp.read()
        except urllib.error.HTTPError as e:
            status = e.code
            raw = e.read() if e.fp else b""
        except Exception as e:
            print(f"FAIL {req['method']} {url_path}: transport error {e}", file=sys.stderr)
            failures += 1
            observed.append(
                {
                    "id": req.get("id"),
                    "method": req["method"],
                    "url": url_path,
                    "status": 0,
                    "body": None,
                    "error": str(e),
                }
            )
            continue

        try:
            resp_body = json.loads(raw.decode("utf-8")) if raw else None
        except Exception:
            resp_body = raw.decode("utf-8", "replace") if raw else None

        expected = str(req.get("expectedStatus", ""))
        ok = expected == "" or str(status) == expected
        if not ok:
            print(f"FAIL {req['method']} {url_path}: status {status} expected {expected}", file=sys.stderr)
            failures += 1
        else:
            print(f"OK   {req['method']} {url_path} -> {status}")

        for name, path in (req.get("capture") or {}).items():
            try:
                captures[name] = _json_path(resp_body, path)
            except Exception as e:
                print(f"FAIL {req['method']} {url_path}: capture {name}={path}: {e}", file=sys.stderr)
                failures += 1

        observed.append(
            {
                "id": req.get("id"),
                "method": req["method"],
                "url": url_path,
                "status": status,
                "body": resp_body,
            }
        )

    open(args.out, "w", encoding="utf-8").write(json.dumps(observed, indent=2) + "\n")
    print(f"wrote {args.out} ({len(observed)} responses, {failures} status failure(s))")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
