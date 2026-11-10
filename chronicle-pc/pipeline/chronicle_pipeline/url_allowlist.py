"""URL allowlists for Ollama (private/loopback) and Grok (api.x.ai only)."""

from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse

ALLOWED_GROK_HOSTS = frozenset({"api.x.ai"})
DEFAULT_GROK_BASE = "https://api.x.ai/v1"
DEFAULT_OLLAMA_BASE = "http://localhost:11434"


def _has_control_char(url: str) -> bool:
    # C0 and DEL. getaddrinfo truncates at NUL and would approve a different host.
    for ch in url or "":
        code = ord(ch)
        if code < 0x20 or code == 0x7F:
            return True
    return False


def _normalize_url(url: str, *, default_scheme: str = "http") -> str:
    cleaned = (url or "").strip().rstrip("/")
    if not cleaned:
        return ""
    if "://" not in cleaned:
        cleaned = f"{default_scheme}://{cleaned}"
    return cleaned.rstrip("/")


def _has_userinfo(url: str, parsed) -> bool:
    # Checked before DNS. urlparse still reports a loopback hostname for
    # userinfo and for a percent-encoded '@', so the raw string has to be read.
    lowered = url.lower()
    if "@" in lowered or "%40" in lowered:
        return True
    return parsed.username is not None or parsed.password is not None


def _host_is_private_or_loopback(host: str) -> bool:
    name = (host or "").strip().lower()
    if not name or _has_control_char(name):
        return False
    if name == "localhost":
        return True
    try:
        addr = ipaddress.ip_address(name)
        return bool(addr.is_loopback or addr.is_private or addr.is_link_local)
    except ValueError:
        pass
    # Resolve hostnames; every address must be private/loopback.
    # UnicodeError is an IDNA failure (label too long), not an OSError.
    try:
        infos = socket.getaddrinfo(name, None)
    except OSError:
        return False
    except UnicodeError:
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


def _grok_port_allowed(parsed) -> bool:
    # An omitted https port is 443. Any other explicit port is refused.
    try:
        port = parsed.port
    except ValueError:
        return False
    return port is None or port == 443


def is_private_or_loopback_url(url: str) -> bool:
    """True when URL host is loopback, RFC1918, link-local, or localhost."""
    if _has_control_char(url):
        return False
    normalized = _normalize_url(url, default_scheme="http")
    if not normalized or _has_control_char(normalized):
        return False
    try:
        parsed = urlparse(normalized)
    except ValueError:
        return False
    if _has_userinfo(normalized, parsed):
        return False
    if parsed.scheme not in ("http", "https"):
        return False
    host = parsed.hostname
    if not host:
        return False
    return _host_is_private_or_loopback(host)


def is_allowed_grok_url(url: str) -> bool:
    """True only for https://api.x.ai (optional path, port 443)."""
    if _has_control_char(url):
        return False
    normalized = _normalize_url(url, default_scheme="https")
    if not normalized or _has_control_char(normalized):
        return False
    try:
        parsed = urlparse(normalized)
    except ValueError:
        return False
    if _has_userinfo(normalized, parsed):
        return False
    if parsed.scheme != "https" or not _grok_port_allowed(parsed):
        return False
    host = (parsed.hostname or "").lower()
    return host in ALLOWED_GROK_HOSTS


def _refuses_userinfo(url: str) -> bool:
    lowered = url.lower()
    return "@" in lowered or "%40" in lowered


def validate_ollama_base_url(url: str) -> str:
    """Return cleaned URL or raise ValueError."""
    if _has_control_char(url):
        raise ValueError(
            "ollama base_url must be a private or loopback address "
            "(localhost / 127.0.0.1 / RFC1918); public hosts are blocked"
        )
    cleaned = _normalize_url(url, default_scheme="http")
    if not cleaned:
        raise ValueError("ollama base_url must be a non-empty URL")
    if (
        _has_control_char(cleaned)
        or _refuses_userinfo(cleaned)
        or not is_private_or_loopback_url(cleaned)
    ):
        raise ValueError(
            "ollama base_url must be a private or loopback address "
            "(localhost / 127.0.0.1 / RFC1918); public hosts are blocked"
        )
    return cleaned


def validate_grok_base_url(url: str) -> str:
    """Return cleaned URL or raise ValueError."""
    if _has_control_char(url):
        raise ValueError(
            "grok_base_url must be https://api.x.ai (exact host); "
            "other hosts are blocked"
        )
    cleaned = _normalize_url(url, default_scheme="https")
    if not cleaned:
        raise ValueError("grok_base_url must be a non-empty URL")
    if (
        _has_control_char(cleaned)
        or _refuses_userinfo(cleaned)
        or not is_allowed_grok_url(cleaned)
    ):
        raise ValueError(
            "grok_base_url must be https://api.x.ai (exact host); "
            "other hosts are blocked"
        )
    return cleaned
