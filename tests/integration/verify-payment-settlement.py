"""Match a browser payment to its independent fixed-width clearing record."""

import json
import sys


def main(evidence_path):
    with open(evidence_path, encoding="utf-8") as stream:
        payment = json.load(stream)
    lines = sys.stdin.buffer.read().decode("ascii").splitlines()
    assert lines[0].startswith("01H") and lines[0].endswith("AUD")
    assert lines[0][3:11] == payment["settlementBusinessDate"].replace("-", "")
    assert lines[-1].startswith("01T")
    records = lines[1:-1]
    assert int(lines[-1][3:9]) == len(records)
    assert int(lines[-1][9:]) == sum(int(row[42:54]) for row in records)
    rows = [row for row in records if row[3:39] == payment["paymentId"]]
    assert len(rows) == 1
    row = rows[0]
    assert len(row) == 66 and row.startswith("01S")
    assert row[39:42] == "AUD" and int(row[42:54]) == payment["amountMinor"]
    assert row[54:] == payment["clearingReference"]


if __name__ == "__main__":
    main(sys.argv[1])
