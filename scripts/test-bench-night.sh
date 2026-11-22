#!/usr/bin/env bash
# Fixture test for scripts/bench-night.sh with fake agent CLIs and a fake jk: a dry run lists every
# cell of the plan with its expected time, each step's minutes and what misses the deadline, every agent
# on its pinned model, the new agent alone first, and names each agent that is not logged in, and no
# agent runs; an agent cut by NIGHT_SKIP or the skip file leaves steps 2 and 3 only; smoke names an
# agent that is not logged in and runs nothing; a real run refuses while a loop runs; pause stops a loop in the runner checkout and resume
# starts it again with the environment it had. Only processes started here are touched: a loop is
# found by its directory, which is this test's scratch runner.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/var/tmp}/jk-bench-night.XXXXXX")"
RUNNER="$WORK/runner"

loops() { # the pids of fake loops running in this test's runner
  local pid
  for pid in $(pgrep -f '^bash scripts/(queue/loop|gate/full-loop)\.sh' || true); do
    [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$RUNNER" ] && echo "$pid"
  done
  return 0
}
cleanup() {
  local pid
  for pid in $(loops); do kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true; done
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
  echo "test-bench-night: $*" >&2
  exit 1
}

mkdir -p "$WORK/bin" "$WORK/home/.config/muse" "$WORK/gr/repos/spring-petclinic" "$RUNNER/scripts/queue"
touch "$WORK/home/.config/muse/auth.json" "$WORK/gr/repos/spring-petclinic/jk.toml"
want="$(sed -nE 's/^version *= *"([^"]+)".*/\1/p' "$ROOT/jk.toml" | head -1)-SNAPSHOT"

# Every fake CLI records any call that is not a login check: a recorded call is an agent that ran.
fake() { # <name> <login-check args> <its answer> <exit>
  printf '%s\n' "$3" >"$WORK/$1.answer"
  cat >"$WORK/bin/$1" <<EOF
#!/usr/bin/env bash
if [ "\$*" = "$2" ]; then cat "$WORK/$1.answer"; exit $4; fi
echo "$1 \$*" >>"$WORK/calls"
exit 1
EOF
  chmod +x "$WORK/bin/$1"
}
fake claude "auth status" '{"loggedIn": true}' 0
fake codex "login status" "Logged in using ChatGPT" 0
fake cursor-agent "status" "Not logged in" 1
fake agy "models" "gemini-4-neon-medium" 0
fake grok "--version" "grok 1" 0
fake muse "--version" "muse 1" 0
fake copilot "--version" "GitHub Copilot CLI 1" 0
cat >"$WORK/bin/jk" <<EOF
#!/usr/bin/env bash
case "\$*" in
  --version) echo "jk $want" ;;
  mcp) exec python3 -I -c '
import json, sys
names = {False: ["build", "diagnostics", "deps", "usages", "skill"],
         True: ["build", "usages", "rename", "delete", "move", "pull_up", "extract_interface"]}
for line in sys.stdin:
    msg = json.loads(line)
    if "id" not in msg:
        continue
    extended = bool((msg.get("params") or {}).get("extended"))
    result = {"tools": [{"name": n} for n in names[extended]]} if msg["method"] == "tools/list" else {}
    print(json.dumps({"jsonrpc": "2.0", "id": msg["id"], "result": result}), flush=True)
' ;;
  *) echo "jk \$*" >>"$WORK/calls"; exit 1 ;;
esac
EOF
chmod +x "$WORK/bin/jk"
cat >"$RUNNER/scripts/queue/loop.sh" <<EOF
#!/usr/bin/env bash
echo "\$QUEUE_CHECKOUT" >>"$WORK/loop-started"
while :; do sleep 1; done
EOF

night() {
  env -u XAI_API_KEY -u JK_BENCH_CLAUDE HOME="$WORK/home" PATH="$WORK/bin:$PATH" GRAPH_RETRIEVAL_HOME="$WORK/gr" \
    NIGHT_RUNNER="$RUNNER" NIGHT_STATE="$WORK/state" GATE_LOCK="$WORK/gate.lock" NIGHT_INHIBITED=1 \
    bash "$ROOT/scripts/bench-night.sh" "$@"
}

# A dry run: every cell of the plan, each with its expected time; the agents not logged in named.
soon="$(date -d '+20 min' +%H:%M)"
night run --until "$soon" --dry-run >"$WORK/dry" 2>&1 || fail "the dry run failed: $(cat "$WORK/dry")"
plan="$(GRAPH_RETRIEVAL_HOME="$WORK/gr" "$ROOT/bench/agent-loop/night-plan" plan)"
cells="$(wc -l <<<"$plan")"
[ "$cells" -gt 20 ] || fail "the plan has $cells cells"
listed="$(grep -cE '^step [123] tier [012]  ~[0-9]+ min ' "$WORK/dry" || true)"
[ "$listed" -eq "$cells" ] || fail "the dry run listed $listed of $cells cells with a time"
grep -q 'expected from cell ' "$WORK/dry" || fail "no expected time comes from an earlier run of its cell"
# Step 1 is copilot alone, tiers 0, 1 and 2; step 2 jk-nograph only; step 3 never copilot.
[ -z "$(awk -F'\t' '$1 == 1 && $3 != "copilot"' <<<"$plan")" ] || fail "step 1 runs an agent other than copilot"
[ "$(awk -F'\t' '$1 == 1 {print $2}' <<<"$plan" | sort -u | xargs)" = "0 1 2" ] || fail "step 1 lacks a tier"
[ -z "$(awk -F'\t' '$1 == 2 && $4 != "jk-nograph"' <<<"$plan")" ] || fail "step 2 runs with the code tools"
[ -z "$(awk -F'\t' '$1 == 3 && $3 == "copilot"' <<<"$plan")" ] || fail "step 3 repeats copilot"
[ "$(awk -F'\t' '$1 == 2 {print $3}' <<<"$plan" | sort -u | wc -l)" -eq 7 ] || fail "step 2 lacks an agent"
for task in petclinic-delete-dead-code petclinic-move-phone-format petclinic-pullup-display-name \
  petclinic-extract-interface-contact; do
  grep -q -- "--driver claude-code --arm jk-graph --task $task " "$WORK/dry" || fail "step 3 lacks $task on jk-graph"
  grep -q -- "--arm jk-nograph --task $task " "$WORK/dry" || fail "step 2 lacks $task"
done
grep -q -- '--driver agy .* --model gemini-4-neon-medium --effort medium' "$WORK/dry" || fail "agy does not run Gemini 4 Neon, medium"
grep -q -- '--driver copilot .* --model fusion --effort standard' "$WORK/dry" || fail "copilot does not run Fusion, standard"
grep -q -- 'smoke-test --driver muse --arm jk --model muse-spark-1.3-contributor --effort max' "$WORK/dry" ||
  fail "muse has no smoke cell on its pinned model"
grep -qE '^step 2, no-code-tools baseline: [0-9]+ cells, ~[0-9]+:[0-9]{2} \(h:mm\)' "$WORK/dry" || fail "no minutes for step 2"
grep -q "^does not fit before $soon: " "$WORK/dry" || fail "the dry run does not say what misses the deadline"
grep -q 'not logged in: cursor-agent is not logged in' "$WORK/dry" || fail "cursor-agent's missing login is not named"
grep -q 'not logged in: grok is not logged in' "$WORK/dry" || fail "grok's missing login is not named"
if grep -qE 'not logged in: (claude|codex|muse|agy|copilot)' "$WORK/dry"; then fail "a logged-in agent was named: $(grep 'not logged in' "$WORK/dry")"; fi
grep -q 'not set up: commons-lang' "$WORK/dry" || fail "a repository not set up is not named"
grep -q -- '--driver cursor .*\[cursor-agent is not logged in' "$WORK/dry" || fail "cursor's cells are not marked skipped"

# An agent cut at the start or by the skip file during the night leaves steps 2 and 3, never step 1.
NIGHT_SKIP=copilot night run --until 07:00 --dry-run >"$WORK/dry-skip" 2>&1 || fail "the NIGHT_SKIP dry run failed"
grep -q -- '^step 1 tier 1 .*--driver copilot ' "$WORK/dry-skip" || fail "NIGHT_SKIP cut copilot from step 1"
if grep -qE -- '^step [23] .*--driver copilot ' "$WORK/dry-skip"; then fail "NIGHT_SKIP left copilot in steps 2 or 3"; fi
echo codex >"$WORK/state/skip"
night run --until 07:00 --dry-run >"$WORK/dry-file" 2>&1 || fail "the skip-file dry run failed"
grep -q -- "^step 2 .*--driver codex .*\[cut by $WORK/state/skip\]" "$WORK/dry-file" || fail "the skip file did not cut codex"
rm "$WORK/state/skip"

# Smoke names an agent that is not logged in and runs nothing for it.
if NIGHT_DRIVERS=cursor night smoke >"$WORK/smoke" 2>&1; then fail "smoke passed an agent not logged in"; fi
grep -q '^FAIL cursor composer-2.5/default: cursor-agent is not logged in' "$WORK/smoke" ||
  fail "smoke does not name cursor's login: $(cat "$WORK/smoke")"
# A loop of another user or sandbox on this host is a problem the run names; none other may show.
if grep 'problem:' "$WORK/dry" | grep -qv 'in a directory this user cannot read'; then
  fail "the dry run saw a problem: $(grep 'problem:' "$WORK/dry")"
fi
[ ! -e "$WORK/calls" ] || fail "the dry run ran an agent or jk: $(cat "$WORK/calls")"

# A loop in the runner checkout: a real run refuses, and nothing runs.
(cd "$RUNNER" && QUEUE_CHECKOUT=/queue-checkout setsid -f bash scripts/queue/loop.sh >/dev/null 2>&1 </dev/null)
for _ in $(seq 50); do [ -n "$(loops)" ] && break; sleep 0.1; done
pid="$(loops)"
[ -n "$pid" ] || fail "the fake queue loop did not start"
if night run --until 07:00 >"$WORK/real" 2>&1; then fail "a run started while the queue loop ran"; fi
grep -q "the queue loop is running (pid $pid)" "$WORK/real" || fail "the refusal does not name the loop: $(cat "$WORK/real")"
[ ! -e "$WORK/calls" ] || fail "a refused run ran an agent or jk: $(cat "$WORK/calls")"

# Pause stops it and saves it; resume starts it again with its environment.
night pause >"$WORK/pause" 2>&1 || fail "pause failed: $(cat "$WORK/pause")"
for _ in $(seq 50); do [ -z "$(loops)" ] && break; sleep 0.1; done
[ -z "$(loops)" ] || fail "pause left the queue loop running"
[ -s "$WORK/state/loops/queue.cmdline" ] || fail "pause did not save the loop's command line"
night resume >"$WORK/resume" 2>&1 || fail "resume failed: $(cat "$WORK/resume")"
for _ in $(seq 50); do [ -n "$(loops)" ] && break; sleep 0.1; done
[ -n "$(loops)" ] || fail "resume did not start the queue loop: $(cat "$WORK/resume")"
for _ in $(seq 50); do [ "$(wc -l <"$WORK/loop-started")" -ge 2 ] && break; sleep 0.1; done
[ "$(sed -n 2p "$WORK/loop-started")" = /queue-checkout ] || fail "the resumed loop lost its environment: $(cat "$WORK/loop-started")"
night resume >"$WORK/resume2" 2>&1
grep -q 'queue loop: already running' "$WORK/resume2" || fail "a second resume did not see the running loop"
echo "test-bench-night: ok"
