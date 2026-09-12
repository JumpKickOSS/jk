#!/usr/bin/env bash
# Does jk's integration profile run what Gradle's integration tier runs, with the same verdicts?
#
# Both builds write JUnit XML, one file per test class. This script runs each tier on the
# checked-out tree, reads every class it produced, and reports:
#   - classes only one side ran,
#   - classes both ran with different verdicts (pass / fail / all-skipped).
# Any line under either heading exits 1. Per-class test counts are printed for the reader and
# never fail the comparison: the two runners count parameterized and nested cases differently.
#
# Usage:
#   scripts/test-tier-parity.sh            # run both tiers, then compare
#   scripts/test-tier-parity.sh gradle     # run Gradle's tier only, keep its results
#   scripts/test-tier-parity.sh jk         # run jk's profile only, keep its results
#   scripts/test-tier-parity.sh compare    # compare results already on disk
#
# Environment:
#   JK            jk client to run (default: jk on PATH)
#   GRADLE_ARGS   extra arguments for ./gradlew (e.g. --max-workers=2)
#   PARITY_OUT    where the two class lists and the report land (default: target/tier-parity)
#
# A red test class is a verdict, not a script failure: both runs continue past failures so the
# comparison sees the whole class set.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
out="${PARITY_OUT:-$root/target/tier-parity}"
jk_bin="${JK:-jk}"
mode="${1:-all}"
mkdir -p "$out"

# Every "TEST-*.xml" under a results directory → "class<TAB>verdict<TAB>tests" lines, one per
# class, sorted. Verdict: FAIL when any test failed or errored, SKIP when every test was skipped,
# else PASS.
summarize() {
  python3 - "$@" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
pattern, dest = sys.argv[1], sys.argv[2]
rows = {}
for path in glob.glob(pattern, recursive=True):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"unreadable {path}: {e}", file=sys.stderr)
        continue
    name = suite.get("name") or ""
    tests = int(suite.get("tests") or 0)
    failed = int(suite.get("failures") or 0) + int(suite.get("errors") or 0)
    skipped = int(suite.get("skipped") or 0)
    verdict = "FAIL" if failed else ("SKIP" if tests and skipped == tests else "PASS")
    prev = rows.get(name)
    if prev:  # the same class reported twice (two modules, or a stale file): keep the worse verdict
        tests += prev[1]
        verdict = "FAIL" if "FAIL" in (verdict, prev[0]) else ("PASS" if "PASS" in (verdict, prev[0]) else verdict)
    rows[name] = (verdict, tests)
with open(dest, "w") as f:
    for name in sorted(rows):
        f.write(f"{name}\t{rows[name][0]}\t{rows[name][1]}\n")
print(f"{len(rows)} classes -> {dest}")
PY
}

run_gradle() {
  echo "== Gradle integrationTest"
  # Results from an earlier tier run would be read as this run's: clear them first.
  find "$root" -type d -path '*/build/test-results/integrationTest' -not -path "$root/target/*" \
    -exec rm -rf {} + 2>/dev/null || true
  (cd "$root" && ./gradlew integrationTest --continue --no-daemon ${GRADLE_ARGS:-}) || echo "Gradle tier finished red (verdicts are compared below)"
  summarize "$root/**/build/test-results/integrationTest/TEST-*.xml" "$out/gradle.tsv"
}

run_jk() {
  echo "== jk test --profile integration"
  # jk appends to target/**/reports/test-results across runs and tiers; only this run may count.
  find "$root/target" -type d -path '*/reports/test-results' -exec rm -rf {} + 2>/dev/null || true
  (cd "$root" && "$jk_bin" test --profile integration) || echo "jk tier finished red (verdicts are compared below)"
  summarize "$root/target/**/reports/test-results/TEST-*.xml" "$out/jk.tsv"
}

compare() {
  [ -s "$out/gradle.tsv" ] || { echo "no Gradle results at $out/gradle.tsv — run the gradle side first" >&2; exit 2; }
  [ -s "$out/jk.tsv" ] || { echo "no jk results at $out/jk.tsv — run the jk side first" >&2; exit 2; }
  python3 - "$out/gradle.tsv" "$out/jk.tsv" "$out/report.txt" <<'PY'
import sys
def load(p):
    rows = {}
    for line in open(p):
        name, verdict, tests = line.rstrip("\n").split("\t")
        rows[name] = (verdict, int(tests))
    return rows
gradle, jk = load(sys.argv[1]), load(sys.argv[2])
only_gradle = sorted(set(gradle) - set(jk))
only_jk = sorted(set(jk) - set(gradle))
both = sorted(set(gradle) & set(jk))
differ = [(c, gradle[c][0], jk[c][0]) for c in both if gradle[c][0] != jk[c][0]]
counts = [(c, gradle[c][1], jk[c][1]) for c in both if gradle[c][1] != jk[c][1]]
lines = [f"classes: gradle {len(gradle)}, jk {len(jk)}, both {len(both)}"]
lines.append(f"\nonly Gradle ran ({len(only_gradle)}):")
lines += [f"  {c}  [{gradle[c][0]}]" for c in only_gradle]
lines.append(f"\nonly jk ran ({len(only_jk)}):")
lines += [f"  {c}  [{jk[c][0]}]" for c in only_jk]
lines.append(f"\nverdict differs ({len(differ)}):")
lines += [f"  {c}  gradle {g}, jk {j}" for c, g, j in differ]
lines.append(f"\ntest count differs, not a failure ({len(counts)}):")
lines += [f"  {c}  gradle {g}, jk {j}" for c, g, j in counts]
report = "\n".join(lines) + "\n"
open(sys.argv[3], "w").write(report)
print(report, end="")
bad = len(only_gradle) + len(only_jk) + len(differ)
print("tier parity: " + ("HOLDS" if bad == 0 else f"BROKEN — {bad} difference(s)"))
sys.exit(1 if bad else 0)
PY
}

case "$mode" in
  all) run_gradle; run_jk; compare ;;
  gradle) run_gradle ;;
  jk) run_jk ;;
  compare) compare ;;
  *) echo "usage: $0 [all|gradle|jk|compare]" >&2; exit 2 ;;
esac
