#!/usr/bin/env bash
# — microbench harness (clean / incremental / no-op)
# Usage:./scripts/microbench.sh [project-dir]
# Requires: jk on PATH (or JK_BIN). Deps should already be cached for fair timings.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK_BIN="${JK_BIN:-jk}"
PROJECT="${1:-}"
RUNS="${RUNS:-3}"

if [[ -z "$PROJECT" ]]; then
  FIX="$ROOT/target/microbench-fixture"
  mkdir -p "$FIX"
  if [[ ! -f "$FIX/jk.toml" ]]; then
    rm -rf "$FIX"
    "$JK_BIN" init microbench-app --dir "$FIX" 2>/dev/null \
      || { mkdir -p "$FIX/src/main/java/demo" && cat >"$FIX/jk.toml" <<'EOF'
group = "demo"
name = "microbench"
version = "0.0.1"
jdk = 25
EOF
      echo 'package demo; public class Main { public static void main(String[] a) {} }' \
        >"$FIX/src/main/java/demo/Main.java"
    }
  fi
  PROJECT="$FIX"
fi

PROJECT="$(cd "$PROJECT" && pwd)"
cd "$PROJECT"

echo "# JumpKick microbench"
echo "project: $PROJECT"
echo "jk: $($JK_BIN --version 2>/dev/null || echo unknown)"
echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "runs: $RUNS (median)"
echo

median() {
 # stdin: one number per line → median
  sort -n | awk '{a[NR]=$1} END{ if(NR==0) print 0; else if(NR%2) print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2 }'
}

time_ms() {
  local start end
  start=$(python3 -c 'import time; print(int(time.time()*1000))')
  "$@" >/dev/null 2>&1 || true
  end=$(python3 -c 'import time; print(int(time.time()*1000))')
  echo $((end - start))
}

run_median() {
  local label="$1"
  shift
  local times=()
  local t
  for _ in $(seq 1 "$RUNS"); do
    t=$(time_ms "$@")
    times+=("$t")
  done
  local med
  med=$(printf '%s\n' "${times[@]}" | median)
  printf '| %s | %s |\n' "$label" "${med}ms"
}

# Warm engine once
"$JK_BIN" engine status >/dev/null 2>&1 || true
"$JK_BIN" build --skip-tests >/dev/null 2>&1 || true

echo "| scenario | median wall |"
echo "|---|---|"

# clean-all: remove jk's outputs if present (build/ is the Gradle bootstrap's, not the bench's)
rm -rf target out 2>/dev/null || true
run_median "clean-all (build --skip-tests)" "$JK_BIN" build --skip-tests

run_median "noop (build --skip-tests)" "$JK_BIN" build --skip-tests

# incremental: touch a java file if any
JAVA_FILE=$(find . -name '*.java' -not -path './out/*' -not -path './.jk/*' 2>/dev/null | head -1 || true)
if [[ -n "${JAVA_FILE:-}" ]]; then
  echo "" >>"$JAVA_FILE"
  run_median "incr-body (touch + build --skip-tests)" "$JK_BIN" build --skip-tests
else
  echo "| incr-body | skipped (no .java) |"
fi

echo
echo "Note: wall times include engine handshake; use warm engine (status above)."
echo "Timeline: out/jk-profile.json after builds."
