#!/usr/bin/env bash
# What sign-windows.yml may build and sign at the ref it was dispatched on, decided on Linux before a
# Windows runner starts: a tag v<version> for the version the checkout's jk.toml declares (the
# release, built as the release lanes build it), or a tag v0.0.0-signtest.<n> (a dry run of the
# signing: the declared -SNAPSHOT is built and signed, and nothing it makes is ever published).
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
  [[ "$declared" == *-SNAPSHOT ]] || fail "$NAME is a dry-run tag, but jk.toml declares the release $declared; tag the release v$declared instead"
  echo "release=false"
  echo "version=$declared"
  exit 0
fi
version="$(bash "$ROOT/scripts/release-version.sh" "${NAME#v}")" || fail "$NAME is neither the release jk.toml declares nor a v0.0.0-signtest.<n> dry-run tag"
echo "release=true"
echo "version=$version"
