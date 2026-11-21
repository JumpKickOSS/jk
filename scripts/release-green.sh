#!/usr/bin/env bash
# Asserts that a release is cut from green: the commit origin's `green` branch names, which the Full
# tier passed last (scripts/gate/full-loop.sh moves it). Before the cut the release commit must be
# that commit; later (a lane building the tag) green may have moved on, and the commit must be one
# it has passed through.
#
# Usage:
#   scripts/release-green.sh [commit]             the commit (default HEAD) is green
#   scripts/release-green.sh --reached <commit>   the commit is green or an ancestor of it
# Fetches green from origin first. Prints green's commit on success; otherwise says why and exits 1.
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
reached=""
if [[ "${1:-}" == --reached ]]; then
  reached=1
  shift
fi
want="${1:-HEAD}"

fail() {
  echo "release-green: $1" >&2
  exit 1
}

commit="$(git -C "$DIR" rev-parse -q --verify "$want^{commit}" 2>/dev/null)" || fail "$want is not a commit here"
git -C "$DIR" fetch -q origin "+refs/heads/green:refs/remotes/origin/green" 2>/dev/null \
  || fail "origin has no green branch (or cannot be reached): the Full tier has not passed a commit yet"
green="$(git -C "$DIR" rev-parse refs/remotes/origin/green)"

if [[ "$commit" != "$green" ]]; then
  [[ -n "$reached" ]] || fail "$want is ${commit:0:12}, not green (${green:0:12}): check out origin/green and cut the release there"
  git -C "$DIR" merge-base --is-ancestor "$commit" "$green" \
    || fail "$want (${commit:0:12}) is not on green's history (${green:0:12}): it was not cut from green"
fi
echo "$green"
