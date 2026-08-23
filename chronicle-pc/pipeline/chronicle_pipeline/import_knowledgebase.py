"""Import KnowledgeBase brain.json into Chronicle curation ops (MindMap)."""

from __future__ import annotations

import html
import json
import logging
import re
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from . import curation as curation_mod
from .paths import resolve_chronicle_dir

log = logging.getLogger("chronicle.import_knowledgebase")

_SLUG_RE = re.compile(r"[^a-z0-9]+")
_PROJECT_GROUP_HINTS = frozenset({"project", "projects"})


class _HTMLToText(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._parts: list[str] = []

    def handle_data(self, data: str) -> None:
        if data:
            self._parts.append(data)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in ("br", "p", "div", "li", "tr"):
            self._parts.append("\n")

    def get_text(self) -> str:
        return "".join(self._parts)


def default_kb_source() -> Path:
    """Resolve default KnowledgeBase brain.json via env or workspace-relative path."""
    import os

    env = (os.environ.get("CHRONICLE_KB_SOURCE") or "").strip()
    candidates: list[Path] = []
    if env:
        candidates.append(Path(env).expanduser())
    workspace = Path(__file__).resolve().parents[3]
    candidates.append(workspace / "KnowledgeBase" / "brain.json")
    for cand in candidates:
        if cand.is_file():
            return cand
    return candidates[0] if candidates else workspace / "KnowledgeBase" / "brain.json"


# Resolved at import for CLI help strings / tests (no hardcoded home path).
DEFAULT_KB_SOURCE = default_kb_source()


def sanitize_slug(raw: str) -> str:
    s = (raw or "").strip().lower()
    s = _SLUG_RE.sub("-", s).strip("-")
    return s or "unnamed"


def strip_html(text: str) -> str:
    if not text:
        return ""
    if "<" not in text:
        return html.unescape(text).strip()
    parser = _HTMLToText()
    try:
        parser.feed(text)
        parser.close()
    except Exception:  # noqa: BLE001
        return html.unescape(re.sub(r"<[^>]+>", " ", text)).strip()
    out = parser.get_text()
    out = html.unescape(out)
    out = re.sub(r"[ \t]+\n", "\n", out)
    out = re.sub(r"\n{3,}", "\n\n", out)
    out = re.sub(r"[ \t]{2,}", " ", out)
    return out.strip()


def annotation_text(node: dict[str, Any]) -> str:
    parts: list[str] = []
    desc = strip_html(str(node.get("desc") or ""))
    details = strip_html(str(node.get("details") or ""))
    if desc:
        parts.append(desc)
    if details and details != desc:
        parts.append(details)
    return "\n\n".join(parts).strip()


def is_project_node(node: dict[str, Any]) -> bool:
    nid = str(node.get("id") or "")
    if nid.startswith("proj_") or nid.startswith("project_"):
        return True
    group = str(node.get("group") or "").lower()
    if group in _PROJECT_GROUP_HINTS:
        return True
    return False


def chronicle_node_id(kb_node: dict[str, Any]) -> tuple[str, str]:
    """Return (chronicle_id, kind) for a KB node."""
    slug = sanitize_slug(str(kb_node.get("id") or ""))
    if is_project_node(kb_node):
        return f"project:{slug}", "project"
    return f"concept:{slug}", "concept"


def map_brain_to_ops(
    brain: dict[str, Any],
    *,
    device: str = "pc",
    ts: str | None = None,
    existing_ids: set[str] | None = None,
    existing_edges: set[tuple[str, str, str]] | None = None,
    existing_annotated: set[str] | None = None,
) -> list[dict[str, Any]]:
    """
    Map KnowledgeBase brain.json → Chronicle curation ops.

    Prefer create_concept + annotate + link so `chronicle brain` preserves them.
    Skips creates/links/annotates already present (idempotent-ish).
    """
    existing_ids = set(existing_ids or ())
    existing_edges = set(existing_edges or ())
    existing_annotated = set(existing_annotated or ())
    when = ts or datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")

    nodes = brain.get("nodes") or []
    links = brain.get("links") or []
    if not isinstance(nodes, list):
        nodes = []
    if not isinstance(links, list):
        links = []

    id_map: dict[str, str] = {}
    ops: list[dict[str, Any]] = []

    for raw in nodes:
        if not isinstance(raw, dict):
            continue
        kb_id = str(raw.get("id") or "").strip()
        if not kb_id:
            continue
        cid, _kind = chronicle_node_id(raw)
        id_map[kb_id] = cid
        label = str(raw.get("label") or kb_id).strip() or kb_id

        if cid not in existing_ids:
            create_op: dict[str, Any] = {
                "op": "create_concept",
                "ts": when,
                "device": device,
                "id": cid,
                "label": label,
            }
            group = str(raw.get("group") or "").strip()
            if group:
                create_op["group"] = group
            ops.append(create_op)
            existing_ids.add(cid)

        note = annotation_text(raw)
        if note and cid not in existing_annotated:
            ops.append(
                {
                    "op": "annotate",
                    "ts": when,
                    "device": device,
                    "node": cid,
                    "text": note,
                }
            )
            existing_annotated.add(cid)

    for link in links:
        if not isinstance(link, dict):
            continue
        src = str(link.get("source") or link.get("from") or "").strip()
        tgt = str(link.get("target") or link.get("to") or "").strip()
        if not src or not tgt:
            continue
        a = id_map.get(src)
        b = id_map.get(tgt)
        if not a or not b or a == b:
            continue
        key = (a, b, "manual")
        if key in existing_edges:
            continue
        ops.append(
            {
                "op": "link",
                "ts": when,
                "device": device,
                "from": a,
                "to": b,
                "rel": "manual",
            }
        )
        existing_edges.add(key)

    return ops


def _collect_existing(root: Path) -> tuple[set[str], set[tuple[str, str, str]], set[str]]:
    """Ids / edges / annotated nodes from ops logs + current graph.json."""
    ids: set[str] = set()
    edges: set[tuple[str, str, str]] = set()
    annotated: set[str] = set()

    for op in curation_mod.read_ops(root):
        kind = op.get("op")
        if kind == "create_concept" and op.get("id"):
            ids.add(str(op["id"]))
        elif kind == "annotate" and op.get("node"):
            annotated.add(str(op["node"]))
        elif kind == "link" and op.get("from") and op.get("to"):
            edges.add((str(op["from"]), str(op["to"]), str(op.get("rel") or "manual")))

    graph_path = root / "brain" / "graph.json"
    if graph_path.is_file():
        try:
            graph = json.loads(graph_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            log.warning("Cannot read graph for idempotency: %s", e)
            graph = None
        if isinstance(graph, dict):
            for n in graph.get("nodes") or []:
                if isinstance(n, dict) and n.get("id"):
                    nid = str(n["id"])
                    if nid.startswith(("concept:", "project:")):
                        ids.add(nid)
                    if n.get("annotation"):
                        annotated.add(nid)
            for e in graph.get("edges") or []:
                if isinstance(e, dict) and e.get("from") and e.get("to"):
                    edges.add(
                        (str(e["from"]), str(e["to"]), str(e.get("rel") or "related"))
                    )

    return ids, edges, annotated


def _has_entries(root: Path) -> bool:
    entries = root / "entries"
    if not entries.is_dir():
        return False
    return any(entries.rglob("*.json"))


def run_import_knowledgebase(
    chronicle_dir: Path | str | None = None,
    *,
    source: Path | str | None = None,
    dry_run: bool = False,
    apply: bool | None = None,
    device: str = "pc",
) -> dict[str, Any]:
    """
    Read KnowledgeBase brain.json and append missing curation ops to pc.jsonl.

    ``apply``: if True, run ``chronicle brain`` after write.
    If None (default), run brain when the vault already has entries.
    """
    root = resolve_chronicle_dir(chronicle_dir)
    src = Path(source).expanduser().resolve() if source else default_kb_source()
    if not src.is_file():
        raise FileNotFoundError(
            f"KnowledgeBase brain.json not found: {src}. "
            f"Pass --source or place brain.json at {DEFAULT_KB_SOURCE}"
        )

    brain = json.loads(src.read_text(encoding="utf-8"))
    if not isinstance(brain, dict):
        raise ValueError(f"Expected object in {src}")

    kb_nodes = [n for n in (brain.get("nodes") or []) if isinstance(n, dict) and n.get("id")]
    kb_links = [
        ln
        for ln in (brain.get("links") or [])
        if isinstance(ln, dict) and (ln.get("source") or ln.get("from"))
    ]

    existing_ids, existing_edges, existing_annotated = _collect_existing(root)
    ops = map_brain_to_ops(
        brain,
        device=device,
        existing_ids=existing_ids,
        existing_edges=existing_edges,
        existing_annotated=existing_annotated,
    )

    creates = [o for o in ops if o["op"] == "create_concept"]
    annotates = [o for o in ops if o["op"] == "annotate"]
    links = [o for o in ops if o["op"] == "link"]

    should_apply = bool(apply) if apply is not None else _has_entries(root)
    brain_result: dict[str, Any] | None = None

    if dry_run:
        log.info(
            "[dry-run] would append %d ops (%d create, %d annotate, %d link) from %s",
            len(ops),
            len(creates),
            len(annotates),
            len(links),
            src,
        )
    else:
        for op in ops:
            curation_mod.append_op(root, op, device=device)
        log.info(
            "Appended %d ops to curation/ops/%s.jsonl from %s",
            len(ops),
            device,
            src,
        )
        if should_apply and ops:
            from .brain import run_brain

            brain_result = run_brain(root, dry_run=False)
        elif should_apply and not ops:
            log.info("No new ops; skipping brain (nothing to apply)")

    return {
        "source": str(src),
        "chronicle_dir": str(root),
        "kb_nodes": len(kb_nodes),
        "kb_links": len(kb_links),
        "ops_appended": len(ops),
        "created": len(creates),
        "annotated": len(annotates),
        "linked": len(links),
        "skipped_existing": max(0, len(kb_nodes) - len(creates)),
        "dry_run": dry_run,
        "apply": should_apply and not dry_run,
        "brain": brain_result,
    }
