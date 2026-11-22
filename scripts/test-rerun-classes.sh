#!/usr/bin/env bash
# Fixtures for scripts/gate/rerun-classes.sh in a scratch repository with a stand-in `jk`: it runs
# each module's classes with one `jk test`, finds a module by its directory or its jk.toml name,
# skips a class the commit does not have, and tells a failing class (exit 1) from a run that could
# not say (exit 2).
set -euo pipefail

unset "${!GATE_@}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-rerun-classes.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
RERUN="$ROOT/scripts/gate/rerun-classes.sh"

fail() {
  echo "test-rerun-classes: $*" >&2
  exit 1
}

# The stand-in jk logs each `jk test`, fails it when it names a class in FAIL_CLASS, and reports
# that class's failure, or a compile error when BUILD_RED is set; INSTALL_RED fails the reinstall.
mkdir -p "$WORK/home/.jk/bin"
cat >"$WORK/home/.jk/bin/jk" <<'JK'
#!/usr/bin/env bash
case "$1" in
  install) [ -z "${INSTALL_RED:-}" ] ;;
  test)
    echo "${*:2}" >>"$JK_LOG"
    for a in "$@"; do [ "$a" = "${FAIL_CLASS:-}" ] && exit 1; done
    exit 0
    ;;
  results)
    if [ -n "${BUILD_RED:-}" ]; then
      echo '{"type":"error","code":"compile-error","module":"jk-core"}'
    else
      echo "{\"type\":\"error\",\"code\":\"test-failure\",\"module\":\"jk-core\",\"testClass\":\"$FAIL_CLASS\"}"
    fi
    ;;
esac
JK
chmod +x "$WORK/home/.jk/bin/jk"
export HOME="$WORK/home" JK_LOG="$WORK/jk.log" GATE_CHECKOUT="$WORK/co"

export GIT_AUTHOR_NAME=dev GIT_AUTHOR_EMAIL=dev@example.invalid
export GIT_COMMITTER_NAME=dev GIT_COMMITTER_EMAIL=dev@example.invalid
co="$WORK/co"
git init -q "$co"
mkdir -p "$co/core/src/test/java/cc/example" "$co/app/src/test/kotlin/cc/example"
echo 'name = "root"' >"$co/jk.toml"
echo 'name = "jk-core"' >"$co/core/jk.toml"
echo 'name = "app"' >"$co/app/jk.toml"
echo 'class RedTest {}' >"$co/core/src/test/java/cc/example/RedTest.java"
echo 'class AppTest' >"$co/app/src/test/kotlin/cc/example/AppTest.kt"
git -C "$co" add -A && git -C "$co" commit -qm base
sha="$(git -C "$co" rev-parse HEAD)"

classes="$WORK/classes"
printf '%s\n' 'jk-core cc.example.RedTest' 'jk-core cc.example.GoneTest' 'app cc.example.AppTest' >"$classes"
run() { bash "$RERUN" "$sha" "$classes" >"$WORK/out" 2>&1; }

# --- every class passes: one run per module, by directory, without the class the commit lacks ---
: >"$JK_LOG"
run || fail "a passing re-run exited $?: $(cat "$WORK/out")"
grep -qx -- '-A -c --no-ansi -m core --class cc.example.RedTest' "$JK_LOG" || fail "core's run was not one --class run: $(cat "$JK_LOG")"
grep -qx -- '-A -c --no-ansi -m app --class cc.example.AppTest' "$JK_LOG" || fail "app's run was not one --class run: $(cat "$JK_LOG")"
[[ "$(wc -l <"$JK_LOG" | tr -d ' ')" -eq 2 ]] || fail "want two runs: $(cat "$JK_LOG")"
grep -q '^SKIP cc.example.GoneTest' "$WORK/out" || fail "the missing class was not skipped: $(cat "$WORK/out")"

# --- a class that fails is a 1; a run red outside a test, an unknown module, a red reinstall or a
# dirty checkout is a 2 ---
rc=0 && FAIL_CLASS=cc.example.RedTest run || rc=$?
[[ "$rc" -eq 1 ]] || fail "a failing class exited $rc: $(cat "$WORK/out")"
rc=0 && FAIL_CLASS=cc.example.RedTest BUILD_RED=1 run || rc=$?
[[ "$rc" -eq 2 ]] || fail "a run red outside a test exited $rc: $(cat "$WORK/out")"
rc=0 && INSTALL_RED=1 run || rc=$?
[[ "$rc" -eq 2 ]] || fail "a red reinstall exited $rc: $(cat "$WORK/out")"
echo 'nowhere cc.example.X' >>"$classes"
rc=0 && run || rc=$?
[[ "$rc" -eq 2 ]] || fail "an unknown module exited $rc: $(cat "$WORK/out")"
sed -i.bak '$d' "$classes" && rm -f "$classes.bak"
echo dirt >"$co/dirt.txt"
rc=0 && run || rc=$?
[[ "$rc" -eq 2 ]] || fail "a dirty checkout exited $rc: $(cat "$WORK/out")"

echo "test-rerun-classes: ok"
