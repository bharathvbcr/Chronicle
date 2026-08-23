"""Ollama-backed LlmProvider (wraps chronicle_pipeline.ollama)."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from .. import ollama as ollama_mod
from .protocol import LlmError

log = logging.getLogger("chronicle.llm.ollama")


class OllamaProvider:
    name = "ollama"

    def reachable(self, timeout: float = 2.0) -> bool:
        return ollama_mod.ollama_reachable(timeout=timeout)

    def chat(
        self,
        messages: list[dict[str, Any]],
        *,
        model: str | None = None,
        temperature: float = ollama_mod.DEFAULT_TEMPERATURE,
        top_p: float = ollama_mod.DEFAULT_TOP_P,
        top_k: int = ollama_mod.DEFAULT_TOP_K,
        format_json: bool = False,
        num_predict: int | None = None,
        num_ctx: int | None = None,
        timeout: float = ollama_mod.DEFAULT_TIMEOUT,
    ) -> str:
        try:
            return ollama_mod.chat(
                messages,
                model=model,
                temperature=temperature,
                top_p=top_p,
                top_k=top_k,
                format_json=format_json,
                num_predict=num_predict,
                num_ctx=num_ctx,
                timeout=timeout,
            )
        except ollama_mod.OllamaError as e:
            raise LlmError(str(e)) from e

    def describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
        prompt: str = "Describe this journal photo in 1-2 factual sentences.",
        timeout: float = ollama_mod.DEFAULT_TIMEOUT,
    ) -> str:
        try:
            return ollama_mod.describe_image(
                image_path, model=model, prompt=prompt, timeout=timeout
            )
        except (ollama_mod.OllamaError, OSError) as e:
            raise LlmError(str(e)) from e

    def try_chat(self, *args: Any, **kwargs: Any) -> str | None:
        if not self.reachable():
            log.warning("Ollama unreachable; skipping LLM call")
            return None
        try:
            return self.chat(*args, **kwargs)
        except LlmError as e:
            log.warning("Ollama chat skipped: %s", e)
            return None

    def try_describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
    ) -> str | None:
        if not self.reachable():
            log.warning("Ollama unreachable; skipping vision for %s", image_path)
            return None
        try:
            return self.describe_image(image_path, model=model)
        except LlmError as e:
            log.warning("Vision describe skipped for %s: %s", image_path, e)
            return None
