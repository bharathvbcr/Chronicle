"""Chronicle brain: enrich, tags, graph (+archive), insights, prompts, curation replay.

Public import surface matches the former ``brain.py`` module.
"""

from __future__ import annotations

import logging
from pathlib import Path

from ..config import ensure_config
from ..entries import load_all_entries
from ..lock import vault_process_lock
from ..paths import resolve_chronicle_dir
from .enrich import (
    build_enrich,
    enrich_entries_batch,
    enrich_entry,
    load_all_enrich,
)
from .graph import build_graph
from .insights import build_insights
from .prompts import build_prompts
from .tags import build_tags
from .util import (
    ENRICH_BATCH_SIZE,
    ENTITY_RE,
    LINK_BATCH_SIZE,
    SPECIAL_TAG_RE,
    WORD_RE,
)

log = logging.getLogger("chronicle.brain")

__all__ = [
    "ENRICH_BATCH_SIZE",
    "ENTITY_RE",
    "LINK_BATCH_SIZE",
    "SPECIAL_TAG_RE",
    "WORD_RE",
    "build_enrich",
    "build_graph",
    "build_insights",
    "build_prompts",
    "build_tags",
    "enrich_entries_batch",
    "enrich_entry",
    "load_all_enrich",
    "run_brain",
]


def run_brain(chronicle_dir: Path | str | None = None, *, dry_run: bool = False) -> dict:
    root = resolve_chronicle_dir(chronicle_dir)
    with vault_process_lock(root):
        return _run_brain(root, dry_run=dry_run)


def _run_brain(root: Path, *, dry_run: bool = False) -> dict:
    cfg = ensure_config(root)
    entries = load_all_entries(root, fallback_tz=cfg.timezone)
    log.info("Building brain from %d entries%s", len(entries), " (dry-run)" if dry_run else "")

    build_enrich(root, entries, dry_run=dry_run)
    enrich = load_all_enrich(root) if not dry_run else {
        e.id: enrich_entry(e, llm_model=cfg.models.llm) for e in entries
    }
    if not dry_run:
        enrich = load_all_enrich(root)

    build_tags(root, entries, enrich, dry_run=dry_run)
    build_graph(root, entries, enrich, dry_run=dry_run, fallback_tz=cfg.timezone)
    insights = build_insights(
        root, entries, enrich, dry_run=dry_run, fallback_tz=cfg.timezone
    )
    build_prompts(root, entries, enrich, dry_run=dry_run)

    return {
        "entries": len(entries),
        "insights": len(insights),
        "dry_run": dry_run,
    }
