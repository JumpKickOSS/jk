#!/usr/bin/env bash
# JK-1075 — measure engine heap/RSS under a synthetic multi-module workspace.
# Usage:
#   ./scripts/heap-monorepo-measure.sh [module-count]
# Env:
#   JK_BIN, MODULES (default 200), FIXTURE_DIR, POLL_MS (default 200)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK_BIN="${JK_BIN:-$HOME/.jk/bin/jk}"
MODULES="${1:-${MODULES:-200}}"
FIXTURE_DIR="${FIXTURE_DIR:-$ROOT/build/heap-monorepo-fixture}"
POLL_MS="${POLL_MS:-200}"

if [[ ! -x "$JK_BIN" ]] && ! command -v "$JK_BIN" >/dev/null 2>&1; then
  echo "jk not found (JK_BIN=$JK_BIN)" >&2
  exit 1
fi

status_json() {
  "$JK_BIN" engine status --output json 2>/dev/null || echo '{"running":false}'
}

field() {
  # $1=json $2=key → number or empty
  python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get(sys.argv[2],""))' "$1" "$2" 2>/dev/null || true
}

mib() {
  python3 -c 'import sys; v=int(sys.argv[1]); print(f"{v/(1024*1024):.1f}" if v>=0 else "n/a")' "$1" 2>/dev/null || echo "?"
}

echo "# JK-1075 engine heap monorepo measure"
echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "jk: $($JK_BIN --version 2>/dev/null || echo unknown)"
echo "modules: $MODULES"
echo "fixture: $FIXTURE_DIR"
echo

# --- generate fixture -------------------------------------------------------
# Prefer the dedicated generator (empty locks, no junit) so builds finish offline
# and do not hang on first resolve of 200× test deps.
GEN="$ROOT/scripts/generate-synthetic-monorepo.sh"
if [[ -x "$GEN" ]]; then
  echo "Generating $MODULES-module workspace via generate-synthetic-monorepo.sh..."
  "$GEN" "$FIXTURE_DIR" "$MODULES"
else
echo "Generating $MODULES-module workspace (inline)..."
rm -rf "$FIXTURE_DIR"
mkdir -p "$FIXTURE_DIR"
mod_list=""
for i in $(seq 1 "$MODULES"); do
  name=$(printf 'm%03d' "$i")
  dir="$FIXTURE_DIR/$name"
  mkdir -p "$dir/src"
  cat >"$dir/jk.toml" <<EOF
[project]
group = "bench"
name = "$name"
version = "0.0.1"
jdk = 25
java = 25
EOF
  cat >"$dir/src/Lib.java" <<EOF
package bench.$name;
public class Lib {
  public static int id() { return $i; }
}
EOF
  if [[ -n "$mod_list" ]]; then mod_list+=", "; fi
  mod_list+="\"$name\""
done

cat >"$FIXTURE_DIR/jk.toml" <<EOF
[project]
group = "bench"
name = "heap-monorepo"
version = "0.0.1"
jdk = 25
java = 25

[workspace]
modules = [$mod_list]
EOF
fi  # end inline generate fallback

# --- fresh engine -----------------------------------------------------------
"$JK_BIN" engine stop >/dev/null 2>&1 || true
sleep 0.5
"$JK_BIN" engine start >/dev/null 2>&1 || true
sleep 0.5

idle_json=$(status_json)
idle_used=$(field "$idle_json" heapUsedBytes)
idle_commit=$(field "$idle_json" heapCommittedBytes)
idle_max=$(field "$idle_json" heapMaxBytes)
idle_rss=$(field "$idle_json" rssBytes)
echo "## Idle engine (after start)"
echo "| metric | bytes | MiB |"
echo "|---|---:|---:|"
echo "| heapUsed | $idle_used | $(mib "${idle_used:-0}") |"
echo "| heapCommitted | $idle_commit | $(mib "${idle_commit:-0}") |"
echo "| heapMax | $idle_max | $(mib "${idle_max:-0}") |"
echo "| rss | $idle_rss | $(mib "${idle_rss:--1}") |"
echo

# Poller during build
peak_file=$(mktemp)
echo "0 0 0" >"$peak_file"
(
  peak_u=0 peak_c=0 peak_r=0
  while true; do
    j=$(status_json)
    u=$(field "$j" heapUsedBytes)
    c=$(field "$j" heapCommittedBytes)
    r=$(field "$j" rssBytes)
    [[ -z "$u" || "$u" == "" ]] && { sleep "$(python3 -c "print($POLL_MS/1000)")"; continue; }
    python3 -c "
u,c,r=int('$u' or 0),int('$c' or 0),int('$r' or -1)
pu,pc,pr=map(int,open('$peak_file').read().split())
if u>pu: pu=u
if c>pc: pc=c
if r>pr: pr=r
open('$peak_file','w').write(f'{pu} {pc} {pr}')
" 2>/dev/null || true
    sleep "$(python3 -c "print($POLL_MS/1000)")"
  done
) &
POLL_PID=$!
cleanup() { kill "$POLL_PID" 2>/dev/null || true; rm -f "$peak_file"; }
trap cleanup EXIT

echo "Building workspace (skip-tests)..."
cd "$FIXTURE_DIR"
# Force a clean engine so status samples are for this run.
"$JK_BIN" engine stop --force >/dev/null 2>&1 || true
sleep 1
"$JK_BIN" engine start >/dev/null 2>&1 || true
sleep 1
set +e
if [[ ! -f jk-lock.toml ]]; then
  "$JK_BIN" lock 2>&1 | tail -5
fi
"$JK_BIN" build --skip-tests 2>&1 | tail -12
build_ec=$?
set -e

# One more sample after finish
sleep 0.3
final_json=$(status_json)
kill "$POLL_PID" 2>/dev/null || true
wait "$POLL_PID" 2>/dev/null || true
trap - EXIT

read -r peak_u peak_c peak_r <"$peak_file" || true
final_used=$(field "$final_json" heapUsedBytes)
final_commit=$(field "$final_json" heapCommittedBytes)
final_rss=$(field "$final_json" rssBytes)
peak_pipe=$(field "$final_json" peakActivePipelines)
# peakActivePipelines may not be in CLI json — ignore if empty

echo
echo "## During / after build ($MODULES modules, exit=$build_ec)"
echo "| metric | bytes | MiB |"
echo "|---|---:|---:|"
echo "| peak heapUsed (polled) | $peak_u | $(mib "${peak_u:-0}") |"
echo "| peak heapCommitted (polled) | $peak_c | $(mib "${peak_c:-0}") |"
echo "| final heapUsed | $final_used | $(mib "${final_used:-0}") |"
echo "| final heapCommitted | $final_commit | $(mib "${final_commit:-0}") |"
echo "| peak rss (polled) | $peak_r | $(mib "${peak_r:--1}") |"
echo "| heapMax | $idle_max | $(mib "${idle_max:-0}") |"
echo
pct=$(python3 -c "print(f\"{100.0*int('$peak_u' or 0)/max(int('$idle_max' or 1),1):.1f}\")" 2>/dev/null || echo "?")
echo "peak heapUsed / heapMax ≈ ${pct}%"
echo
echo "## Decision aid"
python3 - <<PY
peak = int("${peak_u}" or 0)
mx = int("${idle_max}" or 1)
ratio = peak / mx if mx else 0
mib = peak / (1024*1024)
print(f"peak_heap_mib={mib:.1f} ratio={ratio:.2f}")
if mib < 150 and ratio < 0.70:
    print("OUTCOME: headroom_ok — close JK-1075 with regression guard; keep 256 default")
elif mib < 200:
    print("OUTCOME: monitor — headroom OK-ish; guard recommended; 1083 optional")
else:
    print("OUTCOME: pressure — promote JK-1083 bound/spill")
PY

rm -f "$peak_file"
exit "$build_ec"
