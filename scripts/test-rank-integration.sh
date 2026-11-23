#!/usr/bin/env bash
# Network-free fixtures for scripts/gate/rank-integration.sh: an edited or curated class always
# runs; then a class naming a changed type, then a class that failed recently (jk's flaky history
# or a gate log's FAILED and FLAKY lines and jk's quoted ones, once per log), then the rest
# cheapest first, while their recorded walls fit the budget; a class longer than the pole, or past
# the budget, is deferred; a class with no recorded wall counts as the median; slow-tagged and
# abstract classes are not the suite's; a replay with --before reads only older gate logs; bad
# arguments are refused by name.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-rank-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
RANK="$ROOT/scripts/gate/rank-integration.sh"

fail() {
  echo "test-rank-integration: $*" >&2
  exit 1
}

r="$WORK/repo"
t="$r/mod/a/src/test/java/a"
mkdir -p "$t" "$r/mod/a/src/main/java/a" "$r/mod/a/target/failsafe-reports" "$WORK/logs"
cat >"$r/jk.toml" <<'EOF'
[workspace]
modules = ["mod/a"]

[test.suites.integration]
tags = ["integration"]
exclude-tags = ["slow", "release"]
EOF
echo 'mod/a | a.CuratedTest | wire | success | The lane.' >"$r/curated-integration.txt"
echo 'package a; public class Engine {}' >"$r/mod/a/src/main/java/a/Engine.java"
# test <name> <body>: an integration class of mod/a.
test_class() { printf 'package a;\n@Tag("integration")\nclass %s {\n%s\n}\n' "$1" "${2:-}" >"$t/$1.java"; }
test_class CuratedTest
test_class EditedTest
test_class UsesTest '    Engine engine;'
test_class FlakyTest
test_class LoggedTest
test_class CheapTest
test_class DearTest
test_class PoleTest
test_class UntimedTest
test_class QuotedTest
printf 'package a;\n@Tag("integration")\n@Tag("slow")\nclass SlowTest {}\n' >"$t/SlowTest.java"
printf 'package a;\n@Tag("integration")\npublic abstract class BaseTest {}\n' >"$t/BaseTest.java"
printf 'package a;\nclass UnitTest {}\n' >"$t/UnitTest.java"
# wall <class> <seconds>: its failsafe report.
wall() { printf '<?xml version="1.0"?>\n<testsuite name="a.%s" tests="1" time="%s">\n</testsuite>\n' "$1" "$2" >"$r/mod/a/target/failsafe-reports/TEST-a.$1.xml"; }
wall CuratedTest 50
wall EditedTest 40
wall UsesTest 30
wall FlakyTest 20
wall LoggedTest 25
wall CheapTest 5
wall DearTest 60
wall PoleTest 200
wall QuotedTest 1
git -C "$r" init -q
rc() { git -C "$r" add -A && git -C "$r" -c user.name=t -c user.email=t@example.invalid commit -qm "$1"; }
rc base
base="$(git -C "$r" rev-parse HEAD)"
echo 'package a; public class Engine { int x; }' >"$r/mod/a/src/main/java/a/Engine.java"
test_class EditedTest '    int changed;'
rc change
tip="$(git -C "$r" rev-parse HEAD)"

echo '{"schema":1,"type":"flaky","class":"a.FlakyTest","module":"mod/a","quarantined":false,"flakes":1,"runs":3,"history":".R."}' >"$WORK/flaky.jsonl"
# The gate's own line and jk's quoted one name LoggedTest in one log: one failure. An older log
# quotes jk's line alone for QuotedTest.
printf '%s\n' 'tip abc' 'FAILED cc:a a.LoggedTest' ' | FAILED LoggedTest.fails(Path)  [w2]' \
  'FLAKY the quarantine on abc is current' >"$WORK/logs/integ-gate-7.log"
printf '%s\n' 'tip abd' ' | FAILED QuotedTest.fails_too(Path)  [w7]' >"$WORK/logs/integ-gate-f3.log"
touch -d '2026-01-02 00:00:00' "$WORK/logs/integ-gate-7.log" "$WORK/logs/integ-gate-f3.log"

rank() { (cd "$r" && RANK_FLAKY="$WORK/flaky.jsonl" RANK_HISTORY_DIR="$WORK/logs" bash "$RANK" "$@"); }

# ---- the order, and the budget: 50+40 must run, then 30, 1, 20, 25 and 5 fit 171; 30 and 60 do not
got="$(rank --budget 171 --pole 90 "$base" "$tip" mod/a)"
want="$(
  cat <<'EOF'
RUN mod/a a.EditedTest 40 edited
RUN mod/a a.CuratedTest 50 curated
RUN mod/a a.UsesTest 30 uses Engine
RUN mod/a a.QuotedTest 1 failed 1x
RUN mod/a a.FlakyTest 20 failed 1x
RUN mod/a a.LoggedTest 25 failed 1x
RUN mod/a a.CheapTest 5 cost
DEFER mod/a a.UntimedTest ~30 over budget
DEFER mod/a a.DearTest 60 over budget
DEFER mod/a a.PoleTest 200 longer than the pole
EOF
)"
[[ "$got" == "$want" ]] || fail "the ranking was:
$got
want:
$want"

# ---- a budget that holds everything but the pole runs the rest -------------------------------------
got="$(rank --budget 10000 --pole 90 "$base" "$tip" mod/a | awk '$1 == "DEFER" { print $3 }')"
[[ "$got" == "a.PoleTest" ]] || fail "with room for all, the deferred classes were: $got"
got="$(rank --budget 10000 --pole 500 "$base" "$tip" mod/a | awk '$1 == "DEFER"')"
[[ -z "$got" ]] || fail "with room for all and a long pole, classes were deferred: $got"

# ---- a replay before the gate log was written does not see its failure ------------------------
got="$(rank --budget 10000 --pole 500 --before "$(date -d '2026-01-01' +%s)" "$base" "$tip" mod/a | grep LoggedTest)"
[[ "$got" == "RUN mod/a a.LoggedTest 25 cost" ]] || fail "a replay before the log read it: $got"

# ---- a module with no integration classes says so and ranks nothing ----------------------------
mkdir -p "$r/mod/b/src/test/java/b" && echo 'package b; class BTest {}' >"$r/mod/b/src/test/java/b/BTest.java" && rc b
got="$(cd "$r" && RANK_FLAKY=- RANK_HISTORY_DIR="$WORK/logs" bash "$RANK" "$base" HEAD mod/b 2>"$WORK/b.err")"
[[ -z "$got" ]] || fail "mod/b ranked: $got"
grep -q 'mod/b has no integration classes' "$WORK/b.err" || fail "mod/b's emptiness is not named: $(cat "$WORK/b.err")"

# ---- bad arguments are refused by name -----------------------------------------------------------
if rank --budget lots "$base" "$tip" mod/a >"$WORK/arg.log" 2>&1; then fail "a budget of 'lots' was accepted"; fi
grep -q -- '--budget needs a whole number' "$WORK/arg.log" || fail "the bad budget is not named: $(cat "$WORK/arg.log")"
if rank --bogus "$base" "$tip" mod/a >"$WORK/arg.log" 2>&1; then fail "an unknown option was accepted"; fi
grep -q 'unknown option --bogus' "$WORK/arg.log" || fail "the unknown option is not named: $(cat "$WORK/arg.log")"
if rank "$base" "$tip" >"$WORK/arg.log" 2>&1; then fail "no module was accepted"; fi
grep -q '^usage:' "$WORK/arg.log" || fail "no usage for a missing module: $(cat "$WORK/arg.log")"

echo "test-rank-integration: ok"
