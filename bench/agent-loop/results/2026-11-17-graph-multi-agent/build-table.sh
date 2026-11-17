#!/usr/bin/env bash
# TABLE.md from the rows in this folder: daybreak's tiers (the measurement), then bocabox's tier 0 (a second
# host, never pooled with daybreak's).   ./build-table.sh > TABLE.md
cd "$(dirname "$0")"
cat <<'HEAD'
# Graph bench across agents: banked table (2026-11-17, daybreak WSL)

Arms: **A** `jk` (0.52.0 graph), **B** `jk-before` (0.51.1), **C** `jk-nograph` (no graph tools; see README.md).
Medians over runs that reported tokens; for the arm rows, all of the tier's tasks pooled. Est. cost is the API
list price of reported tokens (an API-equivalent estimate for subscription CLIs; README.md says how cache warmth
moves it). Savings: positive means A lower. A row with "1 run" has no noise estimate.

Every table names its host. **daybreak** (Windows 11 + WSL Ubuntu 26.04, 12 GB) is the measurement host; tier 0
was first run on **bocabox** (Fedora) and is kept below as a second host's table, never pooled with daybreak's.
HEAD
args=()
[ -f rows-tier0-daybreak.jsonl ] && args+=("Tier 0 on daybreak (smoke fixture, 1 run per cell)=rows-tier0-daybreak.jsonl")
[ -f rows-tier1-daybreak.jsonl ] && args+=("Tier 1 on daybreak (3 runs per cell, interleaved)=rows-tier1-daybreak.jsonl")
[ -f rows-tier2-daybreak.jsonl ] && args+=("Tier-2 sliver on daybreak (3 runs per cell, interleaved)=rows-tier2-daybreak.jsonl")
echo; echo "Status: $(cat STATUS 2>/dev/null)"
echo
echo "**Void rows are in no table** (README.md, \"Defects found after tier 0\"): daybreak's 40 tier-0 B rows ran 0.52.0"
echo "(rows-tier0-daybreak-VOID-B-ran-0.52.0.jsonl), and its 36 tier-1 codex rows ran without a codex login"
echo "(rows-tier1-daybreak-VOID-codex-login.jsonl). Redone rows replace them as they are run."
python3 tabulate.py "${args[@]}" | sed 's/^## Totals (all tiers so far)/## Totals on daybreak (all tiers so far)/'
echo; echo "# Second host: bocabox (Fedora), tier 0 only"; echo
echo "The same 120 cells on bocabox, run before the bench moved. Not pooled with daybreak. The 55 tier-1 rows bocabox"
echo "wrote before the move (of 180) are kept raw in rows-tier1-bocabox-partial.jsonl and are not used in any table."
echo; echo "**Tier 0's B on bocabox is not 0.51.1:** the jk-before home updated itself to 0.52.0 two minutes in. 38 of its 42"
echo "rows ran 0.52.0; only Sonnet's callers, rename and rename-field ran 0.51.1. So its A-vs-B rows are run-to-run noise"
echo "between identical builds (README.md, Findings)."
python3 tabulate.py "Tier 0 on bocabox (smoke fixture, 1 run per cell)=rows-tier0-bocabox.jsonl" \
  | sed 's/^## /### /; s/^### Totals (all tiers so far)/### Totals on bocabox (tier 0)/'
