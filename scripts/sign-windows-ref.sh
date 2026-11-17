#!/usr/bin/env bash
# What sign-windows.yml may build and sign at the ref it was dispatched on, decided on Linux before a
# Windows runner starts: a tag v<version> for the version the checkout's jk.toml declares (the
# release, built as the release lanes build it), or a tag v0.0.0-signtest.<n> on a commit that is
# not a release (a dry run of the signing: jk builds such a commit as <version>-SNAPSHOT, which is
# signed the same way and never published).
# Anything else is refused: a branch, an untagged commit, a v* tag naming another version.
#
# Usage:
#   scripts/sign-windows-ref.sh <ref-type> <ref-name>    (github.ref_type, github.ref_name)
# Run from a checkout of the ref. Prints release=true|false and version=<v> (the lines the workflow
# appends to GITHUB_OUTPUT) and exits 0; otherwise says why and exits 1.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TYPE="${1:-}"
NAME="${2:-}"

fail() {
  echo "sign-windows-ref: $1" >&2
  exit 1
}

[[ "$TYPE" == tag ]] || fail "dispatched on the $TYPE '$NAME'; dispatch it on a release tag v<version> (or a v0.0.0-signtest.<n> dry-run tag)"
[[ "$NAME" =~ ^v[0-9] ]] || fail "'$NAME' is not a v* tag; the artifact-signing environment accepts only v* tags"
tagged="$(git -C "$ROOT" rev-parse -q --verify "refs/tags/$NAME^{commit}" 2>/dev/null || true)"
head="$(git -C "$ROOT" rev-parse HEAD)"
[[ "$tagged" == "$head" ]] || fail "the checkout is $head, not the commit tag $NAME names (${tagged:-none})"

if [[ "$NAME" =~ ^v0\.0\.0-signtest\.[0-9]+$ ]]; then
  declared="$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$ROOT/jk.toml" | head -1)"
  [[ -n "$declared" ]] || fail "jk.toml declares no version"
  # A commit carrying its version's tag builds as the release, whatever ref it was dispatched on.
  released="$(git -C "$ROOT" rev-parse -q --verify "refs/tags/v$declared^{commit}" 2>/dev/null || true)"
  [[ "$released" != "$head" ]] || fail "$NAME is a dry-run tag on the release commit v$declared; dispatch on v$declared instead"
  echo "release=false"
  echo "version=$declared-SNAPSHOT"
  exit 0
fi
version="$(bash "$ROOT/scripts/release-version.sh" "${NAME#v}")" || fail "$NAME is neither the release jk.toml declares nor a v0.0.0-signtest.<n> dry-run tag"
echo "release=true"
echo "version=$version"
