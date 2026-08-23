"""URL allowlists for Ollama (private/loopback) and Grok (api.x.ai only)."""

from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse

ALLOWED_GROK_HOSTS = frozenset({"api.x.ai"})
DEFAULT_GROK_BASE = "https://api.x.ai/v1"
DEFAULT_OLLAMA_BASE = "http://localhost:11434"


def _normalize_url(url: str, *, default_scheme: str = "http") -> str:
    cleaned = (url or "").strip().rstrip("/")
    if not cleaned:
        return ""
    if "://" not in cleaned:
        cleaned = f"{default_scheme}://{cleaned}"
    return cleaned.rstrip("/")


def _host_is_private_or_loopback(host: str) -> bool:
    name = (host or "").strip().lower()
    if not name:
        return False
    if name == "localhost":
        return True
    try:
        addr = ipaddress.ip_address(name)
        return bool(addr.is_loopback or addr.is_private or addr.is_link_local)
    except ValueError:
        pass
    # Resolve hostnames; every address must be private/loopback.
    try:
        infos = socket.getaddrinfo(name, None)
    except OSError:
        return False
    if not infos:
        return False
    for info in infos:
        raw = info[4][0]
        try:
            addr = ipaddress.ip_address(raw)
        except ValueError:
            return False
        if not (addr.is_loopback or addr.is_private or addr.is_link_local):
            return False
    return True


def is_private_or_loopback_url(url: str) -> bool:
    """True when URL host is loopback, RFC1918, link-local, or localhost."""
    normalized = _normalize_url(url, default_scheme="http")
    if not normalized:
        return False
    try:
        parsed = urlparse(normalized)
    except ValueError:
        return False
    if parsed.scheme not in ("http", "https"):
        return False
    host = parsed.hostname
    if not host:
        return False
    return _host_is_private_or_loopback(host)


def is_allowed_grok_url(url: str) -> bool:
    """True only for https://api.x.ai (optional path)."""
    normalized = _normalize_url(url, default_scheme="https")
    if not normalized:
        return False
    try:
        parsed = urlparse(normalized)
    except ValueError:
        return False
    if parsed.scheme != "https":
        return False
    host = (parsed.hostname or "").lower()
    return host in ALLOWED_GROK_HOSTS


def validate_ollama_base_url(url: str) -> str:
    """Return cleaned URL or raise ValueError."""
    cleaned = _normalize_url(url, default_scheme="http")
    if not cleaned:
        raise ValueError("ollama base_url must be a non-empty URL")
    if not is_private_or_loopback_url(cleaned):
        raise ValueError(
            "ollama base_url must be a private or loopback address "
            "(localhost / 127.0.0.1 / RFC1918); public hosts are blocked"
        )
    return cleaned


def validate_grok_base_url(url: str) -> str:
    """Return cleaned URL or raise ValueError."""
    cleaned = _normalize_url(url, default_scheme="https")
    if not cleaned:
        raise ValueError("grok_base_url must be a non-empty URL")
    if not is_allowed_grok_url(cleaned):
        raise ValueError(
            "grok_base_url must be https://api.x.ai (exact host); "
            "other hosts are blocked"
        )
    return cleaned
