#!/usr/bin/env bash
# Fixtures for the gate under scripts/gate/. The gate runs in a scratch repository against a
# stand-in `jk`: green on a clean tip, one install that runs tests (the fast tier once, under the
# engine the reinstall before it installed), a push gate red in a class that passes when run once
# more green and naming the class, twice in a row, red when the class fails again or the first run
# was red outside a test class, red at the reinstall when it fails, and refused on a dirty checkout.
# A red guard names each check it failed from its own transcript (each broken rule by its id, each
# other failed step as `<module> <task>`, `format` when it left the checkout changed), and names
# none when it exited 70 or when the transcript is another run's.
# Each tier logs how many native images the reinstall and the install after it linked, and an
# install that kept no transcript linked none. A class the stand-in quarantines
# leaves the checkout clean, gets a ticket, and goes to a stand-in merge queue in the quarantine
# file with the ticket's id; it is not filed twice, a closed ticket takes its class out, an entry
# with no ticket gets one, and a class that cannot get a ticket is not submitted. Last the Queue
# tier, in a scratch workspace: the members a range changed (none for docs, scripts and the test
# quarantine, every one for a root build file), the reinstall without tests and then, under the
# engine it installed, one install that runs the fast tier (red at the reinstall when it fails,
# before any test), the integration suite of the changed members and the curated lane in the
# others as one run, then the docs suite, a red run in test classes run once more, and none of the Full
# tier's steps. In the same workspace, the Full tier runs the docs suite on its own and the slow
# tier only when a plugin changed since its base, and is red when a suite other than the docs
# suite run alone was seen reading a doc that scripts/gate/test-reads.txt does not list. The docs
# tier runs guard, the scoreboard check and the docs suite alone, and is red over a range that is
# not docs-only or when the docs suite is.
# Last of all it runs itself once more under a gate's environment, as the gate's own fixture run
# does, and must pass there too.
set -euo pipefail

# A gate runs this file among its fixtures with its own settings exported (GATE_TIER=queue, its
# log directory, its checkout), and gate.sh and flaky.sh would read them in place of the defaults
# each case here is written against. Neither the settings nor the stand-ins' switches carry in.
unset "${!GATE_@}" "${!FLAKY_@}" "${!PRE_@}" POST_SCRIPTS FAIL_PUSHGATE FAIL_REINSTALL FAIL_INTEG \
  FAIL_SLOW FAIL_DOCS FAIL_INTEG_ONCE FAIL_INSTALL_ONCE RESULTS_FIRST RERUN_JSONL RESULTS_RUN JK_CALLS KA_STATE FAIL_SCOREBOARD FAIL_GUARD GUARD_DIRTIES \
  JK_CACHE_DIR JK_QUEUE_REMOTE BRANCH_TIER_BASE QUARANTINE_ADD KA_FAIL NATIVE_LINKED NATIVE_NO_TRANSCRIPT JK_HOME

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-gate.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "test-gate: $*" >&2
  exit 1
}

# --- run transcripts and a stand-in ka ---
# A transcript as jk writes it: classes passed on re-run (a nested class of one among them), one
# that passed and had not failed, and a line that is not JSON.
cat >"$WORK/rerun.jsonl" <<'EOF'
{"schema":1,"type":"session-start","command":"install"}
not json
{"schema":1,"type":"warn","task":"run-tests","code":"tests-not-rerun","message":"9 test classes passed earlier","module":"cc.jumpkick:jk-engine"}
{"schema":1,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-engine","testClass":"a.FlakyTest$Inner","tests":1,"failed":0,"skipped":0,"millis":4,"passedOnRerun":true}
{"schema":1,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-engine","testClass":"a.FlakyTest","tests":2,"failed":0,"skipped":0,"millis":9,"passedOnRerun":true}
{"schema":1,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-engine","testClass":"a.OtherTest","tests":1,"failed":0,"skipped":0,"millis":3,"passedOnRerun":true}
{"schema":1,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-engine","testClass":"a.SteadyTest","tests":1,"failed":0,"skipped":0,"millis":3}
{"schema":1,"type":"warn","task":"run-tests","code":"test-passed-on-rerun","message":"passed on a re-run with no input changed, so flaky or dependent on what ran before: a.FlakyTest, a.FlakyTest$Inner, a.OtherTest","module":"cc.jumpkick:jk-engine"}
EOF
[[ "$(bash "$ROOT/scripts/gate/flaky.sh" passed "$WORK/rerun.jsonl")" == "$(printf '%s\n' 'cc.jumpkick:jk-engine a.FlakyTest' 'cc.jumpkick:jk-engine a.OtherTest')" ]] ||
  fail "the classes passed on the re-run were: $(bash "$ROOT/scripts/gate/flaky.sh" passed "$WORK/rerun.jsonl")"
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
{"schema":1,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-engine","testClass":"b.NewTest","tests":1,"failed":0,"skipped":0,"millis":7,"passedOnRerun":true}
{"schema":1,"type":"warn","task":"run-tests","code":"test-passed-on-rerun","message":"passed on a re-run with no input changed, so flaky or dependent on what ran before: b.NewTest","module":"cc.jumpkick:jk-engine"}
EOF
# Full cycle f15, as jk wrote it: the push gate red in one e2e class, and that class alone run on
# the re-run, passing. The install's transcript after the fix; before it, `jk install` kept none,
# and `jk results` printed the gate's `jk guard` run, which runs no test.
cat >"$WORK/f15-red.jsonl" <<'EOF'
{"schema":1,"ts":1795347749300,"type":"session-start","command":"install","argv":["install"],"trigger":"cli","session":"script 4be0a1","progress":null}
{"schema":1,"ts":1795347749330,"type":"job","jid":1756,"buildNumber":2086,"detailsPath":"/home/u/.jk/state/builds/projects/a66f/runs/2086/details.jsonl"}
{"schema":1,"ts":1795347902241,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-cli-e2e","testClass":"cc.jumpkick.command.toolchain.JdkUpdateCommandTest","tests":10,"failed":0,"skipped":0,"millis":2429,"tier":"integration","progress":5.2}
{"schema":1,"ts":1795347958247,"type":"error","task":"run-tests","code":"test-failure","message":"\"✘ New   template not found: plugin\"\n Expected: 0\n  But Was: 64","module":"cc.jumpkick:jk-cli-e2e","testClass":"cc.jumpkick.command.project.NewCommandTest","test":null,"progress":99}
{"schema":1,"ts":1795347958300,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-cli-e2e","testClass":"cc.jumpkick.command.project.NewCommandTest","tests":6,"failed":1,"skipped":0,"millis":4120,"tier":"integration","progress":99}
{"schema":1,"ts":1795347959418,"type":"session-finish","exit":4,"millis":210118,"wedge":"Install failed","modules":["cc.jumpkick:jk-cli-e2e"],"progress":99,"jid":1756,"buildNumber":2086}
EOF
cat >"$WORK/f15-rerun.jsonl" <<'EOF'
{"schema":1,"ts":1795347964400,"type":"session-start","command":"install","argv":["install"],"trigger":"cli","session":"script 4be0a1","progress":null}
{"schema":1,"ts":1795347964456,"type":"job","jid":1758,"buildNumber":2088,"detailsPath":"/home/u/.jk/state/builds/projects/a66f/runs/2088/details.jsonl"}
{"schema":1,"ts":1795347966000,"type":"warn","task":"run-tests","code":"tests-not-rerun","message":"142 test classes passed earlier under these same inputs and are not run again; running the rest (--redo runs every class)","module":"cc.jumpkick:jk-cli-e2e"}
{"schema":1,"ts":1795347980100,"type":"class-finish","task":"run-tests","module":"cc.jumpkick:jk-cli-e2e","testClass":"cc.jumpkick.command.project.NewCommandTest","tests":6,"failed":0,"skipped":0,"millis":3980,"tier":"integration","passedOnRerun":true,"progress":60}
{"schema":1,"ts":1795347980200,"type":"warn","task":"run-tests","code":"test-passed-on-rerun","message":"passed on a re-run with no input changed, so flaky or dependent on what ran before: cc.jumpkick.command.project.NewCommandTest","module":"cc.jumpkick:jk-cli-e2e"}
{"schema":1,"ts":1795348790000,"type":"session-finish","exit":0,"millis":825600,"wedge":"Install successful","modules":["cc.jumpkick:jk-cli-e2e"],"progress":100,"jid":1758,"buildNumber":2088}
EOF
cat >"$WORK/f15-guard.jsonl" <<'EOF'
{"schema":1,"ts":1795347732049,"type":"session-start","command":"build","argv":["build","--skip-tests"],"trigger":"cli","session":"script bcaa1d","progress":null}
{"schema":1,"ts":1795347732131,"type":"job","jid":1753,"buildNumber":2083,"detailsPath":"/home/u/.jk/state/builds/projects/a66f/runs/2083/details.jsonl"}
{"schema":1,"ts":1795347732159,"type":"preflight","stage":"lock","done":0,"total":0,"label":"Workspace lock ready","progress":null}
{"schema":1,"ts":1795347737181,"type":"session-finish","exit":0,"millis":5132,"wedge":"Build successful. Built 1 module, 1 up to date - took 5.1s","modules":["cc.jumpkick:jk-cli-e2e","cc.jumpkick:jk"],"progress":100,"jid":1753,"buildNumber":2083}
EOF
bash "$ROOT/scripts/gate/flaky.sh" ran "$WORK/f15-red.jsonl" install || fail "an install's transcript was not read as the install's"
if bash "$ROOT/scripts/gate/flaky.sh" ran "$WORK/f15-guard.jsonl" install 2>/dev/null; then fail "a guard's transcript was read as an install's"; fi
[[ "$(bash "$ROOT/scripts/gate/flaky.sh" ran "$WORK/f15-guard.jsonl" install 2>&1)" == "jk results printed the run of jk build, not this jk install" ]] ||
  fail "flaky.sh ran said: $(bash "$ROOT/scripts/gate/flaky.sh" ran "$WORK/f15-guard.jsonl" install 2>&1)"
[[ "$(bash "$ROOT/scripts/gate/flaky.sh" failed "$WORK/f15-red.jsonl")" == "cc.jumpkick:jk-cli-e2e cc.jumpkick.command.project.NewCommandTest" ]] ||
  fail "f15's failed classes were: $(bash "$ROOT/scripts/gate/flaky.sh" failed "$WORK/f15-red.jsonl")"
# A stand-in ka: `new` opens TK-F<n> (or fails under KA_FAIL) and logs its arguments, `show`
# answers the status written in $KA_STATE/<id>. Each reads its stdin to the end, as a ka that
# prompts would.
mkdir -p "$WORK/bin" "$WORK/ka"
cat >"$WORK/bin/ka" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  new)
    cat >/dev/null
    [[ -z "${KA_FAIL:-}" ]] || exit 1
    id="$(printf 'TK-F%04d' $(($(find "$KA_STATE" -type f | wc -l) + 1)))"
    echo ready >"$KA_STATE/$id"
    printf '%s\n' "$@" >>"$KA_STATE.new.log"
    echo "{\"id\":\"$id\",\"status\":\"ready\"}"
    ;;
  show)
    cat >/dev/null
    echo "{\"id\":\"$2\",\"status\":\"$(cat "$KA_STATE/$2")\"}"
    ;;
esac
EOF
chmod +x "$WORK/bin/ka"
tickets() { find "$WORK/ka" -type f | wc -l | tr -d ' '; }

# --- the gate, against a stand-in jk ---
# The stand-in logs each call, fails the push gate's first FAIL_PUSHGATE runs, the
# reinstall without tests when FAIL_REINSTALL is set, an exclusive test run when FAIL_INTEG is set
# (the first one only under FAIL_INTEG_ONCE), the slow tier when FAIL_SLOW is set, the docs suite
# when FAIL_DOCS is set and the first
# `install -c` under FAIL_INSTALL_ONCE,
# prints $RESULTS_FIRST as the first run's transcript and $RERUN_JSONL as any later one's, each as
# the transcript of the jk command it ran last (of $RESULTS_RUN when set), and
# reports the checkout's HEAD as the engine's commit. A run that runs tests quarantines class
# $QUARANTINE_ADD of mod/a in the checkout, as jk does past its threshold. `jk guard` exits
# $FAIL_GUARD when it is set, and leaves the file guard-dirt in the checkout when GUARD_DIRTIES is.
export HOME="$WORK/home"
mkdir -p "$HOME/.jk/bin"
cat >"$HOME/.jk/bin/jk" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$JK_CALLS"
q=.jk/test/quarantine.toml
if [[ -n "${QUARANTINE_ADD:-}" && ( "$1" == test || ( "$1" == install && " $* " != *" -S "* ) ) ]] \
  && ! grep -qs "name = \"$QUARANTINE_ADD\"" "$q"; then
  mkdir -p .jk/test
  [[ -f "$q" ]] || echo "# Test classes in quarantine" >"$q"
  printf '\n[[class]]\nname = "%s"\nmodule = "mod/a"\nreason = "flaky, 2 of 20"\nsince = 2026-11-22\n' "$QUARANTINE_ADD" >>"$q"
fi
case "$1" in
  --version) echo "jk 0.0.0" ;;
  engine) [[ "${2:-}" == status ]] && echo "Version  0.0.0 (commit $(git rev-parse --short=12 HEAD))" ;;
  results)
    # -d: the last install's transcript, which linked the native client when its arguments match
    # NATIVE_LINKED and restored it otherwise. One matching NATIVE_NO_TRANSCRIPT kept none, so the
    # transcript is an earlier run's, begun long before.
    if [[ "${2:-}" == -d ]]; then
      last="$(grep '^install' "$JK_CALLS" | tail -1)"
      ts="$(($(date +%s) * 1000))"
      [[ -n "${NATIVE_NO_TRANSCRIPT:-}" && "$last" =~ $NATIVE_NO_TRANSCRIPT ]] && ts=1
      status=SKIPPED
      [[ -n "${NATIVE_LINKED:-}" && "$last" =~ $NATIVE_LINKED ]] && status=SUCCESS
      echo "{\"schema\":1,\"ts\":$ts,\"type\":\"session-start\",\"command\":\"install\"}"
      echo "{\"schema\":1,\"type\":\"task-finish\",\"task\":\"native-image\",\"status\":\"$status\",\"module\":\"m:cli\"}"
      exit 0
    fi
    # The run just made, as its session-start names it; RESULTS_RUN names another, as a jk that
    # keeps no transcript of that run prints the one before it.
    t="$RERUN_JSONL"
    [[ -n "${RESULTS_FIRST:-}" && "$(grep -c '^results -O' "$JK_CALLS")" -eq 1 ]] && t="$RESULTS_FIRST"
    ran="${RESULTS_RUN:-$(grep -vE '^(results|engine|--version)' "$JK_CALLS" | tail -1 | cut -d' ' -f1)}"
    echo "{\"schema\":1,\"ts\":$(($(date +%s) * 1000)),\"type\":\"session-start\",\"command\":\"$ran\"}"
    grep -v '"type":"session-start"' "$t" || true
    ;;
  guard)
    [[ -z "${GUARD_DIRTIES:-}" ]] || echo dirt >guard-dirt
    exit "${FAIL_GUARD:-0}"
    ;;
  test)
    [[ -n "${FAIL_SLOW:-}" && " $* " == *" -s slow "* ]] && { echo "FAIL a.AndroidE2eTest"; exit 4; }
    [[ -n "${FAIL_DOCS:-}" && " $* " == *" -s docs "* ]] && { echo "FAIL a.DocsTest"; exit 4; }
    [[ -n "${FAIL_TREE:-}" && " $* " == *" -s tree "* ]] && { echo "FAIL a.TreeTest"; exit 4; }
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
    if [[ -n "${FAIL_PUSHGATE:-}" && " $* " == *" -g "* && "$(grep -c '^install -g' "$JK_CALLS")" -le "$FAIL_PUSHGATE" ]]; then
      echo "FAIL a.FlakyTest"
      exit 4
    fi
    ;;
esac
exit 0
EOF
chmod +x "$HOME/.jk/bin/jk"

repo="$WORK/repo"
mkdir -p "$repo/scripts/gate" "$repo/scripts/queue"
cp "$ROOT"/scripts/gate/*.sh "$repo/scripts/gate/"
cp "$ROOT"/scripts/queue/*.sh "$repo/scripts/queue/"
# shellcheck disable=SC2016 # the stand-in reads $* and $JK_CALLS when it runs
printf '%s\n' '#!/usr/bin/env bash' 'echo "release-notes $*" >>"$JK_CALLS"' >"$repo/scripts/release-notes.sh"
cp "$ROOT/scripts/release-version.sh" "$repo/scripts/"
echo 'version = "9.8.7"' >"$repo/jk.toml"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$repo/scripts/test-ok.sh"
git -C "$repo" init -q
git -C "$repo" config user.name gate
git -C "$repo" config user.email gate@example.invalid
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
    KA_STATE="$WORK/ka" FLAKY_BASE="$tip" JK_QUEUE_REMOTE="$WORK/queue.git" \
    "$@" bash "$launcher" "$n" "$tip" >/dev/null 2>&1
  [[ "$(tail -1 "$WORK/logs/integ-gate-$n.log")" == "GATE$n DONE" ]] || fail "gate $n did not finish: $(cat "$WORK/logs/integ-gate-$n.log")"
}
verdict() { grep -E '^(GREEN |RED|CHECKOUT |NO CHECKOUT)' "$WORK/logs/integ-gate-$1.log" | tail -1; }

gate 1
[[ "$(verdict 1)" == "GREEN $tip" ]] || fail "a clean tip was not green: $(cat "$WORK/logs/integ-gate-1.log")"
if grep -q '^test -m' "$WORK/calls-1"; then fail "the gate ran a pre-run: $(cat "$WORK/calls-1")"; fi
grep -qx 'release-notes 9.8.7' "$WORK/calls-1" || fail "the gate did not check the jk.toml version's notes: $(cat "$WORK/calls-1")"
# The reinstall runs no tests, the engine it ran on stops, and the push gate is the one install that
# runs the fast tier.
got="$(sed -n '/^install /,$p' "$WORK/calls-1" | grep -E '^(install |engine stop)')"
want="$(printf '%s\n' 'install -S' 'engine stop' 'install -g -c')"
[[ "$got" == "$want" ]] || fail "the installs were:
$got
want:
$want"
[[ ! -f "$WORK/logs/quarantine-tickets.tsv" ]] || fail "a gate with nothing quarantined opened a ticket"
grep -q '^FLAKY the quarantine on .* is current' "$WORK/logs/integ-gate-1.log" || fail "the gate did not review the quarantine: $(cat "$WORK/logs/integ-gate-1.log")"
if grep -q -- '-s slow' "$WORK/calls-1"; then fail "a Full tier with no base ran the slow tier: $(cat "$WORK/calls-1")"; fi
grep -qx 'test -s docs -c --no-ansi' "$WORK/calls-1" || fail "the Full tier did not run the docs suite alone: $(cat "$WORK/calls-1")"
# The native client: linked by the reinstall alone, and the log says so; linked by both installs,
# and the log shows it twice; and an install that kept no transcript is not read as linking it.
grep -qx 'NATIVE_LINKS reinstall=0 install=0' "$WORK/logs/integ-gate-1.log" || fail "a gate that linked nothing logged: $(grep NATIVE "$WORK/logs/integ-gate-1.log")"
for c in '42 -S 1 0' '43 install 1 1' '44 install 1 0 -g'; do
  read -r g linked r i none <<<"$c"
  gate "$g" NATIVE_LINKED="$linked" NATIVE_NO_TRANSCRIPT="${none:-}"
  grep -qx "NATIVE_LINKS reinstall=$r install=$i" "$WORK/logs/integ-gate-$g.log" ||
    fail "gate $g logged $(grep NATIVE "$WORK/logs/integ-gate-$g.log"), want reinstall=$r install=$i"
done

# Red in a class that passes on the re-run: green, naming the class; and so again the next gate.
for g in 2 36; do
  gate "$g" FAIL_PUSHGATE=1 RESULTS_FIRST="$WORK/red-new.jsonl" RERUN_JSONL="$WORK/rerun-new.jsonl"
  [[ "$(verdict "$g")" == "GREEN $tip" ]] || fail "a push gate green on its re-run was not green: $(cat "$WORK/logs/integ-gate-$g.log")"
  [[ "$(grep -c '^install -g -c$' "$WORK/calls-$g")" -eq 2 ]] || fail "a red push gate did not run once more: $(cat "$WORK/calls-$g")"
  grep -qx 'FLAKY cc.jumpkick:jk-engine b.NewTest passed on the re-run' "$WORK/logs/integ-gate-$g.log" ||
    fail "the log does not name the class that passed on the re-run: $(cat "$WORK/logs/integ-gate-$g.log")"
done
# The class fails again: red, naming it, with no FLAKY line.
gate 37 FAIL_PUSHGATE=2 RESULTS_FIRST="$WORK/red-new.jsonl" RERUN_JSONL="$WORK/red-new.jsonl"
[[ "$(verdict 37)" == "RED" ]] || fail "a push gate red on its re-run was not red: $(cat "$WORK/logs/integ-gate-37.log")"
if grep -q '^FLAKY .* passed on the re-run$' "$WORK/logs/integ-gate-37.log"; then fail "a class red twice was called flaky: $(cat "$WORK/logs/integ-gate-37.log")"; fi
grep -q '^FAIL a.FlakyTest' "$WORK/logs/integ-gate-37.log" || fail "the log does not name the re-run's failure: $(cat "$WORK/logs/integ-gate-37.log")"
# The first run red outside a test class: the re-run does not make it green.
gate 38 FAIL_PUSHGATE=1 RESULTS_FIRST="$WORK/red-other.jsonl" RERUN_JSONL="$WORK/rerun-new.jsonl"
[[ "$(verdict 38)" == "RED" ]] || fail "a push gate red outside a test class was green on its re-run: $(cat "$WORK/logs/integ-gate-38.log")"
grep -q '^RERUN green, but the first run was red outside a test class' "$WORK/logs/integ-gate-38.log" ||
  fail "the log does not say why the re-run did not count: $(cat "$WORK/logs/integ-gate-38.log")"
# Full cycle f15's transcripts: green, naming the e2e class. Under a jk that printed the gate's
# guard run in place of each install's, as f15's did: red, saying which run jk printed, and naming
# no class.
gate 39 FAIL_PUSHGATE=1 RESULTS_FIRST="$WORK/f15-red.jsonl" RERUN_JSONL="$WORK/f15-rerun.jsonl"
[[ "$(verdict 39)" == "GREEN $tip" ]] || fail "f15's push gate, green on its re-run, was not green: $(cat "$WORK/logs/integ-gate-39.log")"
grep -qx 'FLAKY cc.jumpkick:jk-cli-e2e cc.jumpkick.command.project.NewCommandTest passed on the re-run' "$WORK/logs/integ-gate-39.log" ||
  fail "the log does not name f15's flaky class: $(cat "$WORK/logs/integ-gate-39.log")"
gate 40 FAIL_PUSHGATE=1 RESULTS_RUN=build RESULTS_FIRST="$WORK/f15-guard.jsonl" RERUN_JSONL="$WORK/f15-guard.jsonl"
[[ "$(verdict 40)" == "RED" ]] || fail "a push gate with no transcript of its installs was green: $(cat "$WORK/logs/integ-gate-40.log")"
grep -qx 'RERUN green, but the gate cannot tell which classes failed (jk results printed the run of jk build, not this jk install): the push gate stays red' \
  "$WORK/logs/integ-gate-40.log" || fail "the log does not say which run jk results printed: $(cat "$WORK/logs/integ-gate-40.log")"
if grep -q '^FLAKY .* passed on the re-run$' "$WORK/logs/integ-gate-40.log"; then fail "another run's classes were called flaky: $(cat "$WORK/logs/integ-gate-40.log")"; fi

# A guard red in a rule and in a compile step: red at guard, naming the rule and the step, read
# from the guard run's own transcript (jk records `jk guard` as a build), and nothing installed.
cat >"$WORK/guard-red.jsonl" <<'EOF2'
{"schema":1,"type":"task-finish","task":"compile-java","status":"FAIL","module":"m:core"}
{"schema":1,"type":"error","task":"compile-java","code":"compile","module":"m:core","message":"cannot find symbol"}
{"schema":1,"type":"error","task":"guard","code":"no-system-out","module":"m:cli","file":"cli/Main.java","line":6}
{"schema":1,"type":"error","task":"guard-tree","code":"no-system-out","module":"m:web","file":"web/Boot.java","line":3}
{"schema":1,"type":"task-finish","task":"guard","status":"FAIL","module":"m:cli"}
{"schema":1,"type":"task-finish","task":"guard-tree","status":"FAIL","module":"m:web"}
{"schema":1,"type":"error","task":"guard","code":"one-owner","module":"m:cli","message":"GUARD one-owner  no-bite"}
EOF2
gate 50 FAIL_GUARD=1 RESULTS_RUN=build RERUN_JSONL="$WORK/guard-red.jsonl"
[[ "$(verdict 50)" == "RED (guard)" ]] || fail "a red guard did not stop the gate: $(cat "$WORK/logs/integ-gate-50.log")"
got="$(grep '^GUARD FAIL ' "$WORK/logs/integ-gate-50.log" || true)"
want="$(printf '%s\n' 'GUARD FAIL m:core compile-java' 'GUARD FAIL no-system-out' 'GUARD FAIL one-owner')"
[[ "$got" == "$want" ]] || fail "a red guard named the checks:
$got
want:
$want"
if grep -q '^install' "$WORK/calls-50"; then fail "the gate installed after a red guard: $(cat "$WORK/calls-50")"; fi
# jk's internal error names no check, and reads no transcript; nor does a transcript of another run.
gate 51 FAIL_GUARD=70 RESULTS_RUN=build RERUN_JSONL="$WORK/guard-red.jsonl"
[[ "$(verdict 51)" == "RED (guard)" ]] || fail "a guard that exited 70 did not stop the gate: $(cat "$WORK/logs/integ-gate-51.log")"
if grep -q '^GUARD FAIL' "$WORK/logs/integ-gate-51.log"; then fail "a guard that exited 70 named a check: $(cat "$WORK/logs/integ-gate-51.log")"; fi
if grep -q '^results' "$WORK/calls-51"; then fail "a guard that exited 70 read a transcript: $(cat "$WORK/calls-51")"; fi
gate 52 FAIL_GUARD=1 RESULTS_RUN=install RERUN_JSONL="$WORK/guard-red.jsonl"
[[ "$(verdict 52)" == "RED (guard)" ]] || fail "a red guard did not stop the gate: $(cat "$WORK/logs/integ-gate-52.log")"
if grep -q '^GUARD FAIL' "$WORK/logs/integ-gate-52.log"; then fail "another run's transcript named a guard check: $(cat "$WORK/logs/integ-gate-52.log")"; fi
# A guard that passes but leaves the checkout changed: red, naming format.
gate 53 GUARD_DIRTIES=1
[[ "$(verdict 53)" == "RED (guard)" ]] || fail "a guard that left changes did not stop the gate: $(cat "$WORK/logs/integ-gate-53.log")"
[[ "$(grep '^GUARD FAIL ' "$WORK/logs/integ-gate-53.log")" == "GUARD FAIL format" ]] ||
  fail "a guard that left changes did not name format: $(cat "$WORK/logs/integ-gate-53.log")"
rm -f "$repo/guard-dirt"

gate 3 FAIL_REINSTALL=1
[[ "$(verdict 3)" == "RED (reinstall)" ]] || fail "a failed reinstall did not stop the gate: $(cat "$WORK/logs/integ-gate-3.log")"
grep -q 'compile failed' "$WORK/logs/integ-gate-3.log" || fail "the gate log does not say why the reinstall failed"
if grep -q '^install -g' "$WORK/calls-3"; then fail "the push gate ran after a failed reinstall"; fi

# --- the quarantine ---
git init -q --bare "$WORK/queue.git"
# waiting: the quarantine file the stand-in queue holds; subject: its commit's subject.
waiting() { git --git-dir="$WORK/queue.git" show "refs/queue/gate/quarantine:.jk/test/quarantine.toml"; }
# Greps the quarantine file from a captured copy: piped into grep -q, git show would die of SIGPIPE
# writing a line after the match, and pipefail would fail the check.
waiting_has() {
  local out
  out="$(waiting)" || return 1
  grep "$@" <<<"$out"
}
subject() { git --git-dir="$WORK/queue.git" log -1 --format=%s refs/queue/gate/quarantine; }
clean() { [[ -z "$(git -C "$repo" status --porcelain)" ]] || fail "gate $1 left the checkout dirty: $(git -C "$repo" status --porcelain)"; }

# A push gate whose re-run passes and quarantines a class: green, the checkout clean, one ticket,
# and the queue holds the file with the class and the ticket's id, on the base and nothing else.
gate 30 FAIL_PUSHGATE=1 QUARANTINE_ADD=a.FlakyTest RESULTS_FIRST="$WORK/red-new.jsonl" RERUN_JSONL="$WORK/rerun.jsonl"
[[ "$(verdict 30)" == "GREEN $tip" ]] || fail "a push gate green on its re-run was not green: $(cat "$WORK/logs/integ-gate-30.log")"
grep -qx 'FLAKY cc.jumpkick:jk-engine a.FlakyTest passed on the re-run' "$WORK/logs/integ-gate-30.log" ||
  fail "the log does not name the class that passed on the re-run: $(cat "$WORK/logs/integ-gate-30.log")"
clean 30
[[ "$(tickets)" -eq 1 ]] || fail "one quarantined class opened $(tickets) tickets"
grep -q 'Flaky test FlakyTest' "$WORK/ka.new.log" || fail "the ticket does not name the class: $(cat "$WORK/ka.new.log")"
grep -q '^FLAKY a.FlakyTest quarantined: TK-F0001' "$WORK/logs/integ-gate-30.log" || fail "the log does not name the ticket: $(cat "$WORK/logs/integ-gate-30.log")"
grep -q '^FLAKY submitted' "$WORK/logs/integ-gate-30.log" || fail "the change was not submitted: $(cat "$WORK/logs/integ-gate-30.log") $(cat "$WORK/logs/g30-quarantine.log")"
waiting_has -qx 'issue = "TK-F0001"' || fail "the submitted file does not name the ticket: $(waiting)"
waiting_has -qx 'name = "a.FlakyTest"' || fail "the submitted file does not hold the class: $(waiting)"
[[ "$(subject)" == "TK-F0001: a.FlakyTest is quarantined under TK-F0001" ]] || fail "the commit's subject was: $(subject)"
[[ "$(git --git-dir="$WORK/queue.git" rev-parse refs/queue/gate/quarantine^)" == "$tip" ]] || fail "the change is not on the base alone"

# A green gate that quarantines another class: green, and the queue holds both, the first still
# unlanded; its ticket is not opened again.
gate 31 QUARANTINE_ADD=a.OtherTest
[[ "$(verdict 31)" == "GREEN $tip" ]] || fail "a gate that quarantined a class was not green: $(cat "$WORK/logs/integ-gate-31.log")"
clean 31
[[ "$(tickets)" -eq 2 ]] || fail "two quarantined classes opened $(tickets) tickets"
[[ "$(waiting | grep -c '^\[\[class\]\]')" -eq 2 ]] || fail "the submitted file does not hold both classes: $(waiting)"
gate 32 QUARANTINE_ADD=a.OtherTest
[[ "$(tickets)" -eq 2 ]] || fail "a class with an open ticket got another"
grep -q '^FLAKY the merge queue already holds' "$WORK/logs/integ-gate-32.log" || fail "the same change was submitted again: $(cat "$WORK/logs/integ-gate-32.log")"

# The queue lands it, with an entry added by hand that names no ticket and one tracked by hand
# under a ticket and its URL; then the first ticket closes. Its class leaves the quarantine, the
# hand-added class gets a ticket's id alone, the others stay as they were.
mkdir -p "$repo/.jk/test"
{
  waiting
  printf '\n[[class]]\nname = "b.HandTest"   # by hand\nreason = "flaky"\n'
  printf '\n[[class]]\nname = "c.TrackedTest"\nissue = "TK-F0009"\nissue-url = "https://example.org/i/9#c"\n'
} >"$repo/.jk/test/quarantine.toml"
git -C "$repo" add -A && git -C "$repo" commit -qm landed
tip="$(git -C "$repo" rev-parse HEAD)"
echo "done" >"$WORK/ka/TK-F0001"
gate 33
[[ "$(verdict 33)" == "GREEN $tip" ]] || fail "the gate after the landing was not green: $(cat "$WORK/logs/integ-gate-33.log")"
clean 33
grep -q '^FLAKY a.FlakyTest leaves the quarantine: TK-F0001 is done' "$WORK/logs/integ-gate-33.log" || fail "the closed ticket did not release its class: $(cat "$WORK/logs/integ-gate-33.log")"
if waiting_has -q a.FlakyTest; then fail "the released class is still in the file: $(waiting)"; fi
waiting_has -qx 'issue = "TK-F0002"' || fail "the class still quarantined lost its ticket: $(waiting)"
waiting_has -qx 'issue = "TK-F0003"' || fail "the hand-added class got no ticket: $(waiting)"
waiting_has -qx 'issue-url = "https://example.org/i/9#c"' || fail "an issue's URL was lost: $(waiting)"
[[ "$(waiting | grep -c '^issue-url')" -eq 1 ]] || fail "the gate wrote an issue URL of its own: $(waiting)"
[[ "$(subject)" == "TK-F0001: The gate's test quarantine follows its tickets" ]] || fail "the commit's subject was: $(subject)"

# A class that cannot get a ticket is not submitted.
before="$(git --git-dir="$WORK/queue.git" rev-parse refs/queue/gate/quarantine)"
gate 34 QUARANTINE_ADD=a.ThirdTest KA_FAIL=1
clean 34
grep -q '^FLAKY a.ThirdTest: no ticket opened' "$WORK/logs/integ-gate-34.log" || fail "the log does not say the class got no ticket: $(cat "$WORK/logs/integ-gate-34.log")"
[[ "$(git --git-dir="$WORK/queue.git" rev-parse refs/queue/gate/quarantine)" == "$before" ]] || fail "a class with no ticket was submitted: $(waiting)"

touch "$repo/stray"
gate 4
[[ "$(verdict 4)" == "CHECKOUT DIRTY" ]] || fail "a dirty checkout was gated: $(cat "$WORK/logs/integ-gate-4.log")"

# --- the Queue tier ---
# A workspace of three members, one nested in another, and a curated lane over two of them.
q="$WORK/qrepo"
mkdir -p "$q/scripts/gate" "$q/scripts/queue" "$q/mod/a/packs/p" "$q/mod/b" "$q/plugins/x" "$q/docs" "$q/.jk/test"
cp "$ROOT"/scripts/gate/*.sh "$q/scripts/gate/"
cp "$ROOT"/scripts/queue/*.sh "$q/scripts/queue/"
cp "$ROOT/scripts/curated-integration.sh" "$q/scripts/"
cp "$repo/scripts/release-notes.sh" "$repo/scripts/test-ok.sh" "$repo/scripts/release-version.sh" "$q/scripts/"
cat >"$q/jk.toml" <<'EOF'
version = "9.8.7"

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

echo 2 >"$q/docs/d.md" && echo 2 >"$q/README.md" && echo 2 >"$q/scripts/notes.txt" && echo '# none' >"$q/.jk/test/quarantine.toml" && qc docs
qdocs="$(git -C "$q" rev-parse HEAD)"
[[ "$(changed "$qbase")" == "" ]] || fail "docs, a root Markdown file, scripts and the test quarantine selected members: $(changed "$qbase")"
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
# Before the integration run, rank-integration asks jk to rank each changed member against the
# base; the stand-in prints nothing, so it ranks by source text, but the calls stay expected so
# a Queue tier that stops asking jk is caught.
got="$(sed -n '/^install /,$p' "$WORK/calls-5" | grep -E '^(install|engine stop|test)')"
want="$(printf '%s\n' 'install -S' 'engine stop' 'install -c' 'engine stop' \
  "test -e $qbase -m mod/a -s integration -O jsonl --no-ansi" \
  "test -e $qbase -m mod/a/packs/p -s integration -O jsonl --no-ansi" \
  'test -s integration -c -X --no-ansi -m mod/a -m mod/a/packs/p -m mod/b --class mod/b:b.CuratedBTest' \
  'test -s docs -c --no-ansi' \
  'test -s tree -c --no-ansi')"
[[ "$got" == "$want" ]] || fail "the Queue tier ran:
$got
want:
$want"
grep -q '^CHANGED_MODULES since .*: mod/a mod/a/packs/p' "$WORK/logs/integ-gate-5.log" || fail "the log does not name the changed members"
# No member changed: no integration suite, and the curated lane in one run over both its members.
tip="$qdocs"
git -C "$q" checkout -q --detach "$qdocs"
gate 46 GATE_TIER=queue GATE_BASE="$qbase"
[[ "$(verdict 46)" == "GREEN $tip" ]] || fail "a Queue tier with no member changed was not green: $(cat "$WORK/logs/integ-gate-46.log")"
got="$(grep -E '^test -s integration' "$WORK/calls-46")"
[[ "$got" == 'test -s integration -c -X --no-ansi -m mod/a --class mod/a:a.CuratedATest -m mod/b --class mod/b:b.CuratedBTest' ]] ||
  fail "with no member changed the Queue tier ran: $got"
grep -q '^INTEG integration EXIT=0' "$WORK/logs/integ-gate-46.log" || fail "the log does not name the curated run: $(cat "$WORK/logs/integ-gate-46.log")"
tip="$qtip"
git -C "$q" checkout -q --detach "$qtip"
gate 45 GATE_TIER=queue GATE_BASE="$qbase" NATIVE_LINKED=-S
grep -qx 'NATIVE_LINKS reinstall=1 install=0' "$WORK/logs/integ-gate-45.log" || fail "the Queue tier logged: $(grep NATIVE "$WORK/logs/integ-gate-45.log")"
if grep -q '^RELEASE_SUITE_EXIT' "$WORK/logs/integ-gate-5.log"; then fail "the Queue tier ran the release suite"; fi

# A changed member's integration classes past the budget: the ones ranked first run, each named
# with its member, and the log names the one left for the Full tier.
mkdir -p "$q/mod/a/src/test/java/a"
for c in NearTest FarTest; do printf 'package a;\n@Tag("integration")\nclass %s {}\n' "$c" >"$q/mod/a/src/test/java/a/$c.java"; done
echo 'target/' >"$q/mod/a/.gitignore"
qc ranked-classes
qranked="$(git -C "$q" rev-parse HEAD)"
echo 3 >"$q/mod/a/A.java" && qc ranked-change
tip="$(git -C "$q" rev-parse HEAD)"
mkdir -p "$q/mod/a/target/failsafe-reports"
printf '<testsuite name="a.NearTest" tests="1" time="10">\n</testsuite>\n' >"$q/mod/a/target/failsafe-reports/TEST-a.NearTest.xml"
printf '<testsuite name="a.FarTest" tests="1" time="50">\n</testsuite>\n' >"$q/mod/a/target/failsafe-reports/TEST-a.FarTest.xml"
gate 47 GATE_TIER=queue GATE_BASE="$qranked" GATE_INTEG_BUDGET=20
[[ "$(verdict 47)" == "GREEN $tip" ]] || fail "a ranked Queue tier was not green: $(cat "$WORK/logs/integ-gate-47.log")"
got="$(grep -E '^test -s integration' "$WORK/calls-47")"
[[ "$got" == 'test -s integration -c -X --no-ansi -m mod/a -m mod/b --class mod/b:b.CuratedBTest --class mod/a:a.NearTest' ]] ||
  fail "the ranked Queue tier ran: $got"
grep -qx 'DEFERRED mod/a a.FarTest (over budget)' "$WORK/logs/integ-gate-47.log" || fail "the deferred class is not named: $(cat "$WORK/logs/integ-gate-47.log")"
grep -qx 'INTEG_RANKED mod/a ran 1 of 2 classes (10s recorded), deferred 1 to the Full tier' "$WORK/logs/integ-gate-47.log" ||
  fail "the ranked count is not logged: $(cat "$WORK/logs/integ-gate-47.log")"
gate 48 GATE_TIER=queue GATE_BASE="$qranked" GATE_INTEG_BUDGET=0
got="$(grep -E '^test -s integration' "$WORK/calls-48")"
[[ "$got" == 'test -s integration -c -X --no-ansi -m mod/a -m mod/b --class mod/b:b.CuratedBTest' ]] ||
  fail "with no budget the Queue tier ran: $got"
rm -rf "$q/mod/a/target"
tip="$qtip"
git -C "$q" checkout -q --detach "$qtip"

# The reinstall red: the gate stops there, and no test ran under the previous engine.
gate 22 GATE_TIER=queue GATE_BASE="$qbase" FAIL_REINSTALL=1
[[ "$(verdict 22)" == "RED (reinstall)" ]] || fail "a failed reinstall did not stop the Queue tier: $(cat "$WORK/logs/integ-gate-22.log")"
if grep -qE '^(install -c|test )' "$WORK/calls-22"; then fail "the Queue tier ran tests after a failed reinstall: $(cat "$WORK/calls-22")"; fi

# Red with a compile error beside a failed class: red, naming the run, and not run again.
gate 7 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INTEG=1 RERUN_JSONL="$WORK/red-other.jsonl"
[[ "$(verdict 7)" == "RED" ]] || fail "a run red outside the quarantine was not red: $(cat "$WORK/logs/integ-gate-7.log")"
grep -q '^INTEG integration EXIT=4' "$WORK/logs/integ-gate-7.log" || fail "the log does not name the red run"
[[ "$(grep -c -- ' -X ' "$WORK/calls-7")" -eq 1 ]] || fail "a run red with a compile error ran again: $(cat "$WORK/calls-7")"
gate 24 GATE_TIER=queue GATE_BASE="$qbase" FAIL_DOCS=1 RERUN_JSONL="$WORK/red-other.jsonl"
[[ "$(verdict 24)" == "RED" ]] || fail "a red docs suite did not make the Queue tier red: $(cat "$WORK/logs/integ-gate-24.log")"
grep -q '^DOCS_SUITE_EXIT=4' "$WORK/logs/integ-gate-24.log" || fail "the log does not name the red docs suite"
gate 25 GATE_TIER=queue GATE_BASE="$qbase" FAIL_TREE=1 RERUN_JSONL="$WORK/red-other.jsonl"
[[ "$(verdict 25)" == "RED" ]] || fail "a red tree suite did not make the Queue tier red: $(cat "$WORK/logs/integ-gate-25.log")"
grep -q '^TREE_SUITE_EXIT=4' "$WORK/logs/integ-gate-25.log" || fail "the log does not name the red tree suite"

# Red in a class that passes when run once more: green.
gate 10 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INTEG_ONCE=1 RESULTS_FIRST="$WORK/red-new.jsonl" \
  RERUN_JSONL="$WORK/rerun-new.jsonl"
[[ "$(verdict 10)" == "GREEN $tip" ]] || fail "a run green on its re-run was not green: $(cat "$WORK/logs/integ-gate-10.log")"
[[ "$(grep -c -- ' -X ' "$WORK/calls-10")" -eq 2 ]] || fail "the red run did not run once more: $(cat "$WORK/calls-10")"
grep -q '^RERUN integration: .*EXIT=0' "$WORK/logs/integ-gate-10.log" || fail "the log does not say the run ran again"
grep -qx 'FLAKY cc.jumpkick:jk-engine b.NewTest passed on the re-run' "$WORK/logs/integ-gate-10.log" ||
  fail "the Queue tier does not name the class that passed on the re-run: $(cat "$WORK/logs/integ-gate-10.log")"

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
# The install's transcript not the install's: red at the install, saying which run jk printed,
# and no class named as failed or run again.
gate 41 GATE_TIER=queue GATE_BASE="$qbase" FAIL_INSTALL_ONCE=1 RESULTS_RUN=build RESULTS_FIRST="$WORK/red-new.jsonl"
[[ "$(verdict 41)" == "RED (install)" ]] || fail "an install with no transcript of its own was not red: $(cat "$WORK/logs/integ-gate-41.log")"
grep -qx 'TRANSCRIPT install: jk results printed the run of jk build, not this jk install, so its failed classes are unknown and do not run again' \
  "$WORK/logs/integ-gate-41.log" || fail "the log does not say which run jk results printed: $(cat "$WORK/logs/integ-gate-41.log")"
[[ "$(grep -cx 'install -c' "$WORK/calls-41")" -eq 1 ]] || fail "an install red in another run's classes ran again: $(cat "$WORK/calls-41")"
if grep -q '^FAILED ' "$WORK/logs/integ-gate-41.log"; then fail "another run's classes were named as failed: $(cat "$WORK/logs/integ-gate-41.log")"; fi

# A root build file changed: the whole integration suite, and no curated lane beside it.
tip="$qroot"
gate 8 GATE_TIER=queue GATE_BASE="$qtip"
[[ "$(verdict 8)" == "GREEN $tip" ]] || fail "the Queue tier over a root change was not green: $(cat "$WORK/logs/integ-gate-8.log")"
got="$(grep -E '^test' "$WORK/calls-8")"
[[ "$got" == "$(printf '%s\n' 'test -s integration -c --no-ansi' 'test -s docs -c --no-ansi' 'test -s tree -c --no-ansi')" ]] ||
  fail "a root change ran: $got"

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
gate 22 GATE_TIER=full GATE_BASE="$qroot" FAIL_DOCS=1
[[ "$(verdict 22)" == "RED" ]] || fail "a red docs suite did not make the Full tier red: $(cat "$WORK/logs/integ-gate-22.log")"
grep -q '^DOCS_SUITE_EXIT=4' "$WORK/logs/integ-gate-22.log" || fail "the log does not name the red docs suite"

# A suite of mod/b was seen reading docs/d.md, which the list does not hold: the Full tier is red
# naming it, and `write` lists it.
cache="$WORK/cache"
tag="$(printf '%s\n%s' p1 mod/b | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12)"
mkdir -p "$cache/projects/m-$tag/suite-reads"
printf '%s\n' 'file ws:docs/d.md' 'file ws:mod/b/B.java' 'file ws:docs/gone.md' 'command proc:git' \
  >"$cache/projects/m-$tag/suite-reads/test-inputs-0123456789ab"
# What `jk test -s docs` alone read is the docs tier's, and never in the list.
docsel="$(printf '%s' 'suites=docs;+tag=;-tag=' | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12)"
printf '%s\n' 'file ws:README.md' 'file ws:docs/d.md' >"$cache/projects/m-$tag/suite-reads/test-inputs-$docsel"
gate 21 GATE_TIER=full GATE_BASE="$qroot" JK_CACHE_DIR="$cache"
[[ "$(verdict 21)" == "RED" ]] || fail "a doc read and not listed was not red: $(cat "$WORK/logs/integ-gate-21.log")"
grep -q 'a test reads docs/d.md, which test-reads.txt does not list' "$WORK/logs/integ-gate-21.log" ||
  fail "the log does not name the doc: $(cat "$WORK/logs/integ-gate-21.log")"
[[ "$(cd "$q" && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh reads)" == docs/d.md ]] ||
  fail "the reads were: $(cd "$q" && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh reads)"
(cd "$q" && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh write && JK_CACHE_DIR="$cache" bash scripts/gate/docs-only.sh check) ||
  fail "the list written was not current: $(cat "$q/scripts/gate/test-reads.txt")"
git -C "$q" checkout -q -- scripts/gate/test-reads.txt

# The docs tier: guard, the scoreboard check and the docs suite over a docs-only range, and nothing
# else but the quarantine's review.
dbase="$tip"
echo 3 >"$q/docs/d.md" && echo 3 >"$q/README.md" && qc docs-only
tip="$(git -C "$q" rev-parse HEAD)"
gate 17 GATE_TIER=docs GATE_BASE="$dbase"
[[ "$(verdict 17)" == "GREEN $tip" ]] || fail "a docs-only range was not green: $(cat "$WORK/logs/integ-gate-17.log")"
got="$(grep -vE '^(--version|engine status)' "$WORK/calls-17")"
[[ "$got" == "$(printf '%s\n' guard 'test -s docs -c --no-ansi')" ]] || fail "the docs tier ran: $got"
grep -q '^SCOREBOARD_EXIT=0' "$WORK/logs/integ-gate-17.log" || fail "the docs tier did not check the scoreboard"
# A docs suite that quarantines a class: green, the checkout clean, and the class goes to review.
gate 35 GATE_TIER=docs GATE_BASE="$dbase" QUARANTINE_ADD=a.DocsTest KA_FAIL=1
[[ "$(verdict 35)" == "GREEN $tip" ]] || fail "a docs tier that quarantined a class was not green: $(cat "$WORK/logs/integ-gate-35.log")"
[[ -z "$(git -C "$q" status --porcelain)" ]] || fail "the docs tier left the checkout dirty: $(git -C "$q" status --porcelain)"
grep -q '^FLAKY a.DocsTest: no ticket opened' "$WORK/logs/integ-gate-35.log" || fail "the docs tier did not review the quarantine: $(cat "$WORK/logs/integ-gate-35.log")"
gate 18 GATE_TIER=docs GATE_BASE="$dbase" FAIL_SCOREBOARD=1
[[ "$(verdict 18)" == "RED" ]] || fail "a stale scoreboard block was not red: $(cat "$WORK/logs/integ-gate-18.log")"
grep -q 'stale: docs/d.md' "$WORK/logs/integ-gate-18.log" || fail "the log does not say what is stale"
gate 23 GATE_TIER=docs GATE_BASE="$dbase" FAIL_DOCS=1
[[ "$(verdict 23)" == "RED" ]] || fail "a red docs suite passed the docs tier: $(cat "$WORK/logs/integ-gate-23.log")"
grep -q '^DOCS_SUITE_EXIT=4' "$WORK/logs/integ-gate-23.log" || fail "the log does not name the red docs suite"
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
    GATE_CHECKOUT="$WORK/outer" GATE_BRANCH=main FLAKY_LEDGER="$WORK/outer-ledger.tsv" \
    FLAKY_BASE=outer JK_QUEUE_REMOTE="$WORK/outer.git" QUARANTINE_ADD=a.OuterTest KA_FAIL=1 \
    PRE_MODULES=mod/a PRE_SCRIPTS=false POST_SCRIPTS=false FAIL_INTEG=1 \
    bash "$ROOT/scripts/test-gate.sh" >"$WORK/nested.log" 2>&1 ||
    fail "under a Queue-tier gate's environment: $(cat "$WORK/nested.log")"
fi

echo "test-gate: ok (41 gate runs, 5 of them quarantining; again under a gate's environment)"
