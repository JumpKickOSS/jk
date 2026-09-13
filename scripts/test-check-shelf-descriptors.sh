#!/usr/bin/env bash
# Fixture test for check-shelf-descriptors.sh: a shelf whose worker jars carry their own descriptor
# passes; a vendored sibling's descriptor at a jar root, or a worker missing from the shelf, fails.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-shelf-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
tree="$WORK/tree"
home="$WORK/home"
mkdir -p "$tree/plugins/alpha" "$tree/plugins/beta" "$tree/plugins/lib"
printf 'name    = "demo"\nversion = "1.2.3"\n' >"$tree/jk.toml"
printf 'name    = "jk-alpha"\n' >"$tree/plugins/alpha/jk.toml"
printf '[plugin]\nid    = "alpha"\ntable = "alpha"\n' >"$tree/plugins/alpha/jk-plugin.toml"
printf 'name    = "jk-beta"\n' >"$tree/plugins/beta/jk.toml"
printf '[plugin]\nid    = "beta"\ntable = "beta"\n' >"$tree/plugins/beta/jk-plugin.toml"
# A library under plugins/ carries no descriptor and is not a worker: never looked for.
printf 'name    = "jk-lib"\n' >"$tree/plugins/lib/jk.toml"

# shelve <worker> <table the jar's root descriptor names>
shelve() {
  local dir="$home/store/repos/jk-local/cc/jumpkick/$1/1.2.3"
  mkdir -p "$dir" "$WORK/stage-$1"
  printf '[plugin]\nid    = "%s"\ntable = "%s"\n' "$2" "$2" >"$WORK/stage-$1/jk-plugin.toml"
  rm -f "$dir/$1-1.2.3.jar"
  (cd "$WORK/stage-$1" && zip -q "$dir/$1-1.2.3.jar" jk-plugin.toml)
}

shelve jk-alpha alpha
shelve jk-beta beta
out="$("$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree")" \
  || { echo "test-check-shelf-descriptors: a self-describing shelf was refused" >&2; exit 1; }
[[ "$out" == *"2 worker jars"* ]] || { echo "test-check-shelf-descriptors: counted '$out'" >&2; exit 1; }

shelve jk-beta alpha
if "$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree" >/dev/null 2>&1; then
  echo "test-check-shelf-descriptors: a vendored sibling's descriptor at the jar root was accepted" >&2
  exit 1
fi

rm "$home/store/repos/jk-local/cc/jumpkick/jk-beta/1.2.3/jk-beta-1.2.3.jar"
if "$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree" >/dev/null 2>&1; then
  echo "test-check-shelf-descriptors: a worker missing from the shelf was accepted" >&2
  exit 1
fi

printf 'name = "demo"\n' >"$tree/jk.toml"
if "$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree" >/dev/null 2>&1; then
  echo "test-check-shelf-descriptors: a tree without a version was accepted" >&2
  exit 1
fi
echo "test-check-shelf-descriptors: ok"
