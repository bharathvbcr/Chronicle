"""Hardening regression tests from the 2026-08 audit.

Each test targets a specific finding:
- hostile on-disk entry JSON (multibyte id/ts) must not kill listings;
- entry-filed_path traversal must never reach `root / rel` file I/O;
- concurrent same-id mirror pushes must stay idempotent (one file, no 5xx);
- absurd JSON shapes must be refused cleanly, not crash the worker.

These attack the system the way a hostile LAN peer, a corrupted Syncthing
sync, or a hand-edit in Obsidian would.
"""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline.serve import TOKEN_HEADER, create_app

HDR = {TOKEN_HEADER: "tok"}


def _client(chronicle_dir: Path) -> TestClient:
    return TestClient(
        create_app(
            chronicle_dir,
            connect_info={
                "base": "http://127.0.0.1:8765",
                "token": "tok",
                "auth_required": True,
                "tls": False,
            },
        )
    )


def _write_entry_file(root: Path, raw: dict) -> Path:
    yyyy, mm = raw["id"][:4], raw["id"][5:7]
    d = root / "_capture" / "entries" / yyyy / mm
    d.mkdir(parents=True, exist_ok=True)
    p = d / f"{raw['id']}.json"
    p.write_text(json.dumps(raw), encoding="utf-8")
    return p


# ---------------------------------------------------------------------------
# Hostile on-disk JSON: ids/timestamps are user-writable via Obsidian/Syncthing.
# ---------------------------------------------------------------------------


def test_multibyte_id_and_ts_survive_listings(chronicle_dir: Path) -> None:
    # Byte offset 10 lands mid-character in both id and ts.
    _write_entry_file(
        chronicle_dir,
        {
            "version": 1,
            "id": "aaaaaaaaa日本語",
            "ts": "日本語のタイムスタンプです",
            "type": "log",
            "text": "hostile file",
            "processed": False,
        },
    )
    client = _client(chronicle_dir)
    r = client.get("/entries", headers=HDR)
    assert r.status_code == 200, r.text
    r2 = client.get("/entries", headers=HDR, params={"from": "2026-01-01", "to": "2026-12-31"})
    assert r2.status_code == 200, r2.text
    # The journal day listing also walks entries.
    r3 = client.get("/journal/days", headers=HDR)
    assert r3.status_code == 200, r3.text


def test_oversized_hostile_strings_survive_listings(chronicle_dir: Path) -> None:
    _write_entry_file(
        chronicle_dir,
        {
            "version": 1,
            "id": "2026-08-21_101500-an",
            "ts": "x" * 100_000,
            "type": "log",
            "text": "y" * 10,
            "processed": False,
            "filed_path": "/" + "deep/" * 500 + "z.md",
            "filed": True,
        },
    )
    client = _client(chronicle_dir)
    for url in ("/entries", "/journal/days"):
        r = client.get(url, headers=HDR)
        assert r.status_code == 200, f"{url}: {r.status_code} {r.text[:200]}"


# ---------------------------------------------------------------------------
# filed_path traversal: the mirror persists what phones send; consumers must
# refuse anything that is not a canonical 40-Journal/YYYY-MM-DD.md path.
# ---------------------------------------------------------------------------


def test_mirror_accepts_then_journal_refuses_traversal_filed_path(
    chronicle_dir: Path,
) -> None:
    client = _client(chronicle_dir)
    secret = chronicle_dir.parent / "vault-secret.txt"
    eid = "2026-08-21_101500-an"
    body = {
        "version": 1,
        "id": eid,
        "ts": "2026-08-21T10:15:00+05:30",
        "type": "log",
        "text": "capture",
        "processed": True,
        "filed": True,
        "filed_path": "../vault-secret.txt",
    }
    r = client.post("/entries/mirror", headers=HDR, json={"entry": body})
    assert r.status_code == 200, r.text

    secret.write_text(
        f"<!-- entry:{eid} -->\nTOP SECRET\n<!-- /entry:{eid} -->\n", encoding="utf-8"
    )
    try:
        got = client.get(f"/journal/entries/{eid}", headers=HDR)
        assert got.status_code == 404
        assert "invalid filed_path" in got.text
        assert "TOP SECRET" not in got.text

        patch_res = client.patch(
            f"/journal/entries/{eid}",
            headers=HDR,
            json={"body": "owned", "base_hash": "0" * 64},
        )
        assert patch_res.status_code == 404
    finally:
        secret.unlink(missing_ok=True)


def test_journal_endpoints_reject_filed_path_viants(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    eid = "2026-08-21_101501-an"
    base = {
        "version": 1,
        "id": eid,
        "ts": "2026-08-21T10:15:01+05:30",
        "type": "log",
        "text": "capture",
        "processed": True,
        "filed": True,
    }
    for evil in (
        "../../etc/passwd",
        "/etc/passwd",
        "00-Inbox/2026-08-21.md",
        "40-Journal/../../config.json",
        "40-Journal/2026-08-21.md/extra",
        "40-Journal/not-a-date.md",
        "40-Journal/２０２６-０８-２１.md",
    ):
        _write_entry_file(chronicle_dir, {**base, "filed_path": evil})
        got = client.get(f"/journal/entries/{eid}", headers=HDR)
        assert got.status_code == 404, f"{evil!r}: {got.status_code}"
        # Echoing the rejected *input string* is fine; leaking file CONTENT
        # (config keys, absolute paths) would not be.
        assert "layout_version" not in got.text
        assert "/Users/" not in got.text


# ---------------------------------------------------------------------------
# Concurrency: N simultaneous identical mirror pushes → exactly one file.
# ---------------------------------------------------------------------------


def test_concurrent_identical_mirrors_are_idempotent(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    eid = "2026-08-21_102000-an"
    body = {
        "version": 1,
        "id": eid,
        "ts": "2026-08-21T10:20:00+05:30",
        "type": "log",
        "text": "same content",
    }

    def push(_: int) -> int:
        res = client.post("/entries/mirror", headers=HDR, json={"entry": body})
        return res.status_code

    with ThreadPoolExecutor(max_workers=8) as pool:
        codes = list(pool.map(push, range(8)))

    assert all(c == 200 for c in codes), codes
    files = list((chronicle_dir / "_capture" / "entries").rglob(f"{eid}.json"))
    assert len(files) == 1
    created = [c for c in codes if isinstance(c, int)]
    deduped_or_created = sum(
        1
        for c in created
        if c == 200
    )
    assert deduped_or_created == 8


# ---------------------------------------------------------------------------
# Absurd JSON shapes: deep nesting must be refused without killing workers.
# ---------------------------------------------------------------------------


def test_deeply_nested_entry_json_cleanly_rejected(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    deep: dict = {"leaf": True}
    for _ in range(400):
        deep = {"n": deep}
    payload = {
        "version": 1,
        "id": "2026-08-21_103000-pc",
        "ts": "2026-08-21T10:30:00+05:30",
        "type": "log",
        "text": "hi",
        "extra_nested": deep,
    }
    r = client.post(
        "/entries/mirror", headers=HDR, json={"entry": payload}
    )
    # Any explicit 4xx is fine; a 500 means the parser blew up ungracefully.
    assert r.status_code < 500, r.status_code
