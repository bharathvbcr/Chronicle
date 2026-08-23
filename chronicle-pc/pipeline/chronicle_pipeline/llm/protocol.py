"""LLM provider protocol and shared errors."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Protocol, runtime_checkable


class LlmError(RuntimeError):
    """Raised when a chat/vision call fails or cloud policy blocks the call."""


@runtime_checkable
class LlmProvider(Protocol):
    """Chat / vision facade. Embeddings stay on Ollama (see ``ollama.embed``)."""

    name: str

    def reachable(self, timeout: float = 2.0) -> bool:
        """True when the active provider can accept requests."""
        ...

    def chat(
        self,
        messages: list[dict[str, Any]],
        *,
        model: str | None = None,
        temperature: float = 0.6,
        top_p: float = 0.95,
        top_k: int = 20,
        format_json: bool = False,
        num_predict: int | None = None,
        num_ctx: int | None = None,
        timeout: float = 300.0,
    ) -> str:
        ...

    def describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
        prompt: str = "Describe this journal photo in 1-2 factual sentences.",
        timeout: float = 300.0,
    ) -> str:
        ...

    def try_chat(self, *args: Any, **kwargs: Any) -> str | None:
        ...

    def try_describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
    ) -> str | None:
        ...
