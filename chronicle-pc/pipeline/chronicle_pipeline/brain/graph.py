"""Graph + archive construction and LLM linking."""

from __future__ import annotations

import json
import logging
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from .. import curation as curation_mod
from .. import llm
from ..config import ensure_config
from ..entries import entry_day
from ..models import Entry
from ..paths import atomic_write_json
from .util import LINK_BATCH_SIZE, load_agent, now_iso, summary_line

log = logging.getLogger("chronicle.brain")


def _topic_id(tag: str) -> str:
    return f"topic:{tag.lstrip('#').lower()}"


def _entry_node(entry: Entry) -> dict[str, Any]:
    label = summary_line(entry.text, 60) or entry.id
    return {
        "id": f"entry:{entry.id}",
        "kind": "entry",
        "label": label,
        "entry_id": entry.id,
        "ts": entry.ts,
        "weight": 1.0,
    }


def _llm_concept_links(
    entries: list[Entry],
    enrich: dict[str, dict[str, Any]],
    existing_concepts: list[dict[str, Any]],
    *,
    llm_model: str | None = None,
    batch_size: int = LINK_BATCH_SIZE,
    chronicle_dir: Path | str | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Structured-output concept extraction + linking; returns (concepts, links)."""
    system = load_agent("brain_link")
    cfg = ensure_config(chronicle_dir)
    provider = llm.try_get_provider(cfg)
    if not system or provider is None or not provider.reachable() or not entries:
        return [], []
    limits = llm.context_limits(llm.provider_name(cfg))

    new_concepts: list[dict[str, Any]] = []
    links: list[dict[str, Any]] = []
    concept_seed = [
        {
            "id": c["id"],
            "label": c.get("label") or c["id"],
            "kind": c.get("kind") or "concept",
        }
        for c in existing_concepts
        if c.get("id")
    ]

    for i in range(0, len(entries), batch_size):
        chunk = entries[i : i + batch_size]
        payload = {
            "entries": [
                {
                    "id": e.id,
                    "summary": (enrich.get(e.id) or {}).get("summary_line")
                    or summary_line(e.text, 160),
                    "tags": list(e.tags),
                    "entities": (enrich.get(e.id) or {}).get("entities") or [],
                }
                for e in chunk
            ],
            "concepts": concept_seed[:80],
        }
        out = provider.try_chat(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(payload)},
            ],
            model=llm_model,
            format_json=True,
            temperature=0.6,
            num_predict=1800,
            num_ctx=limits.num_ctx_brain,
        )
        if not out:
            continue
        try:
            data = llm.extract_json(out)
        except (ValueError, TypeError) as e:
            log.debug("brain link parse failed: %s", e)
            continue
        if not isinstance(data, dict):
            continue
        for c in data.get("concepts") or []:
            if not isinstance(c, dict) or not c.get("id") or not c.get("label"):
                continue
            kind = str(c.get("kind") or "concept")
            if kind not in ("person", "place", "project", "concept", "topic"):
                kind = "concept"
            node = {
                "id": str(c["id"]),
                "kind": kind if kind != "topic" else "topic",
                "label": str(c["label"]),
                "weight": float(c.get("weight") or 1.0),
            }
            new_concepts.append(node)
            concept_seed.append(
                {"id": node["id"], "label": node["label"], "kind": node["kind"]}
            )
        for link in data.get("links") or []:
            if not isinstance(link, dict):
                continue
            frm = str(link.get("from") or "").strip()
            to = str(link.get("to") or "").strip()
            rel = str(link.get("rel") or "related").strip()
            if not frm or not to:
                continue
            if rel not in ("about", "related", "continues", "mentions", "manual"):
                rel = "related"
            try:
                score = float(link.get("score") if link.get("score") is not None else 0.6)
            except (TypeError, ValueError):
                score = 0.6
            if score < 0.5:
                continue
            links.append({"from": frm, "to": to, "rel": rel, "score": min(score, 1.0)})
    return new_concepts, links


def build_graph(
    root: Path,
    entries: list[Entry],
    enrich: dict[str, dict[str, Any]],
    *,
    dry_run: bool = False,
    fallback_tz: str = "UTC",
) -> tuple[Path, list[Path]]:
    """Build graph.json + graph-archive/yyyy.json. Returns (graph_path, archive_paths)."""
    cfg = ensure_config(root)
    now = datetime.now(timezone.utc).date()
    cutoff = now - timedelta(days=365)

    nodes: dict[str, dict[str, Any]] = {}
    edges: list[dict[str, Any]] = []
    archive_nodes: dict[int, dict[str, dict[str, Any]]] = defaultdict(dict)

    # Topic / entity nodes from tags + enrich
    tag_weights: dict[str, float] = defaultdict(float)
    for e in entries:
        for t in e.tags:
            if t.startswith("future:") or t.startswith("prompt:"):
                continue
            key = t.lstrip("#").lower() if t != "#plan" else "#plan"
            tag_weights[key] += 1.0
        for t in (enrich.get(e.id) or {}).get("auto_tags") or []:
            tag_weights[str(t).lower()] += 0.5
        for ent in (enrich.get(e.id) or {}).get("entities") or []:
            if isinstance(ent, dict) and ent.get("name"):
                kind = ent.get("kind") or "concept"
                if kind not in ("person", "place", "project", "concept", "topic"):
                    kind = "concept"
                eid = f"{kind}:{ent['name'].lower().replace(' ', '-')}"
                nodes[eid] = {
                    "id": eid,
                    "kind": kind if kind != "topic" else "topic",
                    "label": ent["name"],
                    "weight": (nodes.get(eid) or {}).get("weight", 0) + 1,
                }
                edges.append(
                    {
                        "from": f"entry:{e.id}",
                        "to": eid,
                        "rel": "mentions",
                        "score": 0.7,
                    }
                )

    for tag, w in tag_weights.items():
        tid = _topic_id(tag)
        nodes[tid] = {"id": tid, "kind": "topic", "label": tag, "weight": w}

    # Entry nodes + about edges; archive older
    plan_entries: list[Entry] = []
    for e in entries:
        day = entry_day(e, fallback_tz=fallback_tz)
        node = _entry_node(e)
        if day >= cutoff or "#plan" in e.tags:
            nodes[node["id"]] = node
        else:
            archive_nodes[day.year][node["id"]] = node

        for t in e.tags:
            if t.startswith("future:") or t.startswith("prompt:"):
                continue
            key = t.lstrip("#").lower() if t != "#plan" else "#plan"
            edges.append(
                {
                    "from": f"entry:{e.id}",
                    "to": _topic_id(key),
                    "rel": "about",
                    "score": 1.0,
                }
            )
            if t == "#plan":
                plan_entries.append(e)

        for t in (enrich.get(e.id) or {}).get("auto_tags") or []:
            edges.append(
                {
                    "from": f"entry:{e.id}",
                    "to": _topic_id(str(t).lower()),
                    "rel": "about",
                    "score": 0.5,
                }
            )

    # continues edges: #plan → later same-tag progress
    plan_by_tag: dict[str, list[Entry]] = defaultdict(list)
    for e in entries:
        if "#plan" in e.tags:
            for t in e.tags:
                if t != "#plan" and not t.startswith("future:") and not t.startswith("prompt:"):
                    plan_by_tag[t.lstrip("#").lower()].append(e)

    for e in entries:
        if "#plan" in e.tags:
            continue
        for t in e.tags:
            key = t.lstrip("#").lower()
            for plan in plan_by_tag.get(key, []):
                if plan.ts < e.ts:
                    edges.append(
                        {
                            "from": f"entry:{plan.id}",
                            "to": f"entry:{e.id}",
                            "rel": "continues",
                            "score": 0.8,
                        }
                    )

    # Structured-output linking (Ornith); shared-tag related is offline fallback
    seed_concepts = [
        n
        for n in nodes.values()
        if n.get("kind") in ("concept", "project", "person", "place")
    ]
    llm_concepts, llm_links = _llm_concept_links(
        entries,
        enrich,
        seed_concepts,
        llm_model=cfg.models.llm,
        chronicle_dir=root,
    )
    for c in llm_concepts:
        cid = c["id"]
        if cid in nodes:
            nodes[cid]["weight"] = float(nodes[cid].get("weight") or 0) + float(
                c.get("weight") or 1
            )
            if c.get("label"):
                nodes[cid]["label"] = c["label"]
        else:
            nodes[cid] = c
    if llm_links:
        edges.extend(llm_links)
        log.info(
            "LLM graph linking added %d concepts, %d links",
            len(llm_concepts),
            len(llm_links),
        )
    else:
        by_tag: dict[str, list[str]] = defaultdict(list)
        for e in entries:
            for t in e.tags:
                if t.startswith("future:") or t.startswith("prompt:") or t == "#plan":
                    continue
                by_tag[t.lstrip("#").lower()].append(e.id)
        related_seen: set[tuple[str, str]] = set()
        for ids in by_tag.values():
            ids = sorted(set(ids))
            for i, a in enumerate(ids):
                for b in ids[i + 1 : i + 4]:
                    pair = (a, b) if a < b else (b, a)
                    if pair in related_seen:
                        continue
                    related_seen.add(pair)
                    edges.append(
                        {
                            "from": f"entry:{a}",
                            "to": f"entry:{b}",
                            "rel": "related",
                            "score": 0.4,
                        }
                    )

    # Deduplicate edges
    edge_map: dict[tuple[str, str, str], dict[str, Any]] = {}
    for ed in edges:
        # Skip edges whose endpoints aren't in main graph (archive entries)
        if ed["from"].startswith("entry:") and ed["from"] not in nodes:
            # keep if other end is topic — still useful? skip for cleanliness
            if ed["to"] not in nodes and not ed["to"].startswith("topic:"):
                continue
        key = (ed["from"], ed["to"], ed["rel"])
        prev = edge_map.get(key)
        if prev is None or (ed.get("score") or 0) > (prev.get("score") or 0):
            edge_map[key] = ed

    graph = {
        "version": 1,
        "generated": now_iso(),
        "nodes": sorted(nodes.values(), key=lambda n: n["id"]),
        "edges": sorted(edge_map.values(), key=lambda e: (e["from"], e["to"], e["rel"])),
    }

    # Preserve KB group defs from a prior migrate-kb (not regenerable from entries)
    existing_path = root / "brain" / "graph.json"
    if existing_path.is_file():
        try:
            prev = json.loads(existing_path.read_text(encoding="utf-8"))
            if isinstance(prev, dict) and isinstance(prev.get("groups"), dict) and prev["groups"]:
                graph["groups"] = prev["groups"]
        except (OSError, json.JSONDecodeError):
            pass

    # Curation replay LAST
    ops = curation_mod.read_ops(root)
    if ops:
        graph = curation_mod.apply_ops_to_graph(graph, ops)
        log.info("Replayed %d curation ops", len(ops))

    # Re-stamp KB groups (create_concept.group + durable brain/kb_meta.json)
    for op in ops:
        if op.get("op") != "create_concept":
            continue
        cid = op.get("id")
        group = op.get("group")
        if not cid or not group:
            continue
        for n in graph.get("nodes") or []:
            if isinstance(n, dict) and n.get("id") == cid:
                n["group"] = str(group)
                break
    try:
        from ..migrate_kb import load_and_stamp_kb_meta

        load_and_stamp_kb_meta(root, graph)
    except Exception as e:  # noqa: BLE001 — kb_meta is optional migrate artifact
        log.warning("kb_meta stamp skipped: %s", e)

    graph_path = root / "brain" / "graph.json"
    if not dry_run:
        atomic_write_json(graph_path, graph)

    archive_paths: list[Path] = []
    for year, nmap in sorted(archive_nodes.items()):
        ap = root / "brain" / "graph-archive" / f"{year}.json"
        payload = {
            "version": 1,
            "generated": now_iso(),
            "nodes": sorted(nmap.values(), key=lambda n: n["id"]),
            "edges": [],
        }
        if not dry_run:
            atomic_write_json(ap, payload)
        archive_paths.append(ap)

    log.info(
        "%s graph.json (%d nodes, %d edges)",
        "[dry-run]" if dry_run else "Wrote",
        len(graph["nodes"]),
        len(graph["edges"]),
    )
    return graph_path, archive_paths

