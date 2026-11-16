#!/usr/bin/env bash
# Fixture test for check-shelf-descriptors.sh: a shelf whose worker jars carry their own descriptor
# passes; a vendored sibling's descriptor at a jar root, or a worker missing from the shelf, fails.
# The version a jar is shelved at is the one jk names it with (a stand-in jk here): -SNAPSHOT on an
# untagged checkout, so a shelf at jk.toml's bare version is the wrong place to look.
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
tree="$(cd "$tree" && pwd -P)"

# A stand-in jk answering `inspect package-jar -O jsonl` as jk does, at the version in $WORK/built.
fake="$WORK/jk"
cat >"$fake" <<'FAKE'
#!/usr/bin/env bash
v="$(cat "$FAKE_BUILT")"
for m in alpha beta lib; do
  printf '{"schema":1,"type":"task-inspect","task":"package-jar","module":"x","moduleDir":"%s","output":"%s","onDisk":false}\n' \
    "$FAKE_TREE/plugins/$m" "$FAKE_TREE/plugins/$m/target/jk-$m-$v.jar"
done
FAKE
export FAKE_TREE="$tree" FAKE_BUILT="$WORK/built"
chmod +x "$fake"
export JK_BIN="$fake"
version="1.2.3-SNAPSHOT"
echo "$version" >"$WORK/built"

# shelve <worker> <table the jar's root descriptor names> [<version>]
shelve() {
  local v="${3:-$version}"
  local dir="$home/store/repos/jk-local/cc/jumpkick/$1/$v"
  mkdir -p "$dir" "$WORK/stage-$1"
  printf '[plugin]\nid    = "%s"\ntable = "%s"\n' "$2" "$2" >"$WORK/stage-$1/jk-plugin.toml"
  rm -f "$dir/$1-$v.jar"
  (cd "$WORK/stage-$1" && zip -q "$dir/$1-$v.jar" jk-plugin.toml)
}

# The untagged checkout's shelf: jars at jk.toml's bare version are not what this build shelved.
shelve jk-alpha alpha 1.2.3
shelve jk-beta beta 1.2.3
if "$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree" >/dev/null 2>&1; then
  echo "test-check-shelf-descriptors: a shelf at the bare version passed for a -SNAPSHOT build" >&2
  exit 1
fi

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

rm "$home/store/repos/jk-local/cc/jumpkick/jk-beta/$version/jk-beta-$version.jar"
if "$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree" >/dev/null 2>&1; then
  echo "test-check-shelf-descriptors: a worker missing from the shelf was accepted" >&2
  exit 1
fi

# A release build shelves at the bare version, and the same check reads it there.
version="1.2.3"
echo "$version" >"$WORK/built"
shelve jk-alpha alpha
shelve jk-beta beta
out="$("$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree")" \
  || { echo "test-check-shelf-descriptors: a release shelf was refused" >&2; exit 1; }
[[ "$out" == *"2 worker jars"* ]] || { echo "test-check-shelf-descriptors: counted '$out' for a release" >&2; exit 1; }

# A jk that cannot plan the tree leaves nothing to judge the shelf by.
if JK_BIN=false "$ROOT/scripts/check-shelf-descriptors.sh" "$home" "$tree" >/dev/null 2>&1; then
  echo "test-check-shelf-descriptors: a jk that could not plan the tree was accepted" >&2
  exit 1
fi
echo "test-check-shelf-descriptors: ok"
