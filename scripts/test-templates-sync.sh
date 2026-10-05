#!/usr/bin/env bash
# Fixture test for scripts/templates-sync.sh, on a throwaway templates/ tree and catalog: a matching
# catalog passes --check; a changed, missing or extra file and a missing or orphaned README row fail it
# with the path named; a sync makes it match, leaves README.md and LICENSE alone, and a second sync
# changes nothing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-templates-sync-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# The script finds templates/ beside itself, so the fixture is a tree with a copy of it.
TREE="$WORK/jk"
CAT="$WORK/catalog"
mkdir -p "$TREE/scripts" "$TREE/templates/java/none/cli.g8/src/main/g8" "$CAT"
cp "$ROOT/scripts/templates-sync.sh" "$TREE/scripts/"
printf 'language = "java"\nframework = "none"\nname = "cli"\n' >"$TREE/templates/java/none/cli.g8/.jk-template.toml"
printf 'name=demo\n' >"$TREE/templates/java/none/cli.g8/default.properties"
printf 'class Main {}\n' >"$TREE/templates/java/none/cli.g8/src/main/g8/Main.java"
cp -R "$TREE/templates/." "$CAT/"
printf '# catalog\n\n| Name |\n|------|\n| \140cli\140 | none |\n' >"$CAT/README.md"
printf 'license\n' >"$CAT/LICENSE"
mkdir -p "$CAT/.git" && printf 'ref\n' >"$CAT/.git/HEAD"

sync() { "$TREE/scripts/templates-sync.sh" "$@" >"$WORK/last.log" 2>&1; }

passes_check() {
  sync --check "$CAT" || { echo "test-templates-sync: '$1' was refused:" >&2; cat "$WORK/last.log" >&2; exit 1; }
}
refuses_naming() {
  local what="$1" needle="$2"
  if sync --check "$CAT"; then
    echo "test-templates-sync: '$what' was accepted" >&2; cat "$WORK/last.log" >&2; exit 1
  fi
  grep -qF "$needle" "$WORK/last.log" \
    || { echo "test-templates-sync: '$what' failed without naming '$needle':" >&2; cat "$WORK/last.log" >&2; exit 1; }
}
resync() { sync "$CAT" || { echo "test-templates-sync: sync failed:" >&2; cat "$WORK/last.log" >&2; exit 1; }; }

passes_check "a matching catalog"

printf 'class Main { int x; }\n' >"$TREE/templates/java/none/cli.g8/src/main/g8/Main.java"
refuses_naming "a changed file" "changed  java/none/cli.g8/src/main/g8/Main.java"
resync
passes_check "a synced change"

printf 'more\n' >"$TREE/templates/java/none/cli.g8/src/main/g8/Extra.java"
refuses_naming "a missing file" "missing  java/none/cli.g8/src/main/g8/Extra.java"
resync

mkdir -p "$CAT/optimize/train" && printf 'x\n' >"$CAT/optimize/train/T.java"
refuses_naming "an extra file" "removed  optimize/train/T.java"
resync
[ ! -e "$CAT/optimize" ] || { echo "test-templates-sync: a removed template's directory was left behind" >&2; exit 1; }
passes_check "a synced removal"

grep -qx 'license' "$CAT/LICENSE" || { echo "test-templates-sync: LICENSE was touched" >&2; exit 1; }
[ -f "$CAT/.git/HEAD" ] || { echo "test-templates-sync: .git was touched" >&2; exit 1; }

before="$(cd "$CAT" && find . -type f -exec cksum {} + | LC_ALL=C sort)"
resync
after="$(cd "$CAT" && find . -type f -exec cksum {} + | LC_ALL=C sort)"
[ "$before" = "$after" ] || { echo "test-templates-sync: a second sync changed the catalog" >&2; exit 1; }

mkdir -p "$TREE/templates/java/none/lib.g8"
printf 'language = "java"\nframework = "none"\nname = "lib"\n' >"$TREE/templates/java/none/lib.g8/.jk-template.toml"
resync
refuses_naming "a template with no README row" "no README row for template \`lib\`"
printf '| \140lib\140 | none |\n| \140gone\140 | none |\n' >>"$CAT/README.md"
refuses_naming "a README row for no template" "README row for \`gone\`"

echo "test-templates-sync: ok"
