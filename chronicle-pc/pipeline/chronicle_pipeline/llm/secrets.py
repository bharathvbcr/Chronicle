"""Off-vault secrets for cloud LLM keys and consent overrides.

Never store API keys in vault ``config.json`` (Syncthing-synced). Prefer:

- ``~/.config/chronicle/secrets.json``
- Environment: ``GROK_API_KEY``, ``GOOGLE_CLOUD_PROJECT`` / ADC for Vertex
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

log = logging.getLogger("chronicle.llm.secrets")

DEFAULT_SECRETS_PATH = Path.home() / ".config" / "chronicle" / "secrets.json"


def secrets_path() -> Path:
    override = (os.environ.get("CHRONICLE_SECRETS") or "").strip()
    if override:
        return Path(override).expanduser()
    return DEFAULT_SECRETS_PATH


def load_secrets(path: Path | None = None) -> dict[str, Any]:
    """Load secrets JSON; missing file → empty dict. Never raises for IO."""
    p = path or secrets_path()
    if not p.is_file():
        return {}
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as e:
        log.warning("Could not read secrets at %s: %s", p, e)
        return {}
    return data if isinstance(data, dict) else {}


def grok_api_key(secrets: dict[str, Any] | None = None) -> str | None:
    env = (os.environ.get("GROK_API_KEY") or "").strip()
    if env:
        return env
    data = secrets if secrets is not None else load_secrets()
    key = data.get("grok_api_key") or data.get("GROK_API_KEY")
    if isinstance(key, str) and key.strip():
        return key.strip()
    return None


def cloud_consent_from_secrets(secrets: dict[str, Any] | None = None) -> bool | None:
    """Return explicit True/False from secrets, or None if unset."""
    data = secrets if secrets is not None else load_secrets()
    if "cloud_consent" not in data:
        return None
    return bool(data.get("cloud_consent"))


def vision_cloud_consent_from_secrets(secrets: dict[str, Any] | None = None) -> bool | None:
    data = secrets if secrets is not None else load_secrets()
    if "vision_cloud_consent" not in data:
        return None
    return bool(data.get("vision_cloud_consent"))


def vertex_project(secrets: dict[str, Any] | None = None) -> str | None:
    env = (
        os.environ.get("GOOGLE_CLOUD_PROJECT")
        or os.environ.get("GCLOUD_PROJECT")
        or os.environ.get("VERTEX_PROJECT")
        or ""
    ).strip()
    if env:
        return env
    data = secrets if secrets is not None else load_secrets()
    for key in ("vertex_project", "google_cloud_project", "project"):
        val = data.get(key)
        if isinstance(val, str) and val.strip():
            return val.strip()
    return None
