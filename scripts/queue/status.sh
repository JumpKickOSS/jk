#!/usr/bin/env bash
# Where a submitted branch stands in the merge queue: waiting, gating in batch <n>, landed on
# origin/main as <sha>, or ejected with the reason and the failing classes. While the queue is paused
# (<QUEUE_REPO>/pause) a last line says so, with the pause's reason.
#
# Usage: scripts/queue/status.sh [branch]     (default: the current branch)
#
# Settings: see scripts/queue/common.sh.
set -euo pipefail
# shellcheck source=scripts/queue/common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

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
