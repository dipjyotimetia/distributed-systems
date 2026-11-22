#!/usr/bin/env python3
"""Drive an HTTP request suite against a running service; write observed.json."""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", required=True)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--ignore-body-status", action="store_true",
                    help="only assert status codes match expectedStatus")
    args = ap.parse_args()

    suite = json.loads(open(args.suite, encoding="utf-8").read())
    base = args.base_url.rstrip("/")
    observed = []
    failures = 0
    for req in suite:
        url = base + req["url"]
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
            print(f"FAIL {req['method']} {req['url']}: transport error {e}", file=sys.stderr)
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

        try:
            resp_body = json.loads(raw.decode("utf-8")) if raw else None
        except Exception:
            resp_body = raw.decode("utf-8", "replace") if raw else None

        expected = str(req.get("expectedStatus", ""))
        ok = expected == "" or str(status) == expected
        if not ok:
            print(f"FAIL {req['method']} {req['url']}: status {status} expected {expected}", file=sys.stderr)
            failures += 1
        else:
            print(f"OK   {req['method']} {req['url']} -> {status}")

        observed.append(
            {
                "id": req.get("id"),
                "method": req["method"],
                "url": req["url"],
                "status": status,
                "body": resp_body,
            }
        )

    open(args.out, "w", encoding="utf-8").write(json.dumps(observed, indent=2) + "\n")
    print(f"wrote {args.out} ({len(observed)} responses, {failures} status failure(s))")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
