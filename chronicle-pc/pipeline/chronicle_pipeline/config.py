"""Load / save PC-owned config.json."""

from __future__ import annotations

from pathlib import Path

from . import ollama
from .models import (
    ChronicleConfig,
    ConfigModels,
    GrokOptions,
    LlmOptions,
    OllamaOptions,
    VertexOptions,
)
from .paths import atomic_write_json, read_json, resolve_chronicle_dir

DEFAULT_MODELS = ConfigModels(
    llm="maxwell1500/ornith-35b:Q4_K_M",
    embed="nomic-embed-text",
    vision="llama3.2-vision:11b",
    whisper="whisper",
)

DEFAULT_OLLAMA = OllamaOptions(
    base_url="http://localhost:11434",
    num_ctx=32768,
    temperature=None,
)

DEFAULT_LLM = LlmOptions(
    provider="ollama",
    cloud_consent=False,
    vision_cloud_consent=False,
    grok=GrokOptions(),
    vertex=VertexOptions(),
)


def config_path(chronicle_dir: Path | None = None) -> Path:
    root = resolve_chronicle_dir(chronicle_dir) if chronicle_dir else resolve_chronicle_dir()
    return root / "config.json"


def _apply_ollama(cfg: ChronicleConfig) -> None:
    ollama.apply_settings(
        base_url=cfg.ollama.base_url,
        num_ctx=cfg.ollama.num_ctx,
        temperature=cfg.ollama.temperature,
    )


def _merge_llm_block(raw: dict) -> None:
    if "llm" not in raw or not isinstance(raw.get("llm"), dict):
        raw["llm"] = DEFAULT_LLM.model_dump()
        return
    merged = DEFAULT_LLM.model_dump()
    incoming = raw["llm"]
    for key, val in incoming.items():
        if key in ("grok", "vertex") and isinstance(val, dict):
            base = merged.get(key) if isinstance(merged.get(key), dict) else {}
            base = dict(base or {})
            base.update(val)
            merged[key] = base
        else:
            merged[key] = val
    raw["llm"] = merged


def load_config(
    chronicle_dir: Path | str | None = None,
    *,
    default_layout_version: int | None = None,
) -> ChronicleConfig:
    """Load vault config.json.

    ``default_layout_version`` is for migrate tooling only: when the key is
    missing on disk, treat it as that value in-memory (do not write). Normal
    callers leave it ``None`` so a missing key stays a hard error.
    """
    root = resolve_chronicle_dir(chronicle_dir)
    path = root / "config.json"
    if not path.exists():
        from .vault_layout import CURRENT_LAYOUT_VERSION

        cfg = ChronicleConfig(
            version=1,
            layout_version=CURRENT_LAYOUT_VERSION,
            timezone="UTC",
            models=DEFAULT_MODELS,
            ollama=DEFAULT_OLLAMA,
            llm=DEFAULT_LLM,
        )
        _apply_ollama(cfg)
        return cfg
    raw = read_json(path)
    from .vault_layout import LayoutVersionError

    if "layout_version" not in raw:
        if default_layout_version is None:
            raise LayoutVersionError(
                "config.json is missing required layout_version. "
                "New installs: add \"layout_version\": 2. "
                "Legacy vaults: add \"layout_version\": 1, then "
                "`chronicle backup` + `migrate-journal-v2 --apply --i-have-backup` "
                "(which bumps to 2). Or restore a known-good config.json."
            )
        raw = {**raw, "layout_version": int(default_layout_version)}
    if "models" not in raw or not raw["models"]:
        raw["models"] = DEFAULT_MODELS.model_dump()
    else:
        merged = DEFAULT_MODELS.model_dump()
        merged.update(raw["models"])
        raw["models"] = merged
    if "ollama" not in raw or not isinstance(raw.get("ollama"), dict):
        raw["ollama"] = DEFAULT_OLLAMA.model_dump()
    else:
        merged_o = DEFAULT_OLLAMA.model_dump()
        merged_o.update(raw["ollama"])
        raw["ollama"] = merged_o
    _merge_llm_block(raw)
    cfg = ChronicleConfig.model_validate(raw)
    _apply_ollama(cfg)
    return cfg


def save_config(cfg: ChronicleConfig, chronicle_dir: Path | str | None = None) -> Path:
    root = resolve_chronicle_dir(chronicle_dir)
    path = root / "config.json"
    data = cfg.model_dump(exclude_none=True)
    atomic_write_json(path, data)
    _apply_ollama(cfg)
    return path


def ensure_config(chronicle_dir: Path | str | None = None) -> ChronicleConfig:
    root = resolve_chronicle_dir(chronicle_dir)
    path = root / "config.json"
    if path.exists():
        return load_config(root)
    from .vault_layout import CURRENT_LAYOUT_VERSION

    cfg = ChronicleConfig(
        version=1,
        layout_version=CURRENT_LAYOUT_VERSION,
        timezone="UTC",
        models=DEFAULT_MODELS,
        ollama=DEFAULT_OLLAMA,
        llm=DEFAULT_LLM,
    )
    save_config(cfg, root)
    return cfg
