"""Curation ops: read, replay (last-write-wins), compact."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from .paths import atomic_write_text, resolve_chronicle_dir

log = logging.getLogger("chronicle.curation")


def ops_dir(root: Path) -> Path:
    return root / "curation" / "ops"


def list_ops_files(root: Path) -> list[Path]:
    d = ops_dir(root)
    if not d.is_dir():
        return []
    return sorted(d.glob("*.jsonl"))


def read_ops(root: Path | str) -> list[dict[str, Any]]:
    """Read all device ops logs, sorted by ts then file order."""
    root = resolve_chronicle_dir(root)
    ops: list[dict[str, Any]] = []
    for path in list_ops_files(root):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as e:
            log.warning("Cannot read %s: %s", path, e)
            continue
        for i, line in enumerate(text.splitlines(), 1):
            line = line.strip()
            if not line:
                continue
            try:
                op = json.loads(line)
            except json.JSONDecodeError as e:
                log.warning("Bad JSON in %s:%d: %s", path.name, i, e)
                continue
            if isinstance(op, dict):
                op.setdefault("_source", path.name)
                ops.append(op)
    ops.sort(key=lambda o: (o.get("ts") or "", o.get("_source") or ""))
    return ops


def append_op(root: Path | str, op: dict[str, Any], *, device: str = "pc") -> Path:
    root = resolve_chronicle_dir(root)
    path = ops_dir(root) / f"{device}.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(op, ensure_ascii=False) + "\n"
    with path.open("a", encoding="utf-8") as f:
        f.write(line)
    return path


def apply_ops_to_graph(graph: dict[str, Any], ops: list[dict[str, Any]]) -> dict[str, Any]:
    """
    Replay curation ops onto a graph dict (mutates and returns).
    Last-write-wins per node/edge key.
    """
    nodes: dict[str, dict[str, Any]] = {n["id"]: dict(n) for n in graph.get("nodes") or []}
    # Edge key: (from, to, rel)
    edges: dict[tuple[str, str, str], dict[str, Any]] = {}
    for e in graph.get("edges") or []:
        key = (e["from"], e["to"], e.get("rel") or "related")
        edges[key] = dict(e)

    aliases: dict[str, str] = {}  # from_id -> into_id after merges

    def resolve(nid: str) -> str:
        seen: set[str] = set()
        while nid in aliases and nid not in seen:
            seen.add(nid)
            nid = aliases[nid]
        return nid

    for op in ops:
        kind = op.get("op")
        if kind == "pin":
            nid = resolve(op["node"])
            if nid in nodes:
                nodes[nid]["pinned"] = True
        elif kind == "unpin":
            nid = resolve(op["node"])
            if nid in nodes:
                nodes[nid]["pinned"] = False
        elif kind == "hide":
            nid = resolve(op["node"])
            if nid in nodes:
                nodes[nid]["hidden"] = True
        elif kind == "unhide":
            nid = resolve(op["node"])
            if nid in nodes:
                nodes[nid]["hidden"] = False
        elif kind == "rename":
            nid = resolve(op["node"])
            if nid in nodes:
                nodes[nid]["label"] = op.get("label") or nodes[nid].get("label")
        elif kind == "annotate":
            nid = resolve(op["node"])
            if nid in nodes:
                nodes[nid]["annotation"] = op.get("text") or ""
        elif kind == "create_concept":
            cid = op.get("id") or ""
            if cid and cid not in nodes:
                # create_concept is the only create op; project:<slug> → kind project
                node_kind = "project" if cid.startswith("project:") else "concept"
                node: dict[str, Any] = {
                    "id": cid,
                    "kind": node_kind,
                    "label": op.get("label") or cid,
                }
                group = op.get("group")
                if group:
                    node["group"] = str(group)
                nodes[cid] = node
        elif kind == "merge":
            src = resolve(op["from"])
            dst = resolve(op["into"])
            if src == dst:
                continue
            aliases[src] = dst
            if src in nodes and dst in nodes:
                # Move weight / annotation
                nodes[dst]["weight"] = (nodes[dst].get("weight") or 0) + (
                    nodes[src].get("weight") or 0
                )
                if nodes[src].get("annotation") and not nodes[dst].get("annotation"):
                    nodes[dst]["annotation"] = nodes[src]["annotation"]
                del nodes[src]
            elif src in nodes and dst not in nodes:
                nodes[dst] = nodes.pop(src)
                nodes[dst]["id"] = dst
            # Rewire edges
            new_edges: dict[tuple[str, str, str], dict[str, Any]] = {}
            for (a, b, rel), ed in edges.items():
                a2, b2 = resolve(a), resolve(b)
                if a2 == b2:
                    continue
                nk = (a2, b2, rel)
                ed2 = dict(ed)
                ed2["from"] = a2
                ed2["to"] = b2
                new_edges[nk] = ed2
            edges = new_edges
        elif kind == "link":
            a = resolve(op["from"])
            b = resolve(op["to"])
            rel = op.get("rel") or "manual"
            edges[(a, b, rel)] = {"from": a, "to": b, "rel": rel, "score": 1.0}
        elif kind == "unlink":
            a = resolve(op["from"])
            b = resolve(op["to"])
            rel = op.get("rel") or "manual"
            edges.pop((a, b, rel), None)
            # Also try without forcing rel if not specified uniquely
            if op.get("rel") is None:
                for key in list(edges):
                    if key[0] == a and key[1] == b:
                        edges.pop(key, None)
        elif kind == "set_doc":
            nid = resolve(op["node"])
            if nid in nodes:
                doc = op.get("doc")
                if doc:
                    nodes[nid]["doc"] = str(doc)
                else:
                    nodes[nid].pop("doc", None)
        elif kind == "delete_concept":
            nid = resolve(op["node"])
            if not (nid.startswith("concept:") or nid.startswith("project:")):
                continue
            nodes.pop(nid, None)
            edges = {
                k: v for k, v in edges.items() if k[0] != nid and k[1] != nid
            }

    graph["nodes"] = sorted(nodes.values(), key=lambda n: n["id"])
    graph["edges"] = sorted(edges.values(), key=lambda e: (e["from"], e["to"], e["rel"]))
    return graph


def compact_ops(root: Path | str, *, dry_run: bool = False) -> dict[str, int]:
    """
    Compact superseded ops per device file.
    Keeps last-write-wins state as a minimal op stream (not full history).
    Never deletes without writing compacted file; original replaced atomically.
    """
    root = resolve_chronicle_dir(root)
    stats = {"files": 0, "before": 0, "after": 0}
    for path in list_ops_files(root):
        lines = [
            ln.strip()
            for ln in path.read_text(encoding="utf-8").splitlines()
            if ln.strip()
        ]
        stats["before"] += len(lines)
        ops: list[dict[str, Any]] = []
        for ln in lines:
            try:
                ops.append(json.loads(ln))
            except json.JSONDecodeError:
                continue
        # Build effective state keys
        node_state: dict[str, dict[str, Any]] = {}
        edge_state: dict[tuple[str, str, str], dict[str, Any] | None] = {}
        merges: list[dict[str, Any]] = []
        creates: dict[str, dict[str, Any]] = {}
        deletes: dict[str, dict[str, Any]] = {}

        for op in sorted(ops, key=lambda o: o.get("ts") or ""):
            kind = op.get("op")
            if kind in ("pin", "unpin", "hide", "unhide", "rename", "annotate", "set_doc"):
                nid = op.get("node")
                if not nid:
                    continue
                st = node_state.setdefault(nid, {})
                if kind == "pin":
                    st["pinned"] = True
                elif kind == "unpin":
                    st["pinned"] = False
                elif kind == "hide":
                    st["hidden"] = True
                elif kind == "unhide":
                    st["hidden"] = False
                elif kind == "rename":
                    st["label"] = op.get("label")
                elif kind == "annotate":
                    st["text"] = op.get("text")
                elif kind == "set_doc":
                    st["doc"] = op.get("doc") or ""
                st["ts"] = op.get("ts")
                st["device"] = op.get("device")
            elif kind == "create_concept":
                cid = op.get("id") or ""
                creates[cid] = op
                deletes.pop(cid, None)
            elif kind == "delete_concept":
                nid = op.get("node") or ""
                if not nid:
                    continue
                # create+delete in same log cancels out
                if nid in creates:
                    creates.pop(nid, None)
                    node_state.pop(nid, None)
                    deletes.pop(nid, None)
                else:
                    deletes[nid] = op
                    node_state.pop(nid, None)
            elif kind == "merge":
                merges.append(op)
            elif kind == "link":
                key = (op["from"], op["to"], op.get("rel") or "manual")
                edge_state[key] = op
            elif kind == "unlink":
                key = (op["from"], op["to"], op.get("rel") or "manual")
                edge_state[key] = None

        compacted: list[dict[str, Any]] = []
        for cid, op in sorted(creates.items()):
            if cid and cid not in deletes:
                compacted.append(op)
        for dop in sorted(deletes.values(), key=lambda o: (o.get("ts") or "", o.get("node") or "")):
            compacted.append(dop)
        for mop in merges:
            compacted.append(mop)
        for nid, st in sorted(node_state.items()):
            if nid in deletes:
                continue
            device = st.get("device") or "pc"
            ts = st.get("ts") or ""
            if "pinned" in st:
                compacted.append(
                    {
                        "op": "pin" if st["pinned"] else "unpin",
                        "ts": ts,
                        "device": device,
                        "node": nid,
                    }
                )
            if "hidden" in st:
                compacted.append(
                    {
                        "op": "hide" if st["hidden"] else "unhide",
                        "ts": ts,
                        "device": device,
                        "node": nid,
                    }
                )
            if "label" in st:
                compacted.append(
                    {
                        "op": "rename",
                        "ts": ts,
                        "device": device,
                        "node": nid,
                        "label": st["label"],
                    }
                )
            if "text" in st:
                compacted.append(
                    {
                        "op": "annotate",
                        "ts": ts,
                        "device": device,
                        "node": nid,
                        "text": st["text"],
                    }
                )
            if "doc" in st and st["doc"]:
                compacted.append(
                    {
                        "op": "set_doc",
                        "ts": ts,
                        "device": device,
                        "node": nid,
                        "doc": st["doc"],
                    }
                )
        for key, op in sorted(edge_state.items(), key=lambda x: x[0]):
            if op is None:
                compacted.append(
                    {
                        "op": "unlink",
                        "ts": "",
                        "device": "pc",
                        "from": key[0],
                        "to": key[1],
                        "rel": key[2],
                    }
                )
            else:
                compacted.append(op)

        stats["after"] += len(compacted)
        stats["files"] += 1
        if dry_run:
            continue
        body = "".join(json.dumps(o, ensure_ascii=False) + "\n" for o in compacted)
        atomic_write_text(path, body)
    return stats
