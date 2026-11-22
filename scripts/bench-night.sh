#!/usr/bin/env bash
# A night of agent benches on the gate host, run in order until a deadline, with the merge queue and
# the Full loop paused. Every agent runs its pinned model and effort, one run a cell. The plan is
# bench/agent-loop/night-plan's, in tiers (0 smoke-test, 1 a screen, 2 a sliver of the claims' tasks):
# step 1 the new agent ($NIGHT_FIRST, copilot) alone through tiers 0, 1 and 2 with JumpKick's code
# tools; step 2 every agent without them (no-code-tools, arm jk-nograph) on every task of tiers 1 and 2;
# step 3 every other agent with them through tiers 0, 1 and 2; step 4 the commands in $NIGHT_EXTRA, one
# per line, only with time left. Each cell is one smoke-test (tier 0) or change-tasks invocation,
# started only when its expected time fits before the deadline and stopped at the deadline otherwise.
# Rows land where the scoreboard reads them, bench/agent-loop/results/<date>-change-tasks/ (smoke rows
# under $AGENT_LOOP_HOME/smoke/); nothing is banked or rendered.
#
# Usage:
#   scripts/bench-night.sh run --until 01:00 [--dry-run]   # the night; --dry-run lists it, runs no agent
#   scripts/bench-night.sh smoke                           # tier 0 for every agent now, one line each
#   scripts/bench-night.sh pause                           # stop the queue and Full loops between gates
#   scripts/bench-night.sh resume                          # start them again as they were
#   scripts/bench-night.sh prepare                         # set up and build the corpus repositories
#
# The dry run prints each step's cells and minutes, and the cells that would not start before the
# deadline. To cut an agent from steps 2 and 3, start with NIGHT_SKIP=copilot, or during the night
# write its name into $NIGHT_STATE/skip (one per line): each cell reads it before it starts.
#
# `run` checks first, and refuses a real run on any failure: the installed jk is this tree's version
# and lists every MCP tool a step calls; the queue and Full loops are not running; no gate holds the
# gate lock (which it then holds to the end). It names every agent CLI that is not logged in and every
# corpus repository not set up, and skips their cells; it never logs in. An agent whose two cells in a
# row end in an error row (a preflight or a login refused) is skipped for the rest of the step.
#
# `pause` takes the gate lock, so it waits for a batch or Full cycle in flight to finish, saves each
# loop's command line, directory, output file and environment under $NIGHT_STATE, and stops the
# loop's process group (the loop and its flock waiting for the lock). `resume` starts each saved loop
# in its own session. A loop is the process running `bash scripts/queue/loop.sh` or
# `bash scripts/gate/full-loop.sh` in $NIGHT_RUNNER; no other process is touched.
#
#   NIGHT_RUNNER    the checkout the loops run from (default ~/src/jk-queue-runner)
#   NIGHT_STATE     saved loops and the night's log (default /var/tmp/jk-bench-night)
#   NIGHT_EXTRA     step 3's file of commands, run from this tree (default none)
#   GATE_LOCK       the lock every gate on this host takes (default /var/tmp/fleet-gate.lock)
#   QUEUE_CHECKOUT, FULL_CHECKOUT
#                   what `resume` gives a loop whose environment could not be saved (default
#                   ~/src/jk-queue-checkout, ~/src/jk-full-checkout)
#   NIGHT_DRIVERS, NIGHT_FIRST, NIGHT_SKIP, NIGHT_NOGRAPH, NIGHT_MAX_MINUTES
#                   the plan's (bench/agent-loop/night-plan)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNNER="${NIGHT_RUNNER:-$HOME/src/jk-queue-runner}"
STATE="${NIGHT_STATE:-/var/tmp/jk-bench-night}"
LOCK="${GATE_LOCK:-/var/tmp/fleet-gate.lock}"
PLAN="$ROOT/bench/agent-loop/night-plan"
CORPUS="$("$PLAN" corpus)" || exit 2
LOOPS=(queue full)
declare -A LOOP_SCRIPT=([queue]=scripts/queue/loop.sh [full]=scripts/gate/full-loop.sh)

die() {
  echo "bench-night: $*" >&2
  exit 2
}
say() { echo "$*" | tee -a "${LOG:-/dev/null}"; }
hm() { date -d "@$1" +%H:%M; }
mins() { echo "$((($1 + 59) / 60)) min"; }
clock() { printf '%d:%02d' $(($1 / 3600)) $(($1 % 3600 / 60)); }

# loop_pid <queue|full>: the pid of that loop's driver in $RUNNER, or nothing.
loop_pid() {
  local pid
  for pid in $(pgrep -f "^bash ${LOOP_SCRIPT[$1]//./\\.}( |\$)"); do
    [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$RUNNER" ] && echo "$pid"
  done
}

# loop_unplaced <queue|full>: pids running that loop's command whose directory cannot be read.
loop_unplaced() {
  local pid
  for pid in $(pgrep -f "^bash ${LOOP_SCRIPT[$1]//./\\.}( |\$)"); do
    readlink "/proc/$pid/cwd" >/dev/null 2>&1 || echo "$pid"
  done
}

# login <driver> <model>: nothing when its CLI is logged in, else why not. Never logs in. Copilot keeps
# its login in the system credential store, which no command reads without a model call: only its
# presence on PATH is checked, and a refused login shows as error rows.
login() {
  local out
  case "$1" in
    claude-code)
      out="$(timeout 30 "${JK_BENCH_CLAUDE:-claude}" auth status 2>&1)" || true
      grep -q '"loggedIn": *true' <<<"$out" || echo "${JK_BENCH_CLAUDE:-claude} is not logged in (\`${JK_BENCH_CLAUDE:-claude} auth status\`)"
      ;;
    codex)
      out="$(timeout 30 codex login status 2>&1)" || true
      grep -q '^Logged in' <<<"$out" || echo "codex is not logged in (\`codex login status\`: ${out%%$'\n'*})"
      ;;
    cursor)
      out="$(timeout 30 cursor-agent status 2>&1)" || true
      grep -q 'Logged in' <<<"$out" || echo "cursor-agent is not logged in (\`cursor-agent status\`)"
      ;;
    grok)
      [ -n "${XAI_API_KEY:-}" ] || [ -f "$HOME/.grok/auth.json" ] || echo "grok is not logged in (no XAI_API_KEY, no ~/.grok/auth.json)"
      ;;
    muse)
      [ -f "$HOME/.config/muse/auth.json" ] || echo "muse is not logged in (no ~/.config/muse/auth.json)"
      ;;
    agy)
      if ! out="$(timeout 60 agy models 2>&1)"; then
        echo "agy is not signed in (\`agy models\`: ${out##*$'\n'})"
      elif ! grep -qF "$2" <<<"$out"; then
        echo "agy does not offer $2 (\`agy models\`)"
      fi
      ;;
    *) command -v "$1" >/dev/null || echo "$1 is not on PATH" ;;
  esac
}

pause() {
  { mkdir -p "$STATE/loops" && chmod 700 "$STATE"; } || die "cannot write $STATE"
  exec 9>>"$LOCK" || die "cannot open the gate lock $LOCK"
  echo "waiting for the gate lock $LOCK (a batch or Full cycle in flight finishes first)"
  flock -w "${NIGHT_PAUSE_WAIT:-10800}" 9 || die "the gate lock stayed taken; nothing was stopped"
  local name pid pgid
  for name in "${LOOPS[@]}"; do
    pid="$(loop_unplaced "$name" | xargs)"
    [ -z "$pid" ] || echo "$name loop: pid $pid runs ${LOOP_SCRIPT[$name]} in a directory this user cannot read; left alone"
    pid="$(loop_pid "$name")"
    if [ -z "$pid" ]; then
      echo "$name loop: not running in $RUNNER"
      continue
    fi
    [ "$(wc -w <<<"$pid")" -eq 1 ] || die "$name loop: several drivers run in $RUNNER ($pid); stop them by hand"
    rm -f "$STATE/loops/$name".*
    (umask 077 && cat "/proc/$pid/environ" >"$STATE/loops/$name.environ" 2>/dev/null) ||
      { rm -f "$STATE/loops/$name.environ"; echo "$name loop: its environment is unreadable; resume uses QUEUE_CHECKOUT and FULL_CHECKOUT"; }
    cat "/proc/$pid/cmdline" >"$STATE/loops/$name.cmdline"
    readlink "/proc/$pid/fd/1" >"$STATE/loops/$name.out" 2>/dev/null || true
    pgid="$(ps -o pgid= -p "$pid" | tr -d ' ')"
    if [ "$pgid" = "$pid" ]; then
      kill -TERM -- "-$pgid"
    else
      # shellcheck disable=SC2046 # one pid per word: the loop's children
      kill -TERM "$pid" $(pgrep -P "$pid")
    fi
    echo "$name loop: stopped (pid $pid); saved in $STATE/loops"
  done
}

resume() {
  local name pid out argv envs
  for name in "${LOOPS[@]}"; do
    pid="$(loop_pid "$name")"
    if [ -n "$pid" ]; then
      echo "$name loop: already running (pid $pid)"
      continue
    fi
    if [ -f "$STATE/loops/$name.cmdline" ]; then
      mapfile -d '' argv <"$STATE/loops/$name.cmdline"
    else
      argv=(bash "${LOOP_SCRIPT[$name]}")
    fi
    if [ -f "$STATE/loops/$name.environ" ]; then
      mapfile -d '' envs <"$STATE/loops/$name.environ"
    else
      envs=("HOME=$HOME" "PATH=$PATH" "QUEUE_CHECKOUT=${QUEUE_CHECKOUT:-$HOME/src/jk-queue-checkout}"
        "FULL_CHECKOUT=${FULL_CHECKOUT:-$HOME/src/jk-full-checkout}")
    fi
    out="$(cat "$STATE/loops/$name.out" 2>/dev/null)"
    case "$out" in /dev/* | pipe:* | socket:* | "") out="$STATE/$name-loop.out" ;; esac
    (cd "$RUNNER" && env -i "${envs[@]}" setsid -f "${argv[@]}" >>"$out" 2>&1 </dev/null) ||
      die "$name loop: did not start"
    rm -f "$STATE/loops/$name".*
    echo "$name loop: started in $RUNNER (${argv[*]}), output to $out"
  done
}

# prepare: every corpus repository a cell needs, checked out at its pin and built by this jk. A repository
# not set up, or at another commit than repos.toml pins, is set up again (clone, import, build, truth).
prepare() {
  local repo pin subdir head out failed=0
  while read -r repo; do
    read -r pin subdir < <(python3 -I -c 'import sys, tomllib; r = tomllib.load(open(sys.argv[1], "rb"))["repos"][sys.argv[2]]
print(r.get("commit") or "-", r.get("subdir") or "")' "$ROOT/bench/graph-retrieval/repos.toml" "$repo") ||
      die "$repo: not in repos.toml"
    [ "$pin" != - ] || pin=""
    # A project in a subdirectory is a repository of its own; its clone beside the corpus holds the pin.
    head="$(git -C "$CORPUS/${subdir:+../sources/}$repo" rev-parse HEAD 2>/dev/null)"
    if [ ! -f "$CORPUS/$repo/jk.toml" ] || { [ -n "$pin" ] && [ "$head" != "$pin" ]; }; then
      echo "$repo: setting up at ${pin:-its ref}"
      "$ROOT/bench/graph-retrieval/bench" setup --repo "$repo" </dev/null || { echo "$repo: setup failed"; failed=1; continue; }
    fi
    if out="$(cd "$CORPUS/$repo" && jk -u -x -a build -S 2>&1 </dev/null)"; then
      echo "$repo: set up at $(git -C "$CORPUS/$repo" rev-parse --short HEAD), builds"
    else
      echo "$repo: does not build with $(jk --version 2>/dev/null):"
      tail -n 5 <<<"$out" | sed 's/^/  /'
      failed=1
    fi
  done < <("$PLAN" plan | cut -f6 | grep -vx -- - | sort -u)
  [ "$failed" -eq 0 ] || die "a corpus repository is not ready"
}

# smoke: tier 0 for every agent on its pinned model, one line each, PASS only when both smoke tasks pass.
smoke() {
  local driver model effort arm why out rc failed=0
  while IFS=$'\t' read -r driver model effort arm; do
    why="$(login "$driver" "$model")"
    if [ -n "$why" ]; then
      echo "FAIL $driver $model/$effort: $why"
      failed=1
      continue
    fi
    out="$(cd "$ROOT" && bench/agent-loop/smoke-test --driver "$driver" --arm "$arm" --model "$model" --effort "$effort" 2>&1)"
    rc=$?
    if [ "$rc" -eq 0 ]; then
      echo "PASS $driver $model/$effort $arm: $(grep -c '^PASS ' <<<"$out") of 2 smoke tasks"
    else
      failed=1
      echo "FAIL $driver $model/$effort $arm: $(grep -m1 -E '^(FAIL|SKIP) ' <<<"$out" || tail -n 1 <<<"$out")"
    fi
  done < <("$PLAN" agents)
  return "$failed"
}

run() {
  local until="" dry=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --until) until="${2:-}"; shift 2 ;;
      --dry-run) dry=1; shift ;;
      *) die "unknown argument: $1" ;;
    esac
  done
  [ -n "$until" ] || die "run needs --until <time>, the deadline (01:00, or a date and time)"
  local now deadline
  now="$(date +%s)"
  deadline="$(date -d "$until" +%s 2>/dev/null)" || die "--until $until is not a time"
  [ "$deadline" -gt "$now" ] || deadline=$((deadline + 86400))
  [ "$dry" -eq 1 ] || [ -n "${NIGHT_INHIBITED:-}" ] || ! command -v systemd-inhibit >/dev/null ||
    exec env NIGHT_INHIBITED=1 systemd-inhibit --what=sleep:idle --who=bench-night \
      --why="agent benches until $(hm "$deadline")" bash "$0" run --until "$until"

  { mkdir -p "$STATE" && chmod 700 "$STATE"; } || die "cannot write $STATE"
  LOG="$STATE/$(date +%F).log"
  [ "$dry" -eq 1 ] && LOG=/dev/null
  say "bench night: $(date '+%F %H:%M') until $(hm "$deadline"), on $(hostname)$([ "$dry" -eq 1 ] && echo ', dry run: no agent runs')"

  local problems=() line name pid
  local want installed
  want="$(sed -nE 's/^version *= *"([^"]+)".*/\1/p' "$ROOT/jk.toml" | head -1)-SNAPSHOT"
  installed="$(jk --version 2>/dev/null | awk '{print $2}')"
  [ "$installed" = "$want" ] || problems+=("the installed jk is ${installed:-missing}, not this tree's $want: install it from main first")
  if [ -n "$installed" ]; then
    line="$("$PLAN" tools 2>&1)" || problems+=("$line")
  fi
  for name in "${LOOPS[@]}"; do
    pid="$(loop_pid "$name")"
    [ -z "$pid" ] || problems+=("the $name loop is running (pid $pid): scripts/bench-night.sh pause")
    pid="$(loop_unplaced "$name" | xargs)"
    [ -z "$pid" ] || problems+=("pid $pid runs ${LOOP_SCRIPT[$name]} in a directory this user cannot read")
  done
  exec 9>>"$LOCK" || die "cannot open the gate lock $LOCK"
  flock -n 9 || problems+=("a gate holds $LOCK")
  [ "$dry" -eq 1 ] && flock -u 9

  local plan
  plan="$("$PLAN" plan)" || die "night-plan failed"
  declare -A skip=()
  local driver model repo why
  while IFS=$'\t' read -r driver model; do
    why="$(login "$driver" "$model")"
    [ -z "$why" ] || { skip[$driver]="$why"; say "not logged in: $why; its cells are skipped"; }
  done < <(cut -f3,9 <<<"$plan" | awk '!seen[$1]++')
  while read -r repo; do
    [ -f "$CORPUS/$repo/jk.toml" ] ||
      { skip[$repo]="$repo is not set up"; say "not set up: $repo (scripts/bench-night.sh prepare); its cells are skipped"; }
  done < <(cut -f6 <<<"$plan" | grep -vx -- - | sort -u)
  for line in "${problems[@]}"; do say "problem: $line"; done
  [ "$dry" -eq 1 ] || [ ${#problems[@]} -eq 0 ] || die "nothing ran"

  start=$now at=$now full=0
  n=0 ran=0 skipped=0 stopped=0 late=0
  declare -A fails=() step_n=() step_s=() step_late=()
  cells <<<"$plan"
  extra
  if [ "$dry" -eq 1 ]; then
    local step
    for step in 1 2 3; do
      [ -n "${step_n[$step]:-}" ] || continue
      echo "step $step, $(step_name "$step"): ${step_n[$step]} cells, ~$(clock "${step_s[$step]}") (h:mm)$(
        [ -z "${step_late[$step]:-}" ] || echo "; ${step_late[$step]} of them do not fit before $(hm "$deadline")")"
    done
    echo "$n cells, ~$(clock "$full") (h:mm) for all of them; those not skipped ~$(clock $((at - start))), deadline $(hm "$deadline") in $(clock $((deadline - start)))"
    [ "$late" -eq 0 ] || echo "does not fit before $(hm "$deadline"): $late cells, marked [after the deadline] above"
    return 0
  fi
  say "$(date +%H:%M) done: $ran cells ran, $skipped skipped. Rows: bench/agent-loop/results/$(date +%F)-change-tasks/; log: $LOG"
  say "Nothing is banked. Review the rows, then bank and re-render the claims as a separate step."
}

step_name() {
  case "$1" in
    1) echo "${NIGHT_FIRST:-copilot} alone, with the code tools" ;;
    2) echo "no-code-tools baseline" ;;
    3) echo "with the code tools" ;;
  esac
}

# cut_by_file <driver>: why the night's skip file cuts it from steps 2 and 3, else nothing.
cut_by_file() {
  [ -f "$STATE/skip" ] && grep -qx -- "$1" "$STATE/skip" && echo "cut by $STATE/skip"
}

# cells: run (or, dry, list) the plan lines on stdin, in order; run's locals are this function's too.
cells() {
  local step tier driver arm task repo runs first model effort est source cmd why rc last when
  while IFS=$'\t' read -r step tier driver arm task repo runs first model effort est source; do
    [ -n "$step" ] || continue
    n=$((n + 1))
    if [ "$tier" = 0 ]; then
      cmd=(bench/agent-loop/smoke-test --driver "$driver" --arm "$arm" --model "$model" --effort "$effort")
    else
      cmd=(bench/agent-loop/change-tasks --driver "$driver" --arm "$arm" --task "$task" --runs "$runs"
        --first-run "$first" --model "$model" --effort "$effort")
    fi
    why="${skip[$driver]:-${skip[$repo]:-}}"
    [ "$step" = 1 ] || [ -n "$why" ] || why="$(cut_by_file "$driver")"
    [ "${fails[$driver.$step]:-0}" -lt 2 ] || why="two error rows in a row for $driver"
    if [ "$dry" -eq 1 ]; then
      full=$((full + est))
      step_n[$step]=$((${step_n[$step]:-0} + 1))
      step_s[$step]=$((${step_s[$step]:-0} + est))
      if [ -n "$why" ]; then
        when="skipped"; why="  [$why]"
      else
        at=$((at + est)) when="done at +$(clock $((at - start)))"
        if [ "$at" -gt "$deadline" ]; then
          why="  [after the deadline]"
          late=$((late + 1)) step_late[$step]=$((${step_late[$step]:-0} + 1))
        fi
      fi
      printf 'step %s tier %s  ~%-7s %-14s %s%s\n    expected from %s\n' "$step" "$tier" "$(mins "$est")" "$when" \
        "${cmd[*]}" "$why" "$source"
      continue
    fi
    if [ "$stopped" -eq 1 ] || [ -n "$why" ]; then
      say "skip: ${cmd[*]} (${why:-deadline})"
      skipped=$((skipped + 1))
      continue
    fi
    now="$(date +%s)"
    if [ $((now + est)) -gt "$deadline" ]; then
      say "skip: ${cmd[*]} (needs ~$(mins "$est"), $(mins $((deadline - now))) left)"
      skipped=$((skipped + 1))
      continue
    fi
    say "$(date +%H:%M) step $step tier $tier ~$(mins "$est"): ${cmd[*]}"
    (cd "$ROOT" && timeout --kill-after=60 $((deadline - now)) "${cmd[@]}") >>"$LOG" 2>&1
    rc=$?
    ran=$((ran + 1))
    if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
      say "stopped at the deadline: ${cmd[*]}"
      stopped=1
      continue
    fi
    # A smoke run's rows are its own; its FAIL is a result, not an error row.
    [ "$tier" != 0 ] || continue
    last="$(tail -n 1 "$ROOT/bench/agent-loop/results/$(date +%F)-change-tasks/rows.jsonl" 2>/dev/null)"
    if grep -q '"outcome": "error"' <<<"$last"; then
      fails[$driver.$step]=$((${fails[$driver.$step]:-0} + 1))
    else
      fails[$driver.$step]=0
    fi
  done
}

# extra: step 4, the commands in $NIGHT_EXTRA, each while time is left and stopped at the deadline.
extra() {
  local lines=() line
  [ -z "${NIGHT_EXTRA:-}" ] || mapfile -t lines < <(grep -v '^[[:space:]]*\(#\|$\)' "$NIGHT_EXTRA")
  if [ "$dry" -eq 1 ]; then
    for line in "${lines[@]}"; do printf 'step 4  ~unknown (no earlier runs)  %s\n' "$line"; done
    [ ${#lines[@]} -gt 0 ] || echo "step 4  nothing: NIGHT_EXTRA names no commands"
    return 0
  fi
  for line in "${lines[@]}"; do
    now="$(date +%s)"
    if [ "$stopped" -eq 1 ] || [ "$now" -ge "$deadline" ]; then
      say "skip: $line (deadline)"
      continue
    fi
    say "$(date +%H:%M) step 4: $line"
    (cd "$ROOT" && timeout --kill-after=60 $((deadline - now)) bash -c "$line") >>"$LOG" 2>&1 || true
  done
}

case "${1:-}" in
  run) shift; run "$@" ;;
  smoke) smoke ;;
  pause) pause ;;
  resume) resume ;;
  prepare) prepare ;;
  *) sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
