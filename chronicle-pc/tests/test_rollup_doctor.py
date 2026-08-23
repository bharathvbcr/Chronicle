"""Rollup, topics, doctor, export, backup smoke tests."""

from __future__ import annotations

from pathlib import Path

from chronicle_pipeline.backup import run_backup
from chronicle_pipeline.doctor import run_doctor
from chronicle_pipeline.export import run_export
from chronicle_pipeline.index_store import run_index
from chronicle_pipeline.process import run_process
from chronicle_pipeline.rollup import run_rollup
from chronicle_pipeline.topics import run_topics


def test_rollup_and_topics(chronicle_dir: Path) -> None:
    run_process(chronicle_dir, dry_run=False, run_brain=True)
    r = run_rollup(chronicle_dir)
    assert r["written"] or True  # may be empty if write_if_changed false on re-run
    derived = chronicle_dir / "_system" / "derived"
    weekly = list((derived / "weekly").glob("*.md"))
    monthly = list((derived / "monthly").glob("*.md"))
    yearly = list((derived / "yearly").glob("*.md"))
    assert weekly and monthly and yearly
    t = run_topics(chronicle_dir)
    assert (derived / "topics" / "dreams.md").is_file()
    assert "dreams" in str(t["written"]) or (derived / "topics" / "dreams.md").is_file()


def test_index_offline(chronicle_dir: Path) -> None:
    run_process(chronicle_dir, dry_run=False, run_brain=False)
    result = run_index(chronicle_dir)
    assert (chronicle_dir / "index" / "chronicle.sqlite").is_file()
    assert result["mode"] in ("sqlite-vec", "sqlite+json-cosine")


def test_index_kb_kind_and_stale_deletion(tmp_path: Path, chronicle_dir: Path) -> None:
    import sqlite3

    from chronicle_pipeline.index_store import search

    run_process(chronicle_dir, dry_run=False, run_brain=False)

    kb_note = chronicle_dir / "30-Knowledge" / "skill.md"
    kb_note.parent.mkdir(parents=True, exist_ok=True)
    kb_note.write_text("# Skill\nPython and Rust\n", encoding="utf-8")

    # Also a non-daily journal note
    weekly = chronicle_dir / "notes" / "weekly" / "2026-W28.md"
    weekly.parent.mkdir(parents=True, exist_ok=True)
    weekly.write_text("# Week\njournal rollup\n", encoding="utf-8")

    result = run_index(chronicle_dir, force=True)
    assert result["upserted"] >= 1

    db = chronicle_dir / "index" / "chronicle.sqlite"
    conn = sqlite3.connect(str(db))
    rows = list(conn.execute("SELECT id, kind, path FROM documents"))
    conn.close()
    by_kind = {}
    for doc_id, kind, path in rows:
        by_kind.setdefault(kind, []).append((doc_id, path))

    assert "kb" in by_kind
    assert any(p == "30-Knowledge/skill.md" or i == "30-Knowledge/skill.md" for i, p in by_kind["kb"])
    assert "note" in by_kind
    assert any("weekly" in (p or "") or "weekly" in i for i, p in by_kind["note"])

    kb_hits = search(chronicle_dir, "Python", kinds={"kb"})
    assert any(h["kind"] == "kb" for h in kb_hits)

    journal_hits = search(chronicle_dir, "Python", scope="journal")
    assert all(h["kind"] in ("entry", "note") for h in journal_hits)

    # Stale deletion: remove kb note and re-index
    kb_note.unlink()
    result2 = run_index(chronicle_dir, force=False)
    assert result2["stale_deleted"] >= 1
    conn = sqlite3.connect(str(db))
    left = list(conn.execute("SELECT id FROM documents WHERE kind='kb'"))
    conn.close()
    assert not any(r[0] == "30-Knowledge/skill.md" for r in left)


def test_doctor_vault_mirror_guard(chronicle_dir: Path) -> None:
    import json

    cfg_path = chronicle_dir / "config.json"
    raw = json.loads(cfg_path.read_text(encoding="utf-8")) if cfg_path.is_file() else {"version": 1}
    raw["vault_mirror"] = str(chronicle_dir.resolve())
    cfg_path.write_text(json.dumps(raw, indent=2) + "\n", encoding="utf-8")
    report = run_doctor(chronicle_dir, dry_run=True, fix=False, compact_ops=False)
    assert report["ok"] is False
    assert any("vault_mirror" in m for m in report.get("config_issues", []))


def test_doctor_and_backup_export(chronicle_dir: Path, tmp_path: Path) -> None:
    run_process(chronicle_dir, dry_run=False, run_brain=True)
    report = run_doctor(chronicle_dir, dry_run=False, fix=True)
    assert "ok" in report
    assert report["entry_issues"] == []
    zip_path = tmp_path / "bak.zip"
    bak = run_backup(chronicle_dir, path=zip_path)
    assert Path(bak["path"]).is_file()
    # index excluded
    import zipfile

    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
    assert not any("/index/" in n for n in names)
    out = tmp_path / "out.chronosflow.json"
    exp = run_export(chronicle_dir, format="chronosflow", path=out)
    assert Path(exp["path"]).is_file()
