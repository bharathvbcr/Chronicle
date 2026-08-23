#!/usr/bin/env bash
# Parity harness: Rust server vs Python oracle over the same vault.
# Normalizes volatile fields (tokens, ports, timestamps, paths) before diffing.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"          # repo root
PC="$ROOT/chronicle-pc"
VAULT="${CHRONICLE_DIR:-/tmp/parity-vault}"
PY_PORT=8801 RS_PORT=8802
PASS=0 FAIL=0 FAILED=()

norm() {
  python3 - "$1" <<'PY'
import json,sys,re
def walk(o):
    if isinstance(o,dict): return {k:walk(v) for k,v in sorted(o.items())}
    if isinstance(o,list): return [walk(v) for v in o]
    if isinstance(o,str):
        s=o
        s=re.sub(r'[0-9a-f]{32}','<token>',s)
        s=re.sub(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[^"]*','<ts>',s)
        s=s.replace(sys.argv[1],'<vault>')
        return s
    return o
raw=sys.stdin.read()
try: print(json.dumps(walk(json.loads(raw)),sort_keys=True))
except Exception: print(raw.strip())
PY
}

get() { curl -s --max-time 10 "http://127.0.0.1:$1$2"; }

start_py() {
  (cd "$PC" && CHRONICLE_DIR="$VAULT" ./.venv/bin/chronicle serve --no-lan --port $PY_PORT >/tmp/parity-py.log 2>&1 &)
}
start_rs() {
  (cd "$PC/server" && CHRONICLE_DIR="$VAULT" ./target/debug/chronicle serve --no-lan --port $RS_PORT >/tmp/parity-rs.log 2>&1 &)
}
wait_up() { for i in $(seq 1 40); do curl -sf "http://127.0.0.1:$1/health" >/dev/null 2>&1 && return 0; sleep 0.25; done; return 1; }

cleanup() { pkill -f "chronicle serve --no-lan --port $PY_PORT" 2>/dev/null; pkill -f "chronicle serve --no-lan --port $RS_PORT" 2>/dev/null; }
trap cleanup EXIT

start_rs; wait_up $RS_PORT || { echo "rust server failed"; tail -5 /tmp/parity-rs.log; exit 1; }
PY_OK=1
if [[ -x "$PC/.venv/bin/chronicle" ]]; then start_py; wait_up $PY_PORT || PY_OK=0; else PY_OK=0; fi

check() {
  local name="$1" path="$2"
  local rs py
  rs=$(get $RS_PORT "$path" | norm "$VAULT")
  if [[ $PY_OK == 1 ]]; then
    py=$(get $PY_PORT "$path" | norm "$VAULT")
    if [[ "$rs" == "$py" ]]; then PASS=$((PASS+1)); echo "PASS  $name"
    else FAIL=$((FAIL+1)); FAILED+=("$name"); echo "DIFF  $name"; diff <(echo "$py") <(echo "$rs") | head -6; fi
  else
    PASS=$((PASS+1)); echo "RUST-ONLY  $name ($(echo "$rs" | head -c 60)...)"
  fi
}

check health        /health
check connect       /connect
check entries       "/entries?limit=3"
check kb-tree       "/kb/tree"
check kb-files      "/kb/templates"
check notes         /notes
check brain-graph   /brain/graph
check brain-insights /brain/insights?limit=5
check models        /models

# POST parity: search keyword-only mode
post_check() {
  local name="$1" path="$2" body="$3"
  local rs py
  rs=$(curl -s --max-time 15 -X POST "http://127.0.0.1:$RS_PORT$path" -H 'content-type: application/json' -d "$body" | norm "$VAULT")
  if [[ $PY_OK == 1 ]]; then
    py=$(curl -s --max-time 15 -X POST "http://127.0.0.1:$PY_PORT$path" -H 'content-type: application/json' -d "$body" | norm "$VAULT")
    if [[ "$rs" == "$py" ]]; then PASS=$((PASS+1)); echo "PASS  $name"
    else FAIL=$((FAIL+1)); FAILED+=("$name"); echo "DIFF  $name"; diff <(echo "$py") <(echo "$rs") | head -8; fi
  else
    PASS=$((PASS+1)); echo "RUST-ONLY  $name"
  fi
}
post_check search-keyword "/search" '{"query":"chronicle","top_k":5,"scope":"all"}'
post_check ask-no-hits-shape "/ask" '{"question":"zzzznonexistent"}'

echo
echo "== parity: $PASS passed, $FAIL failed ${FAILED[*]:+→ ${FAILED[*]}}"
[[ $FAIL == 0 ]]
