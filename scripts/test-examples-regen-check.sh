#!/usr/bin/env bash
# The report's rows and the README quote Markdown backticks literally.
# shellcheck disable=SC2016
# Fixture test for scripts/examples-regen-check.sh, with a stand-in jk whose `new` and `import`
# write fixed manifests: an example equal to its scaffold passes; one that lacks a key the
# scaffold writes fails naming it, unless its README names the key's table; a converted example is
# compared with what `jk import` writes; a workspace root is skipped. A rule pack or plugin manifest
# key that differs from the scaffold's is a finding, and every JumpKick pin in any example (a
# skipped workspace root and its members included) must name the generating jk's release.
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
    printf '[guards]\nextends = ["cc.jumpkick.guards:library:9.9.9"]\n' >"$dir/jk-guards.toml"
    mkdir -p "$dir/src/main/resources"
    printf '[plugin]\nid = "p"\nsdk = "9.9.9"\njk-compat = ">=9.9.9"\n' >"$dir/src/main/resources/jk-plugin.toml"
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
packs() { printf '[guards]\nextends = ["%s"]\n' "$2" >"$EX/$1/jk-guards.toml"; }
packs 01-start/equal cc.jumpkick.guards:library:9.9.9
packs 05-recipes/documented cc.jumpkick.guards:library:9.9.9
packs 01-start/behind cc.jumpkick.guards:library:0.50.2
scaffold 05-recipes/plug $'\n[format]\nstyle = "standard"\nenforce = true\n'
packs 05-recipes/plug cc.jumpkick.guards:library:9.9.9
mkdir -p "$EX/05-recipes/plug/src/main/resources"
printf '[plugin]\nid = "plug"\nsdk = "9.9.9"\njk-compat = ">=0.13"\n' >"$EX/05-recipes/plug/src/main/resources/jk-plugin.toml"
mkdir -p "$EX/01-start/ws/member"
printf 'name = "ws"\ngroup = "g"\nversion = "1"\n\n[workspace]\nmodules = ["member"]\n' >"$EX/01-start/ws/jk.toml"
printf 'name = "member"\n\n[dependencies]\njk-plugin-sdk = "cc.jumpkick:jk-plugin-sdk:0.50.2"\n' >"$EX/01-start/ws/member/jk.toml"
printf '[guards]\nextends = ["cc.jumpkick.guards:monorepo:0.20.0"]\n' >"$EX/01-start/ws/jk-guards.toml"
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
grep -qF '| `01-start/ws/member`' "$WORK/report" && { cat "$WORK/report" >&2; echo "a workspace member was compared on its own" >&2; exit 1; }
grep -qF -- '- **differs**: jk-guards.toml: [guards] extends cc.jumpkick.guards:library: 0.50.2, jk new writes 9.9.9' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "a rule pack behind the scaffold's was not named" >&2; exit 1; }
grep -qF -- '- **differs**: jk-plugin.toml: [plugin] jk-compat: ">=0.13", jk new writes ">=9.9.9"' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "a plugin manifest's jk-compat behind the scaffold's was not named" >&2; exit 1; }
grep -qF '## JumpKick pins behind jk 9.9.9' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "stale JumpKick pins got no section" >&2; exit 1; }
for stale in \
  '01-start/behind/jk-guards.toml: [guards] extends cc.jumpkick.guards:library:0.50.2, jk 9.9.9 writes cc.jumpkick.guards:library:9.9.9' \
  '01-start/ws/jk-guards.toml: [guards] extends cc.jumpkick.guards:monorepo:0.20.0' \
  '01-start/ws/member/jk.toml: [dependencies] jk-plugin-sdk = cc.jumpkick:jk-plugin-sdk:0.50.2, jk 9.9.9 writes cc.jumpkick:jk-plugin-sdk:9.9.9'; do
  grep -qF -- "- **stale**: $stale" "$WORK/report" \
    || { cat "$WORK/report" >&2; echo "a stale JumpKick pin was not named: $stale" >&2; exit 1; }
done
grep -qF 'equal/jk-guards.toml' "$WORK/report" && { cat "$WORK/report" >&2; echo "a pin at the release was called stale" >&2; exit 1; }

run --only equal --only documented --only migrate \
  || { cat "$WORK/report" >&2; echo "examples with only documented deviations failed" >&2; exit 1; }
grep -qF 'Every JumpKick pin names jk 9.9.9.' "$WORK/report" \
  || { cat "$WORK/report" >&2; echo "pins at the release were not reported as such" >&2; exit 1; }
if run --only ws; then cat "$WORK/report" >&2; echo "a skipped workspace's stale pins passed" >&2; exit 1; fi

echo "examples-regen-check fixtures passed"
