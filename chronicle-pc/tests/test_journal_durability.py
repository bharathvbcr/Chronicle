"""Day-file durability: a failed read must not clobber bytes, and a crash
between the fence write and mark_filed must recover on the next file_entry.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from chronicle_pipeline.entries import load_entry, save_entry
from chronicle_pipeline.journal import (
    file_entry,
    render_entry_block_body,
    upsert_entry_block,
    wrap_entry_fence,
)
from chronicle_pipeline.models import Entry
from chronicle_pipeline.paths import content_hash


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


def _vault(tmp_path: Path) -> Path:
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}),
        encoding="utf-8",
    )
    return root


def _day(root: Path) -> Path:
    return root / "40-Journal" / "2026-07-09.md"


def _seed_matching_fence(root: Path, entry: Entry) -> bytes:
    body = render_entry_block_body(entry)
    fence = wrap_entry_fence(entry.id, body)
    day = _day(root)
    day.parent.mkdir(parents=True)
    day.write_bytes(f"# 2026-07-09\n\n{fence}".encode())
    return day.read_bytes()


def test_crash_window_recovers_filed_stamp_without_force(tmp_path: Path) -> None:
    root = _vault(tmp_path)
    entry = _entry()
    save_entry(root, entry)
    before = _seed_matching_fence(root, entry)
    assert entry.filed is False
    assert not entry.filed_content_hash

    result = file_entry(root, entry, dry_run=False, force=False)

    assert result["action"] == "repair"
    assert result.get("skipped_reason") is None
    assert entry.filed is True
    assert entry.filed_content_hash == content_hash(render_entry_block_body(entry))
    assert _day(root).read_bytes() == before
    stored = load_entry(root / "_capture" / "entries" / "2026" / "07" / f"{entry.id}.json")
    assert stored is not None
    assert stored.filed is True
    assert stored.filed_content_hash == entry.filed_content_hash


def test_crash_window_recovers_filed_stamp_with_force(tmp_path: Path) -> None:
    root = _vault(tmp_path)
    entry = _entry()
    save_entry(root, entry)
    before = _seed_matching_fence(root, entry)

    result = file_entry(root, entry, dry_run=False, force=True)

    assert result["action"] == "repair"
    assert entry.filed is True
    assert entry.filed_content_hash == content_hash(render_entry_block_body(entry))
    assert _day(root).read_bytes() == before


def test_divergent_fence_without_filed_hash_is_not_overwritten(tmp_path: Path) -> None:
    root = _vault(tmp_path)
    entry = _entry()
    save_entry(root, entry)
    day = _day(root)
    day.parent.mkdir(parents=True)
    original = (
        "# 2026-07-09\n\n"
        f"<!-- entry:{entry.id} -->\n"
        "HUMAN PROSE KEEP ME\n"
        f"<!-- /entry:{entry.id} -->\n"
    ).encode()
    day.write_bytes(original)

    result = file_entry(root, entry, dry_run=False, force=True)

    assert result["action"] == "skip"
    assert result["skipped_reason"] == "missing_filed_content_hash"
    assert entry.filed is False
    assert not entry.filed_content_hash
    assert day.read_bytes() == original


def test_non_utf8_day_file_raises_and_keeps_bytes(tmp_path: Path) -> None:
    root = _vault(tmp_path)
    entry = _entry()
    day = _day(root)
    day.parent.mkdir(parents=True)
    marker = b"\xff\xfeKEEP-ME-NON-UTF8"
    day.write_bytes(marker)

    with pytest.raises(UnicodeDecodeError):
        upsert_entry_block(root, entry, dry_run=False, force=True)

    assert day.read_bytes() == marker
