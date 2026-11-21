#!/usr/bin/env bash
# Fixtures for the gate under scripts/gate/. The flaky record reads the classes a run passed on
# re-run from its transcript; past the threshold a class is quarantined with a ticket opened for it,
# leaves quarantine when that ticket closes, and a run red only in quarantined classes is excused.
# Then the gate runs in a scratch repository against a stand-in `jk`: green on a clean tip, one
# install that runs tests (the fast tier once, under the engine the reinstall before it installed),
# a red push gate run once more to record its flaky classes and still red, red at the reinstall
# when it fails, and refused on a dirty checkout.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-gate.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FLAKY="$ROOT/scripts/gate/flaky.sh"

fail() {
  echo "test-gate: $*" >&2
  exit 1
}

# --- the flaky record and quarantine ---
# A transcript as jk writes it: classes passed on re-run (a nested class of one among them), and a
# line that is not JSON.
cat >"$WORK/rerun.jsonl" <<'EOF'
{"schema":1,"type":"session-start","command":"install"}
not json
{"schema":1,"type":"warn","task":"run-tests","code":"tests-not-rerun","message":"9 test classes passed earlier","module":"cc.jumpkick:jk-engine"}
{"schema":1,"type":"warn","task":"run-tests","code":"test-passed-on-rerun","message":"passed on a re-run with no input changed, so flaky or dependent on what ran before: a.FlakyTest, a.FlakyTest$Inner, a.OtherTest","module":"cc.jumpkick:jk-engine"}
EOF
cat >"$WORK/red.jsonl" <<'EOF'
{"schema":1,"type":"error","task":"run-tests","code":"test-failure","module":"cc.jumpkick:jk-engine","testClass":"a.FlakyTest$Inner"}
{"schema":1,"type":"workspace-finish","success":false}
EOF
cat >"$WORK/red-other.jsonl" <<'EOF'
{"schema":1,"type":"error","task":"run-tests","code":"test-failure","module":"cc.jumpkick:jk-engine","testClass":"a.FlakyTest"}
{"schema":1,"type":"error","task":"compile-java","code":"compile","module":"cc.jumpkick:jk-engine","message":"cannot find symbol"}
EOF
# A stand-in ka: `new` opens fake-<n> and logs its arguments, `show` answers the status written
# in $KA_STATE/<id>. Each reads its stdin to the end, as a ka that prompts would.
mkdir -p "$WORK/bin" "$WORK/ka"
cat >"$WORK/bin/ka" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  new)
    cat >/dev/null
    n=$(($(find "$KA_STATE" -type f | wc -l) + 1))
    echo ready >"$KA_STATE/fake-$n"
    printf '%s\n' "$@" >>"$KA_STATE.new.log"
    echo "{\"id\":\"fake-$n\",\"status\":\"ready\"}"
    ;;
  show)
    cat >/dev/null
    echo "{\"id\":\"$2\",\"status\":\"$(cat "$KA_STATE/$2")\"}"
    ;;
esac
EOF
chmod +x "$WORK/bin/ka"
# flaky <unix time> <args...>: flaky.sh against this fixture's history and stand-in ka.
flaky() {
  local now="$1"
  shift
  FLAKY_HISTORY="$WORK/history.tsv" FLAKY_KA="$WORK/bin/ka" KA_STATE="$WORK/ka" FLAKY_NOW="$now" \
    bash "$FLAKY" "$@"
}
tickets() { find "$WORK/ka" -type f | wc -l | tr -d ' '; }
day=86400
t0=1800000000

flaky $t0 record "$WORK/rerun.jsonl" tip1 >"$WORK/out" || fail "record failed: $(cat "$WORK/out")"
got="$(cut -f2- "$WORK/history.tsv")"
want="$(printf 'rerun-pass\tcc.jumpkick:jk-engine\t%s\ttip1\n' a.FlakyTest a.OtherTest)"
[[ "$got" == "$want" ]] || fail "the history was:
$got
want:
$want"

# One pass each is under the threshold of two: nothing is quarantined and no ticket opens.
flaky $t0 review >"$WORK/out"
[[ "$(tickets)" -eq 0 ]] || fail "a ticket opened under the threshold: $(cat "$WORK/out")"
if flaky $t0 excused "$WORK/red.jsonl" >/dev/null; then fail "a failure in a class not quarantined was excused"; fi

# A second pass 40 days later is outside the 30-day window: still one in it.
flaky $((t0 + 40 * day)) record "$WORK/rerun.jsonl" tip2 >/dev/null
flaky $((t0 + 40 * day)) review >/dev/null
[[ "$(tickets)" -eq 0 ]] || fail "a pass outside the window counted"

# A third within the window quarantines both classes, each with a ticket that carries its history.
flaky $((t0 + 41 * day)) record "$WORK/rerun.jsonl" tip3 >/dev/null
flaky $((t0 + 41 * day)) review >"$WORK/out"
got="$(flaky $t0 list)"
want="$(printf '%s\n' 'cc.jumpkick:jk-engine a.FlakyTest fake-1' 'cc.jumpkick:jk-engine a.OtherTest fake-2')"
[[ "$got" == "$want" ]] || fail "the quarantine was:
$got
want:
$want
review said: $(cat "$WORK/out")"
grep -q 'Flaky test FlakyTest' "$WORK/ka.new.log" || fail "the ticket does not name the class: $(cat "$WORK/ka.new.log")"
grep -q 'rerun-pass.*a.FlakyTest.*tip3' "$WORK/ka.new.log" || fail "the ticket does not carry the history"
flaky $((t0 + 41 * day)) review >/dev/null
[[ "$(tickets)" -eq 2 ]] || fail "a second review opened another ticket for a quarantined class"

# A run red only in a quarantined class (a nested class of it) is excused; red elsewhere too is not.
flaky $t0 excused "$WORK/red.jsonl" >"$WORK/out" || fail "a failure only in quarantine was not excused: $(cat "$WORK/out")"
if flaky $t0 excused "$WORK/red-other.jsonl" >/dev/null; then fail "a compile error was excused"; fi
if flaky $t0 excused "$WORK/rerun.jsonl" >/dev/null; then fail "a run with no failure was excused"; fi

# Closing a ticket releases its class, and only the passes after that count again.
echo "done" >"$WORK/ka/fake-1"
flaky $((t0 + 42 * day)) review >/dev/null
got="$(flaky $t0 list)"
[[ "$got" == 'cc.jumpkick:jk-engine a.OtherTest fake-2' ]] || fail "a closed ticket did not release its class: $got"
flaky $((t0 + 43 * day)) record "$WORK/rerun.jsonl" tip4 >/dev/null
flaky $((t0 + 43 * day)) review >/dev/null
[[ "$(tickets)" -eq 2 ]] || fail "passes before the release counted toward a new quarantine"

# --- the gate, against a stand-in jk ---
# The stand-in logs each call, fails the push gate's first run when FAIL_PUSHGATE is set and the
# reinstall without tests when FAIL_REINSTALL is set, prints the rerun transcript above as the
# latest run's, and reports the checkout's HEAD as the engine's commit.
export HOME="$WORK/home"
mkdir -p "$HOME/.jk/bin"
cat >"$HOME/.jk/bin/jk" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$JK_CALLS"
case "$1" in
  --version) echo "jk 0.0.0" ;;
  engine) [[ "${2:-}" == status ]] && echo "Version  0.0.0 (commit $(git rev-parse --short=12 HEAD))" ;;
  results) cat "$RERUN_JSONL" ;;
  install)
    [[ -n "${FAIL_REINSTALL:-}" && " $* " == *" -S "* ]] && { echo "error: compile failed"; exit 1; }
    if [[ -n "${FAIL_PUSHGATE:-}" && " $* " == *" -g "* && "$(grep -c '^install -g' "$JK_CALLS")" -eq 1 ]]; then
      echo "FAIL a.FlakyTest"
      exit 4
    fi
    ;;
esac
exit 0
EOF
chmod +x "$HOME/.jk/bin/jk"

repo="$WORK/repo"
mkdir -p "$repo/scripts/gate"
cp "$ROOT"/scripts/gate/*.sh "$repo/scripts/gate/"
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
    GATE_LOCK="$WORK/gate.lock" RERUN_JSONL="$WORK/rerun.jsonl" FLAKY_KA="$WORK/bin/ka" \
    KA_STATE="$WORK/ka" bash "$launcher" "$n" "$tip" >/dev/null 2>&1
  tail -1 "$WORK/logs/integ-gate-$n.log" | grep -qx "GATE$n DONE" || fail "gate $n did not finish: $(cat "$WORK/logs/integ-gate-$n.log")"
}
verdict() { grep -E '^(GREEN |RED|CHECKOUT |NO CHECKOUT)' "$WORK/logs/integ-gate-$1.log" | tail -1; }

gate 1
[[ "$(verdict 1)" == "GREEN $tip" ]] || fail "a clean tip was not green: $(cat "$WORK/logs/integ-gate-1.log")"
if grep -q '^test -m' "$WORK/calls-1"; then fail "the gate ran a pre-run: $(cat "$WORK/calls-1")"; fi
# The reinstall runs no tests, the engine it ran on stops, and the push gate is the one install that
# runs the fast tier.
got="$(sed -n '/^install /,$p' "$WORK/calls-1" | grep -E '^(install |engine stop)')"
want="$(printf '%s\n' 'install -S' 'engine stop' 'install -g -c')"
[[ "$got" == "$want" ]] || fail "the installs were:
$got
want:
$want"
[[ ! -f "$WORK/logs/flaky-history.tsv" ]] || fail "a green gate recorded flaky classes"

gate 2 FAIL_PUSHGATE=1
[[ "$(verdict 2)" == "RED" ]] || fail "a push gate green only on its re-run was not red: $(cat "$WORK/logs/integ-gate-2.log")"
[[ "$(grep -c '^install -g -c$' "$WORK/calls-2")" -eq 2 ]] || fail "a red push gate did not run once more: $(cat "$WORK/calls-2")"
grep -q "rerun-pass.*a.FlakyTest.*$tip" "$WORK/logs/flaky-history.tsv" || fail "the gate did not record the class that passed on its re-run: $(cat "$WORK/logs/integ-gate-2.log")"
grep -q '^FLAKY' "$WORK/logs/integ-gate-2.log" || fail "the gate log does not say what it recorded"

gate 3 FAIL_REINSTALL=1
[[ "$(verdict 3)" == "RED (reinstall)" ]] || fail "a failed reinstall did not stop the gate: $(cat "$WORK/logs/integ-gate-3.log")"
grep -q 'compile failed' "$WORK/logs/integ-gate-3.log" || fail "the gate log does not say why the reinstall failed"
if grep -q '^install -g' "$WORK/calls-3"; then fail "the push gate ran after a failed reinstall"; fi

touch "$repo/stray"
gate 4
[[ "$(verdict 4)" == "CHECKOUT DIRTY" ]] || fail "a dirty checkout was gated: $(cat "$WORK/logs/integ-gate-4.log")"

echo "test-gate: ok (flaky record and quarantine; 4 gate runs)"
