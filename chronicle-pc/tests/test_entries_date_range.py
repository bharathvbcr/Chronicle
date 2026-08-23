"""GET /entries from/to date filters (entry_day + cfg timezone)."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline.serve import create_app


def _client(chronicle_dir: Path) -> TestClient:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    return TestClient(app)


def test_entries_from_to_inclusive(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)

    # Fixtures: 2026-07-08 (1) and 2026-07-09 (3) in Asia/Kolkata wall dates
    only_8 = client.get("/entries", params={"from": "2026-07-08", "to": "2026-07-08", "limit": 100})
    assert only_8.status_code == 200
    body = only_8.json()
    assert body["total"] == 1
    assert all(e["id"].startswith("2026-07-08") for e in body["entries"])

    only_9 = client.get("/entries", params={"from": "2026-07-09", "to": "2026-07-09", "limit": 100})
    assert only_9.status_code == 200
    assert only_9.json()["total"] == 3

    both = client.get("/entries", params={"from": "2026-07-08", "to": "2026-07-09", "limit": 100})
    assert both.status_code == 200
    assert both.json()["total"] == 4


def test_entries_from_to_before_limit(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.get(
        "/entries",
        params={"from": "2026-07-09", "to": "2026-07-09", "limit": 2, "offset": 0},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 3
    assert len(body["entries"]) == 2


def test_entries_from_to_validation(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    bad = client.get("/entries", params={"from": "07-09-2026"})
    assert bad.status_code == 400
    order = client.get("/entries", params={"from": "2026-07-10", "to": "2026-07-08"})
    assert order.status_code == 400
