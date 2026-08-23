"""Weekly / monthly / yearly note rollups."""

from __future__ import annotations

import logging
from collections import defaultdict
from datetime import date
from pathlib import Path

from .config import ensure_config
from .entries import entry_day, load_all_entries
from .notes import (
    mirror_note,
    render_monthly_note,
    render_weekly_note,
    render_yearly_note,
    week_start,
    write_if_changed,
)
from .paths import resolve_chronicle_dir

log = logging.getLogger("chronicle.rollup")


def run_rollup(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
) -> dict:
    root = resolve_chronicle_dir(chronicle_dir)
    cfg = ensure_config(root)
    entries = load_all_entries(root, fallback_tz=cfg.timezone)

    by_week: dict[date, list] = defaultdict(list)
    by_month: dict[tuple[int, int], list] = defaultdict(list)
    by_year: dict[int, list] = defaultdict(list)

    for e in entries:
        d = entry_day(e, fallback_tz=cfg.timezone)
        by_week[week_start(d)].append(e)
        by_month[(d.year, d.month)].append(e)
        by_year[d.year].append(e)

    written: list[str] = []

    for week, ents in sorted(by_week.items()):
        path = root / "_system" / "derived" / "weekly" / f"{week.isoformat()}.md"
        content = render_weekly_note(week, ents)
        if write_if_changed(path, content, dry_run=dry_run):
            written.append(str(path))
        if not dry_run and path.is_file():
            mirror_note(path, cfg.vault_mirror)

    for (y, m), ents in sorted(by_month.items()):
        path = root / "_system" / "derived" / "monthly" / f"{y:04d}-{m:02d}.md"
        content = render_monthly_note(y, m, ents)
        if write_if_changed(path, content, dry_run=dry_run):
            written.append(str(path))
        if not dry_run and path.is_file():
            mirror_note(path, cfg.vault_mirror)

    for y, ents in sorted(by_year.items()):
        path = root / "_system" / "derived" / "yearly" / f"{y:04d}.md"
        content = render_yearly_note(y, ents)
        if write_if_changed(path, content, dry_run=dry_run):
            written.append(str(path))
        if not dry_run and path.is_file():
            mirror_note(path, cfg.vault_mirror)

    log.info(
        "%s rollup: %d notes touched",
        "[dry-run]" if dry_run else "Wrote",
        len(written),
    )
    return {"written": written, "dry_run": dry_run}
