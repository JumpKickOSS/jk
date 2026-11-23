#!/usr/bin/env bash
# Where a submitted branch stands in the merge queue: waiting, gating in batch <n>, landed on
# origin/main as <sha>, or ejected with the reason and the failing classes. While the queue is paused
# (<QUEUE_REPO>/pause) a last line says so, with the pause's reason.
#
# --stats prints the last 20 landings instead (queue-timings.log, see scripts/queue/loop.sh): for
# each, ready to landed (from its submission to origin/main carrying it), the lock wait (from its
# submission to its batch taking the gate lock) and the gate, then the median of each and the
# slowest three.
#
# Usage: scripts/queue/status.sh [branch]     (default: the current branch)
#        scripts/queue/status.sh --stats
#
# Settings: see scripts/queue/common.sh.
set -euo pipefail
# shellcheck source=scripts/queue/common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

if [[ "${1:-}" == --stats ]]; then
  dur() { if [[ "$1" == - ]]; then echo -; else printf '%dm%02ds' $(($1 / 60)) $(($1 % 60)); fi; }
  median() { # of the numbers on stdin, - when there are none
    sort -n | awk '{ a[NR] = $1 } END { if (!NR) print "-"; else if (NR % 2) print a[(NR + 1) / 2]; else print int((a[NR / 2] + a[NR / 2 + 1]) / 2) }'
  }
  # One row per landing: branch, batch, then ready to landed, lock wait and gate in seconds.
  rows="$(queue_sh "cat queue-timings.log 2>/dev/null || true" | awk '
    $2 == "landed" {
      total = wait = gate = "-"
      if ($8 != "-") { total = $1 - $8; wait = $10 > $8 ? $10 - $8 : 0 }
      if ($12 != "-" && $13 != "-") gate = $13 - $12
      print $3, $6, total, wait, gate
    }' | tail -20)"
  if [[ -z "$rows" ]]; then
    echo "no landings recorded yet"
    exit 0
  fi
  printf '%-28s %6s  %15s  %9s  %9s\n' branch batch "ready to landed" "lock wait" gate
  while read -r b n t w g; do
    printf '%-28s %6s  %15s  %9s  %9s\n' "$b" "$n" "$(dur "$t")" "$(dur "$w")" "$(dur "$g")"
  done <<<"$rows"
  col() { awk -v c="$1" '$c != "-" { print $c }' <<<"$rows" | median; }
  printf '%-28s %6s  %15s  %9s  %9s\n' "median of $(($(wc -l <<<"$rows")))" "" "$(dur "$(col 3)")" "$(dur "$(col 4)")" "$(dur "$(col 5)")"
  echo "slowest:"
  awk '$3 != "-"' <<<"$rows" | sort -k3,3nr | head -3 | while read -r b n t _; do
    echo "  $b (batch $n) $(dur "$t")"
  done
  exit 0
fi

branch="${1:-$(git symbolic-ref --short HEAD 2>/dev/null)}"
[[ -n "$branch" ]] || { echo "status: not on a branch; name one" >&2; exit 2; }
b="$(printf %q "$branch")"
# The submitted tip (empty when nothing waits), the pause (empty when the queue is not paused), then
# the queue's record of the branch.
out="$(queue_sh "git rev-parse -q --verify refs/queue/$b || echo; if [ -f pause ]; then echo \"paused: \$(head -1 pause | grep . || echo 'no reason given')\"; else echo; fi; cat queue-state/$b 2>/dev/null || true")"
ref="$(sed -n 1p <<<"$out")"
pause="$(sed -n 2p <<<"$out")"
rec="$(tail -n +3 <<<"$out")"
read -r state sha _ <<<"$(head -1 <<<"$rec")"

rc=0
if [[ -n "$ref" && "${sha:-}" != "$ref" ]]; then
  echo "$branch: waiting ($(cut -c1-12 <<<"$ref"))"
elif [[ -n "${state:-}" ]]; then
  echo "$branch: $rec"
else
  echo "$branch: not in the queue"
  rc=1
fi
[[ -z "$pause" ]] || echo "the queue is $pause; it starts no batch until the pause file is removed"
exit "$rc"
