"""Shared FastAPI dependencies for API routers."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import Request


def get_root(request: Request) -> Path:
    return Path(request.app.state.chronicle_dir)


def get_connect_info(request: Request) -> dict[str, Any]:
    return dict(getattr(request.app.state, "connect_info", {}) or {})
