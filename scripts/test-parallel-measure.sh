#!/usr/bin/env bash
# C1 — wall-time monorepo test parallel measure (serial gate vs --parallel-tests).
#
# Usage:
# ./scripts/test-parallel-measure.sh
# MODULES='shared/*' ./scripts/test-parallel-measure.sh
# JK_BIN=/path/to/jk ./scripts/test-parallel-measure.sh
#
# Env:
# JK_BIN — jk binary (default: ~/.local/bin/jk or PATH)
# MODULES — --modules filter (default: multi-module library set without clients/cli)
# EXTRA_ARGS — extra args appended to both runs (e.g. --no-progress)
# WARM — if 1 (default), do one warm-up test before timing
# OUT_DIR — where to write logs (default: build/test-parallel-measure)
#
# Reports wall seconds for:
# A) -j0 -w0 (default: auto within-module; serial across modules)
# B) -j0 -w0 --parallel-tests
# C) -j0 -w1 (serial within-module baseline)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -n "${JK_BIN:-}" ]]; then
  JK="$JK_BIN"
elif [[ -x "$HOME/.jk/bin/jk" ]]; then
  JK="$HOME/.jk/bin/jk"
else
  JK="$(command -v jk || true)"
fi
if [[ -z "${JK}" ]]; then
  echo "jk not found (set JK_BIN or install via ./install.sh build/dist/jk)" >&2
  exit 1
fi
if [[ ! -x "$JK" ]] && ! command -v "$JK" >/dev/null 2>&1; then
  echo "jk not executable: $JK" >&2
  exit 1
fi

MODULES="${MODULES:-shared/*,server/io,server/resolver,server/toolchain,server/engine,plugins/*}"
EXTRA_ARGS="${EXTRA_ARGS:-}"
WARM="${WARM:-1}"
OUT_DIR="${OUT_DIR:-$ROOT/build/test-parallel-measure}"
export JK_AOT_TRAIN="${JK_AOT_TRAIN:-off}"

mkdir -p "$OUT_DIR"
SUMMARY="$OUT_DIR/summary.txt"
: >"$SUMMARY"

log() {
  echo "$@" | tee -a "$SUMMARY"
}

# Run one config; write full log to OUT_DIR; print only wall seconds on stdout for capture.
run_once() {
  local label="$1"
  shift
  local logf="$OUT_DIR/${label}.log"
  {
    echo "--- $label ---"
    echo "+ $JK test $* $EXTRA_ARGS --modules '$MODULES'"
  } | tee "$logf" | tee -a "$SUMMARY" >/dev/null

  local start end status
  start=$(date +%s)
  set +e
 # shellcheck disable=SC2086
  "$JK" test "$@" $EXTRA_ARGS --modules "$MODULES" >>"$logf" 2>&1
  status=$?
  set -e
  end=$(date +%s)
  local wall=$((end - start))
  {
    echo "exit=$status WALL_SECONDS[$label]=$wall"
    if [[ $status -ne 0 ]]; then
      echo "FAILED: $label (see $logf)"
    fi
  } | tee -a "$SUMMARY"
 # last line of this function's stdout is the wall time only (for callers that capture)
  echo "$wall" >"$OUT_DIR/${label}.wall"
  echo "$wall"
  return "$status"
}

log "# C1 test parallel measure"
log "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "jk: $($JK --version 2>/dev/null || echo unknown)"
log "host: $(uname -s) $(uname -m) cpus=$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo '?')"
log "modules: $MODULES"
log "JK_AOT_TRAIN=$JK_AOT_TRAIN"
log "logs: $OUT_DIR"
log ""

"$JK" engine stop >/dev/null 2>&1 || true
"$JK" engine status >/dev/null 2>&1 || true

if [[ "$WARM" == "1" ]]; then
  log "Warm-up (not timed)..."
  set +e
 # shellcheck disable=SC2086
  "$JK" test -j0 -w1 $EXTRA_ARGS --modules "$MODULES" >"$OUT_DIR/warm.log" 2>&1
  set -e
  log "warm done (log: $OUT_DIR/warm.log)"
  log ""
fi

fail=0
w_serial=0
w_auto=0
w_par=0

if ! w_serial=$(run_once "w1-serial-within" -j0 -w1); then
  fail=1
  w_serial=$(cat "$OUT_DIR/w1-serial-within.wall" 2>/dev/null || echo 0)
fi
if ! w_auto=$(run_once "w0-auto-serial-modules" -j0 -w0); then
  fail=1
  w_auto=$(cat "$OUT_DIR/w0-auto-serial-modules.wall" 2>/dev/null || echo 0)
fi
if ! w_par=$(run_once "w0-auto-parallel-tests" -j0 -w0 --parallel-tests); then
  fail=1
  w_par=$(cat "$OUT_DIR/w0-auto-parallel-tests.wall" 2>/dev/null || echo 0)
fi

# strip any accidental multi-line capture — keep last integer line
w_serial=$(echo "$w_serial" | awk '/^[0-9]+$/ {v=$0} END{print v+0}')
w_auto=$(echo "$w_auto" | awk '/^[0-9]+$/ {v=$0} END{print v+0}')
w_par=$(echo "$w_par" | awk '/^[0-9]+$/ {v=$0} END{print v+0}')

log ""
log "## Summary"
log "| config | wall s |"
log "|--------|--------|"
log "| -j0 -w1 (serial within) | $w_serial |"
log "| -j0 -w0 (auto, serial modules) | $w_auto |"
log "| -j0 -w0 --parallel-tests | $w_par |"

python3 - <<PY | tee -a "$SUMMARY"
serial = int("$w_serial")
auto = int("$w_auto")
par = int("$w_par")
def ratio(a, b):
    return f"{a/b:.2f}×" if b > 0 else "n/a"
print(f"speedup auto vs -w1: {ratio(serial, auto)} (higher better for auto)")
print(f"speedup --parallel-tests vs auto serial-modules: {ratio(auto, par)}")
if auto > 0 and par > 0:
    win = (auto - par) / auto * 100
    print(f"wall win from --parallel-tests: {win:.0f}%")
    if win >= 20:
        print("GO criterion (≥20% wall win): met on this run")
    else:
        print("GO criterion (≥20% wall win): not met on this run")
print(f"all_green: {'no' if $fail else 'yes'}")
PY

exit "$fail"
