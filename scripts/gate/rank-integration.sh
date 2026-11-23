#!/usr/bin/env bash
# Ranks the integration classes of the modules a range changed, and picks the ones the Queue tier
# runs within a budget; the Full tier runs every class, so a class left out is deferred, never
# dropped.
#
# A class ranks by how likely the range is to break it, then by cost:
#   1. its own source changed (`edited`), or the curated lane names it (`curated`): always run;
#   2. its source names a type whose source the range changed (`uses <Type>`);
#   3. it failed, or passed only on a re-run, recently (`failed <n>x`): in `jk flaky list -O jsonl`
#      (jk's per-class history on this machine) and in the newest gate logs (matched by simple name);
#   4. the rest, cheapest first, so that as many classes as fit run.
# Within each rank the cheaper class comes first. Classes are taken from the top while the sum of
# their recorded walls stays within the budget; a class that does not fit is deferred and the next
# one is tried. A class whose wall exceeds the pole is deferred unless it must run, since the step
# lasts at least as long as its longest class. A class's wall is its `time` in the newest
# target/failsafe-reports/TEST-<class>.xml; a class with none counts as the median of the others.
#
# The suite's classes are the test sources of the module (src/test, src/e2e) that carry
# @Tag("integration"), at the class or on a method, and none of the integration suite's
# exclude-tags (root jk.toml) at the class; abstract classes are skipped.
#
# Prints one line per class, in rank order:
#   RUN|DEFER <module> <class> <seconds> <reason>
# where <seconds> is the recorded wall (`~` before it when it is the median), and <reason> is why
# it ranks where it does, or for a deferred class why it was left (`over budget`, `longer than the
# pole`). A module none of whose classes was deferred runs its whole suite, so the caller may name
# it alone.
#
# Usage: scripts/gate/rank-integration.sh [--budget <s>] [--pole <s>] [--before <epoch>]
#            <base> <tip> <module>...                (run in the checkout)
#
#   --budget  the most recorded class seconds to run across every module (default 900)
#   --pole    the longest class that runs without having to (default 90)
#   --before  read only gate logs older than this (a ranking replayed for an earlier batch)
#
#   RANK_TIMINGS      the checkout whose target/failsafe-reports hold the walls (default: this one)
#   RANK_HISTORY_DIR  the gate logs read for failures (default $GATE_LOG_DIR, else /var/tmp/jk-gate)
#   RANK_HISTORY_LOGS how many of the newest gate logs to read (default 100)
#   RANK_FLAKY        a file holding `jk flaky list -O jsonl` (default: run it; `-` reads none)
#   CURATED_REGISTRY  the curated lane's registry (default curated-integration.txt at the root)
set -euo pipefail

budget=900
pole=90
before=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --budget | --pole | --before)
      [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || {
        echo "rank-integration: $1 needs a whole number" >&2
        exit 2
      }
      case "$1" in
        --budget) budget="$2" ;;
        --pole) pole="$2" ;;
        --before) before="$2" ;;
      esac
      shift 2
      ;;
    --*)
      echo "rank-integration: unknown option $1" >&2
      exit 2
      ;;
    *) break ;;
  esac
done
[[ $# -ge 3 ]] || {
  echo "usage: rank-integration.sh [--budget <s>] [--pole <s>] [--before <epoch>] <base> <tip> <module>..." >&2
  exit 2
}
base="$1"
tip="$2"
shift 2
root="$(git rev-parse --show-toplevel)"
timings="${RANK_TIMINGS:-$root}"
history="${RANK_HISTORY_DIR:-${GATE_LOG_DIR:-/var/tmp/jk-gate}}"
registry="${CURATED_REGISTRY:-$root/curated-integration.txt}"
W="$(mktemp -d "${TMPDIR:-/tmp}/jk-rank.XXXXXX")"
trap 'rm -rf "$W"' EXIT

git diff --name-only "$base" "$tip" >"$W/changed"
# The types the range changed: each changed JVM source's file name.
sed -nE 's#^(.*/)?([A-Za-z_][A-Za-z0-9_]*)\.(java|kt|groovy)$#\2#p' "$W/changed" | sort -u >"$W/types"

# The integration suite's exclude-tags, as an alternation for the class-level check.
excluded="$(git show "$tip:jk.toml" | awk '
  /^\[test\.suites\.integration\][[:space:]]*$/ { s = 1; next }
  /^\[/ { s = 0 }
  s && /^[[:space:]]*exclude-tags[[:space:]]*=/ {
    line = $0
    while (match(line, /"[^"]+"/)) {
      out = out (out == "" ? "" : "|") substr(line, RSTART + 1, RLENGTH - 2)
      line = substr(line, RSTART + RLENGTH)
    }
  }
  END { print out }
')"

# Failures: a class's simple name once per failure seen, from jk's flaky history (each run that
# failed or passed only on a re-run), and once per gate log that names it failed: the gate's own
# `FAILED <module> <class>` and `FLAKY <module> <class> passed on` lines, and jk's
# ` | FAILED <Class>.<method>(…)` lines the log quotes.
: >"$W/failures"
flaky="${RANK_FLAKY:-}"
if [[ -z "$flaky" ]]; then
  flaky="$W/flaky.jsonl"
  (cd "$root" && "${JK:-jk}" flaky list -O jsonl 2>/dev/null </dev/null) >"$flaky" || true
fi
if [[ "$flaky" != - && -s "$flaky" ]]; then
  jq -rR 'fromjson? // empty | select(.type == "flaky")
    | (.class | split(".") | last) as $c | (.history // "") | split("") | .[] | select(. == "F" or . == "R") | $c' \
    "$flaky" >>"$W/failures" 2>/dev/null || true
fi
if [[ -d "$history" ]]; then
  find "$history" -maxdepth 1 -name 'integ-gate-*.log' -printf '%T@ %p\n' 2>/dev/null |
    awk -v b="$before" 'b == "" || $1 < b' | sort -rn | head -n "${RANK_HISTORY_LOGS:-100}" |
    cut -d' ' -f2- | while IFS= read -r log; do
    awk '
      /^FAILED [^ ]+ [^ ]+$/ || /^FLAKY [^ ]+ [^ ]+ passed on/ { n = split($3, p, "."); seen[p[n]] = 1 }
      match($0, /^[ |]*FAILED [A-Za-z_][A-Za-z0-9_$]*\./) {
        c = substr($0, RSTART, RLENGTH - 1); sub(/^[ |]*FAILED /, "", c); seen[c] = 1
      }
      END { for (c in seen) print c }
    ' "$log"
  done >>"$W/failures" || true
fi
sort "$W/failures" | uniq -c | awk '{ print $2, $1 }' >"$W/failcount"

# One row per class: module, class, edited, curated, used type, failures, seconds (blank: none).
: >"$W/rows"
for mod in "$@"; do
  git grep -lE '@Tag\("integration"\)' "$tip" -- "$mod/src/test" "$mod/src/e2e" 2>/dev/null |
    sed "s#^$tip:##" | grep -E '\.(java|kt|groovy)$' >"$W/files" || true
  : >"$W/mod-rows"
  while IFS= read -r f; do
    src="$(git show "$tip:$f")"
    grep -qE '^(public |final )*abstract class ' <<<"$src" && continue
    [[ -n "$excluded" ]] && grep -qE "^@Tag\(\"($excluded)\"\)" <<<"$src" && continue
    cls="$(sed -E 's#^.*/src/(test|e2e)/(java|kotlin|groovy)/##; s#\.(java|kt|groovy)$##; s#/#.#g' <<<"$f")"
    edited=0
    grep -qxF "$f" "$W/changed" && edited=1
    curated=0
    [[ -f "$registry" ]] && awk -F'|' -v m="$mod" -v c="$cls" '
      { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $1); gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2) }
      $1 == m && $2 == c { hit = 1 }
      END { exit !hit }
    ' "$registry" && curated=1
    used=-
    if [[ -s "$W/types" ]]; then
      used="$(grep -owF -f "$W/types" <<<"$src" | grep -vxF "${cls##*.}" | head -1 || true)"
      [[ -n "$used" ]] || used=-
    fi
    fails="$(awk -v c="${cls##*.}" '$1 == c { print $2 }' "$W/failcount")"
    secs=""
    xml="$timings/$mod/target/failsafe-reports/TEST-$cls.xml"
    [[ -f "$xml" ]] && secs="$(grep -om1 '<testsuite [^>]*' "$xml" | sed -nE 's/.* time="([0-9.]+)".*/\1/p')"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$mod" "$cls" "$edited" "$curated" "$used" "${fails:-0}" "$secs" >>"$W/mod-rows"
  done <"$W/files"
  [[ -s "$W/mod-rows" ]] || echo "rank-integration: $mod has no integration classes at $tip" >&2
  cat "$W/mod-rows" >>"$W/rows"
done

# Rank, then take from the top while the budget lasts.
awk -F'\t' -v budget="$budget" -v pole="$pole" '
  { row[NR] = $0; if ($7 != "") known[++k] = $7 + 0 }
  END {
    # The median of the recorded walls stands in for a class with none.
    for (i = 1; i <= k; i++) for (j = i + 1; j <= k; j++) if (known[j] < known[i]) { t = known[i]; known[i] = known[j]; known[j] = t }
    median = k ? known[int((k + 1) / 2)] : 30
    for (i = 1; i <= NR; i++) {
      split(row[i], f, "\t")
      secs = f[7] == "" ? median : f[7] + 0
      if (f[3] == 1) { r = 0; why = "edited" }
      else if (f[4] == 1) { r = 0; why = "curated" }
      else if (f[5] != "-") { r = 1; why = "uses " f[5] }
      else if (f[6] > 0) { r = 2; why = "failed " f[6] "x" }
      else { r = 3; why = "cost" }
      printf "%d\t%012.3f\t%s\t%s\t%s\t%s\t%s\n", r, secs, f[1], f[2], secs, f[7] == "" ? "~" : "", why
    }
  }
' "$W/rows" | sort -t$'\t' -k1,1n -k2,2 -k4,4 | awk -F'\t' -v budget="$budget" -v pole="$pole" '
  {
    secs = $5 + 0
    shown = $6 sprintf("%.0f", secs)
    if ($1 == 0) { spent += secs; print "RUN", $3, $4, shown, $7; next }
    if (secs > pole) { print "DEFER", $3, $4, shown, "longer than the pole"; next }
    if (spent + secs > budget) { print "DEFER", $3, $4, shown, "over budget"; next }
    spent += secs
    print "RUN", $3, $4, shown, $7
  }
'
