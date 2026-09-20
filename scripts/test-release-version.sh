#!/usr/bin/env bash
# Fixture test for release-version.sh: the request must be well-formed and equal the source's
# version; no request at all (a dry run) answers with the source's version.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
declared="$(grep -E 'VERSION = "' "$ROOT/shared/jk-api/src/main/java/cc/jumpkick/model/JkVersion.java" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
[[ -n "$declared" ]] || { echo "test-release-version: JkVersion.java declares no VERSION" >&2; exit 1; }

accepts() {
  local got
  got="$("$ROOT/scripts/release-version.sh" "$1")" || { echo "test-release-version: '$1' was refused" >&2; exit 1; }
  [[ "$got" == "$declared" ]] || { echo "test-release-version: '$1' printed '$got'" >&2; exit 1; }
}
refuses() {
  if "$ROOT/scripts/release-version.sh" "$1" >/dev/null 2>&1; then
    echo "test-release-version: '$1' was accepted (${2:-})" >&2
    exit 1
  fi
}

accepts "$declared"
accepts ""
refuses "v$declared" "a v prefix is the tag's, not the version's"
refuses "$declared.9999" "a version the source does not declare"
refuses '0.1.0; rm -rf /' "shell text in a dispatch input"
refuses '0.1' "two segments"
refuses "$declared-" "a dangling qualifier separator"
echo "test-release-version: ok"
