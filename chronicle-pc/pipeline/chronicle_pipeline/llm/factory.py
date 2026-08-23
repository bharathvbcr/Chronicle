"""Build the active LlmProvider from vault config + off-vault secrets."""

from __future__ import annotations

import logging
from typing import Any

from ..models import ChronicleConfig, LlmOptions
from . import secrets as secrets_mod
from .consent import require_cloud_consent
from .grok_provider import DEFAULT_GROK_BASE, DEFAULT_GROK_MODEL, GrokProvider
from .ollama_provider import OllamaProvider
from .protocol import LlmError, LlmProvider
from .vertex_provider import DEFAULT_LOCATION, DEFAULT_MODEL, VertexProvider

log = logging.getLogger("chronicle.llm")


def provider_name(cfg: ChronicleConfig | None = None) -> str:
    if cfg is None:
        return "ollama"
    name = (cfg.llm.provider if cfg.llm else "ollama") or "ollama"
    return str(name).strip().lower() or "ollama"


def get_provider(
    cfg: ChronicleConfig,
    *,
    secrets: dict[str, Any] | None = None,
    enforce_consent: bool = True,
) -> LlmProvider:
    """
    Construct the configured chat/vision provider.

    Embeddings are never routed here — callers must use ``ollama.embed`` /
    ``ollama.try_embed`` (nomic @ 768) regardless of chat provider.
    """
    name = provider_name(cfg)
    llm = cfg.llm or LlmOptions()
    sec = secrets if secrets is not None else secrets_mod.load_secrets()

    if enforce_consent and name != "ollama":
        require_cloud_consent(
            name,
            cfg_consent=bool(llm.cloud_consent),
            secrets=sec,
        )

    if name == "ollama":
        return OllamaProvider()

    if name == "grok":
        key = secrets_mod.grok_api_key(sec)
        if not key:
            raise LlmError(
                "Grok selected but no API key. Set GROK_API_KEY or "
                "grok_api_key in ~/.config/chronicle/secrets.json"
            )
        from ..url_allowlist import is_allowed_grok_url

        grok_opts = llm.grok
        base = (grok_opts.base_url if grok_opts else None) or DEFAULT_GROK_BASE
        if not is_allowed_grok_url(base):
            raise LlmError(
                f"Grok base_url must be https://api.x.ai only; refused {base!r}"
            )
        model = (grok_opts.model if grok_opts else None) or cfg.models.llm or DEFAULT_GROK_MODEL
        vision = cfg.models.vision or model
        return GrokProvider(
            api_key=key,
            base_url=base,
            default_model=model,
            vision_model=vision,
        )

    if name == "vertex":
        vopts = llm.vertex
        project = (vopts.project if vopts else None) or secrets_mod.vertex_project(sec)
        if not project:
            raise LlmError(
                "Vertex selected but no project. Set llm.vertex.project in config.json "
                "or GOOGLE_CLOUD_PROJECT / vertex_project in secrets."
            )
        location = (vopts.location if vopts else None) or DEFAULT_LOCATION
        model = (vopts.model if vopts else None) or cfg.models.llm or DEFAULT_MODEL
        vision = cfg.models.vision or model
        return VertexProvider(
            project=project,
            location=location,
            default_model=model,
            vision_model=vision,
        )

    raise LlmError(f"Unknown llm.provider {name!r}; expected ollama|grok|vertex")


def try_get_provider(
    cfg: ChronicleConfig,
    *,
    secrets: dict[str, Any] | None = None,
) -> LlmProvider | None:
    """Like get_provider but returns None on config/consent/key errors."""
    try:
        return get_provider(cfg, secrets=secrets, enforce_consent=True)
    except LlmError as e:
        log.warning("LLM provider unavailable: %s", e)
        return None
