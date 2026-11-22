#!/usr/bin/env python3
"""Mint an HS256 JWT for offline replay suites."""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import time


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def mint(secret: str, sub: str, user_id: str, roles: str = "USER", ttl: int = 3600) -> str:
    header = _b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    now = int(time.time())
    payload = _b64url(
        json.dumps(
            {"sub": sub, "userId": user_id, "roles": roles, "iat": now, "exp": now + ttl},
            separators=(",", ":"),
        ).encode()
    )
    sig = hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
    return f"{header}.{payload}.{_b64url(sig)}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sub", default="replay.user")
    ap.add_argument("--user-id", default="1")
    ap.add_argument("--roles", default="USER")
    args = ap.parse_args()
    secret = os.environ.get("REPLAY_JWT_SECRET")
    if not secret:
        raise SystemExit("REPLAY_JWT_SECRET is required")
    print(mint(secret, args.sub, args.user_id, args.roles))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
