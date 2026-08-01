#!/usr/bin/env bash
# AOT-on (production forks) vs AOT-off (cold forks) wall + rough RSS.
# Warm-pool arm is not available on main — this measures the gap a pool must beat (A vs B).
#
# Usage: ./scripts/aot-vs-fork-bench.sh [project-dir]
# Env: RUNS (default 5), JK_BIN
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK_BIN="${JK_BIN:-jk}"
PROJECT="${1:-}"
RUNS="${RUNS:-5}"
# HotSpot only — Graal is ineligible for PluginAot worker caches (see PluginAot.eligible).
JDK_SPEC="${JDK_SPEC:-temurin-25}"

if [[ -z "$PROJECT" ]]; then
  echo "usage: $0 <project-dir>" >&2
  exit 2
fi
PROJECT="$(cd "$PROJECT" && pwd)"
cd "$PROJECT"
JK=("$JK_BIN" --jdk "$JDK_SPEC")

median() {
  sort -n | awk '{a[NR]=$1} END{ if(NR==0) print 0; else if(NR%2) print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2 }'
}

time_ms() {
  local start end
  start=$(python3 -c 'import time; print(int(time.time()*1000))')
  "$@" >/dev/null 2>&1 || true
  end=$(python3 -c 'import time; print(int(time.time()*1000))')
  echo $((end - start))
}

# Peak RSS of engine process during a command (macOS: rss in KB via ps)
sample_engine_rss_kb() {
 # jk-engine or java -jar jk-engine
  local pids
  pids=$(pgrep -f 'jk-engine' 2>/dev/null || true)
  if [[ -z "$pids" ]]; then
    echo 0
    return
  fi
  local sum=0 pid
  for pid in $pids; do
    local r
    r=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ' || echo 0)
    sum=$((sum + ${r:-0}))
  done
  echo "$sum"
}

run_median() {
  local label="$1"
  shift
  local times=()
  local rss_samples=()
  local i t r
  for i in $(seq 1 "$RUNS"); do
    t=$(time_ms "$@")
    times+=("$t")
    r=$(sample_engine_rss_kb)
    rss_samples+=("$r")
  done
  local med rss_med
  med=$(printf '%s\n' "${times[@]}" | median)
  rss_med=$(printf '%s\n' "${rss_samples[@]}" | median)
 # rss is KB on macOS/Linux ps
  local rss_mb
  rss_mb=$(python3 -c "print(round(${rss_med}/1024, 1))")
  printf '| %s | %s | ~%s MiB |\n' "$label" "${med}ms" "$rss_mb"
}

echo "# AOT-on vs AOT-off (cold fork workers) on HotSpot"
echo "project: $PROJECT"
echo "jk: $($JK_BIN --version 2>/dev/null || echo unknown)"
echo "jdk: --jdk $JDK_SPEC  (Graal is NOT eligible for worker AOT)"
echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "runs: $RUNS (median wall)"
echo "aot caches:"
find "${HOME}/.jk/state/aot" -name 'javac-*.aot' 2>/dev/null | head -10 | sed 's/^/  /' || echo "  (none)"
echo

# Ensure engine up + deps + AOT trained under HotSpot
unset JK_WORKER_AOT || true
"$JK_BIN" engine status >/dev/null 2>&1 || true
"${JK[@]}" build --skip-tests >/dev/null 2>&1 || true
# train pass (rebuild) + settle background trainer
"${JK[@]}" build --skip-tests --redo >/dev/null 2>&1 || true
sleep 3
"${JK[@]}" build --skip-tests --redo >/dev/null 2>&1 || true

JAVA_FILE=$(find . -name '*.java' -not -path './target/*' -not -path './out/*' -not -path './.jk/*' 2>/dev/null | head -1 || true)

echo "| scenario | median wall | engine RSS (median sample) |"
echo "|---|---|---|"

# --- AOT ON (cold fork + AOT map) ---
unset JK_WORKER_AOT || true
rm -rf target out 2>/dev/null || true
run_median "AOT-on clean (rm target)" "${JK[@]}" build --skip-tests
run_median "AOT-on noop" "${JK[@]}" build --skip-tests
# Force recompile even when action cache is warm (the fair AOT stress):
run_median "AOT-on rebuild (cold fork+AOT)" "${JK[@]}" build --skip-tests --redo
if [[ -n "${JAVA_FILE:-}" ]]; then
  echo "" >>"$JAVA_FILE"
  run_median "AOT-on incr-body" "${JK[@]}" build --skip-tests
fi

# --- AOT OFF (pure cold fork) ---
export JK_WORKER_AOT=off
rm -rf target out 2>/dev/null || true
run_median "AOT-off clean (rm target)" "${JK[@]}" build --skip-tests
run_median "AOT-off noop" "${JK[@]}" build --skip-tests
run_median "AOT-off rebuild (cold fork)" "${JK[@]}" build --skip-tests --redo
if [[ -n "${JAVA_FILE:-}" ]]; then
  echo "" >>"$JAVA_FILE"
  run_median "AOT-off incr-body" "${JK[@]}" build --skip-tests
fi
unset JK_WORKER_AOT

echo
echo "Notes:"
echo "- Arms are both cold process starts: AOT map vs no AOT. Warm *pool* is not on main."
echo "- A pool must beat AOT-on on HotSpot (Temurin), not Graal (ineligible)."
echo "- Confirm: ps shows …/temurin…/bin/javac and -J-XX:AOTCache= during AOT-on rebuild."
echo "- Engine RSS is a coarse sample after each run (not peak worker set)."
echo "- Timeline: target/jk-chrome-profile.json after a build."
