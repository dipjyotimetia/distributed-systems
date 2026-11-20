"""Match the browser payment to its settled Kafka lifecycle event."""

import json
import sys
import uuid


def main(evidence_path):
    with open(evidence_path, encoding="utf-8") as stream:
        payment = json.load(stream)
    matched = []
    for line in sys.stdin:
        key, body = line.rstrip("\n").split("\t", maxsplit=1)
        event = json.loads(body)
        if event["paymentId"] == payment["paymentId"] and event["status"] == "SETTLED":
            assert key == payment["paymentId"]
            matched.append(event)
    assert len(matched) == 1
    event = matched[0]
    assert str(uuid.UUID(event["eventId"])) == event["eventId"]
    assert event["version"] == 1 and event["currency"] == "AUD"
    assert event["amountMinor"] == payment["amountMinor"]
    assert event["clearingReference"] == payment["clearingReference"]
    assert event["settlementBusinessDate"] == payment["settlementBusinessDate"]


if __name__ == "__main__":
    main(sys.argv[1])
