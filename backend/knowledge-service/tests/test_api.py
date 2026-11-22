"""API tests against in-memory knowledge store (no Postgres/MinIO required)."""

from __future__ import annotations

import os
from datetime import UTC, datetime, timedelta

import jwt
import pytest
from fastapi.testclient import TestClient

os.environ["KNOWLEDGE_STORE"] = "memory"
os.environ["STORAGE_BACKEND"] = "local"
os.environ["LOCAL_BLOB_ROOT"] = "/tmp/knowledge-test-blobs"
os.environ["JWT_SECRET"] = (
    "banking-app-super-secret-key-change-this-in-production-256-bit"
)
os.environ["KNOWLEDGE_INTERNAL_TOKEN"] = "local-knowledge-internal"
os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://127.0.0.1:4318"

import store
from main import app


@pytest.fixture(autouse=True)
def _clean_store(tmp_path, monkeypatch):
    monkeypatch.setenv("LOCAL_BLOB_ROOT", str(tmp_path))
    store.reset_memory()
    yield
    store.reset_memory()


def _token(user_id: int = 42) -> str:
    return jwt.encode(
        {
            "userId": user_id,
            "exp": datetime.now(UTC) + timedelta(hours=1),
        },
        os.environ["JWT_SECRET"],
        algorithm="HS256",
    )


def test_health_and_seed_policies():
    with TestClient(app) as client:
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "UP"
        assert body["documents"] >= 3


def test_search_policy_corpus():
    with TestClient(app) as client:
        token = _token()
        resp = client.post(
            "/api/v1/knowledge/search",
            headers={"Authorization": f"Bearer {token}"},
            json={
                "query": "dispute timeframe 60 days",
                "docType": "POLICY",
                "limit": 3,
            },
        )
        assert resp.status_code == 200
        hits = resp.json()["hits"]
        assert hits
        assert any(
            "60" in hit["excerpt"] or "Timeframe" in hit["title"] for hit in hits
        )


def test_upload_and_excerpt_owner_document():
    with TestClient(app) as client:
        token = _token(7)
        files = {
            "file": (
                "receipt.txt",
                b"Merchant XYZ unauthorized charge receipt",
                "text/plain",
            )
        }
        data = {
            "title": "Dispute receipt",
            "docType": "DISPUTE_EVIDENCE",
            "caseId": "101",
            "visibility": "OWNER",
        }
        created = client.post(
            "/api/v1/knowledge/documents",
            headers={"Authorization": f"Bearer {token}"},
            files=files,
            data=data,
        )
        assert created.status_code == 201, created.text
        doc_id = created.json()["id"]

        excerpt = client.get(
            f"/api/v1/knowledge/documents/{doc_id}/excerpt",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert excerpt.status_code == 200
        assert "unauthorized" in excerpt.json()["excerpt"].lower()

        other = client.get(
            f"/api/v1/knowledge/documents/{doc_id}/excerpt",
            headers={"Authorization": f"Bearer {_token(99)}"},
        )
        assert other.status_code == 404


def test_policy_upload_requires_internal_token():
    with TestClient(app) as client:
        files = {"file": ("policy.md", b"# secret", "text/markdown")}
        data = {"title": "Secret", "docType": "POLICY", "visibility": "PUBLIC_BANK"}
        denied = client.post(
            "/api/v1/knowledge/documents",
            headers={"Authorization": f"Bearer {_token()}"},
            files=files,
            data=data,
        )
        assert denied.status_code == 403


def test_customer_cannot_upload_bank_visibility():
    with TestClient(app) as client:
        files = {"file": ("receipt.txt", b"secret evidence", "text/plain")}
        data = {
            "title": "Should stay private",
            "docType": "DISPUTE_EVIDENCE",
            "visibility": "PUBLIC_BANK",
        }
        denied = client.post(
            "/api/v1/knowledge/documents",
            headers={"Authorization": f"Bearer {_token(7)}"},
            files=files,
            data=data,
        )
        assert denied.status_code == 403


def test_upload_sanitizes_path_traversal_filename(tmp_path, monkeypatch):
    monkeypatch.setenv("LOCAL_BLOB_ROOT", str(tmp_path))
    with TestClient(app) as client:
        files = {"file": ("../../etc/passwd", b"not a passwd", "text/plain")}
        data = {"title": "Traversal", "docType": "OTHER", "visibility": "OWNER"}
        created = client.post(
            "/api/v1/knowledge/documents",
            headers={"Authorization": f"Bearer {_token(3)}"},
            files=files,
            data=data,
        )
        assert created.status_code == 201, created.text
        key = None
        for row in store.list_documents(
            user_id=3,
            internal=False,
            doc_type=None,
            case_id=None,
            account_id=None,
            limit=10,
        ):
            if row["title"] == "Traversal":
                key = row["storage_key"]
                break
        assert key is not None
        assert ".." not in key
        assert (
            key.endswith("/passwd") or key.endswith("/upload.bin") or "/passwd" in key
        )
        # Blob must stay under the configured root.
        blob_path = (tmp_path / key).resolve()
        assert blob_path.is_relative_to(tmp_path.resolve())
        assert blob_path.exists()


def test_download_content_disposition_is_sanitized():
    with TestClient(app) as client:
        token = _token(11)
        files = {"file": ("ok.txt", b"hello", "text/plain")}
        data = {
            "title": 'evil\r\nX-Injected: yes"',
            "docType": "OTHER",
            "visibility": "OWNER",
        }
        created = client.post(
            "/api/v1/knowledge/documents",
            headers={"Authorization": f"Bearer {token}"},
            files=files,
            data=data,
        )
        assert created.status_code == 201
        doc_id = created.json()["id"]
        download = client.get(
            f"/api/v1/knowledge/documents/{doc_id}/content",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert download.status_code == 200
        disposition = download.headers.get("content-disposition", "")
        # Header injection via CR/LF must be impossible; tokens may remain in the
        # sanitized basename but cannot split the header into a second field.
        assert "\r" not in disposition and "\n" not in disposition
        assert disposition.count("filename=") >= 1
        assert "filename*=UTF-8''" in disposition


def test_seed_restores_missing_policy_blob(tmp_path, monkeypatch):
    monkeypatch.setenv("LOCAL_BLOB_ROOT", str(tmp_path))
    import seed

    store.reset_memory()
    assert seed.seed_policies() >= 3
    row = store.find_policy_by_title("Dispute Timeframe")
    assert row is not None
    blob = tmp_path / row["storage_key"]
    assert blob.exists()
    blob.unlink()
    assert not blob.exists()
    # Second seed should restore the blob without creating a duplicate row.
    assert seed.seed_policies() == 0
    assert blob.exists()
    assert store.count_documents() >= 3
