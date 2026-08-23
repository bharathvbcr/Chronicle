"""Shared helpers for brain package modules."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from pathlib import Path

SPECIAL_TAG_RE = re.compile(r"^(#plan|future:\d{4}-\d{2}-\d{2}|prompt:.+)$")
ENTITY_RE = re.compile(r"\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)+)\b")
WORD_RE = re.compile(r"[a-zA-Z][a-zA-Z0-9_/-]{2,}")

_AGENTS_DIR = Path(__file__).resolve().parent.parent / "agents"
ENRICH_BATCH_SIZE = 8
LINK_BATCH_SIZE = 24


def load_agent(name: str) -> str:
    path = _AGENTS_DIR / f"{name}.md"
    if path.is_file():
        return path.read_text(encoding="utf-8").strip()
    return ""


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def summary_line(text: str, max_len: int = 120) -> str:
    line = (text or "").strip().splitlines()
    if not line:
        return ""
    s = line[0].strip()
    return s if len(s) <= max_len else s[: max_len - 1] + "…"
