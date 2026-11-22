#!/usr/bin/env python3
"""Compare pinned third-party GitHub Actions against each repo's latest release."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PIN = re.compile(r"uses:\s*((?!./)[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?)@([^\s#]+)")

# Map action path to the GitHub repo that publishes releases.
REPO_FOR_ACTION = {
    "gradle/actions/setup-gradle": "gradle/actions",
}


def latest_tag(repo: str) -> str:
    result = subprocess.run(
        ["gh", "api", f"repos/{repo}/releases/latest", "--jq", ".tag_name"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def main() -> int:
    pins: dict[str, set[str]] = {}
    for path in (ROOT / ".github").rglob("*.yml"):
        text = path.read_text()
        for match in PIN.finditer(text):
            action, version = match.group(1), match.group(2)
            pins.setdefault(action, set()).add(version)

    outdated = 0
    for action in sorted(pins):
        repo = REPO_FOR_ACTION.get(action, action)
        current_versions = sorted(pins[action])
        try:
            latest = latest_tag(repo)
        except subprocess.CalledProcessError as failure:
            print(f"UNKNOWN    {action:40} current={','.join(current_versions)} ({failure})")
            outdated += 1
            continue
        # Accept exact pin or major floating tag that matches latest major.
        ok = any(
            version == latest
            or (re.fullmatch(r"v\d+", version) and latest.startswith(version + "."))
            or (re.fullmatch(r"v\d+", version) and latest == version)
            for version in current_versions
        )
        if ok and len(current_versions) == 1:
            print(f"OK         {action:40} {current_versions[0]} (latest {latest})")
        elif ok:
            print(f"OK         {action:40} {','.join(current_versions)} (latest {latest})")
        else:
            print(f"OUTDATED   {action:40} current={','.join(current_versions):15} latest={latest}")
            outdated += 1
    return 1 if outdated else 0


if __name__ == "__main__":
    raise SystemExit(main())
