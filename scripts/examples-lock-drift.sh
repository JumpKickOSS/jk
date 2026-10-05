#!/usr/bin/env bash
# After the nightly has re-locked, built, tested and run every sample, the committed lockfiles must
# read as they did before — that is lockfile-as-law, enforced on the samples that are supposed to
# demonstrate it. Two lockfiles, two rules:
#
#   jk-lock.toml       may differ in `checksum = "sha256:…"` lines and in the writer lines
#                      (`generated-by`, `generated-by-build`, `generated-by-build-time`), nothing
#                      else. A first-party plugin pinned at the product's own, still-moving version is
#                      republished with every side-load, so its sha256 can never match a committed
#                      lock (pre-1.0); and `jk lock` records the jk build that ran it, which in the
#                      job is the checkout's own build. Neither changes what the lock resolves.
#   package-lock.json  may not differ at all. A frozen install refuses a lock out of step with
#                      package.json, so a lock the job rewrote (an `npm install`, a Node upgrade)
#                      would otherwise be noticed only when the install fails on a contributor's machine.
#
# Usage: scripts/examples-lock-drift.sh [checkout] [jk-examples checkout]
#   checkout             the jk tree whose docs/user/examples/** are checked (default: .)
#   jk-examples checkout also check its web/** (default: $JK_EXAMPLES_DIR, if set)
# Exit 1 with every drifted file named; the `::error::` prefix is a GitHub Actions annotation and
# reads fine in a terminal.
set -euo pipefail

failed=0

# check <repo> <tree>: the two rules over <tree> inside the git checkout <repo>.
check() {
  local repo="$1" tree="$2" drift npm_drift
  (
    cd "$repo"
    git diff --stat -- "$tree/**/jk-lock.toml"
    drift="$(git diff -U0 -- "$tree/**/jk-lock.toml" | grep -E '^[+-][^+-]' | grep -vE '^[+-](checksum = "sha256:|generated-by(-build|-build-time)? = )' || true)"
    if [[ -n "$drift" ]]; then
      echo "::error::a sample lock under $tree changed beyond plugin checksums and its writer lines:"
      printf '%s\n' "$drift"
      exit 1
    fi
  ) || failed=1
  # `git diff --name-only` names a modified or deleted lock; a lock that is not committed is a
  # sample's own business and cannot drift.
  npm_drift="$(git -C "$repo" diff --name-only -- "$tree/**/package-lock.json")"
  if [[ -n "$npm_drift" ]]; then
    while IFS= read -r file; do
      echo "::error file=$file::a sample's npm lock changed after the job: $file (commit the lock package.json needs, or restore it)"
    done <<<"$npm_drift"
    failed=1
  fi
}

check "${1:-.}" docs/user/examples

examples="${2:-${JK_EXAMPLES_DIR:-}}"
if [[ -n "$examples" ]]; then
  check "$examples" web
fi

exit "$failed"
