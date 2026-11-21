#!/usr/bin/env bash
# The Branch tier, the price of submitting a branch to the merge queue: `jk guard`, then the fast
# tier of the modules changed since the branch left origin/main (`jk build -e <merge-base>`). A
# pass is recorded as a note on HEAD under refs/notes/branch-tier naming HEAD's tree, which
# scripts/queue/submit.sh requires. The working tree must be clean, so what passed is what HEAD holds.
#
# Usage: scripts/queue/branch-tier.sh
#
#   BRANCH_TIER_BASE  the ref the branch is measured against (default origin/main)
set -euo pipefail

fail() {
  echo "branch-tier: $*" >&2
  exit 1
}

[[ -z "$(git status --porcelain)" ]] || fail "the working tree is not clean; commit first"
base="$(git merge-base HEAD "${BRANCH_TIER_BASE:-origin/main}")" || fail "no merge base with ${BRANCH_TIER_BASE:-origin/main}"

jk guard || fail "jk guard is red"
[[ -z "$(git status --porcelain)" ]] || fail "jk guard formatted files; commit them and run again"
jk build -e "$base" || fail "the fast tier of the changed modules is red"

git notes --ref=branch-tier add -f -m "passed $(git rev-parse 'HEAD^{tree}') on $(hostname) at $(date -u +%Y-%m-%dT%H:%M:%SZ)" HEAD
echo "branch-tier: $(git rev-parse --short HEAD) passed; submit it with scripts/queue/submit.sh"
