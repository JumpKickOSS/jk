#!/usr/bin/env bash
# Fixture test for release-fleet-check.sh's checkout step (--checkout-only), network-free: a clean
# checkout on main is brought to the release tag even when it holds a local tag origin moved, which
# a plain `git fetch --tags` refuses ("would clobber existing tag"); a dirty one is still left alone.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
g() { git -c user.name=fixture -c user.email=fixture@example.invalid -c init.defaultBranch=main "$@"; }
fails=0
# check <what> <command…>: the command's exit status is the verdict.
check() {
  local what="$1"
  shift
  if "$@"; then echo "ok   $what"; else echo "FAIL $what"; fails=$((fails + 1)); fi
}

# origin: A, B on main, v0.9.0 at A; then C, tagged v1.0.0, the release.
g init -q "$scratch/seed"
g -C "$scratch/seed" commit -q --allow-empty -m A
g -C "$scratch/seed" tag v0.9.0
g -C "$scratch/seed" commit -q --allow-empty -m B
g clone -q --bare "$scratch/seed" "$scratch/origin.git"
for c in usual dirty; do
  g clone -q "$scratch/origin.git" "$scratch/$c"
  # A local tag origin does not hold there: the release that moved v0.9.0 after this clone fetched it.
  g -C "$scratch/$c" tag -f v0.9.0 HEAD >/dev/null
done
g -C "$scratch/seed" commit -q --allow-empty -m C
g -C "$scratch/seed" tag v1.0.0
g -C "$scratch/seed" push -q "$scratch/origin.git" main v1.0.0
release="$(g -C "$scratch/origin.git" rev-parse v1.0.0)"
echo dirt >"$scratch/dirty/file"

out="$(bash "$ROOT/scripts/release-fleet-check.sh" --checkout-only 1.0.0 "$scratch/usual" 2>&1)" && code=0 || code=$?
check "a checkout holding a stale local tag is usable (exit $code): $out" test "$code:$out" = 0:usable
check "it is fast-forwarded to the release tag" test "$(g -C "$scratch/usual" rev-parse HEAD)" = "$release"
check "the stale local tag is left as it was" \
  test "$(g -C "$scratch/usual" rev-parse v0.9.0)" != "$(g -C "$scratch/origin.git" rev-parse v0.9.0)"

out="$(bash "$ROOT/scripts/release-fleet-check.sh" --checkout-only 1.0.0 "$scratch/dirty" 2>&1)" && code=0 || code=$?
check "a dirty checkout is left alone (exit $code): $out" test "$code:$out" = 1:dirty

if ((fails)); then echo "test-release-fleet-check: $fails failed"; exit 1; fi
echo "test-release-fleet-check: all passed"
