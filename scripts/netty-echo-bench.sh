#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Cold / warm / no-op wall times for the netty-echo sample.
# Usage: ./scripts/netty-echo-bench.sh [project-dir]
# Optional: RUNS=5 JK_BIN=jk
set -euo pipefail

PROJ="${1:-${JK_EXAMPLES:-$HOME/src/oss/jk-examples}/jvm/netty-echo}"
JK_BIN="${JK_BIN:-jk}"
RUNS="${RUNS:-3}"

if [[ ! -f "$PROJ/jk.toml" ]]; then
  echo "missing $PROJ/jk.toml" >&2
  exit 1
fi

median() {
  # stdin: numbers; stdout: median
  sort -n | awk '{a[NR]=$1} END{if(NR==0)print 0; else if(NR%2)print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2}'
}

time_cmd() {
  local label=$1
  shift
  local times=()
  local i t0 t1
  for ((i = 1; i <= RUNS; i++)); do
    t0=$(date +%s%3N)
    "$@" >/dev/null 2>&1 || true
    t1=$(date +%s%3N)
    times+=($((t1 - t0)))
  done
  local med
  med=$(printf '%s\n' "${times[@]}" | median)
  printf '| %s | %s | %s |\n' "$label" "$med" "$(printf '%s ' "${times[@]}")"
}

echo "# netty-echo bench ($(date -u +%Y-%m-%dT%H:%MZ))"
echo
echo "- project: \`$PROJ\`"
echo "- jk: \`$($JK_BIN --version 2>/dev/null || echo unknown)\`"
echo "- runs: $RUNS (median ms)"
echo "- machine: $(uname -srm)"
echo
echo "| scenario | median_ms | samples_ms |"
echo "|----------|----------:|------------|"

(
  cd "$PROJ"
  $JK_BIN lock --no-progress --no-ansi 2>/dev/null || true

  # cold: wipe target, keep lock + global cache
  rm -rf target
  time_cmd "cold jk build --skip-tests" $JK_BIN build --skip-tests --no-progress --no-ansi

  time_cmd "warm no-op jk build --skip-tests" $JK_BIN build --skip-tests --no-progress --no-ansi

  # dirty: touch one source
  touch src/main/java/com/example/echo/EchoServer.java
  time_cmd "warm single-file dirty" $JK_BIN build --skip-tests --no-progress --no-ansi
)

echo
echo "Maven/Mill arms: run on the same machine against Netty's pom / Mill thirdparty port;"
echo "methodology: 3 warmup + 5 measured runs per shape; report the median."
