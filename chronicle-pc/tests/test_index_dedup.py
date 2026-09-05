"""Test archive deduplication during run_index (D3)."""

from __future__ import annotations

import sqlite3
from pathlib import Path

from chronicle_pipeline.index_store import index_db_path, run_index


def test_archive_dedup_skips_duplicate_archive_note(chronicle_dir: Path) -> None:
    live_kb = chronicle_dir / "30-Knowledge"
    live_kb.mkdir(parents=True, exist_ok=True)
    archive_dir = chronicle_dir / "90-Archive"
    archive_dir.mkdir(parents=True, exist_ok=True)

    # Identical content in live and archive
    content = "# Note A\n\nIdentical body content for dedup test."
    (live_kb / "a.md").write_text(content, encoding="utf-8")
    (archive_dir / "a.md").write_text(content, encoding="utf-8")

    res = run_index(chronicle_dir)
    assert res.get("dedup_skipped") == 1

    con = sqlite3.connect(str(index_db_path(chronicle_dir)))
    rows = con.execute("SELECT id, path, content_hash FROM documents WHERE path LIKE '%a.md'").fetchall()
    con.close()

    # Only one row indexed, corresponding to the live note
    assert len(rows) == 1
    assert rows[0][1] == "30-Knowledge/a.md"


def test_archive_dedup_keeps_different_archive_note(chronicle_dir: Path) -> None:
    live_kb = chronicle_dir / "30-Knowledge"
    live_kb.mkdir(parents=True, exist_ok=True)
    archive_dir = chronicle_dir / "90-Archive"
    archive_dir.mkdir(parents=True, exist_ok=True)

    (live_kb / "a.md").write_text("# Note A Live\n\nLive version.", encoding="utf-8")
    (archive_dir / "a.md").write_text("# Note A Archive\n\nArchived version differs.", encoding="utf-8")

    res = run_index(chronicle_dir)
    assert res.get("dedup_skipped", 0) == 0

    con = sqlite3.connect(str(index_db_path(chronicle_dir)))
    rows = con.execute("SELECT id, path FROM documents WHERE path LIKE '%a.md'").fetchall()
    con.close()

    assert len(rows) == 2


def test_archive_dedup_does_not_dedup_two_live_notes(chronicle_dir: Path) -> None:
    live_kb = chronicle_dir / "30-Knowledge"
    live_kb.mkdir(parents=True, exist_ok=True)
    work_dir = chronicle_dir / "10-Work"
    work_dir.mkdir(parents=True, exist_ok=True)

    content = "# Duplicate Live Notes\n\nSame content in two live locations."
    (live_kb / "note1.md").write_text(content, encoding="utf-8")
    (work_dir / "note2.md").write_text(content, encoding="utf-8")

    res = run_index(chronicle_dir)
    assert res.get("dedup_skipped", 0) == 0

    con = sqlite3.connect(str(index_db_path(chronicle_dir)))
    rows = con.execute("SELECT id, path FROM documents WHERE path LIKE '%note1.md' OR path LIKE '%note2.md'").fetchall()
    con.close()

    assert len(rows) == 2
