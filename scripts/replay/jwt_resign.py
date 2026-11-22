#!/usr/bin/env python3
"""Re-sign REDACTED JWTs in a request suite Authorization header."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import time


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_json(obj) -> str:
    return _b64url(json.dumps(obj, separators=(",", ":"), sort_keys=False).encode())


def resign(token: str, key: str) -> str:
    parts = token.split(".")
    if len(parts) != 3:
        return token
    header_b64, payload_b64, sig = parts
    # Only rewrite suite placeholders. Leave intentionally invalid tokens alone
    # (e.g. "invalid.expired.token") so 401 cases stay negative tests.
    if sig != "REDACTED":
        return token
    try:
        pad = "=" * (-len(payload_b64) % 4)
        payload = json.loads(base64.urlsafe_b64decode(payload_b64 + pad))
    except Exception:
        return token
    now = int(time.time())
    payload["iat"] = now
    payload["exp"] = now + 3600
    signing_input = f"{header_b64}.{_b64url_json(payload)}".encode()
    new_sig = hmac.new(key.encode(), signing_input, hashlib.sha256).digest()
    return f"{header_b64}.{_b64url_json(payload)}.{_b64url(new_sig)}"



def main():
    if len(sys.argv) != 3:
        print("usage: jwt_resign.py <requests.json> <out.json>", file=sys.stderr)
        return 2
    key = os.environ.get("REPLAY_JWT_SECRET")
    if not key:
        print("error: REPLAY_JWT_SECRET is required", file=sys.stderr)
        return 1
    suite = json.loads(open(sys.argv[1], encoding="utf-8").read())
    for req in suite:
        headers = req.get("headers") or {}
        auth = headers.get("Authorization")
        if not auth:
            continue
        if auth.lower().startswith("bearer "):
            token = auth.split(" ", 1)[1]
            headers["Authorization"] = f"Bearer {resign(token, key)}"
        req["headers"] = headers
    open(sys.argv[2], "w", encoding="utf-8").write(json.dumps(suite, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
