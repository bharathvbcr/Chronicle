"""Production callers must use the helpers that used to have no inbound edge."""

from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from chronicle_pipeline import e2ee, path_map, vault_paths
from chronicle_pipeline.api.entries import _next_media_index
from chronicle_pipeline.api.kb import _move_note
from chronicle_pipeline.config import load_config
from chronicle_pipeline.entries import load_all_entries, load_entry, save_entry
from chronicle_pipeline.journal import upsert_entry_block
from chronicle_pipeline.link_repair import RepairResult
from chronicle_pipeline.markdown_index import collect_index_rows
from chronicle_pipeline.media_paths import MediaPathError, safe_media_path
from chronicle_pipeline.models import Entry
from chronicle_pipeline.notes import daily_note_path, regenerate_daily_for_days
from chronicle_pipeline.process import run_process


def test_load_config_reads_config_path(chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    missing = tmp_path / "other"
    missing.mkdir()
    monkeypatch.setattr(
        "chronicle_pipeline.config.config_path",
        lambda chronicle_dir=None: missing / "config.json",
    )
    cfg = load_config(chronicle_dir)
    assert cfg.timezone == "UTC"


def test_e2ee_load_reads_config_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block("passphrase"), tmp_path)
    missing = tmp_path / "missing-config"
    missing.mkdir()
    monkeypatch.setattr(
        "chronicle_pipeline.config.config_path",
        lambda chronicle_dir=None: missing / "config.json",
    )
    assert e2ee.load_e2ee_config(tmp_path) is None


def test_process_marks_processed_through_set_processed(
    chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    called = {"n": 0}
    real = __import__("chronicle_pipeline.entries", fromlist=["set_processed"]).set_processed

    def wrapped(root, entry, *, processed=True):
        called["n"] += 1
        return real(root, entry, processed=processed)

    monkeypatch.setattr("chronicle_pipeline.process.set_processed", wrapped)
    run_process(chronicle_dir, dry_run=False, run_brain=False)
    assert called["n"] >= 1


def test_daily_chrome_uses_entries_for_day(chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "chronicle_pipeline.notes.entries_for_day",
        lambda entries, day, *, fallback_tz="UTC": [],
    )
    day = date(2026, 7, 9)
    regenerate_daily_for_days(chronicle_dir, {day}, dry_run=False)
    text = (chronicle_dir / "_system" / "derived" / "daily" / "2026-07-09.md").read_text(
        encoding="utf-8"
    )
    assert "2026-07-09_090015-an" not in text


def test_journal_upsert_uses_journal_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    hits = {"n": 0}
    real = __import__("chronicle_pipeline.notes", fromlist=["journal_path"]).journal_path

    def wrapped(root, day):
        hits["n"] += 1
        return real(root, day)

    monkeypatch.setattr("chronicle_pipeline.notes.journal_path", wrapped)
    entry = Entry(
        id="2026-07-09_090015-an",
        ts="2026-07-09T09:00:15+05:30",
        type="log",
        text="wired",
    )
    upsert_entry_block(tmp_path, entry, day=date(2026, 7, 9))
    assert hits["n"] == 1


def test_load_all_entries_scans_iter_entry_roots(
    chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("chronicle_pipeline.entries.iter_entry_roots", lambda root: [])
    assert load_all_entries(chronicle_dir) == []


def test_index_walks_knowledge_roots(chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    note = chronicle_dir / "30-Knowledge" / "idea.md"
    note.parent.mkdir()
    note.write_text("# Idea\n", encoding="utf-8")
    monkeypatch.setattr(
        "chronicle_pipeline.path_map.knowledge_roots",
        lambda root, *, existing_only=True: [],
    )
    rows = collect_index_rows(chronicle_dir)
    assert not any(r["rel"].startswith("30-Knowledge/") for r in rows)


def test_assert_section_uses_path_allowed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "chronicle_pipeline.path_map.path_allowed_for_section",
        lambda rel, section: False,
    )
    with pytest.raises(ValueError):
        path_map.assert_path_allowed_for_section("30-Knowledge/a.md", "kb")


def test_daily_note_path_uses_derived_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "chronicle_pipeline.notes.derived_path",
        lambda root, *parts: root / "wired" / parts[-1],
    )
    assert daily_note_path(tmp_path, date(2026, 7, 9)) == tmp_path / "wired" / "2026-07-09.md"


def test_path_map_excludes_come_from_vault_paths() -> None:
    assert getattr(path_map, "machine_exclude_dirs", None) is vault_paths.machine_exclude_dirs
    assert path_map.MACHINE_EXCLUDE_DIRS == vault_paths.machine_exclude_dirs()


def test_media_rewrite_uses_preferred_attachment_rel(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        vault_paths,
        "preferred_attachment_rel",
        lambda yyyy, mm, name: f"pref/{yyyy}/{mm}/{name}",
    )
    assert (
        vault_paths.media_rewrite_legacy_to_attachments("img/2026/07/a.jpg")
        == "pref/2026/07/a.jpg"
    )


def test_attachment_resolve_uses_legacy_media_rel(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seen: list[str] = []
    real = vault_paths.legacy_media_rel

    def wrapped(kind, yyyy, mm, name):
        seen.append(kind)
        return real(kind, yyyy, mm, name)

    monkeypatch.setattr(vault_paths, "legacy_media_rel", wrapped)
    vault_paths.resolve_media_abs(tmp_path, "_attachments/2026/07/a.m4a")
    assert seen == ["img", "audio"]


def test_safe_media_classifies_with_legacy_and_attachment_helpers(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("chronicle_pipeline.media_paths.is_legacy_media", lambda rel: False)
    monkeypatch.setattr("chronicle_pipeline.media_paths.is_attachment_media", lambda rel: False)
    with pytest.raises(MediaPathError):
        safe_media_path(tmp_path, "img/2026/07/a.jpg")


def test_next_media_index_uses_attachments_dir(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seen: list[Path] = []
    real = vault_paths.attachments_dir

    def wrapped(root: Path) -> Path:
        seen.append(root)
        return real(root)

    monkeypatch.setattr("chronicle_pipeline.api.entries.attachments_dir", wrapped)
    _next_media_index(tmp_path, "img", "2026-07-09_090015-an", "jpg")
    assert seen == [tmp_path]


def test_move_note_reports_repair_as_dict(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    src = tmp_path / "30-Knowledge" / "a.md"
    src.parent.mkdir(parents=True)
    src.write_text("# A\n", encoding="utf-8")
    (tmp_path / "10-Work").mkdir()
    hits = {"n": 0}
    real = RepairResult.as_dict

    def wrapped(self):
        hits["n"] += 1
        return real(self)

    monkeypatch.setattr(RepairResult, "as_dict", wrapped)
    out = _move_note(tmp_path, "30-Knowledge/a.md", "10-Work/a.md")
    assert hits["n"] == 1
    assert out["links_repaired"] == 0


def test_load_entry_opens_text_through_open_entry_text(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block("passphrase"), tmp_path)
    e2ee.unlock(tmp_path, "passphrase")
    entry = Entry(
        id="2026-08-01_101010-an",
        ts="2026-08-01T10:10:10+05:30",
        type="log",
        text="secret",
    )
    path = save_entry(tmp_path, entry)
    monkeypatch.setattr(e2ee, "open_entry_text", lambda entry, root=None: "WIRED")
    loaded = load_entry(path, tmp_path)
    assert loaded is not None
    assert loaded.text == "WIRED"


def test_save_reseals_encrypted_entry_through_reseal_entry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block("passphrase"), tmp_path)
    e2ee.unlock(tmp_path, "passphrase")
    entry = Entry(
        id="2026-08-01_101010-an",
        ts="2026-08-01T10:10:10+05:30",
        type="log",
        text="secret",
    )
    path = save_entry(tmp_path, entry)
    loaded = load_entry(path, tmp_path)
    assert loaded is not None
    loaded.text = "edited"

    def boom(entry, root):
        raise RuntimeError("reseal")

    monkeypatch.setattr(e2ee, "reseal_entry", boom)
    with pytest.raises(RuntimeError, match="reseal"):
        save_entry(tmp_path, loaded)
