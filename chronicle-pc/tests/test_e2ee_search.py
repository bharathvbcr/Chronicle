"""Sealed search must fail closed when config.json cannot be trusted.

A missing or plain config stays searchable. A truncated, non-object, or
broken e2ee block must not return indexed text. A locked vault must not
turn a failed sealed-row delete into a successful search.
"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

import pytest

from chronicle_pipeline import e2ee, index_store, rag
from chronicle_pipeline.entries import save_entry
from chronicle_pipeline.index_store import (
    get_documents_by_ids,
    purge_locked_entries,
    run_index,
    search,
)
from chronicle_pipeline.models import Entry

PASS = "correct horse battery staple"
MARKER = "qxv-sealed-marker-7c2e"
OTHER = "qxv-other-marker-1b90"
UNRELATED = "qxv-plain-marker-44aa"
DOC_ID = "2026-08-12_010101-pc"
OTHER_ID = "2026-08-12_020202-pc"
PLAIN_ID = "2026-08-12_030303-pc"


def _plain_config(root: Path) -> None:
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}),
        encoding="utf-8",
    )


def _locked_config(root: Path) -> None:
    _plain_config(root)
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), root)
    e2ee.lock(root)


def _seed_document(root: Path, doc_id: str, text: str, *, sealed: int = 0) -> None:
    conn = index_store._connect(root)
    try:
        index_store._init_schema(conn, use_vec=False)
        conn.execute(
            """
            INSERT OR REPLACE INTO documents
            (id, kind, path, text, content_hash, embed_model, embedding_json, updated_at, sealed)
            VALUES (?, 'entry', ?, ?, 'hash', 'nomic-embed-text', '[]', datetime('now'), ?)
            """,
            (doc_id, f"_capture/entries/{doc_id}.json", text, sealed),
        )
        conn.commit()
    finally:
        conn.close()


def _stored_texts(root: Path) -> list[str]:
    db = index_store.index_db_path(root)
    conn = sqlite3.connect(str(db))
    try:
        return [row[0] or "" for row in conn.execute("SELECT text FROM documents")]
    finally:
        conn.close()


def _joined(hits: list[dict]) -> str:
    return "\n".join(h.get("text") or "" for h in hits)


def _replace_with_directory(path: Path) -> None:
    """Unreadable entry: a directory whose name still matches *.json."""
    path.unlink()
    path.mkdir()


def test_truncated_config_does_not_return_indexed_text(tmp_path: Path) -> None:
    _plain_config(tmp_path)
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    (tmp_path / "config.json").write_text('{"layout_version": 2, "e2ee": {', encoding="utf-8")

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert hits == []
    assert docs == []
    assert MARKER not in _joined(hits)
    assert MARKER not in _joined(docs)


def test_plain_config_returns_indexed_text(tmp_path: Path) -> None:
    _plain_config(tmp_path)
    _seed_document(tmp_path, DOC_ID, MARKER)

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert MARKER in _joined(hits)
    assert MARKER in _joined(docs)


def test_missing_config_returns_indexed_text(tmp_path: Path) -> None:
    _seed_document(tmp_path, DOC_ID, MARKER)
    assert not (tmp_path / "config.json").exists()

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    created = tmp_path / "config.json"
    assert created.is_file()
    assert not created.is_symlink()
    assert MARKER in _joined(hits)
    assert MARKER in _joined(docs)


def test_null_e2ee_block_returns_indexed_text(tmp_path: Path) -> None:
    (tmp_path / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC", "e2ee": None}),
        encoding="utf-8",
    )
    _seed_document(tmp_path, DOC_ID, MARKER)

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert MARKER in _joined(hits)
    assert MARKER in _joined(docs)


@pytest.mark.parametrize(
    "raw",
    [
        "",
        "[]",
        json.dumps({"version": 1, "layout_version": 2, "e2ee": {}}),
        json.dumps({"version": 1, "layout_version": 2, "e2ee": "broken"}),
        json.dumps({"version": 1, "layout_version": 2, "e2ee": {"kdf": "nope"}}),
    ],
)
def test_unreadable_config_shapes_hide_indexed_text(tmp_path: Path, raw: str) -> None:
    _plain_config(tmp_path)
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    (tmp_path / "config.json").write_text(raw, encoding="utf-8")

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert hits == []
    assert docs == []
    assert MARKER not in _joined(hits)
    assert MARKER not in _joined(docs)


def test_config_directory_hides_indexed_text(tmp_path: Path) -> None:
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    cfg = tmp_path / "config.json"
    if cfg.exists():
        cfg.unlink()
    cfg.mkdir()

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert hits == []
    assert docs == []
    assert MARKER not in _joined(hits)
    assert MARKER not in _joined(docs)


def test_dangling_config_symlink_hides_indexed_text(tmp_path: Path) -> None:
    """config.json present as a symlink to a missing target is unreadable.

    Path.exists() is false for a dangling symlink. That must not be treated
    as an absent config (plain), or search returns the sealed marker.
    """
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    cfg = tmp_path / "config.json"
    cfg.symlink_to(tmp_path / "config.json.missing")
    assert cfg.is_symlink()
    assert not cfg.exists()

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert hits == []
    assert docs == []
    assert MARKER not in _joined(hits)
    assert MARKER not in _joined(docs)
    assert cfg.is_symlink()
    assert not cfg.exists()


def test_config_symlink_loop_hides_indexed_text(tmp_path: Path) -> None:
    """A self-referential config.json symlink is present and unreadable."""
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    cfg = tmp_path / "config.json"
    cfg.symlink_to("config.json")
    assert cfg.is_symlink()
    assert not cfg.exists()

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert hits == []
    assert docs == []
    assert MARKER not in _joined(hits)
    assert MARKER not in _joined(docs)
    assert cfg.is_symlink()
    assert not cfg.exists()


def test_symlink_to_config_directory_hides_indexed_text(tmp_path: Path) -> None:
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    target = tmp_path / "cfg-dir"
    target.mkdir()
    cfg = tmp_path / "config.json"
    cfg.symlink_to(target)

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert hits == []
    assert docs == []
    assert MARKER not in _joined(hits)
    assert MARKER not in _joined(docs)
    assert cfg.is_symlink()
    assert target.is_dir()


def test_symlink_to_plain_config_returns_marker(tmp_path: Path) -> None:
    real = tmp_path / "real-config.json"
    real.write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}),
        encoding="utf-8",
    )
    cfg = tmp_path / "config.json"
    cfg.symlink_to(real)
    _seed_document(tmp_path, DOC_ID, MARKER)

    hits = search(tmp_path, MARKER, top_k=5)
    docs = get_documents_by_ids(tmp_path, [DOC_ID])
    assert MARKER in _joined(hits)
    assert MARKER in _joined(docs)
    assert cfg.is_symlink()
    assert real.is_file()


def test_run_index_dangling_symlink_raises_and_keeps_link(tmp_path: Path) -> None:
    cfg = tmp_path / "config.json"
    cfg.symlink_to(tmp_path / "missing-config.json")
    with pytest.raises(index_store.UnreadableVaultConfig):
        run_index(tmp_path, force=True)
    assert cfg.is_symlink()
    assert not cfg.exists()


def test_run_index_leaves_truncated_config_bytes(tmp_path: Path) -> None:
    cfg = tmp_path / "config.json"
    raw = b'{"layout_version": 2, "e2ee": {'
    cfg.write_bytes(raw)
    with pytest.raises(index_store.UnreadableVaultConfig):
        run_index(tmp_path, force=True)
    assert cfg.read_bytes() == raw
    assert cfg.is_file()
    assert not cfg.is_symlink()


def test_run_index_leaves_config_directory(tmp_path: Path) -> None:
    cfg = tmp_path / "config.json"
    cfg.mkdir()
    with pytest.raises(index_store.UnreadableVaultConfig):
        run_index(tmp_path, force=True)
    assert cfg.is_dir()
    assert not cfg.is_symlink()


def _deny_deletes(conn: sqlite3.Connection, table: str) -> None:
    """Make the real sqlite execute fail for DELETE on one table.

    sqlite3.Connection.execute is a read-only slot on the C type, so the
    failure is injected on the connection index_store uses, not by replacing
    search.
    """

    def auth(action: int, arg1: str | None, arg2: str | None, db: str | None, source: str | None) -> int:
        if action == sqlite3.SQLITE_DELETE and arg1 == table:
            return sqlite3.SQLITE_DENY
        return sqlite3.SQLITE_OK

    conn.set_authorizer(auth)


def test_locked_sealed_delete_error_raises_and_keeps_row(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _locked_config(tmp_path)
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    real_connect = index_store._connect

    def wrapped(root: Path) -> sqlite3.Connection:
        conn = real_connect(root)
        _deny_deletes(conn, "documents")
        return conn

    monkeypatch.setattr(index_store, "_connect", wrapped)
    try:
        with pytest.raises(sqlite3.Error, match="not authorized"):
            search(tmp_path, MARKER, top_k=5)
    finally:
        monkeypatch.setattr(index_store, "_connect", real_connect)

    assert MARKER in _stored_texts(tmp_path)


def test_locked_vec_delete_error_rolls_back_and_raises(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _locked_config(tmp_path)
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    real_connect = index_store._connect

    def wrapped(root: Path) -> sqlite3.Connection:
        conn = real_connect(root)
        conn.execute("CREATE TABLE IF NOT EXISTS vec_documents (id TEXT PRIMARY KEY)")
        conn.commit()
        _deny_deletes(conn, "vec_documents")
        return conn

    monkeypatch.setattr(index_store, "_connect", wrapped)
    try:
        with pytest.raises(sqlite3.Error, match="not authorized"):
            search(tmp_path, MARKER, top_k=5)
    finally:
        monkeypatch.setattr(index_store, "_connect", real_connect)

    assert MARKER in _stored_texts(tmp_path)


def test_purge_locked_removes_unreadable_entry_file(tmp_path: Path) -> None:
    """Entry path is replaced with a directory; chmod is not used."""
    _locked_config(tmp_path)
    e2ee.unlock(tmp_path, PASS)
    try:
        path = save_entry(
            tmp_path,
            Entry(id=DOC_ID, ts="2026-08-12T01:01:01+00:00", type="log", text=MARKER),
        )
        run_index(tmp_path, force=True)
    finally:
        e2ee.lock(tmp_path)
    assert any(MARKER in text for text in _stored_texts(tmp_path))
    _replace_with_directory(path)

    removed = purge_locked_entries(tmp_path)
    assert removed >= 1
    assert MARKER not in "\n".join(_stored_texts(tmp_path))


def test_plain_vault_unreadable_entry_does_not_purge_plaintext(tmp_path: Path) -> None:
    """Same directory technique. The plaintext row is not sealed and must stay."""
    _plain_config(tmp_path)
    plain_path = save_entry(
        tmp_path,
        Entry(id=PLAIN_ID, ts="2026-08-12T03:03:03+00:00", type="log", text=UNRELATED),
    )
    other_path = save_entry(
        tmp_path,
        Entry(id=OTHER_ID, ts="2026-08-12T02:02:02+00:00", type="log", text=OTHER),
    )
    run_index(tmp_path, force=True)
    _replace_with_directory(other_path)

    removed = purge_locked_entries(tmp_path)
    stored = "\n".join(_stored_texts(tmp_path))
    assert removed == 0
    assert UNRELATED in stored
    assert OTHER in stored
    assert plain_path.is_file()


def test_recall_dangling_config_symlink_hides_indexed_text(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A dangling config.json symlink stays a symlink and hides indexed text.

    recall calls ensure_config before search. That must raise instead of
    replacing the symlink with a plain config. The error text has no marker.
    """
    monkeypatch.setattr(
        "chronicle_pipeline.ollama.ollama_reachable", lambda *_args, **_kwargs: False
    )
    _seed_document(tmp_path, DOC_ID, MARKER, sealed=1)
    missing = tmp_path / "config.json.missing"
    cfg = tmp_path / "config.json"
    cfg.symlink_to(missing)

    from chronicle_pipeline.config import UnreadableConfigError

    try:
        with pytest.raises(UnreadableConfigError) as caught:
            rag.recall(tmp_path, MARKER)
        assert MARKER not in str(caught.value)
    finally:
        assert cfg.is_symlink()
        assert not cfg.exists()
        assert not missing.exists()
