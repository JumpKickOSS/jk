#!/usr/bin/env bash
# Fixture test for release-fleet-check.sh's steps, network-free:
#  - checkout (--checkout-only): a clean checkout on main is brought to the release tag even when it
#    holds a local tag origin moved, which a plain `git fetch --tags` refuses ("would clobber existing
#    tag"); a dirty one is still left alone;
#  - wipe (--wipe-only): everything under the home goes but creds, hidden entries included;
#  - drain (--idle-only): an engine with a job, a queued build or an agent session is busy, one with
#    only connections is idle, and an engine of another home does not hold the stop.
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

home="$scratch/home/.jk"
for f in bin/jk lib/jk-engine/jk-engine.jar cache/actions/a state/engine/x.pid state/builds/1.json store/repos/a.jar \
  config/app/config.toml config.toml .hidden creds/forge/github.com creds/repo/central; do
  mkdir -p "$(dirname "$home/$f")"
  echo "$f" >"$home/$f"
done
out="$(bash "$ROOT/scripts/release-fleet-check.sh" --wipe-only "$home" 2>&1)" && code=0 || code=$?
check "the wipe succeeds (exit $code): $out" test "$code" = 0
check "only creds is left" test "$(cd "$home" && find . -mindepth 1 -maxdepth 1 | sort | tr '\n' ' ')" = "./creds "
check "the stored logins survive" test "$(cat "$home/creds/forge/github.com" "$home/creds/repo/central")" \
  = "$(printf 'creds/forge/github.com\ncreds/repo/central')"

idle_of() { bash "$ROOT/scripts/release-fleet-check.sh" --idle-only "$1"; }
quiet='"running":true,"activeRequests":2,"queuedBuildPlans":0,"jobs":[],"mcpActiveConnections":0'
check "an engine with connections only is idle" test "$(idle_of "{$quiet,\"engines\":[]}")" = idle
check "an engine with a job is busy" \
  test "$(idle_of "{${quiet/\"jobs\":[]/\"jobs\":[{\"id\":\"j1\"\}]},\"engines\":[]}")" = busy
check "an engine with a queued build is busy" \
  test "$(idle_of "{${quiet/\"queuedBuildPlans\":0/\"queuedBuildPlans\":1},\"engines\":[]}")" = busy
check "an engine with an agent session is busy" \
  test "$(idle_of "{${quiet/\"mcpActiveConnections\":0/\"mcpActiveConnections\":1},\"engines\":[]}")" = busy
check "no engine of this home is stopped, another home's engine aside" \
  test "$(idle_of '{"running":false,"engines":[{"pid":7,"otherHome":true}]}')" = stopped
check "a draining engine of this home is not stopped" \
  test "$(idle_of '{"running":false,"engines":[{"pid":7,"otherHome":false,"draining":true}]}')" = idle

if ((fails)); then echo "test-release-fleet-check: $fails failed"; exit 1; fi
echo "test-release-fleet-check: all passed"
