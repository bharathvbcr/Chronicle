"""Search + Recall (with optional graph-seeded node_ids).

Retrieval / Ornith long-context logic lives in ``rag``; this module is the HTTP
adapter and preserves the Phase 1 response shape (per-citation ``node_ids``,
``seed_node_ids``).
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from .. import index_store, llm, rag
from .deps import get_root

log = logging.getLogger("chronicle.api.recall")

router = APIRouter(tags=["recall"])

Scope = Literal["journal", "kb", "all"]


class SearchBody(BaseModel):
    query: str
    top_k: int = Field(8, ge=1, le=50)
    scope: Scope = "all"


class RecallBody(BaseModel):
    message: str
    history: list[dict[str, str]] = Field(default_factory=list)
    scope: Scope = "all"
    node_ids: list[str] = Field(
        default_factory=list,
        description="Graph node ids that seed recall context (1-hop neighbors included)",
    )


def _citation_node_ids(hit: dict[str, Any], graph: dict[str, Any]) -> list[str]:
    """Map a search hit to related graph node ids."""
    nodes = graph.get("nodes") or []
    hid = hit.get("id") or ""
    kind = hit.get("kind")
    path = hit.get("path") or ""
    mapped: list[str] = []

    if kind == "entry":
        candidate = f"entry:{hid}"
        for n in nodes:
            if not isinstance(n, dict):
                continue
            if n.get("id") == candidate or n.get("entry_id") == hid:
                mapped.append(n["id"])
        if not mapped:
            mapped.append(candidate)
    elif kind in ("kb", "note", "rollup_week", "rollup_month"):
        for n in nodes:
            if not isinstance(n, dict):
                continue
            doc = n.get("doc") or ""
            if doc and (doc == hid or doc == path or path.endswith(str(doc)) or hid.endswith(str(doc))):
                mapped.append(n["id"])

    seen: set[str] = set()
    out: list[str] = []
    for nid in mapped:
        if nid not in seen:
            seen.add(nid)
            out.append(nid)
    return out


@router.post("/search")
def post_search(body: SearchBody, root: Path = Depends(get_root)) -> dict[str, Any]:
    hits = index_store.search(root, body.query, top_k=body.top_k, scope=body.scope)
    from ..config import ensure_config

    cfg = ensure_config(root)
    provider = llm.try_get_provider(cfg)
    ok = bool(provider and provider.reachable())
    return {
        "query": body.query,
        "hits": hits,
        "ollama": ok,  # legacy field: active provider reachable
        "provider": llm.provider_name(cfg),
        "provider_ok": ok,
    }


@router.post("/recall")
def post_recall(body: RecallBody, root: Path = Depends(get_root)) -> dict[str, Any]:
    from ..config import ensure_config

    cfg = ensure_config(root)
    pname = llm.provider_name(cfg)
    try:
        llm.check_cloud_rate_limit(pname)
    except RuntimeError as e:
        raise HTTPException(429, str(e)) from e

    seeds = [s for s in (body.node_ids or []) if s]
    result = rag.recall(
        root,
        body.message,
        history=body.history,
        scope=body.scope,
        node_ids=seeds or None,
    )
    if result.get("error") and not result.get("answer"):
        raise HTTPException(503, str(result["error"]))

    graph = rag.load_graph(root) or {"nodes": [], "edges": []}
    expanded = rag.neighbor_node_ids(graph, seeds, hops=1) if seeds else set()

    citations = []
    citation_nodes = result.get("citation_nodes") or {}
    for c in result.get("citations") or []:
        hid = c.get("id") or ""
        node_ids = list(citation_nodes.get(hid) or []) or _citation_node_ids(c, graph)
        citations.append(
            {
                "id": c.get("id"),
                "kind": c.get("kind"),
                "score": c.get("score", 0),
                "snippet": (c.get("snippet") or c.get("text") or "")[:240],
                "path": c.get("path"),
                "node_ids": node_ids,
            }
        )

    return {
        "answer": result.get("answer") or "",
        "citations": citations,
        "degraded": bool(result.get("degraded")),
        "seed_node_ids": sorted(expanded),
    }
