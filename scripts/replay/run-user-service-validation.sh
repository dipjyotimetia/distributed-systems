#!/usr/bin/env bash
# Thin wrapper: user-service offline WireMock + Postgres replay.
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/replay-service.sh" user-service "$@"
