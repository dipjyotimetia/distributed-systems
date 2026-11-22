"""Local blob path safety."""

from __future__ import annotations

import pytest
import storage


def test_local_put_rejects_traversal(tmp_path, monkeypatch):
    monkeypatch.setenv("STORAGE_BACKEND", "local")
    monkeypatch.setenv("LOCAL_BLOB_ROOT", str(tmp_path))
    storage.ensure_ready()
    with pytest.raises(ValueError):
        storage.put_bytes("../escape.txt", b"nope", "text/plain")
    assert list(tmp_path.iterdir()) == []
