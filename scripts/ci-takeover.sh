#!/usr/bin/env bash
# The checkout's own jk takes the job's home over: the jk the bootstrap left on PATH (the pinned
# release, or the bridge it built) installs the tree, then the tree's own client — now at
# $JK_HOME/bin/jk — installs it again. A client older than the tree hands that second, re-shelving
# pass to the tree's client instead of running it; a client of the tree's version has already run
# it, and the second install finds nothing to do. Then the home is proven to be the checkout's:
# its client and engine byte for byte, and every first-party worker on the shelf describes itself.
#
# Usage: scripts/ci-takeover.sh [--build] [--install-only] [--yes]
#   --build          build the tree first (jobs whose earlier step did not)
#   --install-only   the two installs and nothing else (jobs that check the home their own way)
#   --yes            passed to jk build and jk install
# Any failure prints the tail of jk's client log, which records each failed command's stack, and
# of the newest engine log, so a red run names its cause.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JK_HOME:?JK_HOME must name the isolated home of this job}"

build=0
checks=1
yes=()
for arg in "$@"; do
  case "$arg" in
    --build) build=1 ;;
    --install-only) checks=0 ;;
    --yes) yes=(--yes) ;;
    *)
      echo "ci-takeover: unknown argument $arg" >&2
      exit 64
      ;;
  esac
done

# The engine state as the next command finds it: every file with its inode, what the endpoint and
# pid files name, the POSIX locks held on those inodes, and the engine JVMs alive.
snapshot() {
  local dir="$JK_HOME/state/engine" f inode
  echo "--- engine state ($1)"
  if [[ ! -d "$dir" ]]; then
    echo "(no $dir)"
    return 0
  fi
  ls -li "$dir" || true
  for f in "$dir"/*.endpoint "$dir"/*.pid; do
    if [[ -f "$f" ]]; then echo "$(basename "$f"): $(tr '\n' ' ' < "$f")"; fi
  done
  if [[ -r /proc/locks ]]; then
    for f in "$dir"/*.lock; do
      if [[ ! -f "$f" ]]; then continue; fi
      inode="$(stat -c %i "$f")"
      echo "$(basename "$f") inode $inode: $(grep -E ":$inode " /proc/locks | tr '\n' ' ' || true)"
    done
  fi
  pgrep -af -- "$JK_HOME/lib/jk-engine" | cut -c1-160 || true
}

dump_logs() {
  local cli="$JK_HOME/state/cli.log" engine
  if [[ -f "$cli" ]]; then
    echo "--- tail of $cli"
    tail -n 80 "$cli"
  fi
  engine=""
  for log in "$JK_HOME"/state/engine/*.log; do
    [[ -f "$log" ]] || continue
    if [[ -z "$engine" || "$log" -nt "$engine" ]]; then engine="$log"; fi
  done
  if [[ -n "$engine" ]]; then
    echo "--- tail of $engine"
    tail -n 80 "$engine"
  fi
}
on_exit() {
  local status=$?
  if [[ $status -ne 0 ]]; then
    if [[ -n "${state_log:-}" && -f "$state_log" ]]; then cat "$state_log"; fi
    snapshot "after the failure" || true
    dump_logs
  fi
  exit "$status"
}
trap on_exit EXIT

run() {
  echo "ci-takeover: $*"
  "$@"
}

# An install, with the engine state before it kept for the failure dump.
install_pass() {
  snapshot "before: $*" >> "$state_log" 2>&1 || true
  run "$@"
}

cd "$ROOT"
state_log="$(mktemp)"
if ((build)); then run jk build --skip-tests ${yes[@]+"${yes[@]}"} --no-ansi; fi
install_pass jk install --skip-tests ${yes[@]+"${yes[@]}"} --no-ansi
install_pass "$JK_HOME/bin/jk" install --skip-tests ${yes[@]+"${yes[@]}"} --no-ansi
((checks)) || exit 0
run cmp target/dist/jk "$JK_HOME/bin/jk"
engine_sha="$(sha256sum target/dist/lib/jk-engine-*.jar | cut -c1-64)"
run grep -q "engine-sha256 = \"$engine_sha\"" "$JK_HOME/lib/jk-engine/jk-engine.toml"
run bash scripts/check-shelf-descriptors.sh "$JK_HOME"
run "$JK_HOME/bin/jk" engine status --no-ansi
