"""Export Chronicle data (chronosflow format)."""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path

from .config import ensure_config
from .entries import load_all_entries
from .paths import atomic_write_text, resolve_chronicle_dir

log = logging.getLogger("chronicle.export")


def export_chronosflow(root: Path, out_path: Path) -> Path:
    """
    Chronosflow: a portable JSON bundle of entries + brain summaries.
    """
    cfg = ensure_config(root)
    entries = [e.model_dump(mode="json") for e in load_all_entries(root, fallback_tz=cfg.timezone)]
    brain: dict = {}
    for name in ("graph.json", "tags.json", "prompts.json"):
        p = root / "brain" / name
        if p.is_file():
            brain[name] = json.loads(p.read_text(encoding="utf-8"))

    bundle = {
        "format": "chronosflow",
        "version": 1,
        "exported": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "entries": entries,
        "brain": brain,
    }
    text = json.dumps(bundle, indent=2, ensure_ascii=False) + "\n"
    atomic_write_text(out_path, text)
    return out_path


def run_export(
    chronicle_dir: Path | str | None = None,
    *,
    format: str = "chronosflow",
    path: str | Path | None = None,
) -> dict:
    root = resolve_chronicle_dir(chronicle_dir)
    fmt = (format or "chronosflow").lower()
    if fmt != "chronosflow":
        raise ValueError(f"unsupported export format: {format} (supported: chronosflow)")

    out = Path(path) if path else root / f"chronicle-export-{datetime.now().strftime('%Y%m%d')}.chronosflow.json"
    out = out.expanduser().resolve()
    export_chronosflow(root, out)
    log.info("Exported chronosflow → %s", out)
    return {"format": fmt, "path": str(out)}
