"""Stale-index leak regression: locking must seal search too (CONTRACT v1.11).

Before this fix, an entry indexed while the vault was unlocked kept its
plaintext snippet in sqlite forever — re-indexes treated the id as live, so
`chronicle lock` left it fully searchable, contradicting the fail-closed
contract. Lock now purges sealed documents.
"""

from __future__ import annotations

from pathlib import Path

from chronicle_pipeline import e2ee
from chronicle_pipeline.entries import load_all_entries, save_entry
from chronicle_pipeline.index_store import (
    get_documents_by_ids,
    purge_locked_entries,
    run_index,
    search,
)
from chronicle_pipeline.models import Entry

PASS = "correct horse battery staple"


def _setup(root: Path) -> None:
    (root / "config.json").write_text('{"layout_version": 2}')
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), root)


def _entry(root: Path) -> Entry:
    return Entry(
        id="2026-08-10_121212-pc",
        ts="2026-08-10T12:12:12+05:30",
        type="idea",
        text="quokka sanctuary blueprint",
    )


def test_lock_purges_indexed_plaintext(tmp_path: Path) -> None:
    _setup(tmp_path)
    e2ee.unlock(tmp_path, PASS)
    entry_path = save_entry(tmp_path, _entry(tmp_path))
    entry_id = entry_path.stem
    # Entry was saved sealed; unlocked load returns plaintext for indexing.
    loaded = [e for e in load_all_entries(tmp_path) if e.id == entry_id]
    assert loaded and loaded[0].text == "quokka sanctuary blueprint"

    run_index(tmp_path, force=True)
    hits = search(tmp_path, "quokka", top_k=5)
    assert any(h["id"] == entry_id for h in hits), "precondition: indexed+searchable"

    # Lock → purge → snippet must be gone from the index.
    e2ee.lock(tmp_path)
    removed = purge_locked_entries(tmp_path)
    assert removed >= 1
    assert not any(h["id"] == entry_id for h in search(tmp_path, "quokka", top_k=5))
    assert get_documents_by_ids(tmp_path, {entry_id}) == []

    # Unlocking + re-index restores searchability.
    e2ee.unlock(tmp_path, PASS)
    run_index(tmp_path, force=True)
    assert any(h["id"] == entry_id for h in search(tmp_path, "quokka", top_k=5))


def test_purge_keeps_plain_entries_and_is_idempotent(tmp_path: Path) -> None:
    _setup(tmp_path)
    plain_path = save_entry(tmp_path, Entry(id="2026-08-10_130130-pc", ts="2026-08-10T13:01:30+05:30", type="log", text="plain as day"))
    plain_id = plain_path.stem
    e2ee.lock(tmp_path)

    first = purge_locked_entries(tmp_path)
    second = purge_locked_entries(tmp_path)
    assert first == 0 and second == 0

    run_index(tmp_path, force=True)
    hits = search(tmp_path, "plain as day", top_k=5)
    assert any(h["id"] == plain_id for h in hits)
