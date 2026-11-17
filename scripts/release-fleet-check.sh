#!/usr/bin/env bash
# After a release is published: put this machine (Linux, WSL or macOS) on it from nothing and time
# it. Stops every engine, DELETES ~/.jk, installs with the published `curl … install.sh | bash`,
# brings the jk checkout to the released commit and builds it twice (cold, then warm).
#
# Usage:
#   bash release-fleet-check.sh <version> [checkout]
#   bash release-fleet-check.sh --checkout-only <version> <checkout>
# --checkout-only runs the checkout step alone (what scripts/test-release-fleet-check.sh drives):
# prints "usable" and exits 0 when <checkout> was brought to tag v<version>, else prints why not.
# <checkout> defaults to ~/src/oss/jk. It is fast-forwarded to tag v<version> only when it is on
# main, clean, and holds no commit origin lacks; otherwise it is left alone, reported, and
# ~/src/oss/jk-fleet is used instead (under the same rule).
# Refuses to run unless https://jumpkick.build/releases/latest/VERSION is <version>: it deletes the
# home, and must only ever replace it with the release just published. Refuses a JK_HOME other than
# ~/.jk.
#
# Prints progress on stderr and one line on stdout:
#   RESULT host=… os=… cores=… ram_gb=… jk=… commit=… install_s=… build1_s=… build2_s=… checkout=… note=… success=true|false
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
jk_ver="" commit="" install_s="" build1_s="" build2_s="" used="" note="" success=false
say() { echo "fleet-check: $*" >&2; }
result() {
  echo "RESULT host=$host os=$os cores=$cores ram_gb=$ram_gb jk=${jk_ver:-none} commit=${commit:-none}" \
    "install_s=${install_s:--} build1_s=${build1_s:--} build2_s=${build2_s:--} checkout=${used:-none}" \
    "note=${note:-none} success=$success"
}
fail() { note="$1"; say "$1"; result; exit 1; }
now() { perl -MTime::HiRes=time -e 'printf "%.1f\n", time'; }
since() { perl -e "printf '%.1f', $(now) - $1"; }

published="$(curl -fsSL "$SITE/releases/latest/VERSION" 2>/dev/null | tr -d '[:space:]')"
[[ "$published" == "$VER" ]] || fail "refusing: $SITE/releases/latest/VERSION is '${published:-unreadable}', not $VER"
if [[ -n "${JK_HOME:-}" && "$JK_HOME" != "$HOMEDIR" ]]; then fail "refusing: JK_HOME is $JK_HOME, not $HOMEDIR"; fi

# 0. every engine of this home stops
if [[ -x "$HOMEDIR/bin/jk" ]]; then
  "$HOMEDIR/bin/jk" engine stop -A --no-ansi >&2 || say "engine stop -A failed; continuing"
fi
# 1. the home goes
rm -rf "$HOMEDIR" || fail "could not delete $HOMEDIR"
[[ ! -e "$HOMEDIR" ]] || fail "$HOMEDIR still exists"

# 2. the published installer, as a user runs it
t0="$(now)"
curl -fsSL "$SITE/install.sh" | env JK_NONINTERACTIVE=1 bash >&2 || fail "the published installer failed"
install_s="$(since "$t0")"
JK="$HOMEDIR/bin/jk"
version_v="$("$JK" --version -v --no-ansi 2>&1)"
jk_ver="$(printf '%s\n' "$version_v" | sed -n '1s/^jk //p')"
commit="$(printf '%s\n' "$version_v" | sed -n 's/.*client .* commit \([0-9a-f]*\).*/\1/p' | head -1)"
[[ "$jk_ver" == "$VER" ]] || fail "the installed jk reports '$jk_ver', not $VER"

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
