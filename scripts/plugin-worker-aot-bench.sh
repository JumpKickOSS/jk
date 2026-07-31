#!/usr/bin/env bash
# Benchmark PluginAot on *java … PluginMain* workers (not bare `javac`).
#
# Arms (engine host must be HotSpot 25+ — PluginAot.eligible rejects Graal):
# AOT-on — default; maps -XX:AOTCache on kotlinc / java-compiler plugin JVMs
# AOT-off — JK_WORKER_AOT=off
#
# Usage:
# ./scripts/plugin-worker-aot-bench.sh kotlin [/path/to/hello-kotlin]
# ./scripts/plugin-worker-aot-bench.sh java-worker # requires engine test / AP project
#
# Env: RUNS (default 7), JDK_SPEC (default temurin-25)
set -euo pipefail

MODE="${1:-kotlin}"
PROJECT="${2:-}"
RUNS="${RUNS:-7}"
JDK_SPEC="${JDK_SPEC:-temurin-25}"
JK_BIN="${JK_BIN:-jk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

median() {
  sort -n | awk '{a[NR]=$1} END{ if(NR==0) print 0; else if(NR%2) print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2 }'
}

took_ms() {
 # parse "took Nms" from jk build line
  local out
  out=$("$@" 2>&1) || true
  echo "$out" | sed -n 's/.*took \([0-9.]*\)ms.*/\1/p' | tail -1
}

run_median_rebuild() {
  local label="$1"
  shift
  local times=() i t
  for i in $(seq 1 "$RUNS"); do
    t=$(took_ms "$@")
    [[ -n "$t" ]] || t=0
    times+=("$t")
  done
  local med
  med=$(printf '%s\n' "${times[@]}" | median)
  printf '| %s | %s | %s |\n' "$label" "${med}ms" "$(printf '%s ' "${times[@]}")"
}

echo "# Plugin worker AOT bench (java … PluginMain)"
echo "mode: $MODE"
echo "jdk: $JDK_SPEC (engine + workers host HotSpot)"
echo "jk: $($JK_BIN --version 2>/dev/null || echo unknown)"
echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "runs: $RUNS"
echo

# Prefer Temurin for engine process
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.3-tem}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "warn: JAVA_HOME=$JAVA_HOME missing; engine may run on Graal (AOT ineligible)" >&2
fi
"$JK_BIN" engine stop >/dev/null 2>&1 || true
"$JK_BIN" engine status >/dev/null 2>&1 || true

case "$MODE" in
  kotlin)
    PROJECT="${PROJECT:-$HOME/src/oss/jk-examples/examples/hello-kotlin}"
    PROJECT="$(cd "$PROJECT" && pwd)"
    echo "project: $PROJECT"
    cd "$PROJECT"
    unset JK_WORKER_AOT || true
 # train: first rebuilds may miss AOT then background-train
    "$JK_BIN" build --skip-tests --rebuild --jdk "$JDK_SPEC" >/dev/null 2>&1 || true
    sleep 4
    "$JK_BIN" build --skip-tests --rebuild --jdk "$JDK_SPEC" >/dev/null 2>&1 || true
    echo "kotlinc aot files:"
    ls -la "${HOME}/.jk/state/aot"/kotlinc-*.aot 2>/dev/null | sed 's/^/  /' || echo "  (none yet)"
    echo
    echo "| arm | median rebuild | samples |"
    echo "|---|---|---|"
    unset JK_WORKER_AOT || true
    run_median_rebuild "kotlinc worker AOT-on" "$JK_BIN" build --skip-tests --rebuild --jdk "$JDK_SPEC"
    export JK_WORKER_AOT=off
    run_median_rebuild "kotlinc worker AOT-off" "$JK_BIN" build --skip-tests --rebuild --jdk "$JDK_SPEC"
    unset JK_WORKER_AOT
    echo
    echo "Confirm AOT map: during a rebuild, ps should show:"
    echo "  …/bin/java -XX:AOTCache=…/kotlinc-….aot … PluginMain …"
    ;;
  java-worker)
    echo "java-compiler worker AOT is exercised when source-generating APs force ForkedJavac."
    echo "Plain Java (no source-gen AP) uses bare \`javac\` — see javac AOT numbers separately."
    echo "Running engine unit microbench if available…"
    cd "$ROOT"
    ./gradlew :engine:test --tests 'cc.jumpkick.compile.ForkedJavacAotBench*' 2>&1 | tail -40
    ;;
  *)
    echo "usage: $0 kotlin|java-worker [project]" >&2
    exit 2
    ;;
esac
