"""Entry enrichment (heuristic + LLM batch)."""

from __future__ import annotations

import json
import logging
from collections import defaultdict
from pathlib import Path
from typing import Any

from .. import llm
from ..config import ensure_config
from ..entries import entry_day
from ..models import Entry
from ..paths import atomic_write_json
from .util import (
    ENRICH_BATCH_SIZE,
    ENTITY_RE,
    SPECIAL_TAG_RE,
    load_agent,
    now_iso,
    summary_line,
)

log = logging.getLogger("chronicle.brain")


def _heuristic_auto_tags(entry: Entry) -> list[str]:
    tags = set(entry.tags)
    text = (entry.text or "").lower()
    # Light keyword hints when offline
    hints = {
        "work": ("work", "meeting", "deadline", "project", "chronicle"),
        "health": ("walk", "run", "gym", "sleep", "health"),
        "dream": ("dream", "nightmare", "lucid"),
        "idea": ("idea", "sketch", "prototype"),
    }
    if entry.type == "dream":
        tags.add("dream")
    if entry.type == "idea":
        tags.add("idea")
    for tag, words in hints.items():
        if any(w in text for w in words):
            tags.add(tag)
    # Drop special convention tags from auto_tags (they're already on entry)
    return sorted(t for t in tags if not SPECIAL_TAG_RE.match(t) and t not in entry.tags)


def _heuristic_entities(entry: Entry) -> list[dict[str, str]]:
    entities: list[dict[str, str]] = []
    seen: set[str] = set()
    for m in ENTITY_RE.finditer(entry.text or ""):
        name = m.group(1).strip()
        if name.lower() in seen:
            continue
        seen.add(name.lower())
        entities.append({"name": name, "kind": "person"})
    return entities[:8]


def _normalize_enrichment(data: dict[str, Any], fallback: dict[str, Any]) -> dict[str, Any]:
    out = dict(fallback)
    if isinstance(data.get("auto_tags"), list):
        out["auto_tags"] = sorted(
            {str(t).strip().lower() for t in data["auto_tags"] if str(t).strip()}
        )
    if isinstance(data.get("summary_line"), str) and data["summary_line"].strip():
        out["summary_line"] = data["summary_line"].strip()[:200]
    if isinstance(data.get("entities"), list):
        ents: list[dict[str, str]] = []
        for e in data["entities"]:
            if isinstance(e, dict) and e.get("name"):
                ents.append(
                    {
                        "name": str(e["name"]),
                        "kind": str(e.get("kind") or "topic"),
                    }
                )
            elif isinstance(e, str) and e.strip():
                ents.append({"name": e.strip(), "kind": "topic"})
        if ents:
            out["entities"] = ents[:12]
    return out


def _heuristic_enrich(entry: Entry) -> dict[str, Any]:
    return {
        "auto_tags": _heuristic_auto_tags(entry),
        "summary_line": summary_line(entry.text),
        "entities": _heuristic_entities(entry),
    }


def enrich_entry(
    entry: Entry,
    *,
    llm_model: str | None = None,
    chronicle_dir: Path | str | None = None,
) -> dict[str, Any]:
    """Build enrichment for one entry; uses LLM when available (single-entry path)."""
    base = _heuristic_enrich(entry)
    if not (entry.text or "").strip():
        return base
    cfg = ensure_config(chronicle_dir)
    provider = llm.try_get_provider(cfg)
    if provider is None or not provider.reachable():
        return base
    limits = llm.context_limits(llm.provider_name(cfg))
    system = load_agent("brain_extract") or (
        "Extract journal enrichment as JSON with keys: "
        "auto_tags (string array of short lowercase tags), "
        "summary_line (one short sentence), "
        "entities (array of {name, kind} where kind is person|place|project|topic|concept). "
        "Be conservative; do not invent people. Return JSON only after thinking."
    )
    # Single-entry call still uses the batch schema when agent prompt expects it
    user_payload = [
        {
            "id": entry.id,
            "text": (entry.text or "")[:4000],
            "tags": list(entry.tags),
            "type": entry.type,
        }
    ]
    out = provider.try_chat(
        [
            {"role": "system", "content": system},
            {"role": "user", "content": json.dumps(user_payload)},
        ],
        model=llm_model,
        format_json=True,
        temperature=0.6,
        num_predict=600,
        num_ctx=limits.num_ctx_enrich,
    )
    if not out:
        return base
    try:
        data = llm.extract_json(out)
        if isinstance(data, dict):
            entries_map = data.get("entries")
            if isinstance(entries_map, dict) and entry.id in entries_map:
                row = entries_map[entry.id]
                if isinstance(row, dict):
                    return _normalize_enrichment(row, base)
            # Tolerate flat single-object responses
            if "auto_tags" in data or "summary_line" in data or "entities" in data:
                return _normalize_enrichment(data, base)
    except (ValueError, TypeError) as e:
        log.debug("enrich parse failed for %s: %s", entry.id, e)
    return base


def enrich_entries_batch(
    entries: list[Entry],
    *,
    llm_model: str | None = None,
    batch_size: int = ENRICH_BATCH_SIZE,
    chronicle_dir: Path | str | None = None,
) -> dict[str, dict[str, Any]]:
    """Batch structured-output enrichment; falls back to heuristics per entry."""
    results: dict[str, dict[str, Any]] = {
        e.id: _heuristic_enrich(e) for e in entries
    }
    cfg = ensure_config(chronicle_dir)
    provider = llm.try_get_provider(cfg)
    if not entries or provider is None or not provider.reachable():
        return results
    limits = llm.context_limits(llm.provider_name(cfg))

    system = load_agent("brain_extract")
    if not system:
        for e in entries:
            results[e.id] = enrich_entry(
                e, llm_model=llm_model, chronicle_dir=chronicle_dir
            )
        return results

    for i in range(0, len(entries), batch_size):
        chunk = entries[i : i + batch_size]
        payload = [
            {
                "id": e.id,
                "text": (e.text or "")[:3500],
                "tags": list(e.tags),
                "type": e.type,
            }
            for e in chunk
            if (e.text or "").strip()
        ]
        if not payload:
            continue
        out = provider.try_chat(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(payload)},
            ],
            model=llm_model,
            format_json=True,
            temperature=0.6,
            num_predict=min(400 * len(payload), 3200),
            num_ctx=limits.num_ctx_brain,
        )
        if not out:
            continue
        try:
            data = llm.extract_json(out)
        except (ValueError, TypeError) as e:
            log.debug("batch enrich parse failed: %s", e)
            continue
        if not isinstance(data, dict):
            continue
        entries_map = data.get("entries")
        if not isinstance(entries_map, dict):
            continue
        for e in chunk:
            row = entries_map.get(e.id)
            if isinstance(row, dict):
                results[e.id] = _normalize_enrichment(row, results[e.id])
    return results


def build_enrich(root: Path, entries: list[Entry], *, dry_run: bool = False) -> dict[str, Path]:
    cfg = ensure_config(root)
    enriched = enrich_entries_batch(entries, llm_model=cfg.models.llm, chronicle_dir=root)
    by_month: dict[str, dict[str, Any]] = defaultdict(dict)
    for e in entries:
        day = entry_day(e, fallback_tz=cfg.timezone)
        month = f"{day.year:04d}-{day.month:02d}"
        by_month[month][e.id] = enriched.get(e.id) or _heuristic_enrich(e)

    written: dict[str, Path] = {}
    generated = now_iso()
    for month, emap in sorted(by_month.items()):
        path = root / "brain" / "enrich" / f"{month}.json"
        payload = {
            "version": 1,
            "generated": generated,
            "month": month,
            "entries": emap,
        }
        if not dry_run:
            atomic_write_json(path, payload)
        written[month] = path
        log.info("%s enrich %s (%d entries)", "[dry-run]" if dry_run else "Wrote", month, len(emap))
    return written


def load_all_enrich(root: Path) -> dict[str, dict[str, Any]]:
    enrich_dir = root / "brain" / "enrich"
    out: dict[str, dict[str, Any]] = {}
    if not enrich_dir.is_dir():
        return out
    for path in sorted(enrich_dir.glob("*.json")):
        try:
            from .paths import read_json

            data = read_json(path)
            for eid, en in (data.get("entries") or {}).items():
                out[eid] = en
        except Exception as e:  # noqa: BLE001 — enrich files may be partial/corrupt
            log.warning("Bad enrich file %s: %s", path, e)
    return out

