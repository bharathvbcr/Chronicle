#!/usr/bin/env bash
# Double-click in Finder to open Chronicle.
# Prefers the Tauri desktop app when built; otherwise starts serve + browser.
cd "$(dirname "$0")"

ROOT="$(pwd)"
export CHRONICLE_PC_ROOT="$ROOT"

# Prime Application Support so /Applications/Chronicle.app can find the venv
# even when Finder launches it with cwd=/ and no env.
SUPPORT_DIR="${HOME}/Library/Application Support/Chronicle"
if [[ -f "$ROOT/.venv/bin/chronicle" ]]; then
  mkdir -p "$SUPPORT_DIR"
  printf '%s\n' "$ROOT" > "$SUPPORT_DIR/pc_root"
  chmod 600 "$SUPPORT_DIR/pc_root" 2>/dev/null || true
fi

TAURI_APP="$ROOT/desktop/src-tauri/target/release/bundle/macos/Chronicle.app"
TAURI_DEBUG_APP="$ROOT/desktop/src-tauri/target/debug/bundle/macos/Chronicle.app"
TAURI_BIN_RELEASE="$ROOT/desktop/src-tauri/target/release/chronicle"
TAURI_BIN_DEBUG="$ROOT/desktop/src-tauri/target/debug/chronicle"

# Prefer the binary so CHRONICLE_PC_ROOT / CHRONICLE_DIR reach the process
# (macOS `open Foo.app` does not reliably forward shell env).
if [[ -x "$TAURI_BIN_RELEASE" ]]; then
  echo "Launching Chronicle (Tauri release)…"
  exec "$TAURI_BIN_RELEASE"
fi

if [[ -x "$TAURI_BIN_DEBUG" ]]; then
  echo "Launching Chronicle (Tauri debug)…"
  exec "$TAURI_BIN_DEBUG"
fi

if [[ -d "$TAURI_APP" ]]; then
  echo "Opening Chronicle.app (pc_root primed for Dock/Finder launches)…"
  open "$TAURI_APP"
  exit 0
fi

if [[ -d "$TAURI_DEBUG_APP" ]]; then
  echo "Opening debug Chronicle.app…"
  open "$TAURI_DEBUG_APP"
  exit 0
fi

exec ./start_dashboard.sh
