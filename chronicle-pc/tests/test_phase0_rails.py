"""Entry preserve-unknown, layout_version config, doctor vault-wide sync-conflicts."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from chronicle_pipeline.config import load_config
from chronicle_pipeline.doctor import _primary_name_for_conflict, run_doctor
from chronicle_pipeline.entries import load_entry, save_entry
from chronicle_pipeline.models import ChronicleConfig, Entry


def test_entry_preserves_unknown_custom_key_on_round_trip(tmp_path: Path) -> None:
    root = tmp_path / "vault"
    raw = {
        "version": 1,
        "id": "2026-07-09_213045-pc",
        "ts": "2026-07-09T21:30:45+05:30",
        "type": "log",
        "text": "hello",
        "tags": [],
        "images": [],
        "processed": False,
        "filed": True,
        "filed_path": "40-Journal/2026-07-09.md",
        "custom_future_key": "keep-me",
    }
    entry = Entry.model_validate(raw)
    assert entry.filed is True
    assert entry.filed_path == "40-Journal/2026-07-09.md"
    assert entry.model_extra is not None
    assert entry.model_extra.get("custom_future_key") == "keep-me"
    path = save_entry(root, entry)
    reloaded = load_entry(path)
    assert reloaded is not None
    dumped = reloaded.model_dump(mode="json")
    assert dumped.get("filed") is True
    assert dumped.get("filed_path") == "40-Journal/2026-07-09.md"
    assert dumped.get("custom_future_key") == "keep-me"
    on_disk = json.loads(path.read_text(encoding="utf-8"))
    assert on_disk["filed"] is True
    assert on_disk["filed_path"] == "40-Journal/2026-07-09.md"
    assert on_disk["custom_future_key"] == "keep-me"


def test_layout_version_missing_key_refused(tmp_path: Path) -> None:
    from chronicle_pipeline.vault_layout import CURRENT_LAYOUT_VERSION, LayoutVersionError

    root = tmp_path / "vault"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "timezone": "UTC"}),
        encoding="utf-8",
    )
    with pytest.raises(LayoutVersionError, match="missing required layout_version"):
        load_config(root)

    # In-memory model default remains 2 for new configs / ensure_config.
    bare = ChronicleConfig.model_validate({"version": 1, "timezone": "UTC"})
    assert bare.layout_version == CURRENT_LAYOUT_VERSION


def test_doctor_reports_markdown_sync_conflict_without_merge(tmp_path: Path) -> None:
    root = tmp_path / "vault"
    notes = root / "notes" / "daily"
    notes.mkdir(parents=True)
    conflict = notes / "2026-07-09.sync-conflict-20260711-120000-ABCD.md"
    conflict.write_text("# conflict copy\n", encoding="utf-8")
    primary = notes / "2026-07-09.md"
    primary.write_text("# primary\n", encoding="utf-8")

    report = run_doctor(root, dry_run=False, fix=True, compact_ops=False)
    rel = "notes/daily/2026-07-09.sync-conflict-20260711-120000-ABCD.md"
    assert rel in report["sync_conflicts"]
    # Markdown conflicts stay on disk even with --fix
    assert conflict.is_file()
    assert primary.read_text(encoding="utf-8") == "# primary\n"
    assert not any("promoted" in r or "quarantined" in r for r in report.get("repairs", []))


def test_doctor_primary_name_preserves_extension() -> None:
    md = Path("notes/daily/2026-07-09.sync-conflict-20260711-120000-ABCD.md")
    assert _primary_name_for_conflict(md) == "2026-07-09.md"
    entry = Path("entries/2026/07/2026-07-09_213045-an.sync-conflict-20260711.json")
    assert _primary_name_for_conflict(entry) == "2026-07-09_213045-an.json"


def test_doctor_repairs_json_conflict_with_fix(tmp_path: Path) -> None:
    root = tmp_path / "vault"
    shard = root / "entries" / "2026" / "07"
    shard.mkdir(parents=True)
    conflict = shard / "2026-07-09_213045-pc.sync-conflict-20260711.json"
    payload = {
        "version": 1,
        "id": "2026-07-09_213045-pc",
        "ts": "2026-07-09T21:30:45+05:30",
        "type": "log",
        "text": "from conflict",
        "tags": [],
        "images": [],
        "processed": False,
    }
    conflict.write_text(json.dumps(payload), encoding="utf-8")

    report = run_doctor(root, dry_run=False, fix=True, compact_ops=False)
    rel = "entries/2026/07/2026-07-09_213045-pc.sync-conflict-20260711.json"
    assert rel in report["sync_conflicts"] or any(
        "promoted" in r for r in report.get("repairs", [])
    )
    primary = shard / "2026-07-09_213045-pc.json"
    assert primary.is_file()
    assert not conflict.exists()
