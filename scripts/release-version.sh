#!/usr/bin/env bash
# The version a release run is allowed to publish: the one JkVersion.java declares.
#
# Usage:
#   scripts/release-version.sh [requested]
# Prints the version when <requested> is well-formed and equals JkVersion.VERSION; otherwise
# says which check failed and exits 1. The release tag is the request; the source is the
# answer — a run that publishes a tree stamped with one version under another's name is refused
# here, before anything is built. With no request (a dry run that publishes nothing) the declared
# version is printed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/shared/jk-api/src/main/java/cc/jumpkick/model/JkVersion.java"
REQUESTED="${1:-}"

declared="$(grep -E 'VERSION = "' "$SOURCE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
if [[ -z "$declared" ]]; then
  echo "release-version: $SOURCE declares no VERSION = \"…\"" >&2
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
  echo "release-version: requested $REQUESTED but JkVersion.java declares $declared — bump the source or fix the tag" >&2
  exit 1
fi
echo "$declared"
