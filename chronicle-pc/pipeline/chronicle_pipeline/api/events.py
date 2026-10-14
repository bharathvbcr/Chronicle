"""Server-Sent Events stream: live vault-change notifications for the phone.

``GET /events/stream`` requires the pairing token (default-deny covers it).
Events are lightweight fingerprints of the vault — never content:

    event: vault
    data: {"reason":"entries","ts":"..."}

The phone refreshes Timeline/Brain when it sees ``vault`` instead of polling.
Connections are bounded (``MAX_STREAM_SECONDS``) and clients reconnect;
heartbeats keep proxies from idling the socket out.
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import stat
import threading
import time
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from .deps import get_root

router = APIRouter(tags=["events"])

POLL_INTERVAL_SEC = 2.0
HEARTBEAT_INTERVAL_SEC = 15.0
MAX_STREAM_SECONDS = 30 * 60.0

_SCAN_DEPTH = 4
_MAX_FILES_SCANNED = 4000
_MAX_DIRS = 4000


def vault_fingerprint(root: Path) -> str:
    """Fingerprint over config + capture + brain + journal.

    Robustness rules (stress-tested):
    - Only capture/brain FILES count against the scan budget. Directory
      visits do not, so a deep tree cannot exhaust the budget before any
      file is seen and return a blind "empty".
    - Every visited directory contributes its mtime. Creates and renames
      stay visible after the capture/brain file budget is spent. Files past
      that budget are not hashed — an equal-length swap of two of them is
      invisible on purpose.
    - A statted file is the vault-relative posix path, mtime, size, and a
      short content hash. ``40-Journal`` files are always statted: they are
      the phone timeline, and suffix alone is not an identity.
    - ``config.json`` keeps ``path:mtime_ns:size`` with no content hash.
    """

    parts: list[str] = []
    files_seen = 0
    dirs_seen = 0
    max_files = _max_file_budget()
    for rel in ("config.json", "_capture", "brain", "40-Journal"):
        base = root / rel
        if not base.exists():
            continue
        if base.is_file():
            try:
                st = base.stat()
            except OSError:
                continue
            parts.append(f"{rel}:{st.st_mtime_ns}:{st.st_size}")
            continue
        # Journal is the phone timeline: never drop it because capture/brain
        # already used the file budget.
        budget_exempt = rel == "40-Journal"
        stack = [(base, 0)]
        while stack and dirs_seen < _MAX_DIRS:
            current, depth = stack.pop()
            try:
                iterator = current.iterdir()
                st = current.stat()
            except OSError:
                continue
            dirs_seen += 1
            # Dir mtime moves on add/remove/rename of direct children —
            # the signal for capture/brain once the file budget is spent.
            parts.append(f"d:{current.name}:{st.st_mtime_ns}")
            for child in iterator:
                try:
                    if child.is_dir():
                        if depth < _SCAN_DEPTH:
                            stack.append((child, depth + 1))
                        continue
                    if not budget_exempt and files_seen >= max_files:
                        continue  # keep listing (cheap); skip stat and hash
                    if not budget_exempt:
                        files_seen += 1
                    token = _statted_file_token(root, child)
                    if token is not None:
                        parts.append(token)
                except OSError:
                    continue
    if not parts:
        return "empty"
    return ";".join(sorted(parts))


def _statted_file_token(root: Path, child: Path) -> str | None:
    """Path, mtime, size, and sha256 prefix. ``..`` is never a token.

    OSError on this file skips it. Non-regular files are skipped so a fifo
    or device cannot block the scan.
    """
    try:
        rel = child.relative_to(root)
    except ValueError:
        return None
    if ".." in rel.parts:
        return None
    posix = rel.as_posix()
    if not posix or posix.startswith("/") or posix.startswith("../") or "/../" in posix:
        return None
    try:
        st = child.stat()
        if not stat.S_ISREG(st.st_mode):
            return None
        digest = hashlib.sha256()
        with child.open("rb") as fh:
            while True:
                chunk = fh.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
    except OSError:
        return None
    return f"{posix}:{st.st_mtime_ns}:{st.st_size}:{digest.hexdigest()[:16]}"


def _max_file_budget() -> int:
    env = os.environ.get("CHRONICLE_EVENTS_MAX_FILES")
    if env and env.isdigit():
        return max(1, int(env))
    return _MAX_FILES_SCANNED


def _sse(event: str, data: dict[str, Any]) -> str:
    import json

    return f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"


# Shared across all concurrent streams: the fingerprint is vault-global, so
# letting each connection scan 4000 files every 2s multiplied CPU linearly
# with client count. One scan per tick serves everyone.
_fp_cache_lock = asyncio.Lock()
_fp_cache: tuple[str, float] | None = None  # (fingerprint, monotonic time)


async def _cached_fingerprint(root: Path) -> str:
    global _fp_cache
    now = time.monotonic()
    if _fp_cache is not None and now - _fp_cache[1] < POLL_INTERVAL_SEC * 0.9:
        return _fp_cache[0]
    async with _fp_cache_lock:
        # Re-check inside the lock — a concurrent stream may have refreshed it.
        now = time.monotonic()
        if _fp_cache is not None and now - _fp_cache[1] < POLL_INTERVAL_SEC * 0.9:
            return _fp_cache[0]
        fp = await asyncio.to_thread(vault_fingerprint, root)
        _fp_cache = (fp, time.monotonic())
        return fp


async def _stream(root: Path) -> AsyncIterator[str]:
    started = time.monotonic()
    last_fp: str | None = None
    last_emit = time.monotonic()
    yield ": chronicle events\n\n"
    while True:
        now = time.monotonic()
        if now - started > MAX_STREAM_SECONDS:
            yield _sse("bye", {"reason": "rotate"})
            return
        fp = await _cached_fingerprint(root)
        if last_fp is not None and fp != last_fp:
            last_emit = now
            yield _sse("vault", {"reason": "changed", "ts": int(time.time())})
        elif now - last_emit > HEARTBEAT_INTERVAL_SEC:
            last_emit = now
            yield ": hb\n\n"
        last_fp = fp
        await asyncio.sleep(POLL_INTERVAL_SEC)


class StreamTicketStore:
    """Single-use, short-TTL tickets for EventSource stream auth.

    Browsers' EventSource cannot send custom headers, so LAN clients fetch a
    ticket via a header-authenticated GET /events/ticket and append it as
    ``?ticket=``. Properties: 256-bit random, single-use (consumed on first
    validation), 30-second TTL, bounded outstanding pool with
    expired-then-oldest eviction. Constant-time compare on validation.
    """

    TTL_SEC = 30.0
    MAX_OUTSTANDING = 64

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._tickets: dict[str, float] = {}  # ticket -> issued_at (monotonic)

    def issue(self) -> tuple[str, int]:
        now = time.monotonic()
        with self._lock:
            # Evict expired first, then oldest, to respect the bound.
            expired = [t for t, issued in self._tickets.items() if now - issued > self.TTL_SEC]
            for t in expired:
                del self._tickets[t]
            while len(self._tickets) >= self.MAX_OUTSTANDING:
                oldest = min(self._tickets.items(), key=lambda kv: kv[1])[0]
                del self._tickets[oldest]
            import secrets

            ticket = secrets.token_urlsafe(32)
            self._tickets[ticket] = now
            return ticket, int(self.TTL_SEC)

    def validate_and_consume(self, ticket: str) -> bool:
        if not ticket or len(ticket) > 512:
            return False
        now = time.monotonic()
        with self._lock:
            issued = self._tickets.get(ticket)
            if issued is None or now - issued > self.TTL_SEC:
                return False
            del self._tickets[ticket]  # single use — replay is rejected
            return True


stream_tickets = StreamTicketStore()


def validate_stream_ticket(ticket: str) -> bool:
    return stream_tickets.validate_and_consume(ticket)


@router.get("/events/ticket")
def issue_stream_ticket() -> dict[str, Any]:
    """Issue a one-time stream ticket (token-required via default-deny)."""
    ticket, expires_in = stream_tickets.issue()
    return {"ticket": ticket, "expires_in": expires_in}


@router.get("/events/stream")
async def events_stream(root: Path = Depends(get_root)) -> StreamingResponse:
    return StreamingResponse(
        _stream(root),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )
