"""Idempotent derived notes + file-once journal helpers.

Daily journal prose lives in ``40-Journal/`` (file-once, never whole-file regen).
Rollup/topic aggregates live under ``_system/derived/``.
Legacy ``notes/`` paths remain readable during dual-read.
"""

from __future__ import annotations

import logging
import re
import shutil
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

from .entries import entry_day, load_all_entries
from .journal import file_entries_for_days
from .models import Entry
from .paths import atomic_write_text, content_hash, resolve_chronicle_dir
from .vault_paths import DERIVED_DIR, JOURNAL_DIR

log = logging.getLogger("chronicle.notes")

MOOD_LABELS = {1: "low", 2: "uneasy", 3: "ok", 4: "good", 5: "great"}


def _stable_frontmatter(fields: dict[str, str]) -> str:
    lines = ["---"]
    for k in sorted(fields):
        v = fields[k].replace("\n", " ").strip()
        lines.append(f"{k}: {v}")
    lines.append("---")
    return "\n".join(lines)


def _wikilink(entry: Entry) -> str:
    return f"[[entry:{entry.id}]]"


def render_daily_chrome(
    day: date,
    day_entries: list[Entry],
) -> str:
    """Aggregates for ``_system/derived/daily/`` — not written over journal body."""
    day_entries = sorted(day_entries, key=lambda e: (e.ts, e.id))
    moods = [e.mood for e in day_entries if e.mood is not None]
    mood_avg = f"{sum(moods) / len(moods):.2f}" if moods else ""
    tags: set[str] = set()
    for e in day_entries:
        tags.update(e.tags)
    fm = {
        "date": day.isoformat(),
        "entries": str(len(day_entries)),
        "mood_avg": mood_avg,
        "tags": ", ".join(sorted(tags)),
    }
    body_parts = [_stable_frontmatter(fm), "", f"# {day.isoformat()} (derived)", ""]
    highlights = []
    for e in day_entries:
        line = (e.text or "").strip().splitlines()
        if line:
            highlights.append(f"- {line[0][:120]} ({_wikilink(e)})")
    if highlights:
        body_parts.append("## Highlights")
        body_parts.append("")
        body_parts.extend(highlights)
        body_parts.append("")
    body_parts.append("## Entries")
    body_parts.append("")
    for e in day_entries:
        body_parts.append(f"- {_wikilink(e)} · {e.type}")
    text = "\n".join(body_parts).rstrip() + "\n"
    return text


# Back-compat name used by older tests — now means derived chrome, not journal SoT.
def render_daily_note(
    day: date,
    day_entries: list[Entry],
    *,
    image_captions: dict[str, str] | None = None,
) -> str:
    del image_captions  # captions belong in journal file-once blocks
    return render_daily_chrome(day, day_entries)


def daily_note_path(root: Path, day: date) -> Path:
    """Derived daily chrome path (not 40-Journal)."""
    return root / DERIVED_DIR / "daily" / f"{day.isoformat()}.md"


def journal_path(root: Path, day: date) -> Path:
    return root / JOURNAL_DIR / f"{day.isoformat()}.md"


def write_if_changed(path: Path, content: str, *, dry_run: bool = False) -> bool:
    """Write only if content hash differs. Returns True if would write / wrote."""
    path = Path(path)
    new_hash = content_hash(content)
    if path.is_file():
        old = path.read_text(encoding="utf-8")
        if content_hash(old) == new_hash:
            return False
    if dry_run:
        return True
    atomic_write_text(path, content)
    return True


def mirror_note(src: Path, vault_mirror: str | Path | None, *, dry_run: bool = False) -> None:
    """One-way copy into vault_mirror, preserving relative notes/ or derived path.

    Disabled unless ``CHRONICLE_ALLOW_VAULT_MIRROR=1`` — ``vault_mirror`` is deprecated.
    """
    if not vault_mirror:
        return
    import os

    if os.environ.get("CHRONICLE_ALLOW_VAULT_MIRROR", "").strip() != "1":
        log.error(
            "vault_mirror is set (%s) but mirroring is disabled; "
            "set CHRONICLE_ALLOW_VAULT_MIRROR=1 to allow (deprecated)",
            vault_mirror,
        )
        return
    vault = Path(vault_mirror).expanduser()
    parts = list(src.parts)
    if "notes" in parts:
        idx = parts.index("notes")
        rel = Path(*parts[idx:])
    elif "_system" in parts and "derived" in parts:
        idx = parts.index("_system")
        rel = Path(*parts[idx:])
    else:
        rel = Path("notes") / src.name
    dest = vault / rel
    if dry_run:
        log.info("[dry-run] would mirror %s → %s", src, dest)
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)


def regenerate_daily_for_days(
    root: Path | str,
    days: set[date],
    *,
    image_captions: dict[str, str] | None = None,
    vault_mirror: str | None = None,
    dry_run: bool = False,
    fallback_tz: str = "UTC",
) -> list[Path]:
    """
    File-once into 40-Journal for processed entries on ``days``, and write
    derived daily chrome under ``_system/derived/daily/``.
    """
    root = resolve_chronicle_dir(root)
    all_entries = load_all_entries(root, fallback_tz=fallback_tz)
    by_day: dict[date, list[Entry]] = defaultdict(list)
    for e in all_entries:
        by_day[entry_day(e, fallback_tz=fallback_tz)].append(e)

    # File-once journal blocks (prose SoT)
    file_entries_for_days(
        root,
        all_entries,
        days=days,
        image_captions=image_captions,
        dry_run=dry_run,
        fallback_tz=fallback_tz,
    )

    written: list[Path] = []
    for day in sorted(days):
        path = daily_note_path(root, day)
        content = render_daily_chrome(day, by_day.get(day, []))
        if write_if_changed(path, content, dry_run=dry_run):
            written.append(path)
            log.info(
                "%s derived daily %s",
                "[dry-run] would write" if dry_run else "Wrote",
                path,
            )
        if not dry_run and path.is_file():
            mirror_note(path, vault_mirror, dry_run=dry_run)
        elif dry_run:
            mirror_note(path, vault_mirror, dry_run=True)
    return written


def week_start(d: date) -> date:
    return d - timedelta(days=d.weekday())  # Monday


def render_weekly_note(week: date, entries: list[Entry]) -> str:
    end = week + timedelta(days=6)
    entries = sorted(entries, key=lambda e: (e.ts, e.id))
    moods = [e.mood for e in entries if e.mood is not None]
    tags: dict[str, int] = defaultdict(int)
    for e in entries:
        for t in e.tags:
            tags[t] += 1
    themes = sorted(tags.keys(), key=lambda t: (-tags[t], t))[:12]
    fm = {
        "week_start": week.isoformat(),
        "week_end": end.isoformat(),
        "entries": str(len(entries)),
        "mood_avg": f"{sum(moods) / len(moods):.2f}" if moods else "",
        "themes": ", ".join(themes),
    }
    lines = [
        _stable_frontmatter(fm),
        "",
        f"# Week of {week.isoformat()}",
        "",
        "## Themes",
        "",
    ]
    for t in themes:
        lines.append(f"- {t} ({tags[t]})")
    if moods:
        lines.extend(["", "## Mood trend", "", f"- average: {sum(moods) / len(moods):.2f}"])
        by_d: dict[str, list[int]] = defaultdict(list)
        for e in entries:
            if e.mood is not None:
                by_d[e.ts[:10]].append(e.mood)
        for d in sorted(by_d):
            avg = sum(by_d[d]) / len(by_d[d])
            lines.append(f"- {d}: {avg:.1f}")
    lines.extend(["", "## Entries", ""])
    for e in entries:
        preview = (e.text or "").strip().splitlines()
        preview_s = preview[0][:100] if preview else "(no text)"
        lines.append(f"- {e.id} · {e.type}: {preview_s}")
    return "\n".join(lines).rstrip() + "\n"


def render_monthly_note(year: int, month: int, entries: list[Entry]) -> str:
    entries = sorted(entries, key=lambda e: (e.ts, e.id))
    moods = [e.mood for e in entries if e.mood is not None]
    tags: dict[str, int] = defaultdict(int)
    for e in entries:
        for t in e.tags:
            tags[t] += 1
    themes = sorted(tags.keys(), key=lambda t: (-tags[t], t))[:20]
    label = f"{year:04d}-{month:02d}"
    fm = {
        "month": label,
        "entries": str(len(entries)),
        "mood_avg": f"{sum(moods) / len(moods):.2f}" if moods else "",
        "themes": ", ".join(themes),
    }
    lines = [
        _stable_frontmatter(fm),
        "",
        f"# {label}",
        "",
        "## Themes",
        "",
    ]
    for t in themes:
        lines.append(f"- {t} ({tags[t]})")
    if moods:
        lines.extend(["", "## Mood", "", f"average: {sum(moods) / len(moods):.2f}"])
    lines.extend(["", f"{len(entries)} entries this month.", ""])
    return "\n".join(lines).rstrip() + "\n"


def render_yearly_note(year: int, entries: list[Entry]) -> str:
    entries = sorted(entries, key=lambda e: (e.ts, e.id))
    moods = [e.mood for e in entries if e.mood is not None]
    tags: dict[str, int] = defaultdict(int)
    types: dict[str, int] = defaultdict(int)
    for e in entries:
        types[e.type] += 1
        for t in e.tags:
            tags[t] += 1
    themes = sorted(tags.keys(), key=lambda t: (-tags[t], t))[:30]
    fm = {
        "year": str(year),
        "entries": str(len(entries)),
        "mood_avg": f"{sum(moods) / len(moods):.2f}" if moods else "",
    }
    lines = [
        _stable_frontmatter(fm),
        "",
        f"# {year}",
        "",
        "## By type",
        "",
    ]
    for t in sorted(types):
        lines.append(f"- {t}: {types[t]}")
    lines.extend(["", "## Top themes", ""])
    for t in themes:
        lines.append(f"- {t} ({tags[t]})")
    return "\n".join(lines).rstrip() + "\n"


_SLUG_RE = re.compile(r"[^a-z0-9]+")


def topic_slug(tag: str) -> str:
    s = tag.lstrip("#").lower().strip()
    s = _SLUG_RE.sub("-", s).strip("-")
    return s or "untagged"
