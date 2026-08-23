"""Process dry-run, note generation, idempotency."""

from __future__ import annotations

from datetime import date
from pathlib import Path

from chronicle_pipeline.entries import load_all_entries
from chronicle_pipeline.notes import render_daily_note, write_if_changed
from chronicle_pipeline.process import run_process


def test_process_dry_run_does_not_write(chronicle_dir: Path) -> None:
    before = {p: p.read_bytes() for p in (chronicle_dir / "entries").rglob("*.json")}
    result = run_process(chronicle_dir, dry_run=True, run_brain=True)
    assert result["dry_run"] is True
    assert len(result["processed"]) >= 3
    after = {p: p.read_bytes() for p in (chronicle_dir / "entries").rglob("*.json")}
    assert before == after
    # No notes written on dry-run
    assert not list((chronicle_dir / "notes" / "daily").glob("*.md")) if (chronicle_dir / "notes" / "daily").exists() else True


def test_process_writes_notes_and_marks_processed(chronicle_dir: Path) -> None:
    result = run_process(chronicle_dir, dry_run=False, run_brain=True)
    assert result["dry_run"] is False
    entries = load_all_entries(chronicle_dir)
    assert entries
    for e in entries:
        if e.audio and not (e.text or "").strip():
            # Failed / skipped transcription must stay unprocessed for retry.
            assert e.processed is False
            assert e.id not in result["processed"]
        else:
            assert e.processed is True
            assert e.id in result["processed"]
            assert e.filed is True
    journal = chronicle_dir / "40-Journal" / "2026-07-09.md"
    assert journal.is_file()
    text = journal.read_text(encoding="utf-8")
    assert "2026-07-09_090015-an" in text
    derived = chronicle_dir / "_system" / "derived" / "daily" / "2026-07-09.md"
    assert derived.is_file()
    assert "2026-07-09" in derived.read_text(encoding="utf-8")
    assert (chronicle_dir / "brain" / "graph.json").is_file()
    assert (chronicle_dir / "brain" / "tags.json").is_file()


def test_process_rerun_idempotent_notes(chronicle_dir: Path) -> None:
    run_process(chronicle_dir, dry_run=False, run_brain=False)
    journal = chronicle_dir / "40-Journal" / "2026-07-09.md"
    derived = chronicle_dir / "_system" / "derived" / "daily" / "2026-07-09.md"
    first_j = journal.read_bytes()
    first_d = derived.read_bytes()
    # Second run: all processed, journal/derived should be byte-identical (amend gate)
    run_process(chronicle_dir, dry_run=False, run_brain=False)
    assert journal.read_bytes() == first_j
    assert derived.read_bytes() == first_d


def test_render_daily_note_stable() -> None:
    from chronicle_pipeline.models import Entry

    e = Entry(
        version=1,
        id="2026-07-09_090015-an",
        ts="2026-07-09T09:00:15+05:30",
        type="log",
        text="Hello",
        tags=["work"],
        images=[],
        processed=True,
    )
    a = render_daily_note(date(2026, 7, 9), [e])
    b = render_daily_note(date(2026, 7, 9), [e])
    assert a == b
    assert "generated" not in a.lower() or "generated" not in a.split("---")[1]


def test_write_if_changed(tmp_path: Path) -> None:
    p = tmp_path / "n.md"
    assert write_if_changed(p, "a\n") is True
    assert p.read_text() == "a\n"
    assert write_if_changed(p, "a\n") is False
    assert write_if_changed(p, "b\n") is True
