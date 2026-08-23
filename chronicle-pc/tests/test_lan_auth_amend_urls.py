"""P0/P1 audit fixes: LAN default-deny auth, amend gate, models URL pins."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from chronicle_pipeline.entries import load_entry, save_entry
from chronicle_pipeline.journal import (
    extract_block,
    file_entry,
    on_disk_block_hash,
    upsert_entry_block,
)
from chronicle_pipeline.models import Entry
from chronicle_pipeline.serve import TOKEN_HEADER, create_app


def _lan_client(chronicle_dir: Path, token: str = "test-pairing-token") -> TestClient:
    app = create_app(
        chronicle_dir,
        connect_info={
            "base": "http://192.168.1.10:8765",
            "bind_host": "0.0.0.0",
            "port": 8765,
            "token": token,
            "auth_required": True,
        },
    )
    return TestClient(app)


def test_unauth_vault_rebuild_index_401(chronicle_dir: Path) -> None:
    client = _lan_client(chronicle_dir)
    denied = client.post("/vault/rebuild-index", json={})
    assert denied.status_code == 401
    ok = client.post(
        "/vault/rebuild-index",
        json={},
        headers={TOKEN_HEADER: "test-pairing-token"},
    )
    assert ok.status_code == 200


def test_unknown_api_prefix_requires_auth(chronicle_dir: Path) -> None:
    client = _lan_client(chronicle_dir)
    assert client.post("/fakenew/mutate", json={}).status_code == 401
    assert client.get("/entries").status_code == 401
    # SPA shell still open
    assert client.get("/settings").status_code in (200, 404)
    assert client.get("/health").status_code == 200


def test_openapi_disabled_when_lan_auth(chronicle_dir: Path) -> None:
    client = _lan_client(chronicle_dir)
    # OpenAPI routes are unregistered on LAN; default-deny also 401s /docs.
    assert client.get("/docs").status_code in (401, 404)
    assert client.get("/openapi.json").status_code in (401, 404)


def test_amend_gate_missing_filed_hash_skips(tmp_path: Path) -> None:
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}),
        encoding="utf-8",
    )
    entry = Entry.model_validate(
        {
            "version": 1,
            "id": "2026-07-09_213045-pc",
            "ts": "2026-07-09T21:30:45+05:30",
            "type": "log",
            "text": "original",
            "tags": [],
            "images": [],
            "processed": True,
            "filed": True,
            # deliberately no filed_content_hash
        }
    )
    save_entry(root, entry)
    # Seed a fence as if Obsidian/human already wrote prose
    day = root / "40-Journal" / "2026-07-09.md"
    day.parent.mkdir(parents=True)
    day.write_text(
        "# 2026-07-09\n\n"
        "<!-- entry:2026-07-09_213045-pc -->\n"
        "HUMAN PROSE KEEP ME\n"
        "<!-- /entry:2026-07-09_213045-pc -->\n",
        encoding="utf-8",
    )
    disk_hash = on_disk_block_hash(day.read_text(encoding="utf-8"), entry.id)
    assert disk_hash

    entry.text = "pipeline wipe attempt"
    r = upsert_entry_block(root, entry, dry_run=False, force=True)
    assert r["action"] == "skip"
    assert r["skipped_reason"] == "missing_filed_content_hash"
    body = extract_block(day.read_text(encoding="utf-8"), entry.id)
    assert body is not None and "HUMAN PROSE KEEP ME" in body

    fr = file_entry(root, entry, dry_run=False, force=True)
    assert fr["action"] == "skip"
    assert fr["skipped_reason"] == "missing_filed_content_hash"


def test_models_rejects_public_ollama_and_bad_grok(chronicle_dir: Path) -> None:
    client = TestClient(
        create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    )
    with patch("chronicle_pipeline.api.system.ollama.list_available_models", return_value=[]):
        bad_ollama = client.post(
            "/models",
            json={"base_url": "https://evil.example.com"},
        )
        assert bad_ollama.status_code == 400
        assert "private" in bad_ollama.json()["detail"].lower()

        bad_grok = client.post(
            "/models",
            json={"grok_base_url": "https://evil.example.com/v1"},
        )
        assert bad_grok.status_code == 400
        assert "api.x.ai" in bad_grok.json()["detail"]

        ok = client.post(
            "/models",
            json={
                "base_url": "http://127.0.0.1:11434",
                "grok_base_url": "https://api.x.ai/v1",
            },
        )
        assert ok.status_code == 200


def test_legacy_redirects_on_layout_2(chronicle_dir: Path) -> None:
    client = TestClient(
        create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    )
    r = client.get("/legacy", follow_redirects=False)
    assert r.status_code == 302
    assert r.headers.get("location") == "/"


def test_migrate_skips_json_rewrite_when_media_not_moved(tmp_path: Path) -> None:
    from chronicle_pipeline.migrate_journal_v2 import run_migrate_journal_v2

    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 1, "timezone": "UTC"}),
        encoding="utf-8",
    )
    eid = "2026-07-09_120000-pc"
    entry_dir = root / "entries" / "2026" / "07"
    entry_dir.mkdir(parents=True)
    (entry_dir / f"{eid}.json").write_text(
        json.dumps(
            {
                "version": 1,
                "id": eid,
                "ts": "2026-07-09T12:00:00+00:00",
                "type": "log",
                "text": "hi",
                "tags": [],
                "images": ["img/2026/07/collide.jpg"],
                "processed": True,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    img = root / "img" / "2026" / "07"
    img.mkdir(parents=True)
    (img / "collide.jpg").write_bytes(b"\xff\xd8\xff\x01")
    # Dest already exists → move skipped → JSON must keep legacy path
    dest = root / "_attachments" / "2026" / "07"
    dest.mkdir(parents=True)
    (dest / "collide.jpg").write_bytes(b"\xff\xd8\xff\x02")

    result = run_migrate_journal_v2(
        root, dry_run=False, apply=True, i_have_backup=True
    )
    assert result["stats"]["skipped_exists"] >= 1
    assert result["layout_bumped"] is False
    loaded = load_entry(root / "entries" / "2026" / "07" / f"{eid}.json")
    if loaded is None:
        loaded = load_entry(
            root / "_capture" / "entries" / "2026" / "07" / f"{eid}.json"
        )
    assert loaded is not None
    assert loaded.images == ["img/2026/07/collide.jpg"]
