"""Sliding-window rate limiting for LAN auth failures.

Blocks an IP after ``max_fails`` failures inside ``window_sec``; the block
lasts ``block_sec``. Success clears the counter. Loopback callers bypass
limiting (the local SPA must never lock itself out). The table is bounded —
under sustained spoofing the oldest entries are evicted rather than growing.
"""

from __future__ import annotations

import threading
import time
from collections import OrderedDict

_MAX_TRACKED_IPS = 4096


class AuthRateLimiter:
    def __init__(
        self,
        *,
        max_fails: int = 5,
        window_sec: float = 300.0,
        block_sec: float = 600.0,
        clock: callable = time.monotonic,
    ):
        self.max_fails = max_fails
        self.window_sec = window_sec
        self.block_sec = block_sec
        self._clock = clock
        self._lock = threading.Lock()
        # ip -> [fails, first_fail_ts | None, blocked_until_ts]
        # first_fail uses None (never a falsy float) as its "no window" mark.
        self._table: OrderedDict[str, list[float | None]] = OrderedDict()

    def _evict_locked(self, now: float) -> None:
        # Prefer dropping expired blocks and stale failure windows before
        # resorting to oldest-inserted eviction, so an attacker rotating many
        # source addresses cannot flush a genuinely blocked peer's entry.
        stale = [
            k
            for k, v in self._table.items()
            if (v[2] and v[2] <= now)  # block expired
            or (
                not v[2] and v[1] and now - v[1] >= self.window_sec
            )  # window lapsed
        ]
        for k in stale:
            del self._table[k]
        if len(self._table) > _MAX_TRACKED_IPS:
            # Flood hardening: evict UNBLOCKED entries oldest-first; a
            # genuinely blocked peer must survive spoofed-source flooding.
            unblocked = [
                k for k, v in self._table.items() if not (v[2] and v[2] > now)
            ]
            overflow = len(self._table) - _MAX_TRACKED_IPS
            for k in unblocked[:overflow]:
                del self._table[k]
            while len(self._table) > _MAX_TRACKED_IPS:
                self._table.popitem(last=False)  # pathological all-blocked case

    def allow(self, client_ip: str) -> bool:
        """True when a request from [client_ip] may attempt auth."""
        if not client_ip or client_ip in ("127.0.0.1", "::1", "localhost", "testclient"):
            return True
        now = self._clock()
        with self._lock:
            self._evict_locked(now)
            entry = self._table.get(client_ip)
            if entry and entry[2] and entry[2] > now:
                return False
            return True

    def record_fail(self, client_ip: str) -> None:
        if not client_ip or client_ip in ("127.0.0.1", "::1", "localhost", "testclient"):
            return
        now = self._clock()
        with self._lock:
            entry = self._table.get(client_ip)
            # None sentinel for first_fail_ts — a falsy-float check conflated
            # timestamp 0.0 with "no window", so same-instant bursts at t=0
            # never reached max_fails (stress test caught this).
            if entry is None or entry[1] is None or now - entry[1] > self.window_sec:
                entry = [1.0, now, 0.0]
            else:
                entry[0] += 1.0
            if entry[0] >= self.max_fails:
                entry[2] = now + self.block_sec
                entry[0], entry[1] = 0.0, None
            self._table[client_ip] = entry
            self._table.move_to_end(client_ip)
            self._evict_locked(now)

    def record_success(self, client_ip: str) -> None:
        if not client_ip:
            return
        with self._lock:
            self._table.pop(client_ip, None)
