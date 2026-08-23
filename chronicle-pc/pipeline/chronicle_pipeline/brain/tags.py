"""Tag taxonomy builder."""

from __future__ import annotations

import logging
from collections import defaultdict
from pathlib import Path
from typing import Any

from .. import curation as curation_mod
from ..models import Entry
from ..paths import atomic_write_json
from .util import SPECIAL_TAG_RE, now_iso

log = logging.getLogger("chronicle.brain")


def build_tags(
    root: Path,
    entries: list[Entry],
    enrich: dict[str, dict[str, Any]],
    *,
    dry_run: bool = False,
) -> Path:
    counts: dict[str, int] = defaultdict(int)
    aliases: dict[str, set[str]] = defaultdict(set)

    # Merge ops feed aliases
    for op in curation_mod.read_ops(root):
        if op.get("op") == "merge":
            src = (op.get("from") or "").removeprefix("topic:")
            dst = (op.get("into") or "").removeprefix("topic:")
            if src and dst:
                aliases[dst].add(src)

    for e in entries:
        for t in e.tags:
            if SPECIAL_TAG_RE.match(t) and t.startswith("future:"):
                continue
            if t.startswith("prompt:"):
                continue
            canonical = t.lstrip("#").lower() if t != "#plan" else "#plan"
            # Apply alias map (src -> dst)
            for dst, srcs in aliases.items():
                if canonical in srcs:
                    canonical = dst
                    break
            counts[canonical] += 1
        for t in (enrich.get(e.id) or {}).get("auto_tags") or []:
            counts[str(t).lower()] += 0  # register without double-count if unused
            # Count auto tags lightly
            counts[str(t).lower()] += 1

    tags_list = []
    for canonical in sorted(counts.keys()):
        parent = None
        if "/" in canonical:
            parent = canonical.split("/", 1)[0]
        tags_list.append(
            {
                "canonical": canonical,
                "aliases": sorted(aliases.get(canonical, [])),
                "parent": parent,
                "count": counts[canonical],
            }
        )

    path = root / "brain" / "tags.json"
    payload = {"version": 1, "generated": now_iso(), "tags": tags_list}
    if not dry_run:
        atomic_write_json(path, payload)
    log.info("%s tags.json (%d tags)", "[dry-run]" if dry_run else "Wrote", len(tags_list))
    return path

