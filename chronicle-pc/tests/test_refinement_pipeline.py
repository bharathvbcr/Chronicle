"""Pipeline integrity, search, path containment, and LAN auth tests."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from chronicle_pipeline import captions as captions_mod
from chronicle_pipeline.entries import load_all_entries, save_entry
from chronicle_pipeline.index_store import _upsert_doc, run_index, search
from chronicle_pipeline.media_paths import MediaPathError, validate_media_rel
from chronicle_pipeline.process import run_process
from chronicle_pipeline.serve import TOKEN_HEADER, create_app


def test_processed_flag_before_filing_survives_notes_failure(
    chronicle_dir: Path,
) -> None:
    """File-once: mark processed before filing; notes failure must not roll it back.

    Stuck ``processed && !filed`` is retried on the next process run.
    """
    with patch(
        "chronicle_pipeline.process.regenerate_daily_for_days",
        side_effect=RuntimeError("notes boom"),
    ):
        with pytest.raises(RuntimeError, match="notes boom"):
            run_process(chronicle_dir, dry_run=False, run_brain=False)

    entries = load_all_entries(chronicle_dir)
    ready = [e for e in entries if not (e.audio and not (e.text or "").strip())]
    assert ready
    assert all(e.processed is True for e in ready)


def test_audio_empty_transcript_stays_unprocessed(chronicle_dir: Path) -> None:
    with patch("chronicle_pipeline.process.transcribe_mod.transcribe", return_value=""):
        result = run_process(chronicle_dir, dry_run=False, run_brain=False)

    audio_empty = [
        e
        for e in load_all_entries(chronicle_dir)
        if e.audio and not (e.text or "").strip()
    ]
    assert audio_empty
    for e in audio_empty:
        assert e.processed is False
        assert e.id not in result["processed"]


def test_vision_captions_persisted_across_runs(chronicle_dir: Path) -> None:
    with patch(
        "chronicle_pipeline.process._describe_image",
        return_value="a red notebook on a desk",
    ):
        run_process(chronicle_dir, dry_run=False, run_brain=False)

    stored = captions_mod.load_captions(chronicle_dir)
    assert stored
    assert any(v for v in stored.values())

    # Second run without vision should still reload captions for notes.
    with patch(
        "chronicle_pipeline.process._describe_image",
        side_effect=AssertionError("should not call vision"),
    ):
        captions = captions_mod.load_captions(chronicle_dir)
        assert captions == stored


def test_embed_failure_preserves_prior_embedding(tmp_path: Path) -> None:
    conn = sqlite3.connect(str(tmp_path / "t.sqlite"))
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE documents (
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            path TEXT,
            text TEXT NOT NULL,
            content_hash TEXT NOT NULL,
            embed_model TEXT,
            embedding_json TEXT,
            updated_at TEXT
        )
        """
    )
    prior = [0.1, 0.2, 0.3]
    existing = {
        "doc1": {
            "id": "doc1",
            "content_hash": "old",
            "embed_model": "nomic-embed-text",
            "embedding_json": json.dumps(prior),
        }
    }
    with (
        patch("chronicle_pipeline.index_store.ollama_mod.ollama_reachable", return_value=True),
        patch("chronicle_pipeline.index_store.ollama_mod.try_embed", return_value=[]),
    ):
        result = _upsert_doc(
            conn,
            doc_id="doc1",
            kind="entry",
            path="entries/x.json",
            text="changed content",
            embed_model="nomic-embed-text",
            existing=existing,
            force=False,
            use_vec=False,
        )
    assert result == "upserted"
    row = conn.execute(
        "SELECT embedding_json, text FROM documents WHERE id='doc1'"
    ).fetchone()
    assert json.loads(row["embedding_json"]) == prior
    assert "changed" in row["text"]
    conn.close()


def test_sqlite_vec_search_falls_back_without_extension(chronicle_dir: Path) -> None:
    run_process(chronicle_dir, dry_run=False, run_brain=False)
    run_index(chronicle_dir, force=True)
    hits = search(chronicle_dir, "coffee", top_k=5)
    assert isinstance(hits, list)
    # Without live Ollama embeds this is keyword fallback; still must not crash.
    assert all("id" in h and "score" in h for h in hits)


def test_media_path_rejects_traversal(chronicle_dir: Path) -> None:
    with pytest.raises(MediaPathError):
        validate_media_rel(chronicle_dir, "img/2026/07/../../config.json", kind="img")
    with pytest.raises(MediaPathError):
        validate_media_rel(
            chronicle_dir, "audio/2026/07/../../config.json.m4a", kind="audio"
        )


def test_kb_notes_path_containment(chronicle_dir: Path) -> None:
    from chronicle_pipeline import path_map

    with pytest.raises(ValueError):
        path_map.validate_knowledge_rel("foo/../../config.json")

    with pytest.raises(ValueError):
        path_map.validate_knowledge_rel("../config.json")

    # Escaping the vault root must fail even if joined naively.
    with pytest.raises(ValueError):
        path_map.abs_under_root(chronicle_dir, "../outside.md")

    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)
    # HTTP clients normalize URL `..` segments; exercise a still-invalid path.
    r = client.put("/kb/notes/not a valid path!", json={"content": "x"})
    assert r.status_code == 400


def test_lan_auth_requires_token_on_vault_routes(chronicle_dir: Path) -> None:
    token = "test-pairing-token"
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
    # Default TestClient peer is "testclient" (non-loopback).
    lan_client = TestClient(app)
    loopback = TestClient(app, client=("127.0.0.1", 50000))

    # Exempt paths ok without token
    assert lan_client.get("/health").status_code == 200

    # Non-loopback /connect omits token; loopback includes it
    lan_connect = lan_client.get("/connect")
    assert lan_connect.status_code == 200
    lan_body = lan_connect.json()
    assert lan_body.get("token") in (None, "")
    assert "token" not in lan_body.get("qr", {})

    lb_connect = loopback.get("/connect")
    assert lb_connect.status_code == 200
    lb_body = lb_connect.json()
    assert lb_body["token"] == token
    assert lb_body["qr"]["token"] == token

    # Vault GET without token → 401
    assert lan_client.get("/entries").status_code == 401
    assert lan_client.get("/kb/tree").status_code == 401
    assert lan_client.get("/models").status_code == 401

    # Vault GET with token → success
    assert lan_client.get("/entries", headers={TOKEN_HEADER: token}).status_code == 200

    # Mutating without token → 401
    denied = lan_client.post("/entries", json={"type": "log", "text": "nope"})
    assert denied.status_code == 401

    # Mutating with token → success
    ok = lan_client.post(
        "/entries",
        json={"type": "log", "text": "allowed"},
        headers={TOKEN_HEADER: token},
    )
    assert ok.status_code == 201

    # Localhost bind skips auth even if token present in info without auth_required
    local = create_app(
        chronicle_dir,
        connect_info={
            "base": "http://127.0.0.1:8765",
            "bind_host": "127.0.0.1",
            "token": token,
            "auth_required": False,
        },
    )
    local_client = TestClient(local)
    assert (
        local_client.post("/entries", json={"type": "log", "text": "local ok"}).status_code
        == 201
    )


def test_process_idle_skips_full_day_regen(chronicle_dir: Path) -> None:
    """Incremental process without unprocessed ready entries must not expand to all days."""
    # Mark all entries processed so the next run has nothing to do.
    for e in load_all_entries(chronicle_dir):
        e.processed = True
        save_entry(chronicle_dir, e)

    with patch("chronicle_pipeline.process.regenerate_daily_for_days") as regen:
        regen.return_value = []
        result = run_process(chronicle_dir, dry_run=False, run_brain=False)
        # Called with empty days set (no regen_all_days).
        assert regen.called
        days_arg = regen.call_args.args[1]
        assert days_arg == set()
        assert result["days"] == []
