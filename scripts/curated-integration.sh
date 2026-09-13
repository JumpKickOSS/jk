#!/usr/bin/env bash
# The curated integration lane: the classes curated-integration.txt names, run module by module
# under the integration profile. It is the only integration coverage a pull request gets; the full
# profile stays nightly.
#
# Usage:
#   scripts/curated-integration.sh            # run the lane
#   scripts/curated-integration.sh --print    # print one `jk test` command per module and exit
#
# Environment:
#   JK                 the jk client to run (default: jk on PATH)
#   CURATED_REGISTRY   the registry to read (default: curated-integration.txt at the checkout root)
#
# One `jk test` per module, every module attempted, non-zero when any module was red: a red class is
# a verdict about its module, not a reason to skip the next one. Each class is named exactly with
# `--class`, and jk fails a run in which no class matched, so a renamed or deleted entry fails the
# lane instead of shrinking it. Guard G63 (`curated-integration`) holds the registry's shape, the
# classes it names and this script's place in the branch gate.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK="${JK:-jk}"
REGISTRY="${CURATED_REGISTRY:-$ROOT/curated-integration.txt}"
MODE="${1:-run}"

if [[ ! -f "$REGISTRY" ]]; then
  echo "curated-integration: no registry at $REGISTRY" >&2
  exit 2
fi

trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

declare -A CLASSES=()
MODULES=()
lineno=0
while IFS= read -r raw || [[ -n "$raw" ]]; do
  lineno=$((lineno + 1))
  line="$(trim "${raw%%#*}")"
  [[ -z "$line" ]] && continue
  fields="$(awk -F'|' '{ print NF }' <<<"$line")"
  if [[ "$fields" -ne 5 ]]; then
    echo "curated-integration: $REGISTRY:$lineno: $fields fields, expected 5 (module | class | surface | outcomes | why)" >&2
    exit 2
  fi
  IFS='|' read -r module fqcn _surface _outcomes _why <<<"$line"
  module="$(trim "$module")"
  fqcn="$(trim "$fqcn")"
  if [[ -z "${CLASSES[$module]:-}" ]]; then
    MODULES+=("$module")
  fi
  CLASSES[$module]+=" --class $fqcn"
done <"$REGISTRY"

if [[ ${#MODULES[@]} -eq 0 ]]; then
  echo "curated-integration: $REGISTRY names no classes, so the lane would run nothing" >&2
  exit 2
fi

failed=0
for module in "${MODULES[@]}"; do
  cmd=("$JK" test --profile integration --no-ansi -m "$module")
  # shellcheck disable=SC2206 # the value is the space-separated `--class <fqcn>` flags built above
  cmd+=(${CLASSES[$module]})
  if [[ "$MODE" == "--print" ]]; then
    printf '%s\n' "${cmd[*]}"
    continue
  fi
  echo "== curated integration lane: $module"
  if ! (cd "$ROOT" && "${cmd[@]}"); then
    failed=$((failed + 1))
    echo "curated-integration: $module is red" >&2
  fi
done

if [[ "$MODE" == "--print" ]]; then
  exit 0
fi
if [[ "$failed" -gt 0 ]]; then
  echo "curated-integration: $failed module(s) red" >&2
  exit 1
fi
echo "curated-integration: every module green (${#MODULES[@]} modules)"
