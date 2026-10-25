#!/usr/bin/env bash
# Reinstall jk from this checkout when the installed jk is too old to build it. Tries the plain
# `jk snapshot --skip-tests` (`jk install` on a jk without it) first; when that fails, installs stepping
# stones from a scratch worktree until the checkout builds:
#   - a table no installed plugin owns, where the tree's own plugin provides it: the tree at HEAD
#     without the members that use the table, which installs the plugin;
#   - anything else: the newest commit between the installed jk's source and HEAD that the
#     installed jk builds, found by bisecting the first-parent history.
# The checkout itself is never edited. See docs/contributors/self-host.md#the-bootstrap-chain.
#
# Usage: scripts/reinstall-chain.sh
#   JK       the jk to run (default: jk on PATH)
#   JK_HOME  the install whose engine jar names the installed jk's source (default: ~/.jk)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK="${JK:-jk}"
JK_HOME="${JK_HOME:-$HOME/.jk}"
MAX_STAGES="${JK_CHAIN_MAX_STAGES:-8}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-reinstall-chain.XXXXXX")"
SCRATCH="$WORK/tree"
LOGS="$WORK/logs"
mkdir -p "$LOGS"

cleanup() {
  if [ -d "$SCRATCH" ]; then git -C "$ROOT" worktree remove --force "$SCRATCH" >/dev/null 2>&1 || true; fi
  git -C "$ROOT" worktree prune >/dev/null 2>&1 || true
}
trap cleanup EXIT

say() { echo "reinstall-chain: $*" >&2; }
die() {
  say "$1"
  if [ -n "${2:-}" ] && [ -f "$2" ]; then tail -n 20 "$2" >&2; fi
  say "logs: $LOGS"
  exit 1
}

# Point the scratch worktree at a commit, discarding whatever the last stage changed there.
scratch_at() {
  if [ -d "$SCRATCH" ]; then
    git -C "$SCRATCH" reset -q --hard
    git -C "$SCRATCH" checkout -q --detach "$1"
  else
    git -C "$ROOT" worktree add -q --detach "$SCRATCH" "$1"
  fi
}

# Build the scratch tree. The scratch lock is thrown away, so a lock the installed jk refuses to
# rewrite is forced there and the build retried once.
scratch_build() {
  local log="$1"
  if (cd "$SCRATCH" && "$JK" build --skip-tests) >"$log" 2>&1; then return 0; fi
  grep -aq "jk-lock.toml" "$log" || return 1
  (cd "$SCRATCH" && "$JK" lock --force && "$JK" build --skip-tests) >>"$log" 2>&1
}

scratch_install() {
  (cd "$SCRATCH" && "$JK" install --skip-tests) >>"$1" 2>&1
}

# The commit the installed jk was built from: the newest first-parent commit no later than its
# engine jar's Build-Time. Empty when the install or its stamp cannot be read.
installed_commit() {
  local pointer="$JK_HOME/lib/jk-engine/jk-engine.toml" jar stamp
  [ -f "$pointer" ] || return 0
  jar="$(sed -n 's/^jar *= *"\(.*\)"$/\1/p' "$pointer")"
  if [ -z "$jar" ] || [ ! -f "$JK_HOME/lib/jk-engine/$jar" ]; then return 0; fi
  stamp="$(python3 - "$JK_HOME/lib/jk-engine/$jar" <<'PY' 2>/dev/null || true
import sys, zipfile
manifest = zipfile.ZipFile(sys.argv[1]).read("META-INF/MANIFEST.MF").decode()
for line in manifest.splitlines():
    if line.startswith("Build-Time:"):
        print(line.split(":", 1)[1].strip())
PY
)"
  [ -n "$stamp" ] || return 0
  git -C "$ROOT" rev-list --first-parent -n 1 --before="$stamp" HEAD
}

# A root jk.toml line that lists member $1, alone on its line.
member_line() { echo "^[[:space:]]*\"$1\",\{0,1\}[[:space:]]*\(#.*\)\{0,1\}$"; }

# Members of the scratch tree whose jk.toml opens table $1, one directory per line.
users_of_table() {
  local table="$1" manifest dir
  while IFS= read -r manifest; do
    dir="${manifest%/jk.toml}"
    grep -q "$(member_line "$dir")" "$SCRATCH/jk.toml" || continue
    if grep -Eq "^\[{1,2}$table(\.[^]]*)?\]{1,2}[[:space:]]*(#.*)?$" "$SCRATCH/$manifest"; then echo "$dir"; fi
  done < <(git -C "$SCRATCH" ls-files | grep -E '/jk\.toml$')
}

# Stage: HEAD without the members that use table $1, so the tree's own plugin for it installs.
strip_stage() {
  local table="$1" log="$LOGS/$stage-without-$1.log" users member
  scratch_at "$(git -C "$ROOT" rev-parse HEAD)"
  users="$(users_of_table "$table")"
  [ -n "$users" ] || die "[$table] is not owned by any installed plugin, and no member the root jk.toml lists opens it"
  while IFS= read -r member; do
    sed -i.bak "\|$(member_line "$member")|d" "$SCRATCH/jk.toml"
    rm -f "$SCRATCH/jk.toml.bak"
  done <<<"$users"
  users="$(echo "$users" | tr '\n' ' ' | sed 's/ $//')"
  say "stage $stage: HEAD without $users to install the plugin that owns [$table]"
  if ! { scratch_build "$log" && scratch_install "$log"; }; then
    die "HEAD without the members that use [$table] did not build and install" "$log"
  fi
  chain+=("HEAD without $users")
}

# Stage: the newest commit after the installed jk's source that the installed jk builds.
bridge_stage() {
  local base head candidates=() lo=-1 hi mid commit log
  base="${last_bridge:-$(installed_commit)}"
  head="$(git -C "$ROOT" rev-parse HEAD)"
  [ "$base" = "$head" ] && die "the installed jk was built from HEAD, so the checkout's failure is its own" "$1"
  while IFS= read -r commit; do candidates+=("$commit"); done < <(
    if [ -n "$base" ]; then
      git -C "$ROOT" rev-list --first-parent --reverse "$base..HEAD^"
    else
      git -C "$ROOT" rev-list --first-parent --reverse -n 50 HEAD^
    fi)
  [ "${#candidates[@]}" -gt 0 ] \
    || die "the checkout does not build and no commit lies between the installed jk's source and HEAD" "$1"
  say "stage $stage: bisecting ${#candidates[@]} commit(s) ${base:+after ${base:0:12} }for the newest the installed jk builds"
  hi="${#candidates[@]}"
  while [ $((hi - lo)) -gt 1 ]; do
    mid=$(((lo + hi) / 2))
    commit="${candidates[$mid]}"
    log="$LOGS/$stage-probe-${commit:0:12}.log"
    scratch_at "$commit"
    if scratch_build "$log"; then
      say "  ${commit:0:12} builds"
      lo="$mid"
    else
      say "  ${commit:0:12} does not"
      hi="$mid"
    fi
  done
  [ "$lo" -ge 0 ] || die "the installed jk builds none of the commits after its source; bridge by hand (docs/contributors/self-host.md#the-bootstrap-chain)" "$1"
  commit="${candidates[$lo]}"
  log="$LOGS/$stage-bridge-${commit:0:12}.log"
  scratch_at "$commit"
  if ! { scratch_build "$log" && scratch_install "$log"; }; then
    die "bridge ${commit:0:12} built but did not install" "$log"
  fi
  say "stage $stage: installed ${commit:0:12} $(git -C "$ROOT" log -1 --format=%s "$commit")"
  last_bridge="$commit"
  chain+=("${commit:0:12}")
}

stage=0
stripped=" "
last_bridge=""
chain=()
while :; do
  stage=$((stage + 1))
  [ "$stage" -le "$MAX_STAGES" ] || die "gave up after $MAX_STAGES stages"
  log="$LOGS/$stage-checkout.log"
  say "stage $stage: the checkout"
  # The checkout is an unreleased version: a jk that has `snapshot` installs it as a snapshot, so the
  # install is replaced on every run; an older jk installs it as the release it cannot tell apart.
  verb=install
  if "$JK" snapshot --help >/dev/null 2>&1; then verb=snapshot; fi
  if (cd "$ROOT" && "$JK" "$verb" --skip-tests) >"$log" 2>&1; then
    chain+=("the checkout")
    say "installed in $stage stage(s): $(printf '%s → ' "${chain[@]}" | sed 's/ → $//')"
    rm -rf "$WORK"
    exit 0
  fi
  table="$(grep -aoE '\[[a-z0-9-]+\] is not owned by any installed plugin' "$log" | head -n 1 | sed -E 's/^\[([^]]+)\].*/\1/' || true)"
  if [ -n "$table" ]; then
    case "$stripped" in *" $table "*) die "[$table] is still unowned after installing the tree without its users" "$log" ;; esac
    stripped="$stripped$table "
    strip_stage "$table"
  else
    bridge_stage "$log"
  fi
done
