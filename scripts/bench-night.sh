#!/usr/bin/env bash
# A night of agent benches on an otherwise idle host, every step run to its end. Every agent runs its pinned model and effort, one run a cell. The plan is
# bench/agent-loop/night-plan's, in tiers (0 smoke-test, 1 a screen, 2 a sliver of the claims' tasks):
# step 1 the new agent ($NIGHT_FIRST, copilot) alone through tiers 0, 1 and 2 with JumpKick's code
# tools; step 2 every agent without them (no-code-tools, arm jk-nograph) on every task of tiers 1 and 2;
# step 3 every other agent with them through tiers 0, 1 and 2; step 4 the commands in $NIGHT_EXTRA, one
# per line. Each cell is one smoke-test (tier 0) or change-tasks invocation. --until is a soft
# deadline: no cell is skipped or stopped for it; past it, each cell's start logs how far past, what is
# done and left, and the estimate for the rest. Rows land where the scoreboard reads them,
# bench/agent-loop/results/<date>-change-tasks/ (smoke rows under $AGENT_LOOP_HOME/smoke/); nothing is
# banked or rendered. At the end the night prints its tables (bench/agent-loop/night-summary): per
# agent and arm, per task and code tool, per task and agent with the tools against without, and every
# cell, with the tool hit rate, wall time, turns, tokens by kind, dollars and diagnostics.
#
# Usage:
#   scripts/bench-night.sh run --until 01:00 [--dry-run]   # the night; --dry-run lists it, runs no agent
#   scripts/bench-night.sh status                          # the night's done, running, left, ETA and tables
#   scripts/bench-night.sh smoke                           # tier 0 for every agent now, one line each
#   scripts/bench-night.sh prepare                         # set up and build the corpus repositories
#
# The dry run prints each step's cells and minutes, when the night would end, and the cells that would
# start past the soft deadline. To cut an agent from steps 2 and 3, start with NIGHT_SKIP=copilot, or
# during the night write its name into $NIGHT_STATE/skip (one per line): each cell reads it before it
# starts. `status` reads the night's progress under $NIGHT_STATE/night, running or ended.
#
# `run`, the dry run too, first puts every cell's arguments to its harness (smoke-test or change-tasks
# --check), which parses them as the cell would, and refuses to start when a harness refuses one: an
# arm, driver or task it does not know. It then checks, and refuses a real run on any failure: the
# installed jk is this tree's version and lists every MCP tool a step calls. It names every agent CLI
# that is not logged in and every corpus repository not set up, and skips their cells; it never logs
# in. An agent whose two cells in a row end in an error row (a preflight that failed) is skipped for the
# rest of the step. A void cell is one that did not run: its agent spent no tokens because its CLI
# refused it for a login or a usage limit (outcome `void`, neither green nor red). An agent whose two
# cells in a row are void is skipped for the rest of the night, and the log says why.
#
#   NIGHT_STATE     the night's progress and its log (default /var/tmp/jk-bench-night)
#   NIGHT_EXTRA     step 4's file of commands, run from this tree (default none)
#   NIGHT_HARNESS   the directory of smoke-test and change-tasks, from this tree (default bench/agent-loop)
#   NIGHT_DRIVERS, NIGHT_FIRST, NIGHT_SKIP, NIGHT_NOGRAPH, NIGHT_MAX_MINUTES, NIGHT_RESULTS
#                   the plan's (bench/agent-loop/night-plan)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE="${NIGHT_STATE:-/var/tmp/jk-bench-night}"
PLAN="$ROOT/bench/agent-loop/night-plan"
NIGHT="$STATE/night"
RESULTS="${NIGHT_RESULTS:-$ROOT/bench/agent-loop/results}"
HARNESS="${NIGHT_HARNESS:-bench/agent-loop}"
SUMMARY="$ROOT/bench/agent-loop/night-summary"
# Where smoke-test writes its rows, as it resolves it.
SMOKE_ROWS="$(python3 -I -c 'import sys; sys.path.insert(0, sys.argv[1]); import benchtools
print(benchtools.scratch("AGENT_LOOP_HOME", "agent-loop") / "smoke")' "$ROOT/bench")" || exit 2
CORPUS="$("$PLAN" corpus)" || exit 2

die() {
  echo "bench-night: $*" >&2
  exit 2
}
say() { echo "$*" | tee -a "${LOG:-/dev/null}"; }
hm() { date -d "@$1" +%H:%M; }
mins() { echo "$((($1 + 59) / 60)) min"; }
clock() { printf '%d:%02d' $(($1 / 3600)) $(($1 % 3600 / 60)); }

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
  [ -n "$until" ] || die "run needs --until <time>, the soft deadline (01:00, or a date and time)"
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

  local problems=() line
  local want installed
  want="$(sed -nE 's/^version *= *"([^"]+)".*/\1/p' "$ROOT/jk.toml" | head -1)-SNAPSHOT"
  installed="$(jk --version 2>/dev/null | awk '{print $2}')"
  [ "$installed" = "$want" ] || problems+=("the installed jk is ${installed:-missing}, not this tree's $want: install it from main first")
  if [ -n "$installed" ]; then
    line="$("$PLAN" tools 2>&1)" || problems+=("$line")
  fi

  local plan refused
  plan="$("$PLAN" plan)" || die "night-plan failed"
  refused="$(check_cells <<<"$plan")" ||
    { say "$refused"; die "a cell has arguments its harness refuses: fix the plan or the harness; nothing ran"; }
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
  n=0 ran=0 skipped=0 late=0
  declare -A fails=() voids=() step_n=() step_s=() step_late=()
  if [ "$dry" -eq 0 ]; then
    # The night's progress, for the past-the-deadline lines and `status`: its cells with their expected
    # seconds, and a line as each starts, ends or is skipped.
    { rm -rf "$NIGHT" && mkdir -p "$NIGHT"; } || die "cannot write $NIGHT"
    printf '%s\t%s\t%s\t%s\n' "$$" "$start" "$deadline" "$LOG" >"$NIGHT/info"
    awk -F'\t' -v OFS='\t' '$1 != "" {print ++i, $11, "step " $1 " tier " $2 ": " $3 " " $4 " " $5}' <<<"$plan" >"$NIGHT/cells"
    local planned
    planned="$(wc -l <"$NIGHT/cells")"
    extra_lines | awk -v OFS='\t' -v n="$planned" '{print n + NR, "-", "step 4: " $0}' >>"$NIGHT/cells"
    : >"$NIGHT/progress"
  fi
  cells <<<"$plan"
  extra
  if [ "$dry" -eq 1 ]; then
    local step
    for step in 1 2 3; do
      [ -n "${step_n[$step]:-}" ] || continue
      echo "step $step, $(step_name "$step"): ${step_n[$step]} cells, ~$(clock "${step_s[$step]}") (h:mm)$(
        [ -z "${step_late[$step]:-}" ] || echo "; ${step_late[$step]} of them past $(hm "$deadline")")"
    done
    echo "$n cells, ~$(clock "$full") (h:mm) for all of them; those not skipped ~$(clock $((at - start))), ending ~$(hm "$at"); soft deadline $(hm "$deadline") in $(clock $((deadline - start)))"
    [ "$late" -eq 0 ] || echo "past the soft deadline $(hm "$deadline"): $late cells, marked [after the deadline] above; they still run"
    return 0
  fi
  say "$(date +%H:%M) done: $ran cells ran, $skipped skipped$([ "$(date +%s)" -le "$deadline" ] || echo ", past the soft deadline $(hm "$deadline")"). Rows: $RESULTS/<date>-change-tasks/; log: $LOG"
  "$SUMMARY" --since "$(date -d "@$start" +%F)" 2>&1 | tee -a "$LOG"
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

# mark <cell number> <start|end|skip>: one line of the night's progress.
mark() { printf '%s\t%s\t%s\n' "$1" "$2" "$(date +%s)" >>"$NIGHT/progress"; }

# progress: done, running, left and the estimate for the rest of the night in $NIGHT, in one line.
progress() {
  awk -F'\t' -v now="$(date +%s)" '
    FNR == NR { est[$1] = $2; what[$1] = $3; total++; next }
    $2 == "start" { started[$1] = $3 }
    $2 == "end" || $2 == "skip" { finished[$1] = 1 }
    END {
      for (c in est) {
        if (c in finished) { done++; continue }
        if (c in started) {
          running++; spent = now - started[c]
          run = run sprintf("%s%s, %d:%02d of ~%s", run == "" ? "" : "; ", what[c], spent / 3600, spent % 3600 / 60,
                            est[c] == "-" ? "unknown" : sprintf("%d:%02d", est[c] / 3600, est[c] % 3600 / 60))
          if (est[c] != "-" && est[c] > spent) rest += est[c] - spent
          continue
        }
        left++
        if (est[c] == "-") unknown++; else rest += est[c]
      }
      printf "%d done, %d running%s, %d left; ~%d:%02d (h:mm) more, ending ~%s", done, running,
        running ? " (" run ")" : "", left, rest / 3600, rest % 3600 / 60, strftime("%H:%M", now + rest)
      if (unknown) printf " (and %d step-4 command%s without an estimate)", unknown, unknown == 1 ? "" : "s"
      printf "\n"
    }' "$NIGHT/cells" "$NIGHT/progress"
}

# past: when the soft deadline is past, one line saying so, with the night's progress.
past() {
  local now
  now="$(date +%s)"
  [ "$now" -le "$deadline" ] || say "$(date +%H:%M) past the soft deadline $(hm "$deadline") by $(clock $((now - deadline))): $(progress)"
}

# status: the night in $NIGHT, running or ended, its progress and its tables so far.
status() {
  [ -f "$NIGHT/info" ] || die "no night has started (no $NIGHT/info)"
  local pid start deadline log state
  IFS=$'\t' read -r pid start deadline log <"$NIGHT/info"
  if kill -0 "$pid" 2>/dev/null && grep -q 'bench-night' "/proc/$pid/cmdline" 2>/dev/null; then
    state="running (pid $pid)"
  else
    state="not running"
  fi
  echo "bench night started $(date -d "@$start" '+%F %H:%M'), soft deadline $(hm "$deadline"), $state; log: $log"
  [ "$(date +%s)" -le "$deadline" ] || echo "past the soft deadline by $(clock $(($(date +%s) - deadline)))"
  progress
  echo
  "$SUMMARY" --since "$(date -d "@$start" +%F)"
}

# cell_cmd: the command of the plan line in its caller's step, tier, driver, … into its caller's cmd.
cell_cmd() {
  if [ "$tier" = 0 ]; then
    cmd=("$HARNESS/smoke-test" --driver "$driver" --arm "$arm" --model "$model" --effort "$effort")
  else
    cmd=("$HARNESS/change-tasks" --driver "$driver" --arm "$arm" --task "$task" --runs "$runs"
      --first-run "$first" --model "$model" --effort "$effort")
  fi
}

# check_cells: every plan line on stdin, its command's arguments put to its own harness's --check, which
# parses them as a run would and starts nothing. Prints each cell a harness refuses; 1 when any is.
check_cells() {
  local step tier driver arm task repo runs first model effort est source cmd harness out failed=0
  declare -A args=()
  while IFS=$'\t' read -r step tier driver arm task repo runs first model effort est source; do
    [ -n "$step" ] || continue
    cell_cmd
    args[${cmd[0]}]+="$(IFS=$'\t' && echo "${cmd[*]:1}")"$'\n'
  done
  for harness in "${!args[@]}"; do
    out="$(cd "$ROOT" && printf '%s' "${args[$harness]}" | "$harness" --check 2>&1)" ||
      { failed=1; echo "refused by $harness:"; echo "  ${out//$'\n'/$'\n'  }"; }
  done
  return "$failed"
}

# cells: run (or, dry, list) the plan lines on stdin, in order; run's locals are this function's too.
# Every cell runs, the soft deadline past or not; past it, each start logs what is done and left.
cells() {
  local step tier driver arm task repo runs first model effort est source cmd why when rows before new
  while IFS=$'\t' read -r step tier driver arm task repo runs first model effort est source; do
    [ -n "$step" ] || continue
    n=$((n + 1))
    cell_cmd
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
    if [ -n "$why" ]; then
      say "skip: ${cmd[*]} ($why)"
      skipped=$((skipped + 1))
      mark "$n" skip
      continue
    fi
    past
    say "$(date +%H:%M) step $step tier $tier ~$(mins "$est"): ${cmd[*]}"
    # The rows this cell writes: those its harness appends to the file of the day it starts.
    if [ "$tier" = 0 ]; then rows="$SMOKE_ROWS/$(date +%F)/rows.jsonl"; else rows="$RESULTS/$(date +%F)-change-tasks/rows.jsonl"; fi
    before="$(wc -l 2>/dev/null <"$rows" || echo 0)"
    mark "$n" start
    (cd "$ROOT" && "${cmd[@]}") >>"$LOG" 2>&1
    mark "$n" end
    ran=$((ran + 1))
    new="$(tail -n "+$((before + 1))" "$rows" 2>/dev/null)"
    [ -n "$new" ] || continue
    if ! grep -qv '"outcome": "void"' <<<"$new"; then
      voids[$driver]=$((${voids[$driver]:-0} + 1))
      if [ "${voids[$driver]}" -ge 2 ]; then
        skip[$driver]="two void cells in a row for $driver: $(void_why <<<"$new")"
        say "$(date +%H:%M) void: ${skip[$driver]}; $driver is skipped for the rest of the night"
      fi
      continue
    fi
    voids[$driver]=0
    # A smoke run's FAIL is a result, not an error row.
    [ "$tier" != 0 ] || continue
    if grep -q '"outcome": "error"' <<<"$(tail -n 1 <<<"$new")"; then
      fails[$driver.$step]=$((${fails[$driver.$step]:-0} + 1))
    else
      fails[$driver.$step]=0
    fi
  done
}

# void_why: the finding of the last of the rows on stdin, the reason its cell was void.
void_why() {
  tail -n 1 | python3 -I -c 'import json, sys; print(json.loads(sys.stdin.read()).get("finding") or "no finding")'
}

# extra_lines: step 4's commands, from $NIGHT_EXTRA, one per line.
extra_lines() {
  [ -z "${NIGHT_EXTRA:-}" ] || grep -v '^[[:space:]]*\(#\|$\)' "$NIGHT_EXTRA"
}

# extra: step 4, the commands in $NIGHT_EXTRA, each run to its end like every cell.
extra() {
  local lines=() line i
  mapfile -t lines < <(extra_lines)
  if [ "$dry" -eq 1 ]; then
    for line in "${lines[@]}"; do printf 'step 4  ~unknown (no earlier runs)  %s\n' "$line"; done
    [ ${#lines[@]} -gt 0 ] || echo "step 4  nothing: NIGHT_EXTRA names no commands"
    return 0
  fi
  i=$n
  for line in "${lines[@]}"; do
    i=$((i + 1))
    past
    say "$(date +%H:%M) step 4: $line"
    mark "$i" start
    (cd "$ROOT" && bash -c "$line") >>"$LOG" 2>&1 || true
    mark "$i" end
  done
}

case "${1:-}" in
  run) shift; run "$@" ;;
  smoke) smoke ;;
  prepare) prepare ;;
  status) status ;;
  *) sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
