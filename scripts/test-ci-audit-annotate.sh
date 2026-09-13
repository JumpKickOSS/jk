#!/usr/bin/env bash
# Fixtures for scripts/ci-audit-annotate.sh over a `jk audit --output json` transcript: a plan
# event is skipped, a finding at or above the threshold is an ::error, one below it a ::warning,
# one an [audit] ignore entry covers a ::notice with the reason, an expired entry counts again,
# an unlabelled severity blocks at every threshold, the summary table is written, a missing report
# is a notice, and the exit status is 0 throughout because the audit's exit is the verdict.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-audit-annotate.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
SCRIPT="$ROOT/scripts/ci-audit-annotate.sh"

cat >"$WORK/audit.jsonl" <<'EOF'
{"schema":1,"ts":1,"type":"buildplan-start","plan":"audit","denominator":3,"tasks":3,"progress":null}
{"schema":1,"ts":2,"type":"audit-finding","id":"GHSA-high-0001","package":"com.example:lib","version":"1.0","severity":"HIGH","summary":"Deserialization of untrusted data","fixedIn":"1.1","ignored":false}
{"schema":1,"ts":3,"type":"audit-finding","id":"CVE-2025-0002","package":"com.example:other","version":"2.0","severity":"MEDIUM","summary":"Path traversal","ignored":false}
{"schema":1,"ts":4,"type":"audit-finding","id":"GHSA-crit-0003","package":"com.example:core","version":"3.0","severity":"CRITICAL","summary":"RCE\twith a tab","fixedIn":"3.1","ignored":true,"reason":"test-only dependency","until":"2099-01-01"}
{"schema":1,"ts":5,"type":"audit-finding","id":"GHSA-lapsed-0004","package":"com.example:old","version":"4.0","severity":"HIGH","summary":"Lapsed ignore","ignored":false,"reason":"was waiting on upstream","until":"2020-01-01","ignoreExpired":true}
{"schema":1,"ts":6,"type":"audit-finding","id":"OSV-unlabelled-0005","package":"com.example:odd","version":"5.0","severity":"UNKNOWN","summary":"No severity from OSV","ignored":false}
{"schema":1,"ts":7,"type":"buildplan-finish","plan":"audit","success":false,"duration_ms":0,"warnings":0,"errors":1,"progress":100}
EOF

count() { grep -c -- "$1" "$2" || true; }

# HIGH threshold: two HIGH (one via a lapsed ignore) + the unlabelled one block; MEDIUM warns; the covered CRITICAL is a notice.
export GITHUB_STEP_SUMMARY="$WORK/summary.md"
"$SCRIPT" "$WORK/audit.jsonl" HIGH >"$WORK/high.out" 2>"$WORK/high.err"
[[ "$(count '^::error ' "$WORK/high.out")" == 3 ]] || { cat "$WORK/high.out"; echo "test-ci-audit-annotate: expected 3 ::error at HIGH" >&2; exit 1; }
[[ "$(count '^::warning ' "$WORK/high.out")" == 1 ]] || { cat "$WORK/high.out"; echo "test-ci-audit-annotate: expected 1 ::warning at HIGH" >&2; exit 1; }
[[ "$(count '^::notice ' "$WORK/high.out")" == 1 ]] || { cat "$WORK/high.out"; echo "test-ci-audit-annotate: expected 1 ::notice at HIGH" >&2; exit 1; }
grep -q '^::error title=GHSA-high-0001::com.example:lib@1.0 HIGH: Deserialization of untrusted data — fixed in 1.1' "$WORK/high.out" \
  || { cat "$WORK/high.out"; echo "test-ci-audit-annotate: the HIGH annotation lacks package, version, severity, summary or fixed version" >&2; exit 1; }
grep -q '^::error title=GHSA-lapsed-0004::' "$WORK/high.out" || { echo "test-ci-audit-annotate: a lapsed ignore must count again" >&2; exit 1; }
grep -q '^::error title=OSV-unlabelled-0005::' "$WORK/high.out" || { echo "test-ci-audit-annotate: an unlabelled severity must block" >&2; exit 1; }
grep -q '^::notice title=GHSA-crit-0003::com.example:core@3.0 ignored (test-only dependency)' "$WORK/high.out" \
  || { cat "$WORK/high.out"; echo "test-ci-audit-annotate: the covered finding must carry its reason" >&2; exit 1; }
grep -q 'RCE with a tab' "$WORK/high.out" || { echo "test-ci-audit-annotate: a tab in a summary must not split the record" >&2; exit 1; }
grep -q '5 finding(s) — 3 blocking, 1 below the threshold, 1 ignored' "$WORK/high.out" || { cat "$WORK/high.out"; echo "test-ci-audit-annotate: wrong verdict line" >&2; exit 1; }
grep -q '^| GHSA-high-0001 | com.example:lib@1.0 | HIGH | \*\*blocking\*\* |' "$GITHUB_STEP_SUMMARY" \
  || { cat "$GITHUB_STEP_SUMMARY"; echo "test-ci-audit-annotate: the summary table lacks the blocking row" >&2; exit 1; }

# LOW threshold: every unignored finding blocks.
"$SCRIPT" "$WORK/audit.jsonl" LOW >"$WORK/low.out" 2>&1
[[ "$(count '^::error ' "$WORK/low.out")" == 4 ]] || { cat "$WORK/low.out"; echo "test-ci-audit-annotate: expected 4 ::error at LOW" >&2; exit 1; }
[[ "$(count '^::warning ' "$WORK/low.out")" == 0 ]] || { echo "test-ci-audit-annotate: nothing is below LOW" >&2; exit 1; }

# CRITICAL threshold: only the unlabelled one blocks (the CRITICAL is covered).
"$SCRIPT" "$WORK/audit.jsonl" CRITICAL >"$WORK/crit.out" 2>&1
[[ "$(count '^::error ' "$WORK/crit.out")" == 1 ]] || { cat "$WORK/crit.out"; echo "test-ci-audit-annotate: expected 1 ::error at CRITICAL" >&2; exit 1; }

# A clean transcript: no annotations, a zero verdict.
grep -v audit-finding "$WORK/audit.jsonl" >"$WORK/clean.jsonl"
"$SCRIPT" "$WORK/clean.jsonl" HIGH >"$WORK/clean.out" 2>&1
[[ "$(count '^::' "$WORK/clean.out")" == 0 ]] || { cat "$WORK/clean.out"; echo "test-ci-audit-annotate: a clean audit must annotate nothing" >&2; exit 1; }
grep -q '0 finding(s) — 0 blocking' "$WORK/clean.out" || { echo "test-ci-audit-annotate: wrong clean verdict" >&2; exit 1; }

# No report: the audit failed before OSV answered; a notice, exit 0.
"$SCRIPT" "$WORK/absent.jsonl" HIGH >"$WORK/absent.out" 2>&1
grep -q '^::notice::jk audit wrote no report' "$WORK/absent.out" || { cat "$WORK/absent.out"; echo "test-ci-audit-annotate: a missing report must be a notice" >&2; exit 1; }

# A threshold that is not a severity is refused.
if "$SCRIPT" "$WORK/audit.jsonl" SEVERE >/dev/null 2>&1; then
  echo "test-ci-audit-annotate: 'SEVERE' was accepted as a threshold" >&2
  exit 1
fi

echo "test-ci-audit-annotate: ok"
