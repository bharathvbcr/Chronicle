"""On-demand incremental pipeline trigger."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query

from ..process import run_process
from ..vault_layout import LayoutVersionError
from .deps import get_root

log = logging.getLogger("chronicle.api.process")

router = APIRouter(tags=["process"])


@router.post("/process")
def post_process(
    root: Path = Depends(get_root),
    run_brain: bool = Query(True, description="Also run chronicle brain after process"),
    dry_run: bool = Query(False),
) -> dict[str, Any]:
    """Run incremental process (unprocessed → file-once journal → optional brain → index)."""
    try:
        result = run_process(root, dry_run=dry_run, run_brain=run_brain)
    except LayoutVersionError as e:
        raise HTTPException(status_code=409, detail=str(e)) from e
    return {"ok": True, **result}
