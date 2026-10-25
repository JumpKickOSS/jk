#!/usr/bin/env bash
# The version a release run is allowed to publish: the one the root jk.toml declares.
#
# Usage:
#   scripts/release-version.sh [requested]
# Prints the version when <requested> is well-formed and equals the jk.toml version; otherwise
# says which check failed and exits 1. The release tag is the request; the source is the
# answer — a run that publishes a tree stamped with one version under another's name is refused
# here, before anything is built. With no request (a dry run that publishes nothing) the declared
# version is printed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/jk.toml"
REQUESTED="${1:-}"

declared="$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$SOURCE" | head -1)"
if [[ -z "$declared" ]]; then
  echo "release-version: $SOURCE declares no version = \"…\"" >&2
  exit 1
fi
if [[ -z "$REQUESTED" ]]; then
  echo "$declared"
  exit 0
fi
if [[ ! "$REQUESTED" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]]; then
  echo "release-version: '$REQUESTED' is not a version (X.Y.Z with optional dotted or dashed qualifiers)" >&2
  exit 1
fi
if [[ "$REQUESTED" != "$declared" ]]; then
  echo "release-version: requested $REQUESTED but jk.toml declares $declared — bump the manifest or fix the tag" >&2
  exit 1
fi
echo "$declared"
