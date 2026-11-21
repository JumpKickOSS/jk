#!/usr/bin/env bash
# Fixtures for the gate under scripts/gate/. The class-list parser turns a list with comments and
# interleaved modules into one line per module in first-seen order and refuses a malformed line by
# its number; every line of the gate's own list names a test class that exists once in its module,
# so a renamed class fails here instead of turning a gate's pre-run red. Then the gate runs in a
# scratch repository against a stand-in `jk`: green on a clean tip, one `jk test` per module of the
# list, red at the pre-run when a class fails, and refused on a dirty checkout.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-gate.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
PLAN="$ROOT/scripts/gate/pre-classes.sh"

fail() {
  echo "test-gate: $*" >&2
  exit 1
}

# --- the class-list parser ---
cat >"$WORK/list.txt" <<'EOF'
# a comment
server/engine AlphaTest
shared/core BetaTest   # why it is here

server/engine GammaTest
EOF
got="$("$PLAN" "$WORK/list.txt")"
want="$(printf '%s\n' 'server/engine AlphaTest,GammaTest' 'shared/core BetaTest')"
[[ "$got" == "$want" ]] || fail "plan was:
$got
want:
$want"

printf '%s\n' 'server/engine AlphaTest' 'server/engine' >"$WORK/bad.txt"
if "$PLAN" "$WORK/bad.txt" >/dev/null 2>"$WORK/bad.log"; then
  fail "a line without a class was accepted"
fi
grep -q 'bad.txt:2:' "$WORK/bad.log" || fail "the refusal does not name line 2: $(cat "$WORK/bad.log")"

# --- the gate's own list ---
checked=0
while read -r module classes; do
  [[ -f "$ROOT/$module/jk.toml" ]] || fail "$module is not a module of this tree"
  for class in ${classes//,/ }; do
    found="$(find "$ROOT/$module/src" -path '*/src/main' -prune -o \( -name "$class.java" -o -name "$class.kt" \) -print | wc -l)"
    [[ "$found" -eq 1 ]] || fail "$module $class: $found test sources by that name, want 1"
    checked=$((checked + 1))
  done
done < <("$PLAN")
[[ "$checked" -gt 0 ]] || fail "the gate's class list names no class"

# --- the gate, against a stand-in jk ---
# The stand-in logs each call, fails `jk test` for a class named in FAIL_CLASS, and reports the
# checkout's HEAD as the engine's commit.
export HOME="$WORK/home"
mkdir -p "$HOME/.jk/bin"
cat >"$HOME/.jk/bin/jk" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$JK_CALLS"
case "$1" in
  --version) echo "jk 0.0.0" ;;
  engine) [[ "${2:-}" == status ]] && echo "Version  0.0.0 (commit $(git rev-parse --short=12 HEAD))" ;;
  test) [[ -n "${FAIL_CLASS:-}" && " $* " == *" $FAIL_CLASS "* ]] && { echo "FAIL $FAIL_CLASS"; exit 1; } ;;
esac
exit 0
EOF
chmod +x "$HOME/.jk/bin/jk"

repo="$WORK/repo"
mkdir -p "$repo/scripts/gate"
cp "$ROOT"/scripts/gate/*.sh "$repo/scripts/gate/"
printf '%s\n' 'mod/a ATest' 'mod/b BTest' 'mod/a CTest' >"$repo/scripts/gate/pre-classes.txt"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$repo/scripts/release-notes.sh"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$repo/scripts/test-ok.sh"
git -C "$repo" init -q
git -C "$repo" add -A
git -C "$repo" -c user.name=gate -c user.email=gate@example.invalid commit -qm tip
tip="$(git -C "$repo" rev-parse HEAD)"

# gate <n> [env...]: runs the gate in the scratch repo and holds it to finishing its log. Through
# run.sh and its lock where flock exists (Linux); macOS has no flock, so there gate.sh alone.
launcher="$repo/scripts/gate/run.sh"
command -v flock >/dev/null 2>&1 || launcher="$repo/scripts/gate/gate.sh"
gate() {
  local n="$1"
  shift
  : >"$WORK/calls-$n"
  env "$@" JK_CALLS="$WORK/calls-$n" GATE_CHECKOUT="$repo" GATE_LOG_DIR="$WORK/logs" \
    GATE_LOCK="$WORK/gate.lock" bash "$launcher" "$n" "$tip" >/dev/null 2>&1
  tail -1 "$WORK/logs/integ-gate-$n.log" | grep -qx "GATE$n DONE" || fail "gate $n did not finish: $(cat "$WORK/logs/integ-gate-$n.log")"
}
verdict() { grep -E '^(GREEN |RED|CHECKOUT |NO CHECKOUT)' "$WORK/logs/integ-gate-$1.log" | tail -1; }

gate 1
[[ "$(verdict 1)" == "GREEN $tip" ]] || fail "a clean tip was not green: $(cat "$WORK/logs/integ-gate-1.log")"
grep -qx 'test -m mod/a -S ATest -S CTest -s test -s integration' "$WORK/calls-1" || fail "mod/a was not run once with both classes: $(cat "$WORK/calls-1")"
grep -qx 'test -m mod/b -S BTest -s test -s integration' "$WORK/calls-1" || fail "mod/b was not run: $(cat "$WORK/calls-1")"
grep -qx 'install -g -c' "$WORK/calls-1" || fail "the push gate did not run: $(cat "$WORK/calls-1")"

gate 2 FAIL_CLASS=BTest
[[ "$(verdict 2)" == "RED (pre)" ]] || fail "a failing pre-run class did not stop the gate: $(cat "$WORK/logs/integ-gate-2.log")"
if grep -q '^guard' "$WORK/calls-2"; then fail "the gate went on past a red pre-run"; fi

touch "$repo/stray"
gate 3
[[ "$(verdict 3)" == "CHECKOUT DIRTY" ]] || fail "a dirty checkout was gated: $(cat "$WORK/logs/integ-gate-3.log")"

echo "test-gate: ok ($checked listed classes; 3 gate runs)"
