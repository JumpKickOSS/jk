#!/usr/bin/env bash
# Fixtures for the gate under scripts/gate/. The flaky record reads the classes a run passed on
# re-run from its transcript; past the threshold a class is quarantined with a ticket opened for it,
# leaves quarantine when that ticket closes, and a run red only in quarantined classes is excused.
# Then the gate runs in a scratch repository against a stand-in `jk`: green on a clean tip, one
# install that runs tests (the fast tier once, under the engine the reinstall before it installed),
# a red push gate run once more to record its flaky classes and still red, red at the reinstall
# when it fails, and refused on a dirty checkout. Last the Queue tier, in a scratch workspace: the
# members a range changed (none for docs and scripts, every one for a root build file), one install
# that runs the fast tier, the integration suite of the changed members and the curated lane in the
# others, a red run excused only when it failed in quarantine, and none of the Full tier's steps.
# In the same workspace, the Full tier runs the slow tier only when a plugin changed since its base,
# and is red when a suite was seen reading a doc that scripts/gate/test-reads.txt does not list. The
# docs tier runs guard and the scoreboard check alone, and is red over a range that is not docs-only.
# Last of all it runs itself once more under a gate's environment, as the gate's own fixture run
# does, and must pass there too.
set -euo pipefail

# A gate runs this file among its fixtures with its own settings exported (GATE_TIER=queue, its
# log directory, its checkout), and gate.sh and flaky.sh would read them in place of the defaults
# each case here is written against. Neither the settings nor the stand-ins' switches carry in.
unset "${!GATE_@}" "${!FLAKY_@}" "${!PRE_@}" POST_SCRIPTS FAIL_PUSHGATE FAIL_REINSTALL FAIL_INTEG \
  FAIL_SLOW FAIL_INTEG_ONCE FAIL_INSTALL_ONCE RESULTS_FIRST RERUN_JSONL JK_CALLS KA_STATE FAIL_SCOREBOARD \
  JK_CACHE_DIR

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
# A class red, with its failure, and then passing on the re-run of it.
cat >"$WORK/red-new.jsonl" <<'EOF'
{"schema":1,"type":"error","task":"run-tests","code":"test-failure","message":"expected: 1","module":"cc.jumpkick:jk-engine","testClass":"b.NewTest","method":"adds()","stack":"org.opentest4j.AssertionFailedError: expected: 1\n\tat b.NewTest.adds(NewTest.java:9)\n"}
{"schema":1,"type":"workspace-finish","success":false}
EOF
cat >"$WORK/rerun-new.jsonl" <<'EOF'
{"schema":1,"type":"warn","task":"run-tests","code":"test-passed-on-rerun","message":"passed on a re-run with no input changed, so flaky or dependent on what ran before: b.NewTest","module":"cc.jumpkick:jk-engine"}
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
# The stand-in logs each call, fails the push gate's first run when FAIL_PUSHGATE is set, the
# reinstall without tests when FAIL_REINSTALL is set, an exclusive test run when FAIL_INTEG is set
# (the first one only under FAIL_INTEG_ONCE), the slow tier when FAIL_SLOW is set and the first
# `install -c` under FAIL_INSTALL_ONCE,
# prints $RESULTS_FIRST as the first run's transcript and $RERUN_JSONL as any later one's, and
# reports the checkout's HEAD as the engine's commit.
export HOME="$WORK/home"
mkdir -p "$HOME/.jk/bin"
cat >"$HOME/.jk/bin/jk" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$JK_CALLS"
case "$1" in
  --version) echo "jk 0.0.0" ;;
  engine) [[ "${2:-}" == status ]] && echo "Version  0.0.0 (commit $(git rev-parse --short=12 HEAD))" ;;
  results)
    if [[ -n "${RESULTS_FIRST:-}" && "$(grep -c '^results' "$JK_CALLS")" -eq 1 ]]; then
      cat "$RESULTS_FIRST"
    else
      cat "$RERUN_JSONL"
    fi
    ;;
  test)
    [[ -n "${FAIL_SLOW:-}" && " $* " == *" -s slow "* ]] && { echo "FAIL a.AndroidE2eTest"; exit 4; }
    if [[ " $* " == *" -X "* ]]; then
      [[ -n "${FAIL_INTEG:-}" ]] && { echo "FAIL a.FlakyTest"; exit 4; }
      [[ -n "${FAIL_INTEG_ONCE:-}" && "$(grep -c -- ' -X ' "$JK_CALLS")" -eq 1 ]] && { echo "FAIL b.NewTest"; exit 4; }
    fi
    ;;
  install)
    if [[ -n "${FAIL_INSTALL_ONCE:-}" && "$*" == "install -c" && "$(grep -cx 'install -c' "$JK_CALLS")" -eq 1 ]]; then
      echo "FAIL b.NewTest"
      exit 4
    fi
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

# gate <n> [env...]: runs the gate in the scratch repo, the env given winning over the defaults,
# and holds it to finishing its log. Through run.sh and its lock where flock exists (Linux); macOS
# has no flock, so there gate.sh alone.
launcher="$repo/scripts/gate/run.sh"
command -v flock >/dev/null 2>&1 || launcher="$repo/scripts/gate/gate.sh"
gate() {
  local n="$1"
  shift
  : >"$WORK/calls-$n"
  env JK_CALLS="$WORK/calls-$n" GATE_CHECKOUT="$repo" GATE_LOG_DIR="$WORK/logs" \
    GATE_LOCK="$WORK/gate.lock" RERUN_JSONL="$WORK/rerun.jsonl" FLAKY_KA="$WORK/bin/ka" \
    KA_STATE="$WORK/ka" "$@" bash "$launcher" "$n" "$tip" >/dev/null 2>&1
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
if grep -q -- '-s slow' "$WORK/calls-1"; then fail "a Full tier with no base ran the slow tier: $(cat "$WORK/calls-1")"; fi

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

# --- the Queue tier ---
# A workspace of three members, one nested in another, and a curated lane over two of them.
q="$WORK/qrepo"
mkdir -p "$q/scripts/gate" "$q/mod/a/packs/p" "$q/mod/b" "$q/plugins/x" "$q/docs"
cp "$ROOT"/scripts/gate/*.sh "$q/scripts/gate/"
cp "$ROOT/scripts/curated-integration.sh" "$q/scripts/"
cp "$repo/scripts/release-notes.sh" "$repo/scripts/test-ok.sh" "$q/scripts/"
cat >"$q/jk.toml" <<'EOF'
[workspace]
modules = [
  "mod/a",
  # a comment that names [brackets]
  "mod/a/packs/p",
  "mod/b", # trailing comment
  "plugins/x",
]

[test]
EOF
cat >"$q/curated-integration.txt" <<'EOF'
mod/a | a.CuratedATest | wire  | success | The client half.
mod/b | b.CuratedBTest | spawn | success | The engine half.
EOF
for f in mod/a/A.java mod/a/packs/p/P.java mod/b/B.java plugins/x/X.java docs/d.md docs/read.md README.md; do echo 1 >"$q/$f"; done
# A project id for the recordings' tags, one doc a test reads, and a stand-in scoreboard.
echo 'project-id = "p1"' >"$q/jk-lock.toml"
printf '%s\n' '# docs a test reads' docs/read.md >"$q/scripts/gate/test-reads.txt"
mkdir -p "$q/bench"
# shellcheck disable=SC2016 # the stand-in reads the variable when it runs
printf '%s\n' '#!/usr/bin/env bash' '[ -z "${FAIL_SCOREBOARD:-}" ] || { echo "stale: docs/d.md"; exit 1; }' >"$q/bench/scoreboard"
chmod +x "$q/bench/scoreboard"
qc() { git -C "$q" add -A && git -C "$q" -c user.name=gate -c user.email=gate@example.invalid commit -qm "$1"; }
git -C "$q" init -q
qc base
qbase="$(git -C "$q" rev-parse HEAD)"
changed() { (cd "$q" && bash scripts/gate/changed-modules.sh "$@" | tr '\n' ' '); }

echo 2 >"$q/docs/d.md" && echo 2 >"$q/README.md" && echo 2 >"$q/scripts/notes.txt" && qc docs
[[ "$(changed "$qbase")" == "" ]] || fail "docs, a root Markdown file and scripts selected members: $(changed "$qbase")"
echo 2 >"$q/mod/a/packs/p/P.java" && qc pack
[[ "$(changed "$qbase")" == "mod/a/packs/p " ]] || fail "a nested member's file did not select it alone: $(changed "$qbase")"
echo 2 >"$q/mod/a/A.java" && qc a
qtip="$(git -C "$q" rev-parse HEAD)"
[[ "$(changed "$qbase")" == "mod/a mod/a/packs/p " ]] || fail "the changed members were: $(changed "$qbase")"
echo "# root" >>"$q/jk.toml" && qc root
qroot="$(git -C "$q" rev-parse HEAD)"
[[ "$(changed "$qtip" "$qroot")" == "* " ]] || fail "a root build file did not select every member: $(changed "$qtip" "$qroot")"
git -C "$q" checkout -q --detach "$qtip"

repo="$q"
launcher="$repo/scripts/gate/run.sh"
command -v flock >/dev/null 2>&1 || launcher="$repo/scripts/gate/gate.sh"
tip="$qtip"
gate 5 GATE_TIER=queue GATE_BASE="$qbase"
[[ "$(verdict 5)" == "GREEN $tip" ]] || fail "a green Queue tier was not green: $(cat "$WORK/logs/integ-gate-5.log")"
got="$(sed -n '/^install /,$p' "$WORK/calls-5" | grep -E '^(install|engine stop|test)')"
want="$(printf '%s\n' 'install -c' 'engine stop' \
  'test -s integration -c -X --no-ansi -m mod/a -m mod/a/packs/p' \
  'test -s integration --no-ansi -m mod/b --class b.CuratedBTest')"
[[ "$got" == "$want" ]] || fail "the Queue tier ran:
$got
want:
$want"
grep -q '^CHANGED_MODULES since .*: mod/a mod/a/packs/p' "$WORK/logs/integ-gate-5.log" || fail "the log does not name the changed members"
if grep -q '^RELEASE_SUITE_EXIT' "$WORK/logs/integ-gate-5.log"; then fail "the Queue tier ran the release suite"; fi

# Red only in a quarantined class: excused, and green. Red in another class too: red, naming the run.
printf '2000-01-01T00:00:00Z\tquarantined\tcc.jumpkick:jk-engine\ta.FlakyTest\tfake-9\n' >>"$WORK/logs/flaky-history.tsv"
echo ready >"$WORK/ka/fake-9"
gate 6 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INTEG=1 RERUN_JSONL="$WORK/red.jsonl"
[[ "$(verdict 6)" == "GREEN $tip" ]] || fail "a run red only in quarantine was not excused: $(cat "$WORK/logs/integ-gate-6.log")"
grep -q '^EXCUSED changed' "$WORK/logs/integ-gate-6.log" || fail "the log does not say the run was excused"
gate 7 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INTEG=1 RERUN_JSONL="$WORK/red-other.jsonl"
[[ "$(verdict 7)" == "RED" ]] || fail "a run red outside the quarantine was not red: $(cat "$WORK/logs/integ-gate-7.log")"
grep -q '^INTEG changed EXIT=4' "$WORK/logs/integ-gate-7.log" || fail "the log does not name the red run"
[[ "$(grep -c -- ' -X ' "$WORK/calls-7")" -eq 1 ]] || fail "a run red with a compile error ran again: $(cat "$WORK/calls-7")"

# Red in a class that passes when run once more: green, and the class is recorded as flaky.
gate 10 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INTEG_ONCE=1 RESULTS_FIRST="$WORK/red-new.jsonl" \
  RERUN_JSONL="$WORK/rerun-new.jsonl"
[[ "$(verdict 10)" == "GREEN $tip" ]] || fail "a run green on its re-run was not green: $(cat "$WORK/logs/integ-gate-10.log")"
[[ "$(grep -c -- ' -X ' "$WORK/calls-10")" -eq 2 ]] || fail "the red run did not run once more: $(cat "$WORK/calls-10")"
grep -q '^RERUN changed: .*EXIT=0' "$WORK/logs/integ-gate-10.log" || fail "the log does not say the run ran again"
grep -q "rerun-pass.*b.NewTest.*$tip" "$WORK/logs/flaky-history.tsv" || fail "the flake was not recorded: $(cat "$WORK/logs/integ-gate-10.log")"

# Red on the re-run too: red, naming the class and keeping its failure.
gate 11 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INTEG=1 RERUN_JSONL="$WORK/red-new.jsonl"
[[ "$(verdict 11)" == "RED" ]] || fail "a run red on its re-run was not red: $(cat "$WORK/logs/integ-gate-11.log")"
[[ "$(grep -c -- ' -X ' "$WORK/calls-11")" -eq 2 ]] || fail "the red run did not run exactly once more: $(cat "$WORK/calls-11")"
grep -qx 'FAILED cc.jumpkick:jk-engine b.NewTest' "$WORK/logs/integ-gate-11.log" || fail "the log does not name the failed class: $(cat "$WORK/logs/integ-gate-11.log")"
grep -q 'at b.NewTest.adds(NewTest.java:9)' "$WORK/logs/g11-failures.txt" || fail "the failure's stack was not kept: $(cat "$WORK/logs/g11-failures.txt")"

# The install's fast tier red in a class that passes when run once more: the install goes on.
gate 12 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INSTALL_ONCE=1 RESULTS_FIRST="$WORK/red-new.jsonl" \
  RERUN_JSONL="$WORK/rerun-new.jsonl"
[[ "$(verdict 12)" == "GREEN $tip" ]] || fail "an install green on its re-run was not green: $(cat "$WORK/logs/integ-gate-12.log")"
[[ "$(grep -cx 'install -c' "$WORK/calls-12")" -eq 2 ]] || fail "the red install did not run once more: $(cat "$WORK/calls-12")"

# A root build file changed: the whole integration suite, and no curated lane beside it.
tip="$qroot"
gate 8 GATE_TIER=queue GATE_BASE="$qtip"
[[ "$(verdict 8)" == "GREEN $tip" ]] || fail "the Queue tier over a root change was not green: $(cat "$WORK/logs/integ-gate-8.log")"
got="$(grep -E '^test' "$WORK/calls-8")"
[[ "$got" == 'test -s integration -c --no-ansi' ]] || fail "a root change ran: $got"

# The Full tier: no slow tier when no plugin changed since its base; the slow tier when one did,
# and the gate is red when it is.
gate 13 GATE_TIER=full GATE_BASE="$qtip"
[[ "$(verdict 13)" == "GREEN $tip" ]] || fail "the Full tier over a root change was not green: $(cat "$WORK/logs/integ-gate-13.log")"
grep -qx 'test -s slow -c --no-ansi' "$WORK/calls-13" || fail "a root build file change did not run the slow tier: $(cat "$WORK/calls-13")"
git -C "$q" checkout -q --detach "$qroot"
echo 2 >"$q/mod/b/B.java" && qc b
tip="$(git -C "$q" rev-parse HEAD)"
gate 14 GATE_TIER=full GATE_BASE="$qroot"
[[ "$(verdict 14)" == "GREEN $tip" ]] || fail "the Full tier with no plugin changed was not green: $(cat "$WORK/logs/integ-gate-14.log")"
if grep -q -- '-s slow' "$WORK/calls-14"; then fail "the Full tier ran the slow tier with no plugin changed: $(cat "$WORK/calls-14")"; fi
grep -q '^SLOW skipped' "$WORK/logs/integ-gate-14.log" || fail "the log does not say the slow tier was skipped"
echo 2 >"$q/plugins/x/X.java" && qc plugin
tip="$(git -C "$q" rev-parse HEAD)"
gate 15 GATE_TIER=full GATE_BASE="$qroot"
[[ "$(verdict 15)" == "GREEN $tip" ]] || fail "the Full tier over a plugin change was not green: $(cat "$WORK/logs/integ-gate-15.log")"
grep -qx 'test -s slow -c --no-ansi' "$WORK/calls-15" || fail "a plugin change did not run the slow tier: $(cat "$WORK/calls-15")"
grep -q '^SLOW_EXIT=0 (changed since .*: plugins/x)' "$WORK/logs/integ-gate-15.log" || fail "the log does not say why the slow tier ran: $(cat "$WORK/logs/integ-gate-15.log")"
gate 16 GATE_TIER=full GATE_BASE="$qroot" FAIL_SLOW=1
[[ "$(verdict 16)" == "RED" ]] || fail "a red slow tier was not red: $(cat "$WORK/logs/integ-gate-16.log")"
grep -q '^SLOW_EXIT=4' "$WORK/logs/integ-gate-16.log" || fail "the log does not name the red slow tier"

# A suite of mod/b was seen reading docs/d.md, which the list does not hold: the Full tier is red
# naming it, and `write` lists it.
cache="$WORK/cache"
tag="$(printf '%s\n%s' p1 mod/b | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12)"
mkdir -p "$cache/projects/m-$tag/suite-reads"
printf '%s\n' 'file ws:docs/d.md' 'file ws:mod/b/B.java' 'file ws:docs/gone.md' 'command proc:git' \
  >"$cache/projects/m-$tag/suite-reads/test-inputs-0123456789ab"
gate 21 GATE_TIER=full GATE_BASE="$qroot" JK_CACHE_DIR="$cache"
[[ "$(verdict 21)" == "RED" ]] || fail "a doc read and not listed was not red: $(cat "$WORK/logs/integ-gate-21.log")"
grep -q 'a test reads docs/d.md, which test-reads.txt does not list' "$WORK/logs/integ-gate-21.log" ||
  fail "the log does not name the doc: $(cat "$WORK/logs/integ-gate-21.log")"
[[ "$(cd "$q" && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh reads)" == docs/d.md ]] ||
  fail "the reads were: $(cd "$q" && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh reads)"
(cd "$q" && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh write && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh check) ||
  fail "the list written was not current: $(cat "$q/scripts/gate/test-reads.txt")"
git -C "$q" checkout -q -- scripts/gate/test-reads.txt

# The docs tier: guard and the scoreboard check over a docs-only range, and nothing else.
dbase="$tip"
echo 3 >"$q/docs/d.md" && echo 3 >"$q/README.md" && qc docs-only
tip="$(git -C "$q" rev-parse HEAD)"
gate 17 GATE_TIER=docs GATE_BASE="$dbase"
[[ "$(verdict 17)" == "GREEN $tip" ]] || fail "a docs-only range was not green: $(cat "$WORK/logs/integ-gate-17.log")"
got="$(grep -vE '^(--version|engine status)' "$WORK/calls-17")"
[[ "$got" == guard ]] || fail "the docs tier ran: $got"
grep -q '^SCOREBOARD_EXIT=0' "$WORK/logs/integ-gate-17.log" || fail "the docs tier did not check the scoreboard"
gate 18 GATE_TIER=docs GATE_BASE="$dbase" FAIL_SCOREBOARD=1
[[ "$(verdict 18)" == "RED" ]] || fail "a stale scoreboard block was not red: $(cat "$WORK/logs/integ-gate-18.log")"
grep -q 'stale: docs/d.md' "$WORK/logs/integ-gate-18.log" || fail "the log does not say what is stale"
gate 19 GATE_TIER=docs GATE_BASE="$qroot"
[[ "$(verdict 19)" == "RED (not docs-only since $qroot)" ]] || fail "a range with code passed the docs tier: $(cat "$WORK/logs/integ-gate-19.log")"
echo 2 >"$q/docs/read.md" && qc read
tip="$(git -C "$q" rev-parse HEAD)"
gate 20 GATE_TIER=docs GATE_BASE="$dbase"
[[ "$(verdict 20)" == "RED (not docs-only since $dbase)" ]] || fail "a doc a test reads passed the docs tier: $(cat "$WORK/logs/integ-gate-20.log")"
grep -q 'a test reads docs/read.md' "$WORK/logs/integ-gate-20.log" || fail "the log does not name the doc a test reads"

gate 9 GATE_TIER=fast
[[ "$(verdict 9)" == "RED (GATE_TIER is 'fast'; full, queue or docs)" ]] || fail "an unknown tier was gated: $(cat "$WORK/logs/integ-gate-9.log")"

# Under a Queue-tier gate's environment, every case above still holds.
if [[ -z "${TEST_GATE_NESTED:-}" ]]; then
  env TEST_GATE_NESTED=1 GATE_TIER=queue GATE_BASE="$qbase" GATE_LOG_DIR="$WORK/outer-logs" \
    GATE_CHECKOUT="$WORK/outer" GATE_BRANCH=main FLAKY_HISTORY="$WORK/outer-history.tsv" \
    FLAKY_THRESHOLD=1 PRE_MODULES=mod/a PRE_SCRIPTS=false POST_SCRIPTS=false FAIL_INTEG=1 \
    bash "$ROOT/scripts/test-gate.sh" >"$WORK/nested.log" 2>&1 ||
    fail "under a Queue-tier gate's environment: $(cat "$WORK/nested.log")"
fi

echo "test-gate: ok (flaky record and quarantine; 21 gate runs; again under a gate's environment)"
