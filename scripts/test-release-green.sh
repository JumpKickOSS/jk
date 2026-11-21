#!/usr/bin/env bash
# Fixture test for scripts/release-green.sh in a scratch checkout: with no green branch on origin
# nothing passes; the commit green names passes, and an older one only as reached; a commit past
# green, or off its history, fails both ways.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-release-green.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "test-release-green: $*" >&2
  exit 1
}
g() { git -C "$WORK/co" -c user.name=t -c user.email=t@localhost -c commit.gpgsign=false "$@"; }
check() { bash "$WORK/co/scripts/release-green.sh" "$@" 2>"$WORK/err"; }

git init -q --bare -b main "$WORK/origin.git"
git clone -q "$WORK/origin.git" "$WORK/co" 2>/dev/null
mkdir -p "$WORK/co/scripts"
cp "$ROOT/scripts/release-green.sh" "$WORK/co/scripts/"
g add -A && g commit -qm one
old="$(g rev-parse HEAD)"
g commit -qm two --allow-empty
green="$(g rev-parse HEAD)"
g push -q origin HEAD:main

if check; then fail "a release passed with no green branch on origin"; fi
grep -q 'no green branch' "$WORK/err" || fail "the refusal does not say green is missing: $(cat "$WORK/err")"

g push -q origin HEAD:green
[[ "$(check)" == "$green" ]] || fail "green itself did not pass: $(cat "$WORK/err")"
if check "$old"; then fail "a commit before green passed as the one to cut"; fi
grep -q 'check out origin/green' "$WORK/err" || fail "the refusal does not say where to cut: $(cat "$WORK/err")"
check --reached "$old" >/dev/null || fail "a commit green has passed through was refused: $(cat "$WORK/err")"

g commit -qm three --allow-empty
past="$(g rev-parse HEAD)"
if check "$past"; then fail "a commit past green passed"; fi
if check --reached "$past"; then fail "a commit past green passed as reached"; fi
grep -q 'not on green' "$WORK/err" || fail "the refusal does not say it is off green: $(cat "$WORK/err")"

echo "test-release-green: ok"
