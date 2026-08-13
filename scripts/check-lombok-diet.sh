#!/usr/bin/env bash
# JK-1934: lombok is diet-only on server/ and plugins/. Forbidden on shared/, clients/.
# Usage: scripts/check-lombok-diet.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

banned="$(git grep -n -E '^import lombok' -- 'shared' 'clients' || true)"
if [ -n "$banned" ]; then
  printf '%s\n' "$banned" >&2
  echo "error: import lombok is forbidden under shared/ and clients/ (Graal + scaffold diet)" >&2
  exit 1
fi

count="$( { git grep -l -E '^import lombok' -- '*.java' '*.kt' || true; } | wc -l | tr -d ' ')"
if [ "${count:-0}" -ge 15 ]; then
  echo "error: $count first-party files import lombok (cap 15)" >&2
  git grep -n -E '^import lombok' -- '*.java' '*.kt' >&2 || true
  exit 1
fi

echo "ok: lombok diet (0 imports in shared/clients; ${count:-0} first-party file(s))"
