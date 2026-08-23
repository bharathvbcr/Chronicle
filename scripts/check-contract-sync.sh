#!/usr/bin/env bash
# Fail if CONTRACT.md or contract/*.schema.json differ between chronicle-pc and chronicle-android.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PC="$ROOT/chronicle-pc"
AND="$ROOT/chronicle-android"
failures=0

cmp_file() {
  local rel="$1"
  if ! cmp -s "$PC/$rel" "$AND/$rel"; then
    echo "MISMATCH: $rel"
    diff -u "$PC/$rel" "$AND/$rel" || true
    failures=$((failures + 1))
  else
    echo "OK: $rel"
  fi
}

cmp_file "CONTRACT.md"

shopt -s nullglob
schemas=("$PC"/contract/*.schema.json)
if [[ ${#schemas[@]} -eq 0 ]]; then
  echo "ERROR: no schema files under chronicle-pc/contract/"
  exit 1
fi

for schema in "${schemas[@]}"; do
  name="$(basename "$schema")"
  if [[ ! -f "$AND/contract/$name" ]]; then
    echo "MISSING on Android: contract/$name"
    failures=$((failures + 1))
    continue
  fi
  cmp_file "contract/$name"
done

# Android must not have extra schemas the PC lacks
for schema in "$AND"/contract/*.schema.json; do
  name="$(basename "$schema")"
  if [[ ! -f "$PC/contract/$name" ]]; then
    echo "EXTRA on Android (missing on PC): contract/$name"
    failures=$((failures + 1))
  fi
done

if [[ "$failures" -ne 0 ]]; then
  echo "Contract sync check failed ($failures difference(s)). Keep PC and Android copies byte-identical."
  exit 1
fi

echo "Contract sync check passed."
