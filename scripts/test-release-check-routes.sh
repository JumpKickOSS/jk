#!/usr/bin/env bash
# Fixture test for scripts/release-check-routes.sh --examples, with a stand-in jk whose second build
# reports nothing to build: every project of a jk-examples checkout is a route of its own, a
# workspace's member is built with its root and not alone, and a group root (a jk.toml with
# [workspaces] members, the checkout's own root here) is no route, so its members stay routes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-routes-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"
cat >"$WORK/bin/jk" <<'SH'
#!/usr/bin/env bash
case "${1:-}" in
  --version) echo "jk 9.9.9-fixture" ;;
  build)
    echo "built $PWD"
    if [[ " $* " == *" -O jsonl "* ]]; then echo '{"type":"workspace-start","modules":0}'; fi
    ;;
  explain) echo "nothing to run" ;;
  *) echo "fixture jk: unexpected $*" >&2; exit 2 ;;
esac
SH
chmod +x "$WORK/bin/jk"

EX="$WORK/examples"
project() { mkdir -p "$EX/$1"; printf '%s' "$2" >"$EX/$1/jk.toml"; }
project . $'[workspaces]\nmembers = ["01-start/*", "02-services/*"]\n'
project 01-start/one $'name = "one"\n'
project 01-start/two $'name = "two"\n'
project 02-services/ws $'name = "ws"\n\n[workspace]\nmodules = ["member"]\n'
project 02-services/ws/member $'name = "member"\n'
project bench/heavy $'name = "heavy"\n'
(cd "$EX" && git init -q && git add -A)

if ! env JK="$WORK/bin/jk" bash "$ROOT/scripts/release-check-routes.sh" --no-templates --examples "$EX" \
  --out "$WORK/out" >"$WORK/report" 2>&1; then
  cat "$WORK/report" >&2; echo "the routes failed" >&2; exit 1
fi
for route in 01-start/one 01-start/two 02-services/ws; do
  grep -qF "| example $route | copied | ok " "$WORK/report" \
    || { cat "$WORK/report" >&2; echo "$route was not built as a route" >&2; exit 1; }
done
[[ "$(grep -c '^| example ' "$WORK/report")" == 3 ]] \
  || { cat "$WORK/report" >&2; echo "the group root, a member or a benchmark was a route" >&2; exit 1; }

echo "release-check-routes fixtures passed"
