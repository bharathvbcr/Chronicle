"""Brain graph, insights, and curation ops."""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

from .. import curation as curation_mod
from ..paths import read_json
from .deps import get_root

log = logging.getLogger("chronicle.api.brain")

router = APIRouter(tags=["brain"])

CURATION_OPS = frozenset(
    {
        "pin",
        "unpin",
        "hide",
        "unhide",
        "rename",
        "merge",
        "link",
        "unlink",
        "annotate",
        "create_concept",
        "set_doc",
        "delete_concept",
    }
)


class CurationOpBody(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    op: str
    ts: str | None = None
    device: str = "pc"
    node: str | None = None
    label: str | None = None
    from_: str | None = Field(default=None, alias="from")
    into: str | None = None
    to: str | None = None
    rel: str | None = None
    text: str | None = None
    id: str | None = None
    doc: str | None = None


def _empty_graph() -> dict[str, Any]:
    return {
        "version": 1,
        "generated": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "nodes": [],
        "edges": [],
    }


def _load_graph(root: Path) -> dict[str, Any]:
    path = root / "brain" / "graph.json"
    if not path.is_file():
        return _empty_graph()
    try:
        data = read_json(path)
    except Exception as e:  # noqa: BLE001 — surface read errors as 500
        log.exception("failed to read graph.json")
        raise HTTPException(500, f"failed to read graph.json: {e}") from e
    if not isinstance(data, dict):
        return _empty_graph()
    data.setdefault("version", 1)
    data.setdefault("nodes", [])
    data.setdefault("edges", [])
    return data


def _validate_op(op: dict[str, Any]) -> None:
    kind = op.get("op")
    if kind not in CURATION_OPS:
        raise HTTPException(400, f"unknown curation op: {kind}")
    if kind in ("pin", "unpin", "hide", "unhide", "delete_concept") and not op.get("node"):
        raise HTTPException(400, f"{kind} requires node")
    if kind == "rename" and (not op.get("node") or op.get("label") is None):
        raise HTTPException(400, "rename requires node and label")
    if kind == "merge" and (not op.get("from") or not op.get("into")):
        raise HTTPException(400, "merge requires from and into")
    if kind in ("link", "unlink") and (not op.get("from") or not op.get("to")):
        raise HTTPException(400, f"{kind} requires from and to")
    if kind == "annotate" and (not op.get("node") or op.get("text") is None):
        raise HTTPException(400, "annotate requires node and text")
    if kind == "create_concept" and (not op.get("id") or not op.get("label")):
        raise HTTPException(400, "create_concept requires id and label")
    if kind == "set_doc" and (not op.get("node") or op.get("doc") is None):
        raise HTTPException(400, "set_doc requires node and doc")


@router.get("/brain/graph")
def get_brain_graph(root: Path = Depends(get_root)) -> dict[str, Any]:
    return _load_graph(root)


@router.get("/brain/insights")
def get_brain_insights(
    root: Path = Depends(get_root),
    date: str | None = Query(None, description="YYYY-MM-DD"),
    limit: int = Query(30, ge=1, le=365),
) -> dict[str, Any]:
    insights_root = root / "brain" / "insights"
    if date:
        try:
            datetime.strptime(date, "%Y-%m-%d")
        except ValueError as e:
            raise HTTPException(400, "date must be YYYY-MM-DD") from e
        year = date[:4]
        path = insights_root / year / f"{date}.json"
        if not path.is_file():
            raise HTTPException(404, f"insight not found for {date}")
        return {"insight": read_json(path)}

    files: list[Path] = []
    if insights_root.is_dir():
        files = sorted(insights_root.rglob("*.json"), reverse=True)
    insights: list[dict[str, Any]] = []
    for path in files[:limit]:
        try:
            insights.append(read_json(path))
        except Exception:  # noqa: BLE001
            log.warning("Skipping unreadable insight file %s", path, exc_info=True)
            continue
    dates = [i.get("date") for i in insights if i.get("date")]
    return {"insights": insights, "dates": dates}


@router.post("/curation/ops", status_code=201)
def post_curation_op(
    body: CurationOpBody, root: Path = Depends(get_root)
) -> dict[str, Any]:
    op = body.model_dump(by_alias=True, exclude_none=True)
    op["device"] = "pc"
    if not op.get("ts"):
        op["ts"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    _validate_op(op)
    path = curation_mod.append_op(root, op, device="pc")
    return {"ok": True, "op": op, "path": str(path.relative_to(root))}
