"""Guards the accepted-risk exception for GHSA-wrw7-89jp-8q8g (glib unsoundness).

glib enters the dependency graph only through tauri's Linux backend
(glib <- atk <- gtk <- libappindicator <- tray-icon <- tauri). There is no
upgrade path: gtk 0.18 pins glib ^0.18, and the latest tauri release still
pins gtk ^0.18. The exception therefore rests on one fact -- Chronicle
desktop does not ship a Linux bundle.

That fact is a build-config choice, not a law, so it is asserted here rather
than remembered. If someone re-enables Linux bundling while glib is still
vulnerable, this fails instead of silently reinstating the risk. Once glib
reaches 0.20 the guard retires itself.

See SECURITY.md.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

DESKTOP = Path(__file__).resolve().parents[1] / "desktop"
TAURI_CONF = DESKTOP / "src-tauri" / "tauri.conf.json"
CARGO_LOCK = DESKTOP / "src-tauri" / "Cargo.lock"

# Bundle formats that cause the Linux (gtk/glib) backend to be built.
LINUX_TARGETS = frozenset({"deb", "rpm", "appimage"})
GLIB_FIXED_IN = (0, 20, 0)

pytestmark = pytest.mark.skipif(
    not TAURI_CONF.is_file(), reason="desktop shell not present"
)


def _locked_version(crate: str) -> tuple[int, ...] | None:
    if not CARGO_LOCK.is_file():
        return None
    match = re.search(
        rf'\[\[package\]\]\nname = "{re.escape(crate)}"\nversion = "([^"]+)"',
        CARGO_LOCK.read_text(encoding="utf-8"),
    )
    if not match:
        return None
    return tuple(int(p) for p in re.findall(r"\d+", match.group(1))[:3])


def _configured_targets() -> list[str] | str:
    return json.loads(TAURI_CONF.read_text(encoding="utf-8"))["bundle"]["targets"]


def test_glib_is_still_the_reason_this_guard_exists():
    """If glib is patched, delete this module and re-widen targets if desired."""
    version = _locked_version("glib")
    if version is None:
        pytest.skip("glib not in the lock file; the exception no longer applies")
    if version >= GLIB_FIXED_IN:
        pytest.fail(
            f"glib is now {version} (>= {GLIB_FIXED_IN}) -- GHSA-wrw7-89jp-8q8g is fixed. "
            "Remove this guard and the SECURITY.md exception."
        )


def test_desktop_does_not_bundle_linux_while_glib_is_vulnerable():
    version = _locked_version("glib")
    if version is None or version >= GLIB_FIXED_IN:
        pytest.skip("glib patched or absent; Linux bundling is no longer gated")

    targets = _configured_targets()
    if targets == "all":
        pytest.fail(
            'bundle.targets is "all", which includes Linux (deb/rpm/appimage) and so '
            f"builds glib {'.'.join(map(str, version))} -- vulnerable to "
            "GHSA-wrw7-89jp-8q8g with no upgrade available. List the macOS targets "
            "explicitly, or accept and document the risk in SECURITY.md."
        )

    assert isinstance(targets, list), f"expected a list of targets, got {targets!r}"
    enabled_linux = sorted(LINUX_TARGETS.intersection(targets))
    assert not enabled_linux, (
        f"Linux bundle target(s) {enabled_linux} enabled while glib is "
        f"{'.'.join(map(str, version))}; that reintroduces GHSA-wrw7-89jp-8q8g. "
        "Upgrade glib to >= 0.20 (needs tauri off gtk3-rs) before shipping Linux."
    )


def test_configured_targets_match_documented_platform_support():
    """The bundle should not claim platforms the project does not support."""
    targets = _configured_targets()
    assert targets != "all", 'targets "all" claims Linux and Windows support'
    assert set(targets) <= {"app", "dmg", "updater"}, (
        f"unexpected bundle targets {targets}; desktop/README.md documents macOS only"
    )
