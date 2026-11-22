#!/usr/bin/env python3
"""Inject minted JWT into suite requests that use Authorization: Bearer __REPLAY_JWT__."""
from __future__ import annotations

import json
import os
import sys

from mint_jwt import mint


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: prepare_suite.py <requests.json> <out.json>", file=sys.stderr)
        return 2
    secret = os.environ.get("REPLAY_JWT_SECRET")
    if not secret:
        print("error: REPLAY_JWT_SECRET required", file=sys.stderr)
        return 1
    token = mint(
        secret,
        os.environ.get("REPLAY_JWT_SUB", "replay.user"),
        os.environ.get("REPLAY_JWT_USER_ID", "1"),
        os.environ.get("REPLAY_JWT_ROLES", "USER"),
    )
    suite = json.loads(open(sys.argv[1], encoding="utf-8").read())
    for req in suite:
        headers = req.get("headers") or {}
        auth = headers.get("Authorization")
        if auth == "Bearer __REPLAY_JWT__":
            headers["Authorization"] = f"Bearer {token}"
            req["headers"] = headers
    open(sys.argv[2], "w", encoding="utf-8").write(json.dumps(suite, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
