#!/usr/bin/env python3
"""Compare pinned third-party Docker image tags against Docker Hub (via curl)."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from packaging.version import InvalidVersion, Version

# repo, current tag, hub name filter, accept regex
CHECKS: list[tuple[str, str, str, str]] = [
    ("temporalio/temporal", "1.10.1", "1.10", r"^1\.\d+\.\d+$"),
    ("temporalio/auto-setup", "1.29.7", "1.29", r"^1\.\d+\.\d+$"),
    ("library/postgres", "19.0-alpine", "19.", r"^19\.\d+(\.\d+)?-alpine$"),
    ("library/mongo", "9.0.8", "9.0.", r"^9\.\d+\.\d+$"),
    ("library/redis", "8.10.3-alpine", "8.", r"^8\.\d+\.\d+-alpine$"),
    ("library/busybox", "1.38.0", "1.3", r"^1\.\d+\.\d+$"),
    ("library/alpine", "3.24.4", "3.2", r"^3\.\d+\.\d+$"),
    ("apache/activemq-artemis", "2.44.0-alpine", "2.4", r"^2\.\d+\.\d+-alpine$"),
    ("jaegertracing/jaeger", "2.23.0", "2.", r"^2\.\d+\.\d+$"),
    ("prom/prometheus", "v3.16.0", "v3.", r"^v3\.\d+\.\d+$"),
    ("grafana/grafana", "13.3.1", "13.", r"^13\.\d+\.\d+$"),
    ("grafana/loki", "3.7.10", "3.", r"^3\.\d+\.\d+$"),
    ("grafana/alloy", "v1.22.0", "v1.", r"^v1\.\d+\.\d+$"),
    ("otel/opentelemetry-collector-contrib", "0.165.0", "0.16", r"^0\.\d+\.\d+$"),
    ("curlimages/curl", "8.23.0", "8.", r"^8\.\d+\.\d+$"),
    ("library/golang", "1.27.4-alpine", "1.27", r"^1\.27\.\d+-alpine$"),
    ("library/node", "26.13.1-alpine", "26.", r"^26\.\d+\.\d+-alpine$"),
    ("library/python", "3.15.2-slim", "3.15", r"^3\.15\.\d+-slim$"),
    ("alpine/k8s", "1.37.2", "1.3", r"^1\.\d+\.\d+$"),
]


def hub_tags(repo: str, name_filter: str) -> list[str]:
    url = (
        f"https://hub.docker.com/v2/repositories/{repo}/tags"
        f"?page_size=100&name={name_filter}"
    )
    result = subprocess.run(
        ["curl", "-fsS", "--max-time", "25", url],
        check=True,
        capture_output=True,
        text=True,
    )
    return [item["name"] for item in json.loads(result.stdout).get("results", [])]


def version_key(tag: str) -> Version:
    base = tag.lstrip("v")
    for suffix in ("-alpine", "-slim"):
        if base.endswith(suffix):
            base = base[: -len(suffix)]
    try:
        return Version(base)
    except InvalidVersion:
        return Version("0")


def main() -> int:
    outdated = 0
    for repo, current, name_filter, pattern in CHECKS:
        try:
            matched = [tag for tag in hub_tags(repo, name_filter) if re.fullmatch(pattern, tag)]
        except subprocess.CalledProcessError as failure:
            print(f"UNKNOWN    {repo:45} current={current} ({failure})")
            outdated += 1
            continue
        latest = sorted(matched, key=version_key, reverse=True)[0] if matched else None
        if latest is None:
            print(f"UNKNOWN    {repo:45} current={current}")
            outdated += 1
        elif latest == current:
            print(f"OK         {repo:45} {current}")
        else:
            print(f"OUTDATED   {repo:45} current={current:20} latest={latest}")
            outdated += 1
    return 1 if outdated else 0


if __name__ == "__main__":
    raise SystemExit(main())
