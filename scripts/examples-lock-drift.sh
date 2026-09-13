#!/usr/bin/env bash
# After the nightly has re-locked, built, tested and run every sample under docs/user/examples,
# the committed lockfiles must read as they did before — that is lockfile-as-law, enforced on the
# samples that are supposed to demonstrate it. Two lockfiles, two rules:
#
#   jk-lock.toml         may differ in `checksum = "sha256:…"` lines and nothing else. A first-party
#                        plugin pinned at the product's own, still-moving version is republished with
#                        every side-load, so its sha256 can never match a committed lock (pre-1.0).
#   web/package-lock.json  may not differ at all. `npm ci` refuses a lock out of step with
#                        package.json, so a lock the job rewrote (an `npm install`, a Node upgrade)
#                        would otherwise be noticed only when `npm ci` fails on a contributor's machine.
#
# Usage: scripts/examples-lock-drift.sh [checkout]   (default: the current directory)
# Exit 1 with every drifted file named; the `::error::` prefix is a GitHub Actions annotation and
# reads fine in a terminal.
set -euo pipefail

cd "${1:-.}"

failed=0

git diff --stat -- 'docs/user/examples/**/jk-lock.toml'
drift="$(git diff -U0 -- 'docs/user/examples/**/jk-lock.toml' | grep -E '^[+-][^+-]' | grep -vE '^[+-]checksum = "sha256:' || true)"
if [[ -n "$drift" ]]; then
  echo "::error::a sample lock changed beyond first-party plugin checksums:"
  printf '%s\n' "$drift"
  failed=1
fi

# `git diff --name-only` names a modified or deleted lock; a lock that is not committed is a
# sample's own business and cannot drift.
npm_drift="$(git diff --name-only -- 'docs/user/examples/**/package-lock.json')"
if [[ -n "$npm_drift" ]]; then
  while IFS= read -r file; do
    echo "::error file=$file::a sample's npm lock changed after the job: $file (commit the lock package.json needs, or restore it)"
  done <<<"$npm_drift"
  failed=1
fi

exit "$failed"
