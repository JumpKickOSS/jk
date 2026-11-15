#!/usr/bin/env bash
# The report's rows and the README quote Markdown backticks literally.
# shellcheck disable=SC2016
# Fixture test for scripts/examples-regen-check.sh, with a stand-in jk whose `new` and `import`
# write fixed manifests: an example equal to its scaffold passes; one that lacks a key the
# scaffold writes fails naming it, unless its README names the key's table; a converted example is
# compared with what `jk import` writes; a workspace root is skipped.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-regen-check-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"
cat >"$WORK/bin/jk" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "--version" ]] && { echo "jk 9.9.9-fixture"; exit 0; }
[[ "${1:-}" == "-q" ]] && shift
case "$1" in
  new)
    dir="$2"; name=""; shift 2
    while [[ $# -gt 0 ]]; do case "$1" in -W) name="$2"; shift 2 ;; *) shift ;; esac; done
    mkdir -p "$dir"
    printf 'name = "%s"\ngroup = "g"\nversion = "0.1.0"\njava = 25\n\n[application]\nmain = "g.Main"\n\n[format]\nstyle = "standard"\nenforce = true\n' "$name" >"$dir/jk.toml"
    ;;
  import)
    printf 'name = "imported"\ngroup = "g"\nversion = "1.0"\njava = 21\n' >jk.toml
    ;;
  *) echo "fixture jk: unexpected $*" >&2; exit 2 ;;
esac
SH
chmod +x "$WORK/bin/jk"

EX="$WORK/examples"
scaffold() {
  mkdir -p "$EX/$1/src/main/java"
  printf 'class Main {}\n' >"$EX/$1/src/main/java/Main.java"
  printf 'name = "%s"\ngroup = "g"\nversion = "1.2.3"\njava = 25\n\n[application]\nmain = "other.Main"\n%s' \
    "$(basename "$1")" "$2" >"$EX/$1/jk.toml"
}
scaffold 01-start/equal $'\n[format]\nstyle = "standard"\nenforce = true\n\n[dependencies]\n"x:y" = "1"\n'
scaffold 01-start/behind ''
scaffold 05-recipes/documented ''
printf '# documented\n\nNo `[format]` here: the recipe is about something else.\n' >"$EX/05-recipes/documented/README.md"
mkdir -p "$EX/01-start/ws/member"
printf 'name = "ws"\ngroup = "g"\nversion = "1"\n\n[workspace]\nmodules = ["member"]\n' >"$EX/01-start/ws/jk.toml"
printf 'name = "member"\n' >"$EX/01-start/ws/member/jk.toml"
mkdir -p "$EX/05-recipes/migrate/from-maven"
printf '<project/>\n' >"$EX/05-recipes/migrate/from-maven/pom.xml"
printf 'name = "imported"\ngroup = "g"\nversion = "1.0"\njava = 21\n' >"$EX/05-recipes/migrate/from-maven/jk.toml"

run() { env JK="$WORK/bin/jk" bash "$ROOT/scripts/examples-regen-check.sh" "$EX" --out "$WORK/out" "$@" >"$WORK/report" 2>&1; }

if run; then cat "$WORK/report" >&2; echo "an example behind its scaffold passed" >&2; exit 1; fi
grep -qF '| `01-start/equal` | jk new -t app -l java | equal |' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "identity, dependencies and the main class were compared" >&2; exit 1; }
grep -qF -- '- **missing**: [format] enforce = true (jk new writes it)' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "the missing [format] was not named" >&2; exit 1; }
grep -qF '| `05-recipes/documented` | jk new -t app -l java | 0 undocumented, 2 documented |' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "a deviation the README names was not taken as documented" >&2; exit 1; }
grep -qF '| `01-start/ws` | — | skipped: a workspace root |' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "the workspace root was not skipped" >&2; exit 1; }
grep -qF '| `05-recipes/migrate/from-maven` | jk import pom.xml | equal |' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "the converted example was not compared with jk import" >&2; exit 1; }
grep -qF '01-start/ws/member' "$WORK/report" && { cat "$WORK/report" >&2; echo "a workspace member was compared on its own" >&2; exit 1; }

run --only equal --only documented --only migrate \
  || { cat "$WORK/report" >&2; echo "examples with only documented deviations failed" >&2; exit 1; }

echo "examples-regen-check fixtures passed"
