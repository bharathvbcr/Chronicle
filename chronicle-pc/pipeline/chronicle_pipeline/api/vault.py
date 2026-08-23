"""Vault maintenance hooks (markdown index rebuild).

Inbox filing and ``_staging`` import stay skill/CLI-driven — not SPA wizards.
Full SETUP rewrite is Phase 5; this module only exposes thin serve endpoints.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from .. import index_store, markdown_index
from .deps import get_root

log = logging.getLogger("chronicle.api.vault")

router = APIRouter(tags=["vault"])


class RebuildIndexBody(BaseModel):
    """Optional triggers beyond the markdown agent shortlist."""

    process: bool = Field(
        default=False,
        description="Also run chronicle process (incremental) after markdown rebuild",
    )
    sqlite: bool = Field(
        default=False,
        description="Also refresh sqlite RAG index under index/",
    )


@router.post("/vault/rebuild-index")
def post_vault_rebuild_index(
    root: Path = Depends(get_root),
    body: RebuildIndexBody | None = None,
    dry_run: bool = Query(False),
) -> dict[str, Any]:
    """Rebuild ``_system/index.md`` (agent shortlist).

    Sqlite ``index/`` remains RAG SoT — pass ``sqlite: true`` to refresh it too.
    Inbox / ``_staging`` stay skill/CLI (``vault-maintenance`` / ``init-vault-structure``).
    """
    opts = body or RebuildIndexBody()
    md = markdown_index.rebuild_markdown_index(root, dry_run=dry_run)
    result: dict[str, Any] = {
        "ok": True,
        "markdown_index": md,
        "dry_run": dry_run,
    }
    if opts.sqlite:
        result["sqlite_index"] = index_store.run_index(
            root, dry_run=dry_run, force=False
        )
    if opts.process:
        from ..process import run_process

        result["process"] = run_process(root, dry_run=dry_run, run_brain=False)
    return result
