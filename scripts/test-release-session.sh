#!/usr/bin/env bash
# Fixture test for scripts/release-session.sh, network-free: a throwaway checkout at version 9.8.7.
# Only a clean checkout whose HEAD carries v9.8.7 (annotated or lightweight) passes; an untagged
# HEAD, the tag on another commit, a tag for another version and a tree with changes each fail with
# the line that says so.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/jk-release-session-test.XXXXXX")" && pwd)"
trap 'rm -rf "$WORK"' EXIT
REPO="$WORK/repo"

fail() {
  echo "test-release-session: $1" >&2
  [[ -f "$WORK/out.log" ]] && cat "$WORK/out.log" >&2
  exit 1
}
g() { git -C "$REPO" -c user.name=t -c user.email=t@localhost -c commit.gpgsign=false -c tag.gpgsign=false "$@"; }
check() { bash "$REPO/scripts/release-session.sh" >"$WORK/out.log" 2>&1; }
refused() {
  if check; then fail "$1 passed"; fi
  grep -qF "$2" "$WORK/out.log" || fail "$1 did not say: $2"
  echo "ok   $1 is refused"
}

mkdir -p "$REPO/scripts"
cp "$ROOT/scripts/release-session.sh" "$ROOT/scripts/release-version.sh" "$REPO/scripts/"
printf 'version = "9.8.7"\n' >"$REPO/jk.toml"
g init -q -b main && g add -A && g commit -q -m "release 9.8.7"

refused "an untagged HEAD" "HEAD carries no tag v9.8.7: fetch tags"
g tag v9.8.6
refused "a tag for another version" "HEAD carries no tag v9.8.7"
g tag v9.8.7
check || fail "a clean checkout at a lightweight v9.8.7 was refused"
[[ "$(cat "$WORK/out.log")" == 9.8.7 ]] || fail "the release session did not print the version"
echo "ok   a clean checkout at its lightweight tag is the release"
g tag -d v9.8.7 >/dev/null && g tag -a v9.8.7 -m "jk 9.8.7"
check || fail "a clean checkout at an annotated v9.8.7 was refused"
echo "ok   an annotated tag counts"
printf 'x\n' >"$REPO/NOTES.md"
refused "a tree with changes" "the tree differs from v9.8.7"
g add NOTES.md && g commit -q -m after
refused "the tag on another commit" "HEAD is not v9.8.7"
echo "test-release-session: ok"
