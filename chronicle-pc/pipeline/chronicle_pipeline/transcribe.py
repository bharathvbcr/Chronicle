"""whisper.cpp subprocess wrapper with graceful skip."""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

log = logging.getLogger("chronicle.transcribe")


def find_whisper_binary() -> str | None:
    """Locate whisper.cpp main / whisper-cli binary."""
    # Never PATH-search bare "main" (PATH confusion / binary planting).
    for name in ("whisper-cli", "whisper-cpp", "whisper"):
        found = shutil.which(name)
        if found:
            return found
    # Common local install paths (explicit whisper.cpp build paths only).
    for candidate in (
        Path.home() / "whisper.cpp" / "build" / "bin" / "whisper-cli",
        Path.home() / "whisper.cpp" / "main",
        Path("/usr/local/bin/whisper-cli"),
    ):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def find_whisper_model() -> str | None:
    env = os.environ.get("WHISPER_MODEL")
    if env and Path(env).is_file():
        return env
    for candidate in (
        Path.home() / "whisper.cpp" / "models" / "ggml-base.en.bin",
        Path.home() / "whisper.cpp" / "models" / "ggml-base.bin",
        Path.home() / "models" / "ggml-base.en.bin",
    ):
        if candidate.is_file():
            return str(candidate)
    return None


def transcribe(
    audio_path: Path,
    *,
    binary: str | None = None,
    model: str | None = None,
    timeout: float = 600.0,
) -> str | None:
    """
    Transcribe audio via whisper.cpp.
    Returns transcript text, or None if binary/model missing or transcription fails.
    """
    audio_path = Path(audio_path)
    if not audio_path.is_file():
        log.warning("Audio file missing: %s", audio_path)
        return None

    bin_path = binary or find_whisper_binary()
    if not bin_path:
        log.warning(
            "whisper.cpp binary not found; skipping transcription for %s. "
            "Install whisper-cli and set PATH, or place binary under ~/whisper.cpp/",
            audio_path.name,
        )
        return None

    model_path = model or find_whisper_model()
    if not model_path:
        log.warning(
            "whisper model not found; skipping transcription for %s. "
            "Set WHISPER_MODEL to a ggml-*.bin path.",
            audio_path.name,
        )
        return None

    with tempfile.TemporaryDirectory(prefix="chronicle-whisper-") as tmp:
        out_base = Path(tmp) / "out"
        cmd = [
            bin_path,
            "-m",
            model_path,
            "-f",
            str(audio_path),
            "-otxt",
            "-of",
            str(out_base),
            "-np",
        ]
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as e:
            log.warning("whisper.cpp failed for %s: %s", audio_path.name, e)
            return None

        txt_path = Path(str(out_base) + ".txt")
        if txt_path.is_file():
            text = txt_path.read_text(encoding="utf-8", errors="replace").strip()
            if text:
                return text

        # Some builds print transcript to stdout
        stdout = (proc.stdout or "").strip()
        if stdout and proc.returncode == 0:
            # Filter whisper progress lines
            lines = [
                ln
                for ln in stdout.splitlines()
                if ln.strip() and not ln.strip().startswith("[")
            ]
            if lines:
                return "\n".join(lines).strip()

        log.warning(
            "whisper.cpp produced no transcript for %s (rc=%s)",
            audio_path.name,
            proc.returncode,
        )
        return None
