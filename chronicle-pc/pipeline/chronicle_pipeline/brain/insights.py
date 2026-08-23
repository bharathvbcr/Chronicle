"""Daily insights generation."""

from __future__ import annotations

import logging
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path
from typing import Any

from ..entries import entry_day
from ..models import Entry
from ..paths import atomic_write_json
from .util import WORD_RE, now_iso, summary_line

log = logging.getLogger("chronicle.brain")


def _token_set(text: str) -> set[str]:
    return {w.lower() for w in WORD_RE.findall(text or "")}


def _jaccard(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def build_insights(
    root: Path,
    entries: list[Entry],
    enrich: dict[str, dict[str, Any]],
    *,
    dry_run: bool = False,
    fallback_tz: str = "UTC",
) -> list[Path]:
    cfg_tz = fallback_tz
    by_day: dict[date, list[Entry]] = defaultdict(list)
    for e in entries:
        by_day[entry_day(e, fallback_tz=cfg_tz)].append(e)

    # Precompute token sets for relatedness
    tokens = {e.id: _token_set(e.text) | {t.lower() for t in e.tags} for e in entries}

    # Time capsules: future:YYYY-MM-DD tags
    capsules_by_due: dict[str, list[dict[str, str]]] = defaultdict(list)
    for e in entries:
        for t in e.tags:
            if t.startswith("future:"):
                due = t[7:]
                capsules_by_due[due].append(
                    {
                        "entry_id": e.id,
                        "due": due,
                        "text": summary_line(e.text, 100),
                    }
                )

    written: list[Path] = []
    generated = now_iso()

    for day, day_entries in sorted(by_day.items()):
        day_entries = sorted(day_entries, key=lambda e: (e.ts, e.id))
        moods = [e.mood for e in day_entries if e.mood is not None]
        themes: list[str] = []
        seen_t: set[str] = set()
        for e in day_entries:
            for t in e.tags:
                if t.startswith("future:") or t.startswith("prompt:"):
                    continue
                key = t.lstrip("#").lower() if t != "#plan" else "#plan"
                if key not in seen_t:
                    seen_t.add(key)
                    themes.append(key)
            for t in (enrich.get(e.id) or {}).get("auto_tags") or []:
                if t not in seen_t:
                    seen_t.add(t)
                    themes.append(t)

        summaries = [
            (enrich.get(e.id) or {}).get("summary_line") or summary_line(e.text)
            for e in day_entries
        ]
        summaries = [s for s in summaries if s]
        summary = "; ".join(summaries[:3]) if summaries else f"{len(day_entries)} entries"

        related: dict[str, list[str]] = {}
        for e in day_entries:
            scored: list[tuple[float, str]] = []
            for other in entries:
                if other.id == e.id:
                    continue
                score = _jaccard(tokens[e.id], tokens[other.id])
                # Boost shared tags
                shared = set(x.lower() for x in e.tags) & set(x.lower() for x in other.tags)
                score += 0.15 * len(shared)
                if score > 0.05:
                    scored.append((score, other.id))
            scored.sort(key=lambda x: (-x[0], x[1]))
            related[e.id] = [i for _, i in scored[:5]]

        # on_this_day: same month-day ~1 month / 1 year back
        on_this: list[str] = []
        candidates_dates = [
            day - timedelta(days=30),
            day - timedelta(days=365),
            day.replace(year=day.year - 1) if day.year > 1 else day,
        ]
        for cd in candidates_dates:
            for e in by_day.get(cd, []):
                on_this.append(e.id)
        # Also same calendar month-day any prior year
        for e in entries:
            ed = entry_day(e, fallback_tz=cfg_tz)
            if ed.month == day.month and ed.day == day.day and ed.year < day.year:
                if e.id not in on_this:
                    on_this.append(e.id)

        connections: list[Any] = []
        for e in day_entries:
            for rid in related.get(e.id, [])[:2]:
                connections.append(
                    {"from": e.id, "to": rid, "reason": "related"}
                )

        insight = {
            "version": 1,
            "date": day.isoformat(),
            "generated": generated,
            "summary": summary,
            "mood_avg": (sum(moods) / len(moods)) if moods else None,
            "themes": themes,
            "connections": connections[:10],
            "related_entries": related,
            "on_this_day": on_this[:10],
            "time_capsules": capsules_by_due.get(day.isoformat(), []),
        }
        path = root / "brain" / "insights" / f"{day.year:04d}" / f"{day.isoformat()}.json"
        if not dry_run:
            atomic_write_json(path, insight)
        written.append(path)

    log.info("%s %d insight files", "[dry-run]" if dry_run else "Wrote", len(written))
    return written

