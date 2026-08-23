"""Consent + cloud policy helpers (no prompt logging)."""

from __future__ import annotations

from typing import Any

from . import secrets as secrets_mod
from .protocol import LlmError

CLOUD_PROVIDERS = frozenset({"grok", "vertex"})


def is_cloud_provider(name: str) -> bool:
    return (name or "").strip().lower() in CLOUD_PROVIDERS


def resolve_cloud_consent(
    *,
    cfg_consent: bool = False,
    secrets: dict[str, Any] | None = None,
) -> bool:
    """Secrets override wins when explicitly set; else vault config flag."""
    from_secrets = secrets_mod.cloud_consent_from_secrets(secrets)
    if from_secrets is not None:
        return from_secrets
    return bool(cfg_consent)


def resolve_vision_cloud_consent(
    *,
    cfg_consent: bool = False,
    secrets: dict[str, Any] | None = None,
) -> bool:
    from_secrets = secrets_mod.vision_cloud_consent_from_secrets(secrets)
    if from_secrets is not None:
        return from_secrets
    return bool(cfg_consent)


def require_cloud_consent(
    provider: str,
    *,
    cfg_consent: bool = False,
    secrets: dict[str, Any] | None = None,
) -> None:
    if not is_cloud_provider(provider):
        return
    if resolve_cloud_consent(cfg_consent=cfg_consent, secrets=secrets):
        return
    raise LlmError(
        f"Cloud LLM provider {provider!r} requires opt-in consent. "
        "Set llm.cloud_consent=true in config.json, or "
        '{"cloud_consent": true} in ~/.config/chronicle/secrets.json '
        "(journal/KB text will leave this machine)."
    )


def require_vision_cloud_consent(
    provider: str,
    *,
    cloud_consent: bool = False,
    vision_consent: bool = False,
    secrets: dict[str, Any] | None = None,
) -> None:
    """Vision-to-cloud needs text consent plus a separate vision consent flag."""
    if not is_cloud_provider(provider):
        return
    require_cloud_consent(provider, cfg_consent=cloud_consent, secrets=secrets)
    if resolve_vision_cloud_consent(cfg_consent=vision_consent, secrets=secrets):
        return
    raise LlmError(
        f"Vision on cloud provider {provider!r} requires separate consent. "
        "Set llm.vision_cloud_consent=true in config.json, or "
        '{"vision_cloud_consent": true} in ~/.config/chronicle/secrets.json. '
        "Until then, vision stays local (Ollama) or is skipped."
    )
