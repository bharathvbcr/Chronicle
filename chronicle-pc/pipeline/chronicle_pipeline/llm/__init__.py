"""Multi-provider LLM facade (Ollama | Grok | Vertex).

Chat/vision go through ``get_provider``. Embeddings stay on Ollama
(``chronicle_pipeline.ollama.embed``) at nomic-embed-text @ 768 — never cloud.
"""

from __future__ import annotations

# Re-export parse helpers used by call sites (still implemented in ollama.py).
from ..ollama import extract_json, strip_think_blocks  # noqa: F401
from .consent import (
    is_cloud_provider,
    require_cloud_consent,
    require_vision_cloud_consent,
    resolve_cloud_consent,
    resolve_vision_cloud_consent,
)
from .context import CLOUD, LOCAL, ContextLimits, context_limits
from .factory import get_provider, provider_name, try_get_provider
from .protocol import LlmError, LlmProvider
from .rate_limit import CLOUD_LLM_PATHS, check_cloud_rate_limit, reset_for_tests
from .secrets import load_secrets, secrets_path

__all__ = [
    "CLOUD",
    "CLOUD_LLM_PATHS",
    "LOCAL",
    "ContextLimits",
    "LlmError",
    "LlmProvider",
    "check_cloud_rate_limit",
    "context_limits",
    "extract_json",
    "get_provider",
    "is_cloud_provider",
    "load_secrets",
    "provider_name",
    "require_cloud_consent",
    "require_vision_cloud_consent",
    "reset_for_tests",
    "resolve_cloud_consent",
    "resolve_vision_cloud_consent",
    "secrets_path",
    "strip_think_blocks",
    "try_get_provider",
]
