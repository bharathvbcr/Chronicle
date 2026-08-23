"""Validate fixture entries against contract/entry.schema.json."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

try:
    import jsonschema
    from jsonschema import Draft202012Validator
except ImportError:  # pragma: no cover
    jsonschema = None

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "contract" / "entry.schema.json"
FIXTURES_DIR = ROOT / "fixtures" / "entries"


@pytest.fixture(scope="module")
def entry_schema() -> dict:
    with SCHEMA_PATH.open(encoding="utf-8") as f:
        return json.load(f)


def _fixture_paths() -> list[Path]:
    return sorted(FIXTURES_DIR.rglob("*.json"))


@pytest.mark.skipif(jsonschema is None, reason="jsonschema not installed")
def test_fixtures_exist() -> None:
    paths = _fixture_paths()
    assert len(paths) >= 3, f"expected ≥3 fixture entries, found {len(paths)}"


@pytest.mark.skipif(jsonschema is None, reason="jsonschema not installed")
@pytest.mark.parametrize("path", _fixture_paths(), ids=lambda p: p.name)
def test_fixture_validates_against_entry_schema(path: Path, entry_schema: dict) -> None:
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    Draft202012Validator(entry_schema).validate(data)


@pytest.mark.skipif(jsonschema is None, reason="jsonschema not installed")
def test_fixture_coverage_tags_mood_audio(entry_schema: dict) -> None:
    """One fixture with tags, one with mood, one with empty text + audio."""
    entries = []
    for path in _fixture_paths():
        with path.open(encoding="utf-8") as f:
            entries.append(json.load(f))

    assert any(e.get("tags") for e in entries), "need a fixture with tags"
    assert any(e.get("mood") is not None for e in entries), "need a fixture with mood"
    assert any(
        (e.get("text") == "") and e.get("audio") for e in entries
    ), "need a voice fixture with empty text + audio"
