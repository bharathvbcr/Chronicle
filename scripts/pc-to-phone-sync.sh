#!/usr/bin/env bash
# Wired PC → phone vault transfer (merge-only).
# Never pipe tar into `adb shell` (PTY corrupts the stream).
# Syncthing remains day-to-day sync; pause it during this push.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PC="$ROOT/chronicle-pc"
ANDROID="$ROOT/chronicle-android"
VENV="$PC/.venv"
START_CMD="$PC/Start Chronicle.command"
PKG="com.chronicle.app"
REMOTE_TAR="/data/local/tmp/chronicle-push.tar"
LOCAL_TAR="${TMPDIR:-/tmp}/chronicle-push.tar"
SERVE_PORT="${SERVE_PORT:-8765}"

PHONE_DIR_FLAG=""
SKIP_PROCESS=0
SKIP_LAUNCH=0
SKIP_ANDROID=0
SKIP_PUSH=0
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: pc-to-phone-sync.sh [options]

  Merge-push Mac vault → phone over ADB, then launch PC + installDebug.

Options:
  --phone-dir PATH   Phone vault path (or set CHRONICLE_PHONE_DIR)
  --skip-process     Skip chronicle process
  --skip-launch      Skip launching Chronicle PC
  --skip-android     Skip gradlew installDebug + am start
  --skip-push        Skip ADB vault push
  --dry-run          Resolve paths / print size; skip mutating steps
  -h, --help         Show this help
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

ok() {
  echo "OK: $*"
}

warn() {
  echo "WARN: $*" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --phone-dir)
      [[ $# -ge 2 ]] || die "--phone-dir requires a path"
      PHONE_DIR_FLAG="$2"
      shift 2
      ;;
    --skip-process) SKIP_PROCESS=1; shift ;;
    --skip-launch) SKIP_LAUNCH=1; shift ;;
    --skip-android) SKIP_ANDROID=1; shift ;;
    --skip-push) SKIP_PUSH=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown flag: $1 (try --help)" ;;
  esac
done

if [[ "$DRY_RUN" -eq 1 ]]; then
  SKIP_PROCESS=1
  SKIP_LAUNCH=1
  SKIP_ANDROID=1
  # Push still planned for size reporting; extract skipped later.
fi

cleanup() {
  rm -f "$LOCAL_TAR"
}
trap cleanup EXIT

# --- Fail-fast prerequisites -------------------------------------------------

command -v adb >/dev/null 2>&1 || die "adb not on PATH (install Android platform-tools)"
command -v python3 >/dev/null 2>&1 || die "python3 not on PATH"
command -v curl >/dev/null 2>&1 || die "curl not on PATH"
command -v md5 >/dev/null 2>&1 || command -v md5sum >/dev/null 2>&1 || die "md5 or md5sum required"

mapfile_devices() {
  adb devices | awk 'NR>1 && $2=="device" {print $1}'
}

DEVICES=()
while IFS= read -r serial; do
  [[ -n "$serial" ]] && DEVICES+=("$serial")
done < <(mapfile_devices)

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  die "no adb device in 'device' state (enable USB debugging)"
fi
if [[ ${#DEVICES[@]} -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
  die "multiple adb devices; set ANDROID_SERIAL to one of: ${DEVICES[*]}"
fi
ok "adb device ${ANDROID_SERIAL:-${DEVICES[0]}}"

resolve_java21() {
  local cand
  for cand in \
    "${JAVA_HOME:-}" \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  do
    if [[ -n "$cand" && -x "$cand/bin/java" ]]; then
      if "$cand/bin/java" -version 2>&1 | head -1 | grep -q 'version "21'; then
        echo "$cand"
        return 0
      fi
    fi
  done
  if cand="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    echo "$cand"
    return 0
  fi
  return 1
}

if [[ "$SKIP_ANDROID" -eq 0 ]]; then
  JAVA21="$(resolve_java21)" || die "JDK 21 required (brew install openjdk@21)"
  export JAVA_HOME="$JAVA21"
  ok "JAVA_HOME=$JAVA_HOME"
fi

if [[ -z "${CHRONICLE_DIR:-}" ]]; then
  if [[ -d "${HOME}/Chronicle" ]]; then
    CHRONICLE_DIR="${HOME}/Chronicle"
  else
    die "CHRONICLE_DIR unset and ~/Chronicle missing (export CHRONICLE_DIR=…)"
  fi
fi
CHRONICLE_DIR="$(cd "$CHRONICLE_DIR" && pwd)"
export CHRONICLE_DIR

DEMO_VAULT="$(cd "$ROOT/demo-vault" 2>/dev/null && pwd || true)"
if [[ -n "$DEMO_VAULT" && "$CHRONICLE_DIR" == "$DEMO_VAULT" ]]; then
  die "refusing repo demo-vault; copy it and migrate, or point CHRONICLE_DIR at your Syncthing vault"
fi

[[ -f "$CHRONICLE_DIR/config.json" ]] || die "missing $CHRONICLE_DIR/config.json"
if [[ -f "$CHRONICLE_DIR/secrets.json" ]]; then
  die "secrets.json found under vault root — move keys to ~/.config/chronicle/secrets.json"
fi

LAYOUT_VERSION="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("layout_version",""))' \
    "$CHRONICLE_DIR/config.json"
)"
[[ "$LAYOUT_VERSION" == "2" ]] || die "layout_version must be 2 (got '${LAYOUT_VERSION:-missing}'); migrate before sync"

ok "CHRONICLE_DIR=$CHRONICLE_DIR (layout_version=2)"

# Non-blocking process.lock probe (fcntl wait has no timeout).
LOCK_PATH="$CHRONICLE_DIR/index/process.lock"
if [[ -e "$LOCK_PATH" ]]; then
  if ! python3 - "$LOCK_PATH" <<'PY'
import fcntl, sys
path = sys.argv[1]
fd = open(path, "a+", encoding="utf-8")
try:
    fcntl.flock(fd.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    sys.exit(1)
else:
    fcntl.flock(fd.fileno(), fcntl.LOCK_UN)
finally:
    fd.close()
PY
  then
    die "index/process.lock is held (watch/process/serve busy); retry when idle"
  fi
fi
ok "process.lock free"

[[ -d "$VENV" ]] || die "missing $VENV — cd chronicle-pc && python3 -m venv .venv && pip install -e \".[dev]\""
# shellcheck disable=SC1091
source "$VENV/bin/activate"
command -v chronicle >/dev/null 2>&1 || die "'chronicle' not on PATH in .venv — pip install -e \".[dev]\""

# --- chronicle process -------------------------------------------------------

if [[ "$SKIP_PROCESS" -eq 0 ]]; then
  echo "Running chronicle process…"
  chronicle process
  ok "chronicle process"
else
  ok "skipped chronicle process"
fi

# --- Phone dir resolution ----------------------------------------------------

CURRENT_USER="$(adb shell am get-current-user 2>/dev/null | tr -d '\r' || echo 0)"
PHONE_DIR=""

phone_dir_looks_like_vault() {
  local dir="$1"
  adb shell "test -f '$dir/config.json' && { test -d '$dir/_capture' || test -d '$dir/40-Journal'; }" \
    >/dev/null 2>&1
}

decode_vault_uri_to_path() {
  # Best-effort: run-as prefs → primary: External Storage only.
  local xml path
  if [[ "$CURRENT_USER" != "0" ]]; then
    warn "non-user-0 (user=$CURRENT_USER); prefs decode skipped — use --phone-dir"
    return 1
  fi
  xml="$(adb exec-out run-as "$PKG" cat shared_prefs/chronicle_prefs.xml 2>/dev/null)" || return 1
  [[ -n "$xml" ]] || return 1
  path="$(
    printf '%s' "$xml" | python3 -c '
import html, re, sys
from urllib.parse import unquote

xml = sys.stdin.read()
m = re.search(r"<string\s+name=\"vault_uri\">([^<]*)</string>", xml)
if not m:
    sys.exit(1)
uri = html.unescape(m.group(1)).strip()
if "com.android.externalstorage.documents" not in uri:
    sys.exit(2)
if "/tree/" not in uri:
    sys.exit(2)
tree_part = uri.split("/tree/", 1)[1]
tree_id = unquote(tree_part.split("/document/", 1)[0])
if ":" not in tree_id:
    sys.exit(2)
volume, rel = tree_id.split(":", 1)
if volume != "primary":
    sys.exit(3)
rel = rel.lstrip("/")
print("/sdcard/" + rel if rel else "/sdcard")
'
  )" || return 1
  [[ -n "$path" ]] || return 1
  if adb shell "test -d '$path'" >/dev/null 2>&1; then
    echo "$path"
    return 0
  fi
  return 1
}

probe_phone_dirs() {
  local cand
  for cand in \
    /sdcard/Chronicle \
    /storage/emulated/0/Chronicle \
    /sdcard/Syncthing/Chronicle \
    /storage/emulated/0/Syncthing/Chronicle
  do
    if phone_dir_looks_like_vault "$cand"; then
      echo "$cand"
      return 0
    fi
  done
  return 1
}

resolve_phone_dir() {
  local found
  if [[ -n "$PHONE_DIR_FLAG" ]]; then
    PHONE_DIR="$PHONE_DIR_FLAG"
  elif [[ -n "${CHRONICLE_PHONE_DIR:-}" ]]; then
    PHONE_DIR="$CHRONICLE_PHONE_DIR"
  elif found="$(decode_vault_uri_to_path)"; then
    PHONE_DIR="$found"
    ok "phone dir from vault_uri prefs: $PHONE_DIR"
  elif found="$(probe_phone_dirs)"; then
    PHONE_DIR="$found"
    ok "phone dir from probe: $PHONE_DIR"
  else
    die "could not resolve phone vault dir. Set --phone-dir or CHRONICLE_PHONE_DIR (e.g. /sdcard/Chronicle). Prefs auto-discover needs a prior debug install + folder pick."
  fi

  if [[ "$CURRENT_USER" != "0" && -z "$PHONE_DIR_FLAG" && -z "${CHRONICLE_PHONE_DIR:-}" ]]; then
    die "work profile / non-user-0 requires --phone-dir or CHRONICLE_PHONE_DIR"
  fi

  # Explicit paths: ensure dir exists (create) or already looks usable.
  if ! adb shell "test -d '$PHONE_DIR'" >/dev/null 2>&1; then
    if [[ "$DRY_RUN" -eq 1 ]]; then
      warn "phone dir does not exist yet (would mkdir -p): $PHONE_DIR"
    else
      adb shell "mkdir -p '$PHONE_DIR'" >/dev/null
    fi
  fi
  ok "PHONE_DIR=$PHONE_DIR"
}

if [[ "$SKIP_PUSH" -eq 0 || "$DRY_RUN" -eq 1 ]]; then
  resolve_phone_dir
else
  ok "skipped phone dir resolve (--skip-push)"
fi

# --- Build portable ustar + push/extract -------------------------------------

mac_md5() {
  local f="$1"
  if command -v md5 >/dev/null 2>&1; then
    md5 -q "$f"
  else
    md5sum "$f" | awk '{print $1}'
  fi
}

phone_md5() {
  local f="$1"
  # toybox md5sum on device
  adb shell "md5sum '$f' 2>/dev/null" | tr -d '\r' | awk '{print $1}'
}

build_ustar() {
  rm -f "$LOCAL_TAR"
  # Portable ustar; never PTY-pipe into adb shell.
  COPYFILE_DISABLE=1 tar --format=ustar --no-xattrs --no-mac-metadata \
    --exclude=./index \
    --exclude=./.stfolder \
    --exclude=./.git \
    --exclude=./.venv \
    --exclude=./__pycache__ \
    --exclude='*.tmp' \
    --exclude='.DS_Store' \
    --exclude='*sync-conflict*' \
    --exclude='*/__pycache__' \
    -cf "$LOCAL_TAR" -C "$CHRONICLE_DIR" .
  ok "built ustar $(du -h "$LOCAL_TAR" | awk '{print $1}') → $LOCAL_TAR"
}

ARCHIVE_COUNT=0

if [[ "$SKIP_PUSH" -eq 0 ]]; then
  build_ustar
  ARCHIVE_COUNT="$(tar -tf "$LOCAL_TAR" | wc -l | tr -d ' ')"
  ok "archive entries: $ARCHIVE_COUNT"
  echo "Pushable vault size (Mac source, excludes applied via archive): $(du -sh "$CHRONICLE_DIR" | awk '{print $1}') (archive $(du -h "$LOCAL_TAR" | awk '{print $1}'))"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    ok "dry-run — skipping adb push/extract"
  else
    warn "pause Syncthing on Mac and phone during this push to avoid races"
    adb shell "mkdir -p '$PHONE_DIR'" >/dev/null
    adb push "$LOCAL_TAR" "$REMOTE_TAR" >/dev/null
    # Merge extract only — never delete phone-only files.
    adb shell "tar -xf '$REMOTE_TAR' -C '$PHONE_DIR' && rm -f '$REMOTE_TAR'"
    ok "merge-extracted ustar into $PHONE_DIR"

    # Verify: spot MD5s
    local_cfg_md5="$(mac_md5 "$CHRONICLE_DIR/config.json")"
    remote_cfg_md5="$(phone_md5 "$PHONE_DIR/config.json")"
    [[ -n "$remote_cfg_md5" && "$local_cfg_md5" == "$remote_cfg_md5" ]] \
      || die "config.json MD5 mismatch (mac=$local_cfg_md5 phone=${remote_cfg_md5:-missing})"
    ok "config.json MD5 match"

    if [[ -f "$CHRONICLE_DIR/brain/graph.json" ]]; then
      local_g="$(mac_md5 "$CHRONICLE_DIR/brain/graph.json")"
      remote_g="$(phone_md5 "$PHONE_DIR/brain/graph.json")"
      [[ -n "$remote_g" && "$local_g" == "$remote_g" ]] \
        || die "brain/graph.json MD5 mismatch (mac=$local_g phone=${remote_g:-missing})"
      ok "brain/graph.json MD5 match"
    fi

    # Sample a filed journal day if present
    sample="$(
      python3 -c '
from pathlib import Path
import sys
root = Path(sys.argv[1])
journal = root / "40-Journal"
if not journal.is_dir():
    raise SystemExit(0)
files = sorted(p for p in journal.rglob("*.md") if p.is_file())
if files:
    print(files[-1].relative_to(root))
' "$CHRONICLE_DIR"
    )"
    if [[ -n "${sample:-}" ]]; then
      local_s="$(mac_md5 "$CHRONICLE_DIR/$sample")"
      remote_s="$(phone_md5 "$PHONE_DIR/$sample")"
      [[ -n "$remote_s" && "$local_s" == "$remote_s" ]] \
        || die "sample $sample MD5 mismatch"
      ok "sample $sample MD5 match"
    fi

    ok "push verified (archive had $ARCHIVE_COUNT entries)"
  fi
else
  ok "skipped vault push"
fi

# --- Launch Chronicle PC (non-blocking) --------------------------------------

probe_connect() {
  local port="$1"
  curl -sf --max-time 1 "http://127.0.0.1:${port}/connect" >/dev/null 2>&1
}

serve_json_port() {
  local serve_json="${CHRONICLE_DIR}/index/serve.json"
  [[ -f "$serve_json" ]] || return 1
  python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('port',''))" "$serve_json" 2>/dev/null
}

find_live_serve_port() {
  local ports=() jp p
  jp="$(serve_json_port || true)"
  [[ -n "${jp:-}" ]] && ports+=("$jp")
  ports+=("$SERVE_PORT")
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

if [[ "$SKIP_LAUNCH" -eq 0 ]]; then
  if LIVE_PORT="$(find_live_serve_port)"; then
    ok "chronicle serve already online at http://127.0.0.1:${LIVE_PORT}/ — reusing"
  else
    if [[ -f "$START_CMD" ]]; then
      # Never foreground bash/exec Tauri — blocks until quit.
      open "$START_CMD"
      ok "opened Start Chronicle.command (non-blocking)"
    else
      nohup env CHRONICLE_DIR="$CHRONICLE_DIR" "$PC/start_dashboard.sh" \
        >>"${TMPDIR:-/tmp}/chronicle-pc-to-phone-launch.log" 2>&1 &
      ok "started start_dashboard.sh in background (pid $!)"
    fi
  fi
else
  ok "skipped PC launch"
fi

# --- Android installDebug (last) + launch ------------------------------------

if [[ "$SKIP_ANDROID" -eq 0 ]]; then
  [[ -x "$ANDROID/gradlew" ]] || die "missing $ANDROID/gradlew"
  (
    cd "$ANDROID"
    export JAVA_HOME
    ./gradlew :app:installDebug
  )
  ok "installDebug"
  adb shell am start -n "${PKG}/.MainActivity" >/dev/null
  ok "started ${PKG}/.MainActivity"
else
  ok "skipped Android install/launch"
fi

echo "Done. Reopen/refresh the Android app if Timeline looks stale. Syncthing remains day-to-day sync."
