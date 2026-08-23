"""Ask / Resume RAG endpoints."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .. import llm, rag
from ..config import ensure_config
from .deps import get_root

router = APIRouter(tags=["ask"])


class AskBody(BaseModel):
    question: str


class ResumeBody(BaseModel):
    role: str


def _maybe_error_response(result: dict[str, Any]) -> JSONResponse | dict[str, Any]:
    """Keep Android body shape; map hard failures (ok:false) to HTTP 5xx."""
    if isinstance(result, dict) and result.get("ok") is False:
        return JSONResponse(status_code=500, content=result)
    return result


def _enforce_cloud_rate(root: Path) -> None:
    cfg = ensure_config(root)
    pname = llm.provider_name(cfg)
    try:
        llm.check_cloud_rate_limit(pname)
    except RuntimeError as e:
        raise HTTPException(429, str(e)) from e


@router.post("/ask")
def post_ask(body: AskBody, root: Path = Depends(get_root)) -> Any:
    _enforce_cloud_rate(root)
    return _maybe_error_response(rag.ask(root, body.question))


@router.post("/resume")
def post_resume(body: ResumeBody, root: Path = Depends(get_root)) -> Any:
    _enforce_cloud_rate(root)
    return _maybe_error_response(rag.resume(root, body.role))
