#!/usr/bin/env bash
# Prepares the spring-petclinic checkout the homepage demo records (docs/assets/demo/demo.tape).
#
# The project is the one bench/wall/measure times: spring-petclinic at the pinned commit, built by
# the bench's own jk.toml and jk-lock.toml, without the two test classes that only start database
# containers. Every run stamps a fresh class into the baseline, so each build in a take is real
# work and never a restore of an earlier take's results; then it builds main once (the cache the
# video's last command lands on) and leaves the one-line bug on a branch, uncommitted.
#
# Both demos run on a private engine (JK_STATE_DIR, default ~/src/scratch/demo-state), so the
# dashboard in the agent demo lists the demo's sessions and nothing else on the machine.
# --agents wires the checkout for coding agents (jk agents) as part of the baseline.
#
#   docs/assets/demo/setup.sh && vhs docs/assets/demo/demo.tape
#   docs/assets/demo/setup.sh --agents && docs/assets/demo/record-agent.sh
set -euo pipefail

agents=false
[[ "${1:-}" == "--agents" ]] && agents=true
export JK_STATE_DIR="${JK_DEMO_STATE:-$HOME/src/scratch/demo-state}"
# Its own dashboard port: the machine's usual engine already holds 8910.
export JK_HTTP_PORT="${JK_DEMO_PORT:-8921}"
# JK_DEMO_WEB_ROOT serves the dashboard from a source tree (the engine's web-root) instead of
# the installed engine's bundled copy: a dashboard fix shows in the take before it ships.
if [[ -n "${JK_DEMO_WEB_ROOT:-}" ]]; then export JK_HTTP_WEB_ROOT="$JK_DEMO_WEB_ROOT"; fi
# A clean slate per take: the dashboard's history starts at the baseline build below.
jk engine stop --now >/dev/null 2>&1 || true
rm -rf "$JK_STATE_DIR"
mkdir -p "$JK_STATE_DIR"

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
demo="${JK_DEMO_DIR:-$HOME/src/scratch/demo-petclinic}"
commit=818c4136ea971c21674525f9053de0d9c7ad8cfe
src=src/main/java/org/springframework/samples/petclinic
tests=src/test/java/org/springframework/samples/petclinic

g() { git -C "$demo" -c user.name="JumpKick demo" -c user.email=demo@jumpkick.build "$@"; }

[[ -d "$demo/.git" ]] || git clone -q https://github.com/spring-projects/spring-petclinic "$demo"
g checkout -q -f -B main "$commit"
g clean -qfd
for b in $(g for-each-ref --format='%(refname:short)' refs/heads/ | grep -vx main || true); do
  g branch -q -D "$b"
done

cp "$root/bench/wall/spring-petclinic/jk.toml" "$root/bench/wall/spring-petclinic/jk-lock.toml" "$demo/"
g rm -q "$tests/PostgresIntegrationTests.java" "$tests/MySqlIntegrationTests.java"
# A constant, not a comment: tests are keyed by the compiled classes, and a comment compiles away.
printf 'package org.springframework.samples.petclinic;\n\nfinal class DemoTake {\n\n\tstatic final long STAMP = %sL;\n\n}\n' \
  "$(date +%s%N)" > "$demo/$src/DemoTake.java"
if $agents; then (cd "$demo" && jk agents -q >/dev/null); fi
g add -A
g commit -q -m "spring-petclinic on jk"

(cd "$demo" && jk test >/dev/null)

g checkout -q -b case-insensitive-pet-types
sed -i 's/if (Objects.equals(type.getName(), text)) {/if (text.equals(type.getName().toLowerCase())) {/' \
  "$demo/$src/owner/PetTypeFormatter.java"
echo "ready: $demo on case-insensitive-pet-types, main built"
