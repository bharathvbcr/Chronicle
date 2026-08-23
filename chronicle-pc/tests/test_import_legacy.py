"""import-legacy converts flat legacy entries to v1.2."""

from __future__ import annotations

import json
from pathlib import Path

from chronicle_pipeline.entries import load_all_entries
from chronicle_pipeline.import_legacy import convert_legacy_entry, run_import_legacy

ROOT = Path(__file__).resolve().parents[1]


def test_convert_legacy_drops_city_weather() -> None:
    raw = {
        "id": "2026-06-01_0915",
        "ts": "2026-06-01T09:15:00+05:30",
        "type": "log",
        "text": "hi",
        "tags": ["work"],
        "images": [],
        "mood": 3,
        "city": "Bengaluru",
        "weather": "sunny",
    }
    e = convert_legacy_entry(raw)
    assert e.id == "2026-06-01_091500-an"
    assert e.version == 1
    dumped = e.model_dump()
    assert "city" not in dumped
    assert "weather" not in dumped


def test_import_legacy_sample(tmp_path: Path) -> None:
    legacy = ROOT / "fixtures" / "legacy"
    dest = tmp_path / "Chronicle"
    dest.mkdir()
    (dest / "entries").mkdir()
    result = run_import_legacy(legacy, dest, dry_run=False)
    assert result["imported"]
    assert "2026-06-01_091500-an" in result["imported"]
    entries = load_all_entries(dest)
    assert any(e.id == "2026-06-01_091500-an" for e in entries)
    path = dest / "_capture" / "entries" / "2026" / "06" / "2026-06-01_091500-an.json"
    assert path.is_file()
    data = json.loads(path.read_text(encoding="utf-8"))
    assert "city" not in data
    assert data["processed"] is False


def test_import_legacy_dry_run(tmp_path: Path) -> None:
    legacy = ROOT / "fixtures" / "legacy"
    dest = tmp_path / "Chronicle"
    dest.mkdir()
    result = run_import_legacy(legacy, dest, dry_run=True)
    assert result["dry_run"] is True
    assert result["imported"]
    assert not list((dest / "entries").rglob("*.json")) if (dest / "entries").exists() else True
