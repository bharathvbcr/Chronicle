"""Vault layout_version hard gate (Phase 4 file-once)."""

from __future__ import annotations

from pathlib import Path

# Phase 4: capture under _capture/, media under _attachments/, journal under 40-Journal/.
CURRENT_LAYOUT_VERSION = 2
MIN_SUPPORTED_LAYOUT_VERSION = 2


class LayoutVersionError(RuntimeError):
    """Vault layout_version does not match this Chronicle build."""


def require_layout_version(root: Path, *, cfg=None) -> int:
    """
    Refuse process/serve (and other mutating pipeline entrypoints) when the vault
    layout_version does not match this build. Loud error > silent path black hole.
    """
    if cfg is None:
        from .config import load_config

        cfg = load_config(root)
    version = int(getattr(cfg, "layout_version", 1) or 1)
    if version != CURRENT_LAYOUT_VERSION:
        raise LayoutVersionError(
            f"Vault layout_version={version} is incompatible with this Chronicle "
            f"build (requires layout_version={CURRENT_LAYOUT_VERSION} for "
            f"file-once paths: _capture/, _attachments/, 40-Journal/). "
            f"Copy the vault if needed, run `chronicle backup` (zip outside "
            f"Syncthing), then "
            f"`chronicle migrate-journal-v2 --apply --i-have-backup`. "
            f"Co-release APK + CLI so phone and Mac agree on paths."
        )
    return version
