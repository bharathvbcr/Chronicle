"""Shared test helpers — copy fixtures into a temp Chronicle root."""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures"


@pytest.fixture
def chronicle_dir(tmp_path: Path) -> Path:
    """Fresh Chronicle folder seeded from fixtures (entries + media + curation)."""
    dest = tmp_path / "Chronicle"
    shutil.copytree(FIXTURES / "entries", dest / "entries")
    if (FIXTURES / "img").exists():
        shutil.copytree(FIXTURES / "img", dest / "img")
    if (FIXTURES / "audio").exists():
        shutil.copytree(FIXTURES / "audio", dest / "audio")
    if (FIXTURES / "curation").exists():
        shutil.copytree(FIXTURES / "curation", dest / "curation")
    (dest / "notes").mkdir(parents=True, exist_ok=True)
    (dest / "brain").mkdir(parents=True, exist_ok=True)
    (dest / "config.json").write_text(
        '{\n  "version": 1,\n  "layout_version": 2,\n  "timezone": "Asia/Kolkata",\n  "models": {\n'
        '    "llm": "maxwell1500/ornith-35b:Q4_K_M",\n    "embed": "nomic-embed-text",\n'
        '    "vision": "llama3.2-vision:11b"\n  }\n}\n',
        encoding="utf-8",
    )
    return dest
