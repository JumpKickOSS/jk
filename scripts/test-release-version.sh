#!/usr/bin/env bash
# Fixture test for release-version.sh: the request must be well-formed and equal the source's
# version; no request at all (a dry run) answers with the source's version.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
declared="$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$ROOT/jk.toml" | head -1)"
[[ -n "$declared" ]] || { echo "test-release-version: jk.toml declares no version" >&2; exit 1; }

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

# A declared -SNAPSHOT version (any case) is never released, requested or not. The script reads the
# jk.toml beside its own scripts/ directory, so a copy of it in a scratch tree answers for a fixture.
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir "$scratch/scripts"
cp "$ROOT/scripts/release-version.sh" "$scratch/scripts/"
for snap in 1.2.3-SNAPSHOT 1.2.3-snapshot; do
  printf '[project]\nversion = "%s"\n' "$snap" > "$scratch/jk.toml"
  if "$scratch/scripts/release-version.sh" "$snap" >/dev/null 2>&1; then
    echo "test-release-version: $snap was released" >&2; exit 1
  fi
  if "$scratch/scripts/release-version.sh" >/dev/null 2>&1; then
    echo "test-release-version: a dry run answered for $snap" >&2; exit 1
  fi
done
printf '[project]\nversion = "1.2.3-SNAPSHOT-rc1"\n' > "$scratch/jk.toml"
[[ "$("$scratch/scripts/release-version.sh" 1.2.3-SNAPSHOT-rc1)" == "1.2.3-SNAPSHOT-rc1" ]] \
  || { echo "test-release-version: a version that only contains -SNAPSHOT was refused" >&2; exit 1; }
echo "test-release-version: ok"
