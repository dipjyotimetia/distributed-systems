"""JWT and internal-token auth helpers."""

from __future__ import annotations

import os
from typing import Annotated

import jwt
from fastapi import Depends, Header, HTTPException, status


JWT_SECRET = os.environ.get(
    "JWT_SECRET",
    "banking-app-super-secret-key-change-this-in-production-256-bit",
)
INTERNAL_TOKEN = os.environ.get("KNOWLEDGE_INTERNAL_TOKEN", "local-knowledge-internal")


class Principal:
    def __init__(self, user_id: int | None, internal: bool = False):
        self.user_id = user_id
        self.internal = internal


def _parse_bearer(authorization: str | None) -> int:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    token = authorization.split(" ", 1)[1].strip()
    try:
        claims = jwt.decode(
            token,
            JWT_SECRET,
            algorithms=["HS256"],
            options={"require": ["exp"]},
        )
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token") from exc
    user_id = claims.get("userId")
    if user_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token missing userId")
    return int(user_id)


def require_user(
    authorization: Annotated[str | None, Header()] = None,
) -> Principal:
    return Principal(user_id=_parse_bearer(authorization), internal=False)


def require_user_or_internal(
    authorization: Annotated[str | None, Header()] = None,
    x_internal_token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
) -> Principal:
    if x_internal_token and x_internal_token == INTERNAL_TOKEN:
        return Principal(user_id=None, internal=True)
    return Principal(user_id=_parse_bearer(authorization), internal=False)


UserPrincipal = Annotated[Principal, Depends(require_user)]
UserOrInternal = Annotated[Principal, Depends(require_user_or_internal)]
