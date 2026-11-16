#!/usr/bin/env bash
# Asserts that a checkout builds the release, before anything is built: jk builds a release exactly
# when the tree is a clean checkout whose HEAD carries the tag v<version> for the version jk.toml
# declares, and anything else (a shallow or tagless CI checkout included) silently builds a
# -SNAPSHOT. The release scripts call this first, so a missing tag fails here and not at the SBOM.
# A workspace a group root lists (an ancestor jk.toml whose [workspaces] members name it) carries its
# artifact name in front: <name>-v<version>, as jk tags it.
#
# Usage:
#   scripts/release-session.sh [checkout]      (default: the checkout this script is in)
# Prints the version on success; otherwise says what is missing and exits 1.
set -euo pipefail

DIR="$(cd "${1:-$(dirname "$0")/..}" && pwd)"
VER="$(bash "$DIR/scripts/release-version.sh")"
TAG="v$VER"

# The group root that lists DIR, when one does: the nearest ancestor jk.toml with a [workspaces]
# table, whose members (literal paths or one-segment globs) name DIR's path below it.
group_tag_prefix() {
  local up="$DIR" rel members m name
  while [[ "$up" != "/" ]]; do
    up="$(dirname "$up")"
    if [[ ! -f "$up/jk.toml" ]] || ! grep -qE '^[[:space:]]*\[workspaces\]' "$up/jk.toml"; then
      continue
    fi
    rel="${DIR#"$up"/}"
    members="$(sed -n '/^[[:space:]]*\[workspaces\]/,/^[[:space:]]*\[/p' "$up/jk.toml" | sed 's/#.*//' | tr '\n' ' ' \
      | sed -n 's/.*members[[:space:]]*=[[:space:]]*\[\([^]]*\)\].*/\1/p' | tr ',' '\n' \
      | sed 's/^[[:space:]]*["'"'"']//; s/["'"'"'][[:space:]]*$//')"
    while IFS= read -r m; do
      [[ -n "$m" ]] || continue
      # A glob entry matches segment by segment, as jk reads it: the same number of slashes.
      # shellcheck disable=SC2053 # the member entry is a glob on purpose
      if [[ "$rel" == "$m" ]] || { [[ "$rel" == $m ]] && [[ "${rel//[^\/]/}" == "${m//[^\/]/}" ]]; }; then
        name="$(sed -nE 's/^name[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$DIR/jk.toml" | head -1)"
        [[ -n "$name" ]] && echo "$name-"
        return 0
      fi
    done <<<"$members"
    return 0
  done
}
TAG="$(group_tag_prefix)$TAG"

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
