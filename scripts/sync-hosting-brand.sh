#!/usr/bin/env bash
# Copy brand media from the engine Web UI (canonical) into Firebase Hosting public/.
# Run after changing logos under clients/web; commit both trees so deploy stays self-contained.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/clients/web/src/main/resources/web"
dst="$root/hosting/public"

files=(jk-logo.svg jumpkick-logo.webp)

usage() {
  echo "Usage: $0 [--check]" >&2
  echo "  (default)  copy brand assets into hosting/public/" >&2
  echo "  --check    exit 1 if hosting copies differ from web sources" >&2
  exit 2
}

mode=sync
if [[ "${1:-}" == "--check" ]]; then
  mode=check
elif [[ -n "${1:-}" ]]; then
  usage
fi

for f in "${files[@]}"; do
  if [[ ! -f "$src/$f" ]]; then
    echo "missing source: $src/$f" >&2
    exit 1
  fi
done

if [[ "$mode" == check ]]; then
  stale=0
  for f in "${files[@]}"; do
    if [[ ! -f "$dst/$f" ]] || ! cmp -s "$src/$f" "$dst/$f"; then
      echo "stale or missing: hosting/public/$f (source: clients/web/.../web/$f)" >&2
      stale=1
    fi
  done
  if [[ "$stale" -ne 0 ]]; then
    echo "run: scripts/sync-hosting-brand.sh" >&2
    exit 1
  fi
  echo "hosting brand assets match web sources"
  exit 0
fi

mkdir -p "$dst"
for f in "${files[@]}"; do
  cp -f "$src/$f" "$dst/$f"
  echo "copied $f → hosting/public/"
done
