#!/usr/bin/env bash
# Start the Mac Chronicle stack: LAN gateway + browser dashboard.
# Prefer the Tauri shell when built: Start Chronicle.command opens Chronicle.app.
# Fallback: ./start_dashboard.sh (serve + open http://127.0.0.1:<port>/).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$ROOT/.." && pwd)"
VENV="$ROOT/.venv"
SERVE_PORT="${SERVE_PORT:-8765}"

# Prime Application Support so Finder/Dock opens of /Applications/Chronicle.app
# can resolve the repo venv (cwd under Finder is often `/`).
SUPPORT_DIR="${HOME}/Library/Application Support/Chronicle"
if [[ -f "$ROOT/.venv/bin/chronicle" ]]; then
  mkdir -p "$SUPPORT_DIR"
  printf '%s\n' "$ROOT" > "$SUPPORT_DIR/pc_root"
  chmod 600 "$SUPPORT_DIR/pc_root" 2>/dev/null || true
fi
export CHRONICLE_PC_ROOT="${CHRONICLE_PC_ROOT:-$ROOT}"

TAURI_BIN_RELEASE="$ROOT/desktop/src-tauri/target/release/chronicle"
TAURI_APP="$ROOT/desktop/src-tauri/target/release/bundle/macos/Chronicle.app"
if [[ "${CHRONICLE_FORCE_BROWSER:-}" != "1" && "${1:-}" != "--browser" ]]; then
  if [[ -x "$TAURI_BIN_RELEASE" ]]; then
    echo "Tauri binary found — launching it (use --browser or CHRONICLE_FORCE_BROWSER=1 to force this script)."
    exec "$TAURI_BIN_RELEASE"
  fi
  if [[ -d "$TAURI_APP" ]]; then
    echo "Tauri Chronicle.app found — opening it (use --browser or CHRONICLE_FORCE_BROWSER=1 to force this script)."
    open "$TAURI_APP"
    exit 0
  fi
fi

# --- Chronicle folder ---
if [[ -z "${CHRONICLE_DIR:-}" ]]; then
  if [[ -d "${HOME}/Chronicle" ]]; then
    export CHRONICLE_DIR="${HOME}/Chronicle"
  elif [[ -d "$REPO_ROOT/demo-vault" ]]; then
    export CHRONICLE_DIR="$REPO_ROOT/demo-vault"
  else
    echo "Set CHRONICLE_DIR to your Syncthing Chronicle folder, e.g.:"
    echo "  export CHRONICLE_DIR=~/Chronicle"
    exit 1
  fi
fi
export CHRONICLE_DIR
echo "CHRONICLE_DIR=${CHRONICLE_DIR}"

# --- Server binary: native Rust first, Python venv as legacy fallback ---
RS_BIN="$PC/server/target/release/chronicle"
RS_BIN_DEBUG="$PC/server/target/debug/chronicle"
if [[ -x "$RS_BIN" ]]; then
  CHRONICLE_BIN="$RS_BIN"
elif [[ -x "$RS_BIN_DEBUG" ]]; then
  CHRONICLE_BIN="$RS_BIN_DEBUG"
elif [[ -d "$VENV" && -x "$VENV/bin/chronicle" ]]; then
  # shellcheck disable=SC1091
  source "$VENV/bin/activate"
  command -v chronicle >/dev/null 2>&1 || { echo "no native binary and broken venv"; exit 1; }
  CHRONICLE_BIN="$(command -v chronicle)"
else
  echo "No server found. Build: (cd server && cargo build --release) or install python -e \".[dev]\""
  exit 1
fi

# --- Discover a live /connect endpoint (never open file://) ---
serve_json_port() {
  local serve_json="${CHRONICLE_DIR}/index/serve.json"
  [[ -f "$serve_json" ]] || return 1
  python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('port',''))" "$serve_json" 2>/dev/null
}

probe_connect() {
  local port="$1"
  curl -sf --max-time 1 "http://127.0.0.1:${port}/connect" >/dev/null 2>&1
}

find_live_serve_port() {
  local ports=()
  local jp p
  jp="$(serve_json_port || true)"
  [[ -n "${jp:-}" ]] && ports+=("$jp")
  ports+=("$SERVE_PORT")
  for p in $(seq "$SERVE_PORT" $((SERVE_PORT + 49))); do
    ports+=("$p")
  done
  local -a uniq=()
  local seen="|"
  for p in "${ports[@]}"; do
    case "$seen" in
      *"|$p|"*) ;;
      *) uniq+=("$p"); seen="${seen}${p}|" ;;
    esac
  done
  for p in "${uniq[@]}"; do
    if probe_connect "$p"; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

open_dashboard() {
  local url="http://127.0.0.1:${SERVE_PORT}/"
  echo "Opening dashboard at ${url} ..."
  if [[ "$(uname -s)" == "Darwin" ]]; then
    open "$url"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$url" >/dev/null 2>&1 || true
  fi
}

# If a healthy gateway is already up, just open it (avoids zombie port cascade).
if LIVE_PORT="$(find_live_serve_port)"; then
  SERVE_PORT="$LIVE_PORT"
  echo "Chronicle serve already online at http://127.0.0.1:${SERVE_PORT}/ — reusing it."
  open_dashboard
  echo "Dashboard opened. (This launcher exits; leave the existing serve Terminal running.)"
  exit 0
fi

wait_and_open_dashboard() {
  for _ in $(seq 1 80); do
    if LIVE_PORT="$(find_live_serve_port)"; then
      SERVE_PORT="$LIVE_PORT"
      open_dashboard
      return 0
    fi
    sleep 0.25
  done
  echo "ERROR: chronicle serve did not become reachable on 127.0.0.1."
  echo "Not opening file:// dashboard (Connect QR requires HTTP)."
  echo "Check the Terminal output above for bind errors."
  return 1
}

echo ""
echo "LAN gateway starting (Ctrl+C stops serve)."
echo "Scan the QR in Android Settings → Scan Mac QR."
echo "Ask / Resume are native (no separate brain_server)."
echo ""

wait_and_open_dashboard &
OPENER_PID=$!

# `|| SERVE_STATUS=$?` keeps set -e from exiting before the opener cleanup.
SERVE_STATUS=0
"$CHRONICLE_BIN" serve --lan --port "$SERVE_PORT" || SERVE_STATUS=$?
kill "$OPENER_PID" 2>/dev/null || true
wait "$OPENER_PID" 2>/dev/null || true
exit "$SERVE_STATUS"
