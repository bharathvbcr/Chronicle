"""FastAPI routers for the Chronicle REST backend."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import FastAPI

from . import ask, auth, brain, entries, events, journal, kb, notes, process, recall, system, vault


def register_routers(
    app: FastAPI,
    root: Path,
    *,
    connect_info: dict[str, Any] | None = None,
) -> None:
    """Attach all API routers and stash vault root on ``app.state``."""
    app.state.chronicle_dir = Path(root)
    app.state.connect_info = dict(connect_info or {})

    app.include_router(system.router)
    app.include_router(auth.router)
    app.include_router(auth.devices_router)
    app.include_router(events.router)
    app.include_router(entries.router)
    app.include_router(kb.router)
    app.include_router(notes.router)
    app.include_router(journal.router)
    app.include_router(brain.router)
    app.include_router(recall.router)
    app.include_router(ask.router)
    app.include_router(process.router)
    app.include_router(vault.router)
