"""KB note optimistic concurrency: content_hash + base_hash 409."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline.paths import content_hash
from chronicle_pipeline.serve import create_app


def _client(chronicle_dir: Path) -> TestClient:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    return TestClient(app)


def test_kb_get_includes_content_hash(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    created = client.post(
        "/kb/notes/00-Inbox/hash-me.md",
        json={"content": "# Hello\nworld\n"},
    )
    assert created.status_code == 201
    body = created.json()
    assert body["content_hash"] == content_hash(body["content"])

    got = client.get("/kb/notes/00-Inbox/hash-me.md")
    assert got.status_code == 200
    assert got.json()["content_hash"] == body["content_hash"]


def test_kb_put_create_ignores_base_hash(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.put(
        "/kb/notes/00-Inbox/brand-new.md",
        json={"content": "# New\n"},
    )
    assert r.status_code == 200
    assert r.json()["path"] == "00-Inbox/brand-new.md"
    assert r.json()["content_hash"]


def test_kb_put_overwrite_requires_matching_base_hash(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    client.post(
        "/kb/notes/00-Inbox/gate.md",
        json={"content": "# v1\n"},
    )
    got = client.get("/kb/notes/00-Inbox/gate.md")
    base_hash = got.json()["content_hash"]

    missing = client.put(
        "/kb/notes/00-Inbox/gate.md",
        json={"content": "# v2\n"},
    )
    assert missing.status_code == 400

    stale = client.put(
        "/kb/notes/00-Inbox/gate.md",
        json={"content": "# v2\n", "base_hash": "a" * 64},
    )
    assert stale.status_code == 409
    detail = stale.json()["detail"]
    assert detail["on_disk_hash"] == base_hash

    ok = client.put(
        "/kb/notes/00-Inbox/gate.md",
        json={"content": "# v2\n", "base_hash": base_hash},
    )
    assert ok.status_code == 200
    assert "# v2" in ok.json()["content"]
    assert ok.json()["content_hash"] != base_hash
