#!/usr/bin/env bash
# Fixtures for scripts/sign-windows-ref.sh in a scratch repository: the release tag of the declared
# version builds the release; a v0.0.0-signtest.<n> tag on an untagged commit builds <version>-SNAPSHOT;
# a branch, a tag of another version, a tag on another commit and a dry-run tag on the release
# commit are refused.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/var/tmp}/jk-sign-windows-ref.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

repo() { # dir version
  mkdir -p "$1/scripts"
  cp "$ROOT/scripts/sign-windows-ref.sh" "$ROOT/scripts/release-version.sh" "$1/scripts/"
  printf '[project]\nversion = "%s"\n' "$2" >"$1/jk.toml"
  git -C "$1" init -q
  git -C "$1" add jk.toml scripts
  git -C "$1" -c user.name=t -c user.email=t@example.invalid commit -q -m one
}
tag() { git -C "$1" tag "$2" "${3:-HEAD}"; }

expect() { # dir type name want-release want-version
  local out
  out="$("$1/scripts/sign-windows-ref.sh" "$2" "$3")" || { echo "test-sign-windows-ref: $2 $3 was refused" >&2; exit 1; }
  [[ "$out" == "release=$4"$'\n'"version=$5" ]] || { echo "test-sign-windows-ref: $2 $3 printed '$out'" >&2; exit 1; }
}
refuses() { # dir type name what
  if "$1/scripts/sign-windows-ref.sh" "$2" "$3" >/dev/null 2>"$WORK/err"; then
    echo "test-sign-windows-ref: $2 $3 was accepted ($4)" >&2
    exit 1
  fi
  grep -q 'sign-windows-ref: ' "$WORK/err" || { cat "$WORK/err" >&2; echo "test-sign-windows-ref: $2 $3 refused without saying why" >&2; exit 1; }
}

rel="$WORK/release"
repo "$rel" 1.2.3
tag "$rel" v1.2.3
tag "$rel" v1.2.4
tag "$rel" v0.0.0-signtest.1
expect "$rel" tag v1.2.3 true 1.2.3
refuses "$rel" tag v1.2.4 "a tag of another version"
refuses "$rel" tag v0.0.0-signtest.1 "a dry-run tag where jk.toml declares a release"
refuses "$rel" branch main "a branch"
refuses "$rel" tag release-1.2.3 "not a v* tag"
refuses "$rel" tag v9.9.9 "a tag that does not exist"

snap="$WORK/snapshot"
repo "$snap" 1.2.4
tag "$snap" v0.0.0-signtest.2
tag "$snap" v1.2.4-SNAPSHOT
expect "$snap" tag v0.0.0-signtest.2 false 1.2.4-SNAPSHOT
refuses "$snap" tag v1.2.4-SNAPSHOT "a tag of a version jk.toml does not declare"
refuses "$snap" tag v0.0.0-signtest "a dry-run tag without its number"
git -C "$snap" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m two
refuses "$snap" tag v0.0.0-signtest.2 "the tag names another commit than the checkout"

echo "test-sign-windows-ref: ok"
