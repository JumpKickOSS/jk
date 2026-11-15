#!/usr/bin/env bash
# Asserts that a checkout builds the release, before anything is built: jk builds a release exactly
# when the tree is a clean checkout whose HEAD carries the tag v<version> for the version jk.toml
# declares, and anything else (a shallow or tagless CI checkout included) silently builds a
# -SNAPSHOT. The release scripts call this first, so a missing tag fails here and not at the SBOM.
#
# Usage:
#   scripts/release-session.sh [checkout]      (default: the checkout this script is in)
# Prints the version on success; otherwise says what is missing and exits 1.
set -euo pipefail

DIR="$(cd "${1:-$(dirname "$0")/..}" && pwd)"
VER="$(bash "$DIR/scripts/release-version.sh")"
TAG="v$VER"

fail() {
  echo "release-session: $1" >&2
  exit 1
}

git -C "$DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || fail "$DIR is not a git checkout, so jk builds a snapshot; build the release from a checkout of $TAG"
head="$(git -C "$DIR" rev-parse HEAD)"
tagged="$(git -C "$DIR" rev-parse -q --verify "refs/tags/$TAG^{commit}" 2>/dev/null || true)"
[[ -n "$tagged" ]] || fail "HEAD carries no tag $TAG: fetch tags (git fetch origin tag $TAG) and check it out"
[[ "$tagged" == "$head" ]] || fail "HEAD is not $TAG ($TAG is ${tagged:0:12}): check out $TAG"
dirty="$(git -C "$DIR" status --porcelain --untracked-files=normal)"
[[ -z "$dirty" ]] || fail "the tree differs from $TAG, so jk would build a snapshot:
$dirty"
echo "$VER"
