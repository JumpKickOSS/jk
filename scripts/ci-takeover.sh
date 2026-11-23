#!/usr/bin/env bash
# The checkout's own jk takes the job's home over: the jk the bootstrap installed into $JK_HOME (the
# pinned release, or the bridge it built) installs the tree, then the tree's own client — now at
# $JK_HOME/bin/jk — installs it again. A client older than the tree hands that second, re-shelving
# pass to the tree's client instead of running it; a client of the tree's version has already run
# it, and the second install finds nothing to do. Then the home is proven to be the checkout's:
# its client and engine byte for byte, and every first-party worker on the shelf describes itself.
#
# Each install builds the tree first; no job runs a separate `jk build` before this.
#
# The first install must be a snapshot, so an older jk's build of the tree never holds a release's
# shelf slots, which the tree's own install then refuses to overwrite ("already installed … with
# other contents"). The home's jk is probed for the retired `jk snapshot` verb:
#   - it has the verb (an older pin): `jk snapshot` in the checkout;
#   - it has not, and the checkout is a clean one at its tag: `jk install` would build and install
#     the release, so the first install runs in a copy of the tag's files without .git, which jk
#     builds as a snapshot (a tree outside git is always one);
#   - it has not, and the checkout is anything else: `jk install` is already a snapshot.
# The second is the tree's own `jk install`, which builds the release when the checkout is a clean
# one at the version's tag and a snapshot otherwise.
#
# Usage: scripts/ci-takeover.sh [--install-only] [--yes] [--release]
#   --install-only   the two installs and nothing else (jobs that check the home their own way)
#   --yes            passed to both installs
#   --release        the install must be the release: the checkout is asserted to be one before
#                    anything builds (scripts/release-session.sh), and the installed jk to report it
# Any failure prints the tail of jk's client log, which records each failed command's stack, and
# of the newest engine log, so a red run names its cause.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JK_HOME:?JK_HOME must name the isolated home of this job}"
# A runner's Windows JK_HOME (D:\a\_temp/jk-home) would turn the log globs below into escapes.
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) JK_HOME="$(cygpath -u "$JK_HOME")" && export JK_HOME ;; esac
# Every jk below is the home's. A step run outside CI, where nothing carries the bootstrap's PATH
# over, would otherwise find the jk of another home first, whose engine this home does not have.
export PATH="$JK_HOME/bin:$PATH"

checks=1
yes=()
release=0
for arg in "$@"; do
  case "$arg" in
    --install-only) checks=0 ;;
    --yes) yes=(--yes) ;;
    --release) release=1 ;;
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
  rm -f "${state_log:-}"
  if [[ -n "${first_dir:-}" && "$first_dir" != "$ROOT" ]]; then rm -rf "$first_dir"; fi
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
# On Windows, setup-graalvm puts cl.exe on PATH with INCLUDE and LIB, and the pinned release's engine
# keeps PATH but drops INCLUDE and LIB, which native-image cannot build under. Its engine is stopped,
# and the next one starts without the Visual Studio environment, so native-image finds Visual Studio
# on its own. Retire this once the pin's engine passes INCLUDE, LIB and LIBPATH through.
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    unset INCLUDE LIB LIBPATH EXTERNAL_INCLUDE
    PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -v -i 'Microsoft Visual Studio\|Windows Kits' | paste -sd:)"
    export PATH
    run jk engine stop --now --no-ansi
    ;;
esac
if ((release)); then version="$(bash "$ROOT/scripts/release-session.sh")"; fi
verb=install
first_dir="$ROOT"
if jk snapshot --help >/dev/null 2>&1; then
  verb=snapshot
elif bash "$ROOT/scripts/release-session.sh" >/dev/null 2>&1; then
  first_dir="$(mktemp -d)"
  git -C "$ROOT" archive HEAD | tar -x -C "$first_dir"
fi
(cd "$first_dir" && install_pass jk "$verb" --skip-tests ${yes[@]+"${yes[@]}"} --no-ansi)
install_pass "$JK_HOME/bin/jk" install --skip-tests ${yes[@]+"${yes[@]}"} --no-ansi
if ((release)); then
  reported="$("$JK_HOME/bin/jk" --version --no-ansi | head -1 | tr -d '\r')"
  [[ "$reported" == "jk $version" ]] || { echo "ci-takeover: the installed jk reports '$reported', not the release jk $version" >&2; exit 1; }
fi
((checks)) || exit 0
run cmp target/dist/jk "$JK_HOME/bin/jk"
engine_sha="$(sha256sum target/dist/lib/jk-engine-*.jar | cut -c1-64)"
run grep -q "engine-sha256 = \"$engine_sha\"" "$JK_HOME/lib/jk-engine/jk-engine.toml"
run env JK_BIN="$JK_HOME/bin/jk" bash scripts/check-shelf-descriptors.sh "$JK_HOME"
run "$JK_HOME/bin/jk" engine status --no-ansi
