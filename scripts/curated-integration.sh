#!/usr/bin/env bash
# The curated integration lane: the classes curated-integration.txt names, run in the integration
# suite of the modules that hold them. It is the only integration coverage a pull request gets; the
# full suite stays nightly.
#
# Usage:
#   scripts/curated-integration.sh [--except <module>]...            # run the lane
#   scripts/curated-integration.sh --print [--except <module>]...    # print its `jk test` command and exit
#
# Environment:
#   JK                 the jk client to run (default: jk on PATH)
#   CURATED_REGISTRY   the registry to read (default: curated-integration.txt at the checkout root)
#
# One `jk test` for every module, so the modules' classes run side by side, and `-c` attempts every
# module: a red class is a verdict about its module, not a reason to skip the next one. Each class is
# named exactly with `--class`. `--except <module>` leaves out a module whose whole integration suite
# runs anyway (the Queue tier's changed modules); with every module left out it prints and runs
# nothing. A renamed or deleted entry is guard G63's to catch (`curated-integration`), which also
# holds the registry's shape, the classes it names and this script's place in the branch gate.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK="${JK:-jk}"
REGISTRY="${CURATED_REGISTRY:-$ROOT/curated-integration.txt}"
MODE=run
EXCEPT=" "
while [[ $# -gt 0 ]]; do
  case "$1" in
    --print) MODE=--print ;;
    --except)
      [[ $# -ge 2 ]] || {
        echo "curated-integration: --except needs a module" >&2
        exit 2
      }
      EXCEPT+="$2 "
      shift
      ;;
    *)
      echo "curated-integration: unknown argument $1; usage: curated-integration.sh [--print] [--except <module>]..." >&2
      exit 2
      ;;
  esac
  shift
done

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

# Parallel arrays, MODULES[i] and its `--class` flags in CLASSES[i]: macOS bash 3.2 has no `declare -A`.
MODULES=()
CLASSES=()
lineno=0
while IFS= read -r raw || [[ -n "$raw" ]]; do
  lineno=$((lineno + 1))
  line="$(trim "${raw%%#*}")"
  [[ -z "$line" ]] && continue
  # A `key = value` directive (integration-floor) is the guard's to read and validate.
  [[ "$line" =~ ^[a-z][a-z-]*[[:space:]]*=[[:space:]]*[^[:space:]]+$ ]] && continue
  fields="$(awk -F'|' '{ print NF }' <<<"$line")"
  if [[ "$fields" -ne 5 ]]; then
    echo "curated-integration: $REGISTRY:$lineno: $fields fields, expected 5 (module | class | surface | outcomes | why)" >&2
    exit 2
  fi
  IFS='|' read -r module fqcn _surface _outcomes _why <<<"$line"
  module="$(trim "$module")"
  fqcn="$(trim "$fqcn")"
  i=0
  while [[ $i -lt ${#MODULES[@]} && "${MODULES[$i]}" != "$module" ]]; do
    i=$((i + 1))
  done
  if [[ $i -eq ${#MODULES[@]} ]]; then
    MODULES+=("$module")
    CLASSES+=("")
  fi
  CLASSES[i]+=" --class $fqcn"
done <"$REGISTRY"

if [[ ${#MODULES[@]} -eq 0 ]]; then
  echo "curated-integration: $REGISTRY names no classes, so the lane would run nothing" >&2
  exit 2
fi

cmd=("$JK" test -s integration -c --no-ansi)
ran=()
for i in "${!MODULES[@]}"; do
  [[ "$EXCEPT" == *" ${MODULES[$i]} "* ]] && continue
  ran+=("${MODULES[$i]}")
  # shellcheck disable=SC2206 # the value is the space-separated `--class <fqcn>` flags built above
  cmd+=(-m "${MODULES[$i]}" ${CLASSES[$i]})
done

if [[ "$MODE" == "--print" ]]; then
  [[ ${#ran[@]} -eq 0 ]] || printf '%s\n' "${cmd[*]}"
  exit 0
fi
if [[ ${#ran[@]} -eq 0 ]]; then
  echo "curated-integration: every module is left out, so the lane runs nothing"
  exit 0
fi
echo "== curated integration lane: ${ran[*]}"
if ! (cd "$ROOT" && "${cmd[@]}"); then
  echo "curated-integration: red in ${ran[*]}" >&2
  exit 1
fi
echo "curated-integration: green in ${ran[*]}"
