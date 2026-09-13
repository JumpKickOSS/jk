#!/usr/bin/env bash
# The curated lane of this fixture's checkout: one `jk test` per registry entry.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGISTRY="${CURATED_REGISTRY:-$ROOT/curated-integration.txt}"

while IFS= read -r line; do
  [[ "$line" != *"|"* ]] && continue
  module="$(cut -d'|' -f1 <<<"$line" | tr -d ' ')"
  class="$(cut -d'|' -f2 <<<"$line" | tr -d ' ')"
  jk test --profile integration -m "$module" --class "$class"
done < "$REGISTRY"
