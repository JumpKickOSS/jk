#!/usr/bin/env bash
# One GitHub annotation per `jk audit` finding, from the machine view the audit wrote.
#
# Usage:
#   scripts/ci-audit-annotate.sh <audit.jsonl> <threshold>
# <audit.jsonl> is the stdout of `jk audit --output json` (plan events and one `audit-finding` line
# per finding); <threshold> is the --severity the audit gated at (CRITICAL|HIGH|MEDIUM|LOW). A
# finding at or above the threshold that no unexpired [audit] ignore entry covers is an ::error
# — the ones that failed the step, each attributed to its advisory; one below the threshold is a
# ::warning; a covered one is a ::notice carrying the entry's reason. An advisory OSV gave no
# severity counts at every threshold, as the audit itself counts it. The same table lands in the
# run summary when GITHUB_STEP_SUMMARY is set.
#
# The exit status is always 0: the audit's own exit status is the verdict, and this script only
# says which findings produced it. A missing file is a notice, since the audit then failed
# before it wrote a report.
set -euo pipefail

REPORT="${1:?usage: ci-audit-annotate.sh <audit.jsonl> <threshold>}"
THRESHOLD="${2:?usage: ci-audit-annotate.sh <audit.jsonl> <threshold>}"

rank() {
  case "$1" in
    CRITICAL) echo 4 ;;
    HIGH) echo 3 ;;
    MEDIUM) echo 2 ;;
    LOW) echo 1 ;;
    *) echo 99 ;; # UNKNOWN: OSV gave no label, so it counts at every threshold
  esac
}
floor="$(rank "$THRESHOLD")"
if [[ "$floor" == 99 ]]; then
  echo "ci-audit-annotate: '$THRESHOLD' is not a severity (CRITICAL|HIGH|MEDIUM|LOW)" >&2
  exit 2
fi

if [[ ! -f "$REPORT" ]]; then
  echo "::notice::jk audit wrote no report at $REPORT — it failed before querying OSV"
  exit 0
fi

blocking=0
below=0
ignored=0
rows=()
while IFS=$'\t' read -r id pkg version severity summary fixed_in is_ignored reason; do
  [[ -n "$id" ]] || continue
  where="$pkg@$version"
  detail="$severity: $summary"
  [[ -n "$fixed_in" ]] && detail="$detail — fixed in $fixed_in"
  if [[ "$is_ignored" == "true" ]]; then
    ignored=$((ignored + 1))
    echo "::notice title=$id::$where ignored ($reason) — $detail"
    rows+=("| $id | $where | $severity | ignored: $reason |")
  elif [[ "$(rank "$severity")" -ge "$floor" ]]; then
    blocking=$((blocking + 1))
    echo "::error title=$id::$where $detail; jk audit --output json prints the finding, [audit] ignore in jk.toml accepts it with a reason"
    rows+=("| $id | $where | $severity | **blocking** |")
  else
    below=$((below + 1))
    echo "::warning title=$id::$where $detail (below $THRESHOLD)"
    rows+=("| $id | $where | $severity | below $THRESHOLD |")
  fi
done < <(jq -r 'select(.type == "audit-finding")
  | [.id, .package, .version, .severity, (.summary // "" | gsub("[\\r\\n\\t]"; " ")), (.fixedIn // ""),
     (if .ignored then "true" else "false" end), (.reason // "")] | @tsv' "$REPORT")

total=$((blocking + below + ignored))
verdict="jk audit --severity $THRESHOLD: $total finding(s) — $blocking blocking, $below below the threshold, $ignored ignored"
echo "$verdict"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### $verdict"
    if [[ ${#rows[@]} -gt 0 ]]; then
      echo
      echo "| Advisory | Package | Severity | Verdict |"
      echo "|---|---|---|---|"
      printf '%s\n' "${rows[@]}"
    fi
  } >>"$GITHUB_STEP_SUMMARY"
fi
