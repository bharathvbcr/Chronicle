"""Test that 40-Journal/CLAUDE.md produces no document row in the search index."""

from __future__ import annotations

import sqlite3
from pathlib import Path

from chronicle_pipeline.index_store import index_db_path, run_index


def test_index_skips_journal_claude_md(chronicle_dir: Path) -> None:
    journal_dir = chronicle_dir / "40-Journal"
    journal_dir.mkdir(parents=True, exist_ok=True)
    claude_md = journal_dir / "CLAUDE.md"
    claude_md.write_text("# Journal Instructions\n\nDo not index this.", encoding="utf-8")

    run_index(chronicle_dir)

    con = sqlite3.connect(str(index_db_path(chronicle_dir)))
    paths = {row[0] for row in con.execute("SELECT path FROM documents WHERE path IS NOT NULL")}
    ids = {row[0] for row in con.execute("SELECT id FROM documents")}
    con.close()

    assert "40-Journal/CLAUDE.md" not in paths
    assert "note:40-Journal/CLAUDE.md" not in ids
