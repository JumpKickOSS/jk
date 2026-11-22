#!/usr/bin/env bash
# After a release is published: put this machine (Linux, WSL or macOS) on it, keeping only the stored
# logins, and time it. One host of release-fleet.sh's walk, in an order that interrupts no work:
#   drain   wait until this home's engine runs no job, plans none and has no agent (MCP) session
#           connected; a host that stays busy past JK_FLEET_DRAIN_S (default 3600) is left untouched
#   stop    every engine of the home stops, and the script waits until none is alive
#   wipe    everything under ~/.jk goes except ~/.jk/creds (bin last); nothing is migrated
#   install the published `curl … install.sh | bash`
#   profile the fleet's machine settings: the file JK_FLEET_PROFILE names becomes ~/.jk/config.toml
#   start   the engine starts and must answer as the release
#   verify  the jk checkout is brought to the released commit and built twice (cold, then warm)
# Secrets outside ~/.jk (the Central token and other home dot-files) are never touched.
#
# Usage:
#   [JK_FLEET_PROFILE=<file>] bash release-fleet-check.sh <version> [checkout]
#   bash release-fleet-check.sh --checkout-only <version> <checkout>
#   bash release-fleet-check.sh --wipe-only <home>
#   bash release-fleet-check.sh --idle-only <engine-status-json>
# The --*-only forms run one step alone (what scripts/test-release-fleet-check.sh drives):
# --checkout-only prints "usable" and exits 0 when <checkout> was brought to tag v<version>, else
# prints why not; --wipe-only empties <home> but its creds; --idle-only prints "stopped", "idle" or
# "busy" for one line of `jk engine status -O json`.
# <checkout> defaults to ~/src/oss/jk. It is fast-forwarded to tag v<version> only when it is on
# main, clean, and holds no commit origin lacks; otherwise it is left alone, reported, and
# ~/src/oss/jk-fleet is used instead (under the same rule).
# Refuses to run unless https://jumpkick.build/releases/latest/VERSION is <version>: it empties the
# home, and must only ever refill it with the release just published. Refuses a JK_HOME other than
# ~/.jk.
#
# Prints progress on stderr and one line on stdout:
#   RESULT host=… os=… cores=… ram_gb=… jk=… commit=… install_s=… build1_s=… build2_s=… checkout=… profile=… note=… success=true|false
set -uo pipefail

# Whether <dir> can be brought to tag v$VER: on main, clean, nothing origin lacks; then fast-forwarded.
# Only main and the release tag are fetched: a local tag that origin moved or dropped is no reason to
# pass over the checkout (a plain fetch --tags fails on it with "would clobber existing tag").
usable() {
  local dir="$1" branch
  [[ -d "$dir/.git" ]] || { echo "absent"; return 1; }
  git -C "$dir" fetch -q --no-tags origin "+refs/heads/main:refs/remotes/origin/main" \
    "+refs/tags/v$VER:refs/tags/v$VER" || { echo "fetch-failed"; return 1; }
  branch="$(git -C "$dir" symbolic-ref --short -q HEAD || echo detached)"
  [[ "$branch" == main ]] || { echo "on-$branch"; return 1; }
  [[ -z "$(git -C "$dir" status --porcelain)" ]] || { echo "dirty"; return 1; }
  [[ -z "$(git -C "$dir" log --oneline origin/main..main)" ]] || { echo "unpushed-commits"; return 1; }
  git -C "$dir" merge -q --ff-only "v$VER" || { echo "not-fast-forward"; return 1; }
  return 0
}

# Whether one line of `jk engine status -O json` shows an engine with nothing to interrupt: none
# running, or one with no job, no queued build and no agent (MCP) session connected. A connection
# alone (this status request, a dashboard tab) is nothing to interrupt.
idle() {
  local json="$1" n
  [[ "$json" == *'"running":false'* ]] && return 0
  [[ "$json" == *'"running":true'* ]] || return 1
  [[ "$json" == *'"jobs":[]'* ]] || return 1
  for n in queuedBuildPlans mcpActiveConnections; do
    [[ "$json" =~ \"$n\":([0-9]+) ]] || continue
    ((BASH_REMATCH[1] == 0)) || return 1
  done
  return 0
}

# Whether that line shows no engine of this home alive: engines of other homes are not ours to wait on.
stopped() { [[ "$1" == *'"running":false'* && "$1" != *'"otherHome":false'* ]]; }

# Delete everything under <home> but creds, bin last: a home left half-deleted keeps a jk to run.
# Prints what is left when something would not go.
wipe() {
  local home="${1:?}" entry left
  [[ -d "$home" ]] || return 0
  for entry in "$home"/* "$home"/.[!.]* "$home"/..?*; do
    [[ -e "$entry" || -L "$entry" ]] || continue
    case "${entry##*/}" in creds | bin) continue ;; esac
    rm -rf "$entry"
  done
  rm -rf "${home:?}/bin"
  left="$(find "$home" -mindepth 1 -maxdepth 1 ! -name creds 2>/dev/null)"
  [[ -z "$left" ]] || { echo "$left"; return 1; }
}

if [[ "${1:-}" == --wipe-only ]]; then
  if left="$(wipe "${2:?usage: release-fleet-check.sh --wipe-only <home>}")"; then exit 0; fi
  echo "left: $left"
  exit 1
fi
if [[ "${1:-}" == --idle-only ]]; then
  json="${2:?usage: release-fleet-check.sh --idle-only <engine-status-json>}"
  if stopped "$json"; then echo stopped; elif idle "$json"; then echo idle; else echo busy; fi
  exit 0
fi
if [[ "${1:-}" == --checkout-only ]]; then
  VER="${2:?usage: release-fleet-check.sh --checkout-only <version> <checkout>}"
  if why="$(usable "${3:?usage: release-fleet-check.sh --checkout-only <version> <checkout>}")"; then
    echo usable
    exit 0
  fi
  echo "$why"
  exit 1
fi
VER="${1:?usage: release-fleet-check.sh <version> [checkout]}"
CHECKOUT="${2:-$HOME/src/oss/jk}"
SITE="${JK_FLEET_SITE:-https://jumpkick.build}"
HOMEDIR="$HOME/.jk"

host="$(hostname -s 2>/dev/null || hostname)"
os="$(uname -s)-$(uname -m)"
[[ -n "${WSL_DISTRO_NAME:-}" ]] && os="WSL-${WSL_DISTRO_NAME}-$(uname -m)"
if [[ "$(uname -s)" == Darwin ]]; then
  cores="$(sysctl -n hw.ncpu)"
  ram_gb="$(($(sysctl -n hw.memsize) / 1073741824))"
else
  cores="$(nproc)"
  ram_gb="$(awk '/MemTotal/ { printf "%d", $2 / 1048576 + 0.5 }' /proc/meminfo)"
fi
jk_ver="" commit="" profile="" install_s="" build1_s="" build2_s="" used="" note="" success=false
say() { echo "fleet-check: $*" >&2; }
result() {
  echo "RESULT host=$host os=$os cores=$cores ram_gb=$ram_gb jk=${jk_ver:-none} commit=${commit:-none}" \
    "install_s=${install_s:--} build1_s=${build1_s:--} build2_s=${build2_s:--} checkout=${used:-none}" \
    "profile=${profile:-none} note=${note:-none} success=$success"
}
fail() { note="$1"; say "$1"; result; exit 1; }
now() { perl -MTime::HiRes=time -e 'printf "%.1f\n", time'; }
since() { perl -e "printf '%.1f', $(now) - $1"; }

published="$(curl -fsSL "$SITE/releases/latest/VERSION" 2>/dev/null | tr -d '[:space:]')"
[[ "$published" == "$VER" ]] || fail "refusing: $SITE/releases/latest/VERSION is '${published:-unreadable}', not $VER"
if [[ -n "${JK_HOME:-}" && "$JK_HOME" != "$HOMEDIR" ]]; then fail "refusing: JK_HOME is $JK_HOME, not $HOMEDIR"; fi

PROFILE="${JK_FLEET_PROFILE:-}"
[[ -z "$PROFILE" || -f "$PROFILE" ]] || fail "refusing: JK_FLEET_PROFILE names $PROFILE, which is no file"
DRAIN_S="${JK_FLEET_DRAIN_S:-3600}"
JK="$HOMEDIR/bin/jk"
# The engine status as one JSON line, or nothing when no jk answers.
status_json() { "$JK" engine status -O json --no-ansi 2>/dev/null | grep -a '"type":"engine-status"' | tail -1; }

# 0. drain: wait until this home's engine has nothing to interrupt, then stop every engine of it
if [[ -x "$JK" ]]; then
  t0="$(now)"
  until idle "$(status_json)"; do
    [[ "$(perl -e "print $(since "$t0") > $DRAIN_S ? 1 : 0")" == 0 ]] ||
      fail "drain: the engine stayed busy for ${DRAIN_S}s (jobs or agent sessions); the host is left as it was"
    say "draining: the engine is busy; looking again in 30 s"
    sleep 30
  done
  "$JK" engine stop -A --no-ansi >&2 || say "engine stop -A failed; waiting for the engines to exit"
  t0="$(now)"
  until stopped "$(status_json)"; do
    [[ "$(perl -e "print $(since "$t0") > $DRAIN_S ? 1 : 0")" == 0 ]] ||
      fail "drain: an engine of $HOMEDIR is still alive after ${DRAIN_S}s; the host is left as it was"
    sleep 5
  done
fi
# 1. wipe: the home goes but its creds
if left="$(wipe "$HOMEDIR")"; then :; else fail "could not empty $HOMEDIR: $(head -1 <<<"$left")"; fi

# 2. the published installer, as a user runs it
t0="$(now)"
curl -fsSL "$SITE/install.sh" | env JK_NONINTERACTIVE=1 bash >&2 || fail "the published installer failed"
install_s="$(since "$t0")"
version_v="$("$JK" --version -v --no-ansi 2>&1)"
jk_ver="$(printf '%s\n' "$version_v" | sed -n '1s/^jk //p')"
commit="$(printf '%s\n' "$version_v" | sed -n 's/.*client .* commit \([0-9a-f]*\).*/\1/p' | head -1)"
[[ "$jk_ver" == "$VER" ]] || fail "the installed jk reports '$jk_ver', not $VER"

# 2b. the fleet's machine settings, then the engine, which must answer as the release
if [[ -n "$PROFILE" ]]; then
  install -m 600 "$PROFILE" "$HOMEDIR/config.toml" || fail "could not write $HOMEDIR/config.toml from $PROFILE"
  profile="$(basename "$PROFILE")"
fi
"$JK" engine start --no-ansi >&2 || fail "the engine did not start"
[[ "$(status_json)" == *"\"version\":\"$VER\""* ]] || fail "the engine does not answer as $VER"

# 3. the checkout at the released commit, or the fleet checkout when the usual one is someone's work
if why="$(usable "$CHECKOUT")"; then
  used="$CHECKOUT"
else
  note="$CHECKOUT:$why"
  say "$CHECKOUT left alone ($why); using $HOME/src/oss/jk-fleet"
  if why2="$(usable "$HOME/src/oss/jk-fleet")"; then
    used="$HOME/src/oss/jk-fleet"
  else
    fail "$note,jk-fleet:$why2"
  fi
fi

# 4. two builds of the released tree with the released jk
cd "$used" || fail "cannot enter $used"
t0="$(now)"
"$JK" build --no-ansi >&2 || { build1_s="$(since "$t0")"; fail "${note:+$note,}first-build-failed"; }
build1_s="$(since "$t0")"
t0="$(now)"
"$JK" build --no-ansi >&2 || { build2_s="$(since "$t0")"; fail "${note:+$note,}second-build-failed"; }
build2_s="$(since "$t0")"
success=true
result
