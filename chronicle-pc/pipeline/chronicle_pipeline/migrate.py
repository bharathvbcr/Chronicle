"""Versioned migrations for Chronicle artifacts."""

from __future__ import annotations

import logging
from pathlib import Path

from .entries import load_all_entries, save_entry
from .paths import resolve_chronicle_dir

log = logging.getLogger("chronicle.migrate")

CURRENT_ENTRY_VERSION = 1


def run_migrate(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
) -> dict:
    """
    Bump / normalize entry versions toward CURRENT_ENTRY_VERSION.
    Currently v1 is the only version; this is a no-op pass that reports counts.
    """
    root = resolve_chronicle_dir(chronicle_dir)
    entries = load_all_entries(root)
    updated = 0
    for e in entries:
        if e.version != CURRENT_ENTRY_VERSION:
            log.info("Would migrate %s from v%d → v%d", e.id, e.version, CURRENT_ENTRY_VERSION)
            if not dry_run:
                e.version = CURRENT_ENTRY_VERSION
                save_entry(root, e)
            updated += 1
    log.info("migrate: %d entries updated (current version=%d)", updated, CURRENT_ENTRY_VERSION)
    return {
        "current_version": CURRENT_ENTRY_VERSION,
        "scanned": len(entries),
        "updated": updated,
        "dry_run": dry_run,
    }
