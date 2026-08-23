"""Stricter retrieval/context caps when the chat provider is cloud."""

from __future__ import annotations

from dataclasses import dataclass

from .consent import is_cloud_provider


@dataclass(frozen=True)
class ContextLimits:
    hit_text_limit: int
    recall_top_k: int
    ask_top_k: int
    resume_top_k: int
    rollup_max_chars: int
    rollup_max_notes: int
    num_ctx_ask: int
    num_ctx_resume: int
    num_ctx_recall: int
    num_ctx_enrich: int
    num_ctx_brain: int


# Local (Ollama / Ornith) — match historical rag/ollama defaults.
LOCAL = ContextLimits(
    hit_text_limit=16000,
    recall_top_k=12,
    ask_top_k=10,
    resume_top_k=14,
    rollup_max_chars=12000,
    rollup_max_notes=4,
    num_ctx_ask=65536,
    num_ctx_resume=65536,
    num_ctx_recall=131072,
    num_ctx_enrich=16384,
    num_ctx_brain=32768,
)

# Cloud — reduce journal egress (plan: high-PII).
CLOUD = ContextLimits(
    hit_text_limit=2000,
    recall_top_k=6,
    ask_top_k=5,
    resume_top_k=6,
    rollup_max_chars=3000,
    rollup_max_notes=2,
    num_ctx_ask=8192,
    num_ctx_resume=8192,
    num_ctx_recall=16384,
    num_ctx_enrich=8192,
    num_ctx_brain=8192,
)


def context_limits(provider: str) -> ContextLimits:
    return CLOUD if is_cloud_provider(provider) else LOCAL
