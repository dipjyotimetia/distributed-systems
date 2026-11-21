import os
from pathlib import Path

from temporal import claim_check


def test_claim_check_round_trip(tmp_path, monkeypatch):
    monkeypatch.setenv("CLAIM_CHECK_DIR", str(tmp_path))
    monkeypatch.setattr(claim_check, "CLAIM_CHECK_DIR", Path(tmp_path))

    key = claim_check.put_sync({"messages": [{"role": "user", "content": "x" * 100}]})
    loaded = claim_check.get_sync(key)
    assert loaded["messages"][0]["role"] == "user"
    assert (tmp_path / f"{key}.json").exists()


def test_should_offload_threshold(monkeypatch):
    monkeypatch.setattr(claim_check, "CLAIM_CHECK_THRESHOLD", 32)
    assert claim_check.should_offload({"blob": "y" * 64})
    assert not claim_check.should_offload({"blob": "ok"})
