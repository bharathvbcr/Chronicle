"""Reflection prompts generation."""

from __future__ import annotations

import logging
from collections import defaultdict
from pathlib import Path
from typing import Any

from ..models import Entry
from ..paths import atomic_write_json
from .util import now_iso

log = logging.getLogger("chronicle.brain")


def build_prompts(
    root: Path,
    entries: list[Entry],
    enrich: dict[str, dict[str, Any]],
    *,
    dry_run: bool = False,
) -> Path:
    """Generate reflection prompts from recent themes."""
    tag_counts: dict[str, int] = defaultdict(int)
    for e in entries:
        for t in e.tags:
            if t.startswith("future:") or t.startswith("prompt:") or t == "#plan":
                continue
            tag_counts[t.lstrip("#").lower()] += 1
        for t in (enrich.get(e.id) or {}).get("auto_tags") or []:
            tag_counts[str(t).lower()] += 1

    top = sorted(tag_counts.keys(), key=lambda t: (-tag_counts[t], t))[:8]
    prompts = []
    templates = [
        ("reflect-{tag}", "What have you learned about {tag} lately?"),
        ("next-{tag}", "What is one small next step for {tag}?"),
    ]
    for tag in top:
        for tid, tmpl in templates:
            prompts.append(
                {
                    "id": tid.format(tag=tag.replace("/", "-")),
                    "text": tmpl.format(tag=tag),
                    "tag": tag,
                }
            )

    # Always include a few evergreen prompts
    evergreen = [
        {"id": "gratitude", "text": "What are you grateful for today?", "tag": None},
        {"id": "energy", "text": "What gave you energy this week?", "tag": None},
        {"id": "open-loop", "text": "What open loop is weighing on you?", "tag": None},
    ]
    for p in evergreen:
        if not any(x["id"] == p["id"] for x in prompts):
            prompts.append(p)

    path = root / "brain" / "prompts.json"
    payload = {"version": 1, "generated": now_iso(), "prompts": prompts[:24]}
    if not dry_run:
        atomic_write_json(path, payload)
    log.info("%s prompts.json (%d prompts)", "[dry-run]" if dry_run else "Wrote", len(payload["prompts"]))
    return path

