"""Phase 4: file-once amend gate, file-ready, layout_version, migrate dry_run."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from chronicle_pipeline.config import load_config
from chronicle_pipeline.entries import load_entry, save_entry
from chronicle_pipeline.journal import (
    amend_filed_block,
    extract_block,
    file_entry,
    is_file_ready,
    on_disk_block_hash,
    render_entry_block_body,
    upsert_entry_block,
)
from chronicle_pipeline.migrate_journal_v2 import run_migrate_journal_v2
from chronicle_pipeline.models import Entry
from chronicle_pipeline.paths import content_hash
from chronicle_pipeline.process import run_process
from chronicle_pipeline.vault_layout import (
    CURRENT_LAYOUT_VERSION,
    LayoutVersionError,
    require_layout_version,
)


def _entry(**kwargs) -> Entry:
    base = dict(
        version=1,
        id="2026-07-09_213045-pc",
        ts="2026-07-09T21:30:45+05:30",
        type="log",
        text="hello world",
        tags=["a"],
        images=[],
        audio=[],
        mood=3,
        processed=True,
        filed=False,
    )
    base.update(kwargs)
    return Entry.model_validate(base)


def test_file_ready_rules() -> None:
    assert is_file_ready(_entry(audio=[], text=""))
    assert is_file_ready(_entry(audio=[], text="x"))
    assert not is_file_ready(
        _entry(audio=["_attachments/2026/07/2026-07-09_213045-pc_1.m4a"], text="")
    )
    assert is_file_ready(
        _entry(audio=["_attachments/2026/07/2026-07-09_213045-pc_1.m4a"], text="ok")
    )


def test_amend_gate_skips_human_edit(tmp_path: Path) -> None:
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}),
        encoding="utf-8",
    )
    entry = _entry()
    save_entry(root, entry)

    r1 = file_entry(root, entry, dry_run=False)
    assert r1["action"] in ("insert",)
    assert entry.filed is True
    filed_hash = entry.filed_content_hash
    assert filed_hash

    day = root / "40-Journal" / "2026-07-09.md"
    text = day.read_text(encoding="utf-8")
    # Human edits the block body
    body = extract_block(text, entry.id)
    assert body is not None
    edited = text.replace(body, body.replace("hello world", "HUMAN EDIT"), 1)
    day.write_text(edited, encoding="utf-8")
    disk_hash = on_disk_block_hash(edited, entry.id)
    assert disk_hash != filed_hash

    entry.text = "pipeline wants rewrite"
    r2 = upsert_entry_block(root, entry, dry_run=False, force=False)
    assert r2["action"] == "skip"
    assert r2["skipped_reason"] == "human_or_agent_edit"
    # On-disk human edit preserved
    assert "HUMAN EDIT" in day.read_text(encoding="utf-8")


def test_amend_gate_rewrites_when_untouched(tmp_path: Path) -> None:
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}),
        encoding="utf-8",
    )
    entry = _entry(text="v1")
    save_entry(root, entry)
    file_entry(root, entry, dry_run=False)
    assert entry.filed_content_hash == content_hash(
        render_entry_block_body(entry)
    )

    entry.text = "v2"
    r = file_entry(root, entry, dry_run=False)
    assert r["action"] == "amend"
    body = extract_block(
        (root / "40-Journal" / "2026-07-09.md").read_text(encoding="utf-8"),
        entry.id,
    )
    assert body and "v2" in body


def test_amend_gate_skips_rebuild_after_prose_edit(tmp_path: Path) -> None:
    """Rebuild-protection: once a user amends a fence via serve, upsert_entry_block
    (the pipeline's re-render path) must never overwrite it again."""
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}),
        encoding="utf-8",
    )
    entry = _entry(text="v1")
    save_entry(root, entry)
    file_entry(root, entry, dry_run=False)

    base_hash = entry.filed_content_hash
    amend_filed_block(root, entry.id, new_body="user amended prose", base_hash=base_hash)

    reloaded = load_entry(
        root / "_capture" / "entries" / "2026" / "07" / f"{entry.id}.json"
    )
    assert reloaded is not None
    assert reloaded.prose_edited is True

    r = upsert_entry_block(root, reloaded, dry_run=False)
    assert r["action"] == "skip"
    assert r["skipped_reason"] == "prose_edited"

    day_text = (root / "40-Journal" / "2026-07-09.md").read_text(encoding="utf-8")
    body = extract_block(day_text, entry.id)
    assert body is not None and "user amended prose" in body


def test_layout_version_refusal(tmp_path: Path) -> None:
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 1, "timezone": "UTC"}),
        encoding="utf-8",
    )
    cfg = load_config(root)
    with pytest.raises(LayoutVersionError):
        require_layout_version(root, cfg=cfg)

    with pytest.raises(LayoutVersionError):
        run_process(root, dry_run=True, run_brain=False)


def test_migrate_journal_v2_dry_run(tmp_path: Path) -> None:
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 1, "timezone": "UTC"}),
        encoding="utf-8",
    )
    eid = "2026-07-09_120000-pc"
    entry_dir = root / "entries" / "2026" / "07"
    entry_dir.mkdir(parents=True)
    entry_path = entry_dir / f"{eid}.json"
    entry_path.write_text(
        json.dumps(
            {
                "version": 1,
                "id": eid,
                "ts": "2026-07-09T12:00:00+00:00",
                "type": "log",
                "text": "hi",
                "tags": [],
                "images": ["img/2026/07/x.jpg"],
                "processed": True,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    img = root / "img" / "2026" / "07"
    img.mkdir(parents=True)
    (img / "x.jpg").write_bytes(b"\xff\xd8\xff")

    result = run_migrate_journal_v2(root, dry_run=True, apply=False)
    assert result["dry_run"] is True
    assert result["layout_version"] == CURRENT_LAYOUT_VERSION
    # Nothing moved on dry-run
    assert entry_path.is_file()
    assert not (root / "_capture" / "entries").exists()

    with pytest.raises(ValueError, match="i-have-backup"):
        run_migrate_journal_v2(root, dry_run=False, apply=True, i_have_backup=False)

    applied = run_migrate_journal_v2(
        root, dry_run=False, apply=True, i_have_backup=True
    )
    assert applied["dry_run"] is False
    assert (root / "_capture" / "entries" / "2026" / "07" / f"{eid}.json").is_file()
    assert (root / "_attachments" / "2026" / "07" / "x.jpg").is_file()
    cfg = load_config(root)
    assert cfg.layout_version == CURRENT_LAYOUT_VERSION
    loaded = load_entry(root / "_capture" / "entries" / "2026" / "07" / f"{eid}.json")
    assert loaded is not None
    assert loaded.images == ["_attachments/2026/07/x.jpg"]
    assert loaded.filed is True
    assert (root / "40-Journal" / "2026-07-09.md").is_file()
