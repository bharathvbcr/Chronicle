"""LAN outbox mirror endpoint: idempotent push of phone captures."""

from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline.serve import TOKEN_HEADER, create_app

HDR = {TOKEN_HEADER: "tok"}


def _client(chronicle_dir: Path) -> TestClient:
    return TestClient(
        create_app(
            chronicle_dir,
            connect_info={
                "base": "http://192.168.1.10:8765",
                "token": "tok",
                "auth_required": True,
                "tls": False,
            },
        )
    )


def _entry_json(entry_id: str, text: str, *, encrypted: bool = False) -> dict:
    body = {
        "version": 1,
        "id": entry_id,
        "ts": "2026-08-05T09:00:00+05:30",
        "type": "log",
        "text": "" if encrypted else text,
        "tags": [],
        "images": [],
        "processed": False,
    }
    if encrypted:
        body["text_enc"] = {
            "v": 1,
            "nonce": "AAAA",
            "ct": "BBBB",
        }
    return {"entry": body}


def test_mirror_creates_dedupes_and_conflicts(tmp_path: Path) -> None:
    client = _client(tmp_path)
    h = {TOKEN_HEADER: "tok"}
    payload = _entry_json("2026-08-05_090000-an", "first capture")

    r1 = client.post("/entries/mirror", json=payload, headers=h)
    assert r1.status_code == 200
    assert r1.json() == {"ok": True, "id": "2026-08-05_090000-an", "deduped": False}

    # Identical re-push → deduped (idempotency for outbox retries)
    r2 = client.post("/entries/mirror", json=payload, headers=h)
    assert r2.status_code == 200 and r2.json()["deduped"] is True

    # Same id, different content → conflict; disk untouched
    payload["entry"]["text"] = "edited elsewhere"
    r3 = client.post("/entries/mirror", json=payload, headers=h)
    assert r3.status_code == 409


def test_mirror_preserves_e2ee_blob_verbatim(tmp_path: Path) -> None:
    client = _client(tmp_path)
    h = {TOKEN_HEADER: "tok"}
    payload = _entry_json("2026-08-05_090001-an", "x", encrypted=True)

    assert client.post("/entries/mirror", json=payload, headers=h).status_code == 200
    written = json.loads(
        (tmp_path / "_capture" / "entries" / "2026" / "08" / "2026-08-05_090001-an.json").read_text()
    )
    assert written["text"] == ""
    assert written["text_enc"] == {"v": 1, "nonce": "AAAA", "ct": "BBBB"}

    # Retry with identical blob dedupes even while the vault is locked.
    again = client.post("/entries/mirror", json=payload, headers=h)
    assert again.json()["deduped"] is True


def test_mirror_requires_token(tmp_path: Path) -> None:
    client = _client(tmp_path)
    assert client.post("/entries/mirror", json=_entry_json("2026-08-05_090000-an", "x")).status_code == 401


def test_mirror_rejects_pc_ids_and_bad_payloads(tmp_path: Path) -> None:
    client = _client(tmp_path)
    h = {TOKEN_HEADER: "tok"}
    pc = _entry_json("2026-08-05_090000-pc", "x")
    assert client.post("/entries/mirror", json=pc, headers=h).status_code == 400
    missing_ts = _entry_json("2026-08-05_090000-an", "x")
    del missing_ts["entry"]["ts"]
    assert client.post("/entries/mirror", json=missing_ts, headers=h).status_code == 400


def test_mirror_dedups_when_client_omits_empty_lists(chronicle_dir: Path) -> None:
    """Absent images/tags/audio must equal [] — omitting them must not turn
    an identical re-push into a spurious 409 'diverged content'."""
    client = _client(chronicle_dir)
    entry = {
        "version": 1,
        "id": "2026-08-22_111111-an",
        "ts": "2026-08-22T11:11:11+05:30",
        "type": "log",
        "text": "no media at all",
        "processed": False,
        # no images/tags/audio keys at all
    }
    first = client.post("/entries/mirror", json={"entry": entry}, headers=HDR)
    assert first.status_code == 200 and first.json()["deduped"] is False

    second = client.post("/entries/mirror", json={"entry": entry}, headers=HDR)
    assert second.status_code == 200
    assert second.json() == {"ok": True, "id": entry["id"], "deduped": True}


def test_mirror_dedups_when_client_omits_processed(tmp_path: Path) -> None:
    """Absent and false are the same content for idempotency (like tags/images)."""
    client = _client(tmp_path)
    h = {TOKEN_HEADER: "tok"}
    payload = _entry_json("2026-08-05_090100-an", "no processed field")
    del payload["entry"]["processed"]

    r1 = client.post("/entries/mirror", json=payload, headers=h)
    assert r1.status_code == 200 and r1.json()["deduped"] is False

    r2 = client.post("/entries/mirror", json=payload, headers=h)
    assert r2.status_code == 200 and r2.json()["deduped"] is True
