"""Test archive title deduplication in markdown_index (D3)."""

from __future__ import annotations

from pathlib import Path

from chronicle_pipeline.markdown_index import collect_index_rows


def test_archive_title_dedup_drops_archived_row(chronicle_dir: Path) -> None:
    live_kb = chronicle_dir / "30-Knowledge"
    live_kb.mkdir(parents=True, exist_ok=True)
    archive_dir = chronicle_dir / "90-Archive"
    archive_dir.mkdir(parents=True, exist_ok=True)

    # Live note and archive note sharing the exact same title
    (live_kb / "live_doc.md").write_text(
        "---\ntitle: Unique Project Title\ntype: project\n---\n# Unique Project Title\nLive body",
        encoding="utf-8",
    )
    (archive_dir / "old_doc.md").write_text(
        "---\ntitle: Unique Project Title\ntype: project\n---\n# Unique Project Title\nArchived body",
        encoding="utf-8",
    )

    rows = collect_index_rows(chronicle_dir)
    matching = [r for r in rows if r["title"] == "Unique Project Title"]

    assert len(matching) == 1
    assert matching[0]["rel"] == "30-Knowledge/live_doc.md"
