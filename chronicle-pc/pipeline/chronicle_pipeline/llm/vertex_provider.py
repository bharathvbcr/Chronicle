"""Google Vertex AI Generative Models adapter (native generateContent, not OpenAI)."""

from __future__ import annotations

import base64
import logging
import mimetypes
from pathlib import Path
from typing import Any

import requests

from ..ollama import strip_think_blocks
from .protocol import LlmError

log = logging.getLogger("chronicle.llm.vertex")

DEFAULT_LOCATION = "us-central1"
DEFAULT_MODEL = "gemini-2.0-flash-001"
DEFAULT_TIMEOUT = 300.0


def _adc_access_token() -> str:
    """Fetch an OAuth token via Application Default Credentials (optional dep)."""
    try:
        import google.auth
        from google.auth.transport.requests import Request as GoogleAuthRequest
    except ImportError as e:
        raise LlmError(
            "Vertex provider requires google-auth. "
            "Install with: pip install google-auth. "
            "Then run: gcloud auth application-default login"
        ) from e
    try:
        credentials, _project = google.auth.default(
            scopes=["https://www.googleapis.com/auth/cloud-platform"]
        )
        credentials.refresh(GoogleAuthRequest())
    except Exception as e:  # noqa: BLE001 — surface ADC failures clearly
        raise LlmError(
            f"Vertex ADC failed: {e}. "
            "Run gcloud auth application-default login or set GOOGLE_APPLICATION_CREDENTIALS."
        ) from e
    token = getattr(credentials, "token", None)
    if not token:
        raise LlmError("Vertex ADC returned no access token")
    return str(token)


class VertexProvider:
    name = "vertex"

    def __init__(
        self,
        *,
        project: str,
        location: str = DEFAULT_LOCATION,
        default_model: str = DEFAULT_MODEL,
        vision_model: str | None = None,
        access_token: str | None = None,
    ) -> None:
        proj = (project or "").strip()
        if not proj:
            raise LlmError(
                "Vertex provider requires project "
                "(llm.vertex.project, VERTEX_PROJECT, or GOOGLE_CLOUD_PROJECT)"
            )
        self.project = proj
        self.location = (location or DEFAULT_LOCATION).strip() or DEFAULT_LOCATION
        self.default_model = default_model or DEFAULT_MODEL
        self.vision_model = vision_model or self.default_model
        self._access_token = (access_token or "").strip() or None

    def _token(self) -> str:
        if self._access_token:
            return self._access_token
        return _adc_access_token()

    def _endpoint(self, model: str) -> str:
        return (
            f"https://{self.location}-aiplatform.googleapis.com/v1/"
            f"projects/{self.project}/locations/{self.location}/"
            f"publishers/google/models/{model}:generateContent"
        )

    def reachable(self, timeout: float = 2.0) -> bool:
        try:
            token = self._token()
        except LlmError:
            return False
        # Lightweight: GET model metadata (or HEAD-equivalent via generate with empty fails).
        url = (
            f"https://{self.location}-aiplatform.googleapis.com/v1/"
            f"projects/{self.project}/locations/{self.location}/"
            f"publishers/google/models/{self.default_model}"
        )
        try:
            r = requests.get(
                url,
                headers={"Authorization": f"Bearer {token}"},
                timeout=timeout,
            )
            return r.status_code < 500
        except requests.RequestException:
            return False

    def _contents_from_messages(
        self,
        messages: list[dict[str, Any]],
    ) -> tuple[str | None, list[dict[str, Any]]]:
        system_parts: list[str] = []
        contents: list[dict[str, Any]] = []
        for msg in messages:
            role = (msg.get("role") or "user").lower()
            content = msg.get("content")
            if role == "system":
                if isinstance(content, str) and content.strip():
                    system_parts.append(content.strip())
                continue
            gemini_role = "model" if role == "assistant" else "user"
            parts: list[dict[str, Any]] = []
            if isinstance(content, str):
                parts.append({"text": content})
            elif isinstance(content, list):
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    if part.get("type") == "text" and part.get("text"):
                        parts.append({"text": str(part["text"])})
                    elif part.get("type") == "image_url":
                        url = (part.get("image_url") or {}).get("url") or ""
                        if isinstance(url, str) and url.startswith("data:"):
                            # data:image/jpeg;base64,...
                            try:
                                header, b64 = url.split(",", 1)
                                mime = header.split(";")[0].split(":")[1]
                            except (ValueError, IndexError):
                                continue
                            parts.append(
                                {
                                    "inlineData": {
                                        "mimeType": mime,
                                        "data": b64,
                                    }
                                }
                            )
            if parts:
                contents.append({"role": gemini_role, "parts": parts})
        system = "\n\n".join(system_parts) if system_parts else None
        return system, contents

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
        num_ctx: int | None = None,  # noqa: ARG002
        timeout: float = DEFAULT_TIMEOUT,
    ) -> str:
        resolved = model or self.default_model
        system, contents = self._contents_from_messages(messages)
        if not contents:
            raise LlmError("Vertex chat requires at least one user/assistant message")
        generation: dict[str, Any] = {
            "temperature": temperature,
            "topP": top_p,
            "topK": int(top_k),
        }
        if num_predict is not None:
            generation["maxOutputTokens"] = int(num_predict)
        if format_json:
            generation["responseMimeType"] = "application/json"
        payload: dict[str, Any] = {
            "contents": contents,
            "generationConfig": generation,
        }
        if system:
            payload["systemInstruction"] = {"parts": [{"text": system}]}
        try:
            r = requests.post(
                self._endpoint(resolved),
                headers={
                    "Authorization": f"Bearer {self._token()}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=timeout,
            )
            r.raise_for_status()
            data = r.json()
        except requests.RequestException as e:
            raise LlmError(f"Vertex chat failed: {e}") from e
        text = _extract_vertex_text(data)
        return strip_think_blocks(text.strip())

    def describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
        prompt: str = "Describe this journal photo in 1-2 factual sentences.",
        timeout: float = DEFAULT_TIMEOUT,
    ) -> str:
        path = Path(image_path)
        raw = path.read_bytes()
        mime = mimetypes.guess_type(path.name)[0] or "image/jpeg"
        b64 = base64.b64encode(raw).decode("ascii")
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:{mime};base64,{b64}"},
                    },
                ],
            }
        ]
        return self.chat(
            messages,
            model=model or self.vision_model,
            temperature=0.1,
            num_predict=200,
            timeout=timeout,
        )

    def try_chat(self, *args: Any, **kwargs: Any) -> str | None:
        if not self.reachable():
            log.warning("Vertex unreachable; skipping LLM call")
            return None
        try:
            return self.chat(*args, **kwargs)
        except LlmError as e:
            log.warning("Vertex chat skipped: %s", e)
            return None

    def try_describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
    ) -> str | None:
        if not self.reachable():
            log.warning("Vertex unreachable; skipping vision for %s", image_path)
            return None
        try:
            return self.describe_image(image_path, model=model)
        except (LlmError, OSError) as e:
            log.warning("Vertex vision skipped for %s: %s", image_path, e)
            return None


def _extract_vertex_text(data: Any) -> str:
    if not isinstance(data, dict):
        raise LlmError("Vertex response was not a JSON object")
    cands = data.get("candidates")
    if not isinstance(cands, list) or not cands:
        raise LlmError("Vertex response missing candidates")
    content = cands[0].get("content") if isinstance(cands[0], dict) else None
    parts = (content or {}).get("parts") if isinstance(content, dict) else None
    if not isinstance(parts, list):
        raise LlmError("Vertex response missing content.parts")
    texts: list[str] = []
    for part in parts:
        if isinstance(part, dict) and isinstance(part.get("text"), str):
            texts.append(part["text"])
    if not texts:
        raise LlmError("Vertex response contained no text parts")
    return "\n".join(texts)
