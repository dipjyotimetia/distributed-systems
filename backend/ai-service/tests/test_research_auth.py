import os

from fastapi.testclient import TestClient

from main import app


def test_dispute_research_requires_internal_token(monkeypatch):
    monkeypatch.setenv("DISPUTES_INTERNAL_TOKEN", "secret-token")
    client = TestClient(app, raise_server_exceptions=False)

    missing = client.post("/api/v1/disputes/research", json={"caseId": 1})
    assert missing.status_code == 401

    wrong = client.post(
        "/api/v1/disputes/research",
        json={"caseId": 1},
        headers={"X-Internal-Token": "wrong"},
    )
    assert wrong.status_code == 401


def test_dispute_research_rejects_when_token_unset(monkeypatch):
    monkeypatch.delenv("DISPUTES_INTERNAL_TOKEN", raising=False)
    client = TestClient(app, raise_server_exceptions=False)
    response = client.post(
        "/api/v1/disputes/research",
        json={"caseId": 1},
        headers={"X-Internal-Token": "anything"},
    )
    assert response.status_code == 401
