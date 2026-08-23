"""Simple process-local rate limiting for cloud LLM serve routes."""

from __future__ import annotations

import threading
import time
from collections import deque

from .consent import is_cloud_provider

# Sliding window: max N cloud LLM POSTs per window across /ask /resume /recall.
DEFAULT_MAX_REQUESTS = 20
DEFAULT_WINDOW_SEC = 60.0

_lock = threading.Lock()
_timestamps: deque[float] = deque()

CLOUD_LLM_PATHS = frozenset({"/ask", "/resume", "/recall"})


def reset_for_tests() -> None:
    with _lock:
        _timestamps.clear()


def allow_cloud_request(
    *,
    provider: str,
    max_requests: int = DEFAULT_MAX_REQUESTS,
    window_sec: float = DEFAULT_WINDOW_SEC,
) -> bool:
    """Return True if the request may proceed; False if rate-limited."""
    if not is_cloud_provider(provider):
        return True
    now = time.monotonic()
    with _lock:
        while _timestamps and (now - _timestamps[0]) > window_sec:
            _timestamps.popleft()
        if len(_timestamps) >= max_requests:
            return False
        _timestamps.append(now)
        return True


def check_cloud_rate_limit(provider: str) -> None:
    """Raise ``RuntimeError`` when over limit (API layer maps to HTTP 429)."""
    if not allow_cloud_request(provider=provider):
        raise RuntimeError(
            "Cloud LLM rate limit exceeded "
            f"({DEFAULT_MAX_REQUESTS} requests / {int(DEFAULT_WINDOW_SEC)}s). "
            "Retry shortly or switch llm.provider to ollama."
        )
