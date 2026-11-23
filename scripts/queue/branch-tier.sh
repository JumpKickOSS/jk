#!/usr/bin/env bash
# The Branch tier, the price of submitting a branch to the merge queue: `jk guard`, then the fast
# tier of the modules the branch reaches: `jk test --exclusive` over the modules
# `--affected-since <merge-base>` selects, those changed since the branch left origin/main and
# every module that depends on them (all of them when a file outside every module changed). The
# modules they build on compile, main and test, but their suites do not run, and nothing is
# packaged past the jars the tests read: no native client. Then the tree suite (`jk test -s tree`),
# the tests that scan every module's sources, which no module's fast tier holds, and a warning when
# those runs read a doc scripts/gate/test-reads.txt lacks. A pass is recorded
# as a note on HEAD under refs/notes/branch-tier naming HEAD's tree, which scripts/queue/submit.sh
# requires. The working tree must be clean, so what passed is what HEAD holds.
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
root="$(git rev-parse --show-toplevel)"

started=$SECONDS
jk guard || fail "jk guard is red"
[[ -z "$(git status --porcelain)" ]] || fail "jk guard formatted files; commit them and run again"
guard_s=$((SECONDS - started))

# One line per selected module, `<coordinates><TAB><jar path>`; the module is the jar's target/'s
# parent, named relative to the checkout (Windows paths print with backslashes).
started=$SECONDS
jars="$(jk show package-jar -e "$base")" || fail "jk could not list the modules changed since $base"
modules="$(printf '%s\n' "$jars" | awk -F'\t' -v root="$root/" 'NF == 2 {
  dir = $2; gsub(/\\/, "/", dir); sub("/target/[^/]*$", "", dir)
  if (index(dir, root) != 1) { print "outside " dir > "/dev/stderr"; exit 1 }
  print substr(dir, length(root) + 1)
}' | sort -u | paste -sd, -)" || fail "a selected module is outside $root"
if [[ -n "$modules" ]]; then
  jk test --exclusive -m "$modules" || fail "the fast tier of the modules the branch reaches is red"
  # The tests that scan every module's sources are in no module's fast tier, and any change can break them.
  jk test -s tree || fail "the tree suite (the tests that scan every module's sources) is red"
else
  echo "branch-tier: no module changed since $(git rev-parse --short "$base"); no tests to run"
fi
test_s=$((SECONDS - started))

# The suites just run recorded the docs they read; one test-reads.txt lacks would turn the Full tier
# red. A warning, not a refusal: the recordings may hold another checkout's runs on this host.
if [[ -n "$modules" ]] && ! bash "$root/scripts/gate/docs-only.sh" check; then
  echo "branch-tier: warning: test-reads.txt lacks a doc a test read; the Full tier fails on it" >&2
fi

git notes --ref=branch-tier add -f -m "passed $(git rev-parse 'HEAD^{tree}') on $(hostname) at $(date -u +%Y-%m-%dT%H:%M:%SZ)" HEAD
echo "branch-tier: $(git rev-parse --short HEAD) passed in $((guard_s + test_s))s (guard ${guard_s}s, tests ${test_s}s); submit it with scripts/queue/submit.sh"
