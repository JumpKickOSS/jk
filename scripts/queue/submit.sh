#!/usr/bin/env bash
# Submit a branch to the merge queue, the only writer to origin/main: its tip goes to the queue's
# repository as refs/queue/<branch>, replacing an earlier submission of the same branch. A tip
# that has not passed the Branch tier (scripts/queue/branch-tier.sh) is refused.
#
# Usage: scripts/queue/submit.sh [branch]     (default: the current branch)
#
# Settings: see scripts/queue/common.sh.
set -euo pipefail
# shellcheck source=scripts/queue/common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

fail() {
  echo "submit: $*" >&2
  exit 1
}

branch="${1:-$(git symbolic-ref --short HEAD 2>/dev/null)}" || fail "not on a branch; name one"
[[ -n "$branch" ]] || fail "not on a branch; name one"
tip="$(git rev-parse -q --verify "refs/heads/$branch^{commit}")" || fail "no branch $branch here"
[[ "$branch" != main ]] || fail "main is what the queue writes; submit a work branch"

note="$(git notes --ref=branch-tier show "$tip" 2>/dev/null || true)"
read -r word tree _ <<<"$note"
if [[ "${word:-}" != passed || "${tree:-}" != "$(git rev-parse "$tip^{tree}")" ]]; then
  fail "$branch ($(git rev-parse --short "$tip")) has not passed the Branch tier; run scripts/queue/branch-tier.sh on it"
fi

git push -q "$QUEUE_REMOTE" "+$tip:refs/queue/$branch" || fail "the push to $QUEUE_REMOTE failed"
# What the queue said about an earlier submission (an ejection of this same tip) no longer holds.
# The tip is ready now, by the queue host's clock: queue-ready/<branch> holds `<sha> <epoch seconds>`.
b="$(printf %q "$branch")"
queue_sh "rm -f queue-state/$b && mkdir -p \"\$(dirname queue-ready/$b)\" && echo $tip \$(date +%s) >queue-ready/$b.tmp && mv queue-ready/$b.tmp queue-ready/$b"
echo "submit: $branch ($(git rev-parse --short "$tip")) is waiting; scripts/queue/status.sh $branch"
