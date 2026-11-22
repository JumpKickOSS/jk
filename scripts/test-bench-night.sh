#!/usr/bin/env bash
# Fixture test for scripts/bench-night.sh with fake agent CLIs and a fake jk: a dry run lists every
# cell of the plan with its expected time, each step's minutes and what starts past the soft deadline,
# every agent on its pinned model, the new agent alone first, and names each agent that is not logged
# in, and no agent runs; an agent cut by NIGHT_SKIP or the skip file leaves steps 2 and 3 only; smoke
# names an agent that is not logged in and runs nothing; a real run over a fake harness runs every cell
# past its deadline, logs how far past with what is done and left, `status` gives done, running, left
# and an ETA while it runs, and the night ends with its tables, the tool hit rate in them; a real run
# refuses while a loop runs; pause stops a loop in the runner checkout and resume starts it again with
# the environment it had. Only processes started here are touched: a loop is found by its directory,
# which is this test's scratch runner.
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

# Each row records what the night's tables read: the right code tool where the arm offers it and its
# calls (over MCP or `jk code` in a shell), the first tool, searches and reads apart, text navigation
# after the first code-tool call, failed tool calls from the raw transcript, and nothing is invented
# for a driver whose transcript marks no failure.
printf '%s\n' '{"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "a", "name": "mcp__jk__usages", "input": {}}]}}' \
  '{"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "a", "is_error": true}]}}' \
  '{"type": "item.completed", "item": {"type": "mcp_tool_call", "status": "failed"}}' \
  '{"type": "tool.execution_complete", "data": {"toolCallId": "b", "success": false}}' >"$WORK/transcript.jsonl"
python3 -I - "$ROOT/bench/agent-loop/change-tasks" "$WORK/transcript.jsonl" <<'PY' || fail "the row's diagnostics are wrong"
import importlib.machinery, importlib.util, sys
from pathlib import Path
loader = importlib.machinery.SourceFileLoader("change_tasks", sys.argv[1])
spec = importlib.util.spec_from_loader("change_tasks", loader)
ct = importlib.util.module_from_spec(spec)
loader.exec_module(ct)
spine = [{"tool_use": "Grep", "input": '{"pattern": "addPet"}'},
         {"tool_use": "mcp__jk__usages", "input": "{}"},
         {"tool_use": "Read", "input": '{"file_path": "/s/Owner.java"}'},
         {"tool_use": "Bash", "input": '{"command": "jk -u code usages Owner.addPet"}'},
         {"tool_use": "Bash", "input": '{"command": "cat Owner.java"}'}]
nav = ct.navigation(spine, Path("/s"))
want = {"first_tool": "Grep", "searches": 1, "reads": 2, "text_after_code_tool": 2, "graph": 2, "text": 3}
got = {k: nav[k] for k in want}
assert got == want, got
assert ct.navigation(spine[:1], Path("/s"))["text_after_code_tool"] is None
assert ct.code_tool({"kind": "question"}, "jk") == "usages"
assert ct.code_tool({"kind": "question"}, "jk-nograph") is None
assert ct.code_tool({"kind": "pull-up"}, "jk") is None
assert ct.code_tool({"kind": "pull-up"}, "jk-graph") == "pull_up"
assert ct.code_tool({"kind": "fix"}, "jk-graph") is None
assert ct.code_tool({"kind": "fix", "code_tool": "usages"}, "jk") == "usages"
assert ct.code_tool_calls(spine, "usages") == 2
assert ct.code_tool_calls([{"tool_use": "Bash", "input": '{"command": "jk code pull-up A.b"}'}], "pull_up") == 1
assert ct.tool_errors(Path(sys.argv[2]), "claude-code") == 3
assert ct.tool_errors(Path(sys.argv[2]), "cursor") is None
PY

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
grep -q "^past the soft deadline $soon: [0-9]* cells, marked \\[after the deadline\\] above; they still run" "$WORK/dry" ||
  fail "the dry run does not say what starts past the soft deadline"
grep -qE '^[0-9]+ cells, .*ending ~[0-9]{2}:[0-9]{2}; soft deadline ' "$WORK/dry" || fail "the dry run does not say when the night ends"
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

# A real run over a fake harness, its soft deadline seconds away: the first cell waits until the deadline
# is past, `status` reads the night meanwhile, and every cell then still runs. Processes this user cannot
# read (another sandbox's loops) would refuse a real run, so here pgrep leaves them out.
mkdir -p "$WORK/harness" "$WORK/hostbin" "$WORK/results"
real_pgrep="$(command -v pgrep)"
cat >"$WORK/hostbin/pgrep" <<EOF
#!/usr/bin/env bash
for p in \$("$real_pgrep" "\$@"); do readlink "/proc/\$p/cwd" >/dev/null 2>&1 && echo "\$p"; done
exit 0
EOF
cat >"$WORK/harness/smoke-test" <<EOF
#!/usr/bin/env bash
echo "smoke-test \$*" >>"$WORK/ran"
if [ ! -e "$WORK/first" ]; then
  touch "$WORK/first"
  for _ in \$(seq 600); do [ -e "$WORK/go" ] && break; sleep 0.1; done
fi
EOF
cat >"$WORK/harness/change-tasks" <<EOF
#!/usr/bin/env bash
echo "change-tasks \$*" >>"$WORK/ran"
exec python3 -I -c '
import datetime, json, os, sys
a = dict(zip(sys.argv[1::2], sys.argv[2::2]))
tools = a["--arm"] != "jk-nograph"
row = {"date": datetime.date.today().isoformat(), "driver": a["--driver"], "task": a["--task"], "kind": "question",
       "arm": a["--arm"], "run": int(a["--first-run"]), "model": a["--model"], "effort": a["--effort"],
       "outcome": "green", "wall_s": 10.0, "turns": 3, "input_tokens": 1000, "output_tokens": 100,
       "cache_read_tokens": 0, "cache_write_tokens": 0, "reasoning_tokens": 0, "tokens": 1100,
       "first_tool": "mcp__jk__usages" if tools else "Grep", "searches": 1, "reads": 2,
       "text_after_code_tool": 0 if tools else None, "tool_errors": 0, "turn_cap": False,
       "code_tool": "usages" if tools else None, "code_tool_calls": 1 if tools else None}
out = os.path.join(os.environ["NIGHT_RESULTS"], row["date"] + "-change-tasks")
os.makedirs(out, exist_ok=True)
open(os.path.join(out, "rows.jsonl"), "a").write(json.dumps(row) + "\n")
' "\$@"
EOF
chmod +x "$WORK/harness/smoke-test" "$WORK/harness/change-tasks" "$WORK/hostbin/pgrep"
echo "echo step four >>'$WORK/ran-extra'" >"$WORK/extra"
real_night() {
  NIGHT_EXTRA="$WORK/extra" NIGHT_DRIVERS="claude-code copilot" NIGHT_HARNESS="$WORK/harness" NIGHT_RESULTS="$WORK/results" \
    AGENT_LOOP_HOME="$WORK/agent-loop" PATH="$WORK/hostbin:$PATH" night "$@"
}
deadline="$(date -d '+3 seconds' '+%F %T')"
real_night run --until "$deadline" >"$WORK/run" 2>&1 &
runpid=$!
for _ in $(seq 600); do [ -e "$WORK/first" ] || ! kill -0 "$runpid" 2>/dev/null && break; sleep 0.1; done
[ -e "$WORK/first" ] || fail "the real run started no cell: $(cat "$WORK/run")"
real_night status >"$WORK/status" 2>&1 || fail "status failed: $(cat "$WORK/status")"
grep -qE "soft deadline .*, running \(pid [0-9]+\)" "$WORK/status" || fail "status does not see the running night: $(cat "$WORK/status")"
grep -qE '^0 done, 1 running \(step 1 tier 0: copilot jk[^ ]* smoke, 0:00 of ~[0-9]+:[0-9]{2}\), [0-9]+ left; ~[0-9]+:[0-9]{2} \(h:mm\) more, ending ~[0-9]{2}:[0-9]{2} \(and 1 step-4 command without an estimate\)$' "$WORK/status" ||
  fail "status does not give done, running, left and an ETA: $(cat "$WORK/status")"
for _ in $(seq 100); do [ "$(date +%s)" -gt "$(date -d "$deadline" +%s)" ] && break; sleep 0.1; done
touch "$WORK/go"
for _ in $(seq 1200); do kill -0 "$runpid" 2>/dev/null || break; sleep 0.1; done
if kill -0 "$runpid" 2>/dev/null; then kill "$runpid"; fail "the real run did not end: $(tail "$WORK/run")"; fi
wait "$runpid" || fail "the real run failed: $(cat "$WORK/run")"
plan="$(NIGHT_DRIVERS="claude-code copilot" NIGHT_RESULTS="$WORK/results" GRAPH_RETRIEVAL_HOME="$WORK/gr" \
  AGENT_LOOP_HOME="$WORK/agent-loop" "$ROOT/bench/agent-loop/night-plan" plan)"
want_ran="$(awk -F'\t' '$6 == "-" || $6 == "spring-petclinic"' <<<"$plan" | wc -l)"
[ "$want_ran" -gt 5 ] || fail "the plan has only $want_ran cells on the set-up repository"
[ "$(wc -l <"$WORK/ran")" -eq "$want_ran" ] || fail "$(wc -l <"$WORK/ran") of $want_ran cells ran: $(cat "$WORK/run")"
grep -qE "past the soft deadline [0-9]{2}:[0-9]{2} by 0:00: 1 done, 0 running, [0-9]+ left; " "$WORK/run" ||
  fail "past the deadline, the run does not log what is done and left: $(cat "$WORK/run")"
if grep -q 'deadline)' "$WORK/run"; then fail "a cell was skipped for the deadline: $(grep 'deadline)' "$WORK/run")"; fi
grep -q '^## Per agent and arm' "$WORK/run" || fail "the night did not end with its tables: $(tail "$WORK/run")"
grep -qE '^\| claude-code \| jk-nograph \| [0-9]+ \| [0-9]+ \| n/a \|' "$WORK/run" ||
  fail "the no-code-tools arm has a hit rate: $(grep '^| claude-code' "$WORK/run")"
grep -qE '^\| copilot \| jk[^ ]* \| [0-9]+ \| [0-9]+ \| ([0-9]+) of \1 \|' "$WORK/run" ||
  fail "copilot's tool hit rate is missing: $(grep '^| copilot' "$WORK/run")"
grep -q '^| petclinic-callers-addpet | claude-code | usages | 1 of 1 | yes / yes |' "$WORK/run" ||
  fail "the task table does not set with the tools against without: $(grep '^| petclinic-callers-addpet' "$WORK/run")"
real_night status >"$WORK/status2" 2>&1 || fail "status after the night failed"
grep -q ', not running;' "$WORK/status2" || fail "status calls an ended night running: $(head -3 "$WORK/status2")"
grep -qE "^$(($(wc -l <<<"$plan") + 1)) done, 0 running, 0 left" "$WORK/status2" || fail "status after the night: $(sed -n 3p "$WORK/status2")"
[ "$(cat "$WORK/ran-extra" 2>/dev/null)" = "step four" ] || fail "step 4 did not run past the deadline"
[ ! -e "$WORK/calls" ] || fail "the real run ran an agent or jk: $(cat "$WORK/calls")"

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
