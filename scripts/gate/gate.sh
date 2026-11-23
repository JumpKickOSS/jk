#!/usr/bin/env bash
# The gate a tip must pass, in one of three tiers. Each detaches a checkout at the tip, runs
# `jk guard` and never pushes; the Full and Queue tiers then check that the engine which served the
# tests is the tip's and run the shell fixtures. Both start with the reinstall without tests
# (`jk install -S`), so every test they run runs under the tip's own engine: test verdicts are keyed
# by the engine that ran them, so a change to the engine reruns every suite in the gate that lands
# it, and the gate after it reuses them. The install that runs tests after it, under the engine it
# installed, restores the native client the reinstall linked: the log's `NATIVE_LINKS
# reinstall=<n> install=<n>` line counts the native images each of the two linked.
#
# The Full tier (GATE_TIER=full, the default): after the reinstall, `jk install -g -c` (the fast
# tier with the gated tiers, run once) under a freshly started engine, the docs suite (`jk test -s
# docs`, alone), the release suite, the slow tier when a plugin (a member under plugins/) or a root
# build file changed since GATE_BASE, the release notes, and a check that
# scripts/gate/test-reads.txt lists every doc the suites were seen reading
# (scripts/gate/docs-only.sh check). A red `jk install -g` runs once more under the same inputs,
# which runs only the classes that did not pass, and jk records those that pass now as flaky. When
# the first run was red only in test classes, the re-run's verdict counts; a class still red there
# keeps the gate red.
#
# The Queue tier (GATE_TIER=queue), the one that blocks a push from the merge queue: after the
# reinstall, one install that runs every module's fast tier (`jk install -c`), then, under a
# freshly started engine, the integration suite of each module changed since GATE_BASE
# (scripts/gate/changed-modules.sh; every module when a root build file changed) and the curated
# lane's classes (curated-integration.txt) in the other modules, as one `jk test`, then the docs suite (`jk test -s
# docs`) and the tree suite (`jk test -s tree`, the tests that scan every module's sources). The
# Full tier runs the tree suite inside `jk install -g`, as one of the guard suites. Each changed
# suite runs only the classes scripts/gate/rank-integration.sh ranks highest for the batch, within
# GATE_INTEG_BUDGET, with the class records the Full tier shipped to <JK_QUEUE_REMOTE>/full-records
# when that queue is a local directory (scripts/gate/class-records.sh); the log says how many of each
# module's classes have a record, names each class left for the Full tier on a `DEFERRED <module>
# <class> (<why>)` line, and each changed module's count on an `INTEG_RANKED` line. jk passes a run red only in the classes .jk/test/quarantine.toml holds; a run red in other
# classes runs them once more under the same inputs, and passes when they pass there. Each class
# still red is a `FAILED <module> <class>` line of the log, its failure in g<n>-failures.txt.
#
# In both tiers, each class that passed on such a re-run is a `FLAKY <module> <class> passed on the
# re-run` line of the log, read from the `passedOnRerun` of its row in `jk results -O jsonl`. The
# gate reads a transcript only when it is the run just made (scripts/gate/flaky.sh ran): `jk
# results` prints the latest run that kept one, and a run red in a transcript the gate cannot read
# stays red, with a line saying which run jk printed.
#
# A red `jk guard` writes a `GUARD FAIL <check>` line for each check it failed, from its own
# transcript: the id of each rule a guard lane broke, `<module> <task>` for each other step that
# failed, and `format` when guard left the checkout changed. An internal error (exit 70) names no
# check, nor does a transcript of another run.
#
# Every tier runs every quarantined class. jk records each class's runs and quarantines a flaky one
# in the checkout; the gate saves that file, restores the checkout, and then opens a ticket for each
# class jk added and submits the file, with their tickets and without the classes whose ticket
# closed, to the merge queue (scripts/gate/flaky.sh review, its output in g<n>-quarantine.log).
#
# The docs tier (GATE_TIER=docs), which the merge queue runs in place of the Queue tier when
# everything since GATE_BASE is docs-only (scripts/gate/docs-only.sh range): `jk guard`, which holds
# the link check and the Markdown rules, `bench/scoreboard --render --check` and the docs suite
# (`jk test -s docs`, the tests that scan the doc tree). It installs nothing and runs no other test,
# and is red when the range is not docs-only.
#
# Its log is $GATE_LOG_DIR/integ-gate-<n>.log: one line per step, the verdict `GREEN <sha>`
# or a line starting `RED` (or the checkout's refusal), and `GATE<n> DONE` last. Per-step output
# lands beside it as g<n>-<step>.log. Run it through scripts/gate/run.sh, which takes the host's
# gate lock first.
#
# Usage: scripts/gate/gate.sh <n> <tip-sha>
#
#   GATE_CHECKOUT     checkout to detach at the tip (default: the one holding this script); clean
#   GATE_LOG_DIR      where the logs go (default /var/tmp/jk-gate)
#   GATE_BRANCH       fetch this branch from origin before the checkout (default: no fetch)
#   GATE_TIER         full (default), queue or docs
#   GATE_BASE         the Queue tier's changed modules are those changed since this commit
#                     (default origin/main), and the docs tier's range starts there; in the Full
#                     tier, the slow tier runs when a plugin changed since it (default: no slow tier)
#   GATE_INTEG_BUDGET the Queue tier's ranked integration classes hold at most this many seconds of
#                     recorded class wall across the changed modules (default 900; 0 runs their
#                     whole suites)
#   GATE_INTEG_POLE   the longest class the ranking runs without having to (default 90 s)
#   PRE_MODULES       modules whose whole suites run first; PRE_MODULE_SUITES their suites
#   PRE_SCRIPTS       scripts that must pass before guard; POST_SCRIPTS after the fixtures
#   PRE_REDO          non-empty: the pre-run ignores cached verdicts (`jk test -r`)
#   PRE_SESSION       non-empty: log what scripts/release-session.sh says about the tree
#   FLAKY_*           the quarantine review's settings (scripts/gate/flaky.sh); its ledger of
#                     tickets defaults to $GATE_LOG_DIR/quarantine-tickets.tsv
#   JK_QUEUE_REMOTE   the merge queue the quarantine change is submitted to (scripts/queue/common.sh)
#   JK_HOME           the JumpKick home the tip is installed into and run from (default ~/.jk)
#
# The whole body is one brace group, so bash has read all of it before the checkout below can
# change this file under it.
{
  set -uo pipefail
  export PATH="${JK_HOME:-$HOME/.jk}/bin:$PATH"

  n="${1:?usage: gate.sh <n> <tip-sha>}"
  tip="${2:?usage: gate.sh <n> <tip-sha>}"
  checkout="${GATE_CHECKOUT:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel)}"
  D="${GATE_LOG_DIR:-/var/tmp/jk-gate}"
  mkdir -p "$D"
  L="$D/integ-gate-$n.log"
  : >"$L"
  : >"$D/g$n-pre.log"
  : >"$D/g$n-failures.txt"

  noansi() { sed 's/\x1b\[[0-9;]*m//g' "$@"; }
  # Once tests have run, the quarantine file jk changed leaves the checkout, and the review files
  # its tickets and submits it; each at most once.
  quarantine_take() {
    [ -n "${quarantine:-}" ] || return 0
    bash scripts/gate/flaky.sh take "$D/g$n-quarantine.toml" >>"$L" 2>&1 || echo "QUARANTINE not restored" >>"$L"
  }
  quarantine_review() {
    [ -n "${quarantine:-}" ] || return 0
    quarantine_take
    quarantine=""
    GATE_LOG_DIR="$D" bash scripts/gate/flaky.sh review "$D/g$n-quarantine.toml" "$D/g$n-quarantine.log" >>"$L" 2>&1 \
      || echo "FLAKY review failed" >>"$L"
  }
  done_gate() { quarantine_review; echo "GATE$n DONE" >>"$L"; exit 0; }
  red() { echo "$1" >>"$L"; done_gate; }
  # flaky_named <details.jsonl> <command>: a FLAKY line in the log for each class that passed on the
  # re-run, when the transcript is that run of `jk <command>`.
  flaky_named() {
    bash scripts/gate/flaky.sh ran "$1" "$2" 2>/dev/null || return 0
    bash scripts/gate/flaky.sh passed "$1" 2>/dev/null | sed 's/$/ passed on the re-run/; s/^/FLAKY /' >>"$L"
  }
  # native_links <since>: how many native images the `jk install` that began at or after <since>
  # (epoch seconds) linked rather than restored, read from its transcript. An install that had
  # nothing to do keeps no transcript, and `jk results` prints an earlier run's: it linked none.
  native_links() {
    local c
    c="$(jk results -d 2>/dev/null </dev/null | jq -nR --argjson since "$(($1 * 1000))" '
      [inputs | fromjson? // empty] as $e
      | ($e | map(select(.type == "session-start")) | .[0]) as $s
      | if $s == null or $s.command != "install" or ($s.ts // 0) < $since then 0
        else $e | map(select(.type == "task-finish" and .task == "native-image" and .status == "SUCCESS")) | length
        end' 2>/dev/null)"
    echo "${c:-0}"
  }
  # guard_failed <details.jsonl> <since>: a `GUARD FAIL <check>` line for each check the red guard
  # run failed, read from its transcript: each rule a guard lane broke, by its id, and each other
  # step that failed, as `<module> <task>`. A transcript of a run begun before <since> (epoch
  # seconds), or of no build, is another run's: it names none.
  guard_failed() {
    jq -nrR --argjson since "$(($2 * 1000))" '
      [inputs | fromjson? // empty] as $e
      | ($e | map(select(.type == "session-start")) | .[0]) as $s
      | if $s == null or $s.command != "build" or ($s.ts // 0) < $since then empty
        else
          def lane: (.task // "") | (. == "guard" or startswith("guard-"));
          ($e[] | select(.type == "error" and lane) | .code // .task),
          ($e[] | select(.type == "task-finish" and .status == "FAIL" and (lane | not)) | "\(.module // "-") \(.task)")
        end' "$1" 2>/dev/null | grep . | sort -u | sed 's/^/GUARD FAIL /'
  }
  # The FAIL/T/E lines of the pre-run log, then the gate stops red.
  pre_red() { noansi "$D/g$n-pre.log" | grep -aE "^(FAIL|T |E )" | tail -8 >>"$L"; red "RED (pre)"; }

  tier="${GATE_TIER:-full}"
  case "$tier" in
    full | queue | docs) ;;
    *) red "RED (GATE_TIER is '$tier'; full, queue or docs)" ;;
  esac
  cd "$checkout" || red "NO CHECKOUT"
  [ -n "$(git status --porcelain)" ] && red "CHECKOUT DIRTY"
  if [ -n "${GATE_BRANCH:-}" ]; then
    git fetch -q origin "$GATE_BRANCH" || red "CHECKOUT FAILED"
  fi
  git checkout -q --detach "$tip" || red "CHECKOUT FAILED"
  if [ -n "${WSL_INTEROP:-}" ]; then
    jk engine stop >/dev/null 2>&1
    echo "engine restarted under WSL_INTEROP=$WSL_INTEROP" >>"$L"
  fi
  echo "tip $(git rev-parse --short HEAD) $(date +%H:%M:%S) jk $(jk --version) tier $tier" >>"$L"

  # Targeted tests first, fail fast: PRE_MODULES, then PRE_SCRIPTS.
  for pm in ${PRE_MODULES:-}; do
    margs=()
    for su in ${PRE_MODULE_SUITES:-}; do margs+=(-s "$su"); done
    jk test ${PRE_REDO:+-r} -m "$pm" "${margs[@]}" >>"$D/g$n-pre.log" 2>&1 </dev/null
    pe=$?
    echo "PRE $pm EXIT=$pe $(date +%H:%M:%S)" >>"$L"
    [ "$pe" -ne 0 ] && pre_red
  done
  for ps in ${PRE_SCRIPTS:-}; do
    pl="$D/g$n-pre-$(basename "$ps").log"
    bash "$ps" >"$pl" 2>&1
    pe=$?
    echo "PRE $ps EXIT=$pe $(date +%H:%M:%S)" >>"$L"
    [ "$pe" -ne 0 ] && { tail -8 "$pl" >>"$L"; red "RED (pre)"; }
  done
  if [ -n "${PRE_SESSION:-}" ]; then
    echo "release-session on this tree (jk.toml version $(bash scripts/release-version.sh)): $(bash scripts/release-session.sh 2>&1 | head -2 | tr '\n' ' ')" >>"$L"
  fi
  git status --porcelain >>"$L"

  since="$(date +%s)"
  jk guard >"$D/g$n-guard.log" 2>&1
  g=$?
  echo "GUARD_EXIT=$g $(date +%H:%M:%S)" >>"$L"
  git status --porcelain >>"$L"
  left=""
  if [ -n "$(git status --porcelain)" ]; then
    left=1
    printf '%s\n' "GUARD LEFT CHANGES" "GUARD FAIL format" >>"$L"
  fi
  if [ "$g" -ne 0 ] && [ "$g" -ne 70 ]; then
    jk results -O jsonl >"$D/g$n-guard.jsonl" 2>/dev/null </dev/null
    guard_failed "$D/g$n-guard.jsonl" "$since" >>"$L"
  fi
  if [ "$g" -ne 0 ] || [ -n "$left" ]; then
    noansi "$D/g$n-guard.log" | grep -vE '^\s*$' | tail -20 >>"$L"
    red "RED (guard)"
  fi

  if [ "$tier" = docs ]; then
    base="${GATE_BASE:-origin/main}"
    bash scripts/gate/docs-only.sh range "$base" HEAD 2>>"$L" || red "RED (not docs-only since $base)"
    bench/scoreboard --render --check >"$D/g$n-scoreboard.log" 2>&1
    sb=$?
    echo "SCOREBOARD_EXIT=$sb $(date +%H:%M:%S)" >>"$L"
    [ "$sb" -eq 0 ] || grep -v 'left alone$' "$D/g$n-scoreboard.log" | tail -8 >>"$L"
    quarantine=1
    rm -f "$D/g$n-quarantine.toml"
    jk test -s docs -c --no-ansi >"$D/g$n-docs.log" 2>&1 </dev/null
    ds=$?
    echo "DOCS_SUITE_EXIT=$ds $(date +%H:%M:%S)" >>"$L"
    [ "$ds" -eq 0 ] || noansi "$D/g$n-docs.log" | grep -aE "^(FAIL|T |E )" | tail -8 >>"$L"
    quarantine_take
    git status --porcelain >>"$L"
    if [ "$sb" -eq 0 ] && [ "$ds" -eq 0 ] && [ -z "$(git status --porcelain)" ]; then
      echo "GREEN $(git rev-parse HEAD)" >>"$L"
    else
      echo "RED" >>"$L"
    fi
    done_gate
  fi

  # The reinstall without tests, first: a change to the engine changes the stamp of every module's
  # test run, so tests run before it would run under the previous engine and leave verdicts that the
  # next gate, under this one, could not reuse. Then stop the engine the steps above ran on, so it
  # cannot serve the tests; the next jk starts a fresh one from the jar just installed. The install
  # after it restores the native client the reinstall linked: NATIVE_LINKS counts each one's links.
  since="$(date +%s)"
  jk install -S >"$D/g$n-reinstall.log" 2>&1
  m=$?
  echo "REINSTALL_EXIT=$m $(date +%H:%M:%S)" >>"$L"
  if [ "$m" -ne 0 ]; then
    noansi "$D/g$n-reinstall.log" | grep -vE '^\s*$' | tail -20 >>"$L"
    red "RED (reinstall)"
  fi
  linked="$(native_links "$since")"
  jk engine stop >/dev/null 2>&1
  quarantine=1
  rm -f "$D/g$n-quarantine.toml"

  m=0 s=0 r=0 rn=0 sl=0 dr=0 ds=0 ts=0
  if [ "$tier" = queue ]; then
    # tested <label> <jk args...>: one jk run that runs tests, its exit in $e. A run red only in
    # test classes runs once more under the same stamp, which runs only those classes: jk records
    # the classes that pass now as flaky, and the second run's verdict counts. A run still red names
    # each failed class in the log as `FAILED <module> <class>`, and the failures' messages and
    # stacks go to g<n>-failures.txt, for the merge queue to tell the branch's ticket.
    tested() {
      local label="$1" out="$D/g$n-$1"
      shift
      jk "$@" >"$out.log" 2>&1 </dev/null
      e=$?
      [ "$e" -eq 0 ] && return
      jk results -O jsonl >"$out.jsonl" 2>/dev/null </dev/null
      if ! why="$(bash scripts/gate/flaky.sh ran "$out.jsonl" "$1" 2>&1)"; then
        echo "TRANSCRIPT $label: $why, so its failed classes are unknown and do not run again" >>"$L"
      elif bash scripts/gate/flaky.sh failed "$out.jsonl" >/dev/null 2>&1; then
        out="$D/g$n-$label-rerun"
        jk "$@" >"$out.log" 2>&1 </dev/null
        e=$?
        echo "RERUN $label: its failed classes once more under the same inputs, EXIT=$e $(date +%H:%M:%S)" >>"$L"
        jk results -O jsonl >"$out.jsonl" 2>/dev/null </dev/null
        flaky_named "$out.jsonl" "$1"
        [ "$e" -eq 0 ] && return
      fi
      noansi "$out.log" | grep -aE "^(FAIL|T |E )|FAILED " | tail -20 >>"$L"
      bash scripts/gate/flaky.sh ran "$out.jsonl" "$1" 2>/dev/null || return 0
      bash scripts/gate/flaky.sh failed "$out.jsonl" 2>/dev/null | sed 's/^/FAILED /' >>"$L"
      jq -rR 'fromjson? // empty | select(.type == "error" and .code == "test-failure")
        | "\(.testClass)#\(.method // "?") (\(.module))", ((.stack // .message // "") | split("\n")[:12][] | "    " + .)' \
        "$out.jsonl" >>"$D/g$n-failures.txt" 2>/dev/null
    }
    # The one install that runs tests: every module's fast tier under the engine just installed,
    # then the reinstall, which installs that same engine again.
    since="$(date +%s)"
    tested install install -c
    m=$e
    echo "INSTALL_EXIT=$m $(date +%H:%M:%S)" >>"$L"
    echo "NATIVE_LINKS reinstall=$linked install=$(native_links "$since")" >>"$L"
    [ "$m" -eq 0 ] || red "RED (install)"
    # The integration runs below start a fresh engine from the jar just installed.
    jk engine stop >/dev/null 2>&1

    # integ <label> <jk test args...>: one integration run, red unless each failure passes when run
    # once more.
    integ() {
      local label="$1" e
      shift
      tested "$label" test "$@"
      echo "INTEG $label EXIT=$e $(date +%H:%M:%S)" >>"$L"
      [ "$e" -eq 0 ] || s=1
    }
    base="${GATE_BASE:-origin/main}"
    mods="$(bash scripts/gate/changed-modules.sh "$base" HEAD 2>>"$L")" || red "RED (changed modules since $base)"
    echo "CHANGED_MODULES since $base: $(tr '\n' ' ' <<<"${mods:-<none>}")" >>"$L"
    if [ "$mods" = "*" ]; then
      integ changed -s integration -c --no-ansi
    else
      # One `jk test`: the whole integration suite of each changed module, and the curated lane's
      # classes, each qualified with its module, in every other module the lane names.
      with=()
      for mo in $mods; do with+=(--with "$mo"); done
      lane="$(JK=jk bash scripts/curated-integration.sh --print ${with[@]+"${with[@]}"} 2>>"$L")" ||
        red "RED (curated lane: scripts/curated-integration.sh could not read its registry)"
      read -r -a cmd <<<"$lane"
      # Within the budget, each changed suite runs only its classes ranked highest for the batch
      # (scripts/gate/rank-integration.sh); the Full tier runs the classes it leaves.
      if [ -n "$mods" ] && [ "${GATE_INTEG_BUDGET:-900}" -gt 0 ]; then
        # The class records the Full tier shipped (scripts/gate/full-loop.sh), when the queue is here.
        records=""
        [[ -z "${JK_QUEUE_REMOTE:-}" || "$JK_QUEUE_REMOTE" =~ ^[^/:]+: ]] || records="$JK_QUEUE_REMOTE/full-records"
        # shellcheck disable=SC2086 # one changed module per word
        RANK_RECORDS="$records" bash scripts/gate/rank-integration.sh --budget "${GATE_INTEG_BUDGET:-900}" \
          --pole "${GATE_INTEG_POLE:-90}" "$base" HEAD $mods >"$D/g$n-ranking.txt" 2>>"$L" ||
          red "RED (ranking: scripts/gate/rank-integration.sh failed)"
        for mo in $mods; do
          awk -v m="$mo" '$2 == m && $1 == "DEFER" { d = 1 } END { exit !d }' "$D/g$n-ranking.txt" || continue
          while read -r c; do cmd+=(--class "$mo:$c"); done < <(awk -v m="$mo" '$2 == m && $1 == "RUN" { print $3 }' "$D/g$n-ranking.txt")
        done
        awk '
          { n[$2]++; t = $4; sub(/^~/, "", t); if ($1 == "RUN") { r[$2]++; s[$2] += t } else d[$2]++ }
          END { for (m in n) printf "INTEG_RANKED %s ran %d of %d classes (%.0fs recorded), deferred %d to the Full tier\n", m, r[m], n[m], s[m], d[m] }
        ' "$D/g$n-ranking.txt" | sort >>"$L"
        awk '$1 == "DEFER" { why = $5; for (i = 6; i <= NF; i++) why = why " " $i; print "DEFERRED " $2 " " $3 " (" why ")" }' \
          "$D/g$n-ranking.txt" >>"$L"
      fi
      integ integration "${cmd[@]:2}"
    fi
    # The docs suite checks the docs against the code, so a code change can break it too.
    tested docs test -s docs -c --no-ansi
    ds=$e
    echo "DOCS_SUITE_EXIT=$ds $(date +%H:%M:%S)" >>"$L"
    # The tests that scan every module's sources sit outside each module's fast tier, so that an
    # edit re-runs only them; they check the whole tree, so they run whatever changed.
    tested tree test -s tree -c --no-ansi
    ts=$e
    echo "TREE_SUITE_EXIT=$ts $(date +%H:%M:%S)" >>"$L"
  else
    since="$(date +%s)"
    jk install -g -c >"$D/g$n-snapshot.log" 2>&1
    s=$?
    echo "PUSHGATE_EXIT=$s $(date +%H:%M:%S)" >>"$L"
    echo "NATIVE_LINKS reinstall=$linked install=$(native_links "$since")" >>"$L"
    [ "$s" -ne 0 ] && noansi "$D/g$n-snapshot.log" | grep -aE "FAILED |OutOfMemory" >>"$L"
    noansi "$D/g$n-snapshot.log" | grep -E "^(FAIL|T |OK|E )" | tail -8 >>"$L"
    # A red `jk install -g` runs once more under the same inputs, which runs only the classes that
    # did not pass; jk records those that pass now as flaky. When the first run was red only in test
    # classes, the re-run's verdict counts.
    if [ "$s" -ne 0 ]; then
      jk results -O jsonl >"$D/g$n-snapshot.jsonl" 2>/dev/null
      jk install -g -c >"$D/g$n-rerun.log" 2>&1
      rs=$?
      echo "RERUN_EXIT=$rs $(date +%H:%M:%S)" >>"$L"
      jk results -O jsonl >"$D/g$n-rerun.jsonl" 2>/dev/null
      flaky_named "$D/g$n-rerun.jsonl" install
      if [ "$rs" -ne 0 ]; then
        noansi "$D/g$n-rerun.log" | grep -aE "^(FAIL|T |E )|FAILED " | tail -8 >>"$L"
      elif ! why="$(bash scripts/gate/flaky.sh ran "$D/g$n-snapshot.jsonl" install 2>&1 \
        && bash scripts/gate/flaky.sh ran "$D/g$n-rerun.jsonl" install 2>&1)"; then
        echo "RERUN green, but the gate cannot tell which classes failed ($why): the push gate stays red" >>"$L"
      elif bash scripts/gate/flaky.sh failed "$D/g$n-snapshot.jsonl" >/dev/null 2>&1; then
        s=0
      else
        echo "RERUN green, but the first run was red outside a test class: the push gate stays red" >>"$L"
      fi
    fi
  fi

  # The engine's commit, read after the tests ran under a freshly started engine. It counts when
  # it is the tip, or when no build input changed between it and the tip (a reused engine jar keeps
  # its parent's stamp). Empty or unknown does not count.
  ec=$(jk engine status 2>&1 | noansi | grep -m1 "Version" | grep -oE "commit [0-9a-f]+" | cut -d" " -f2)
  echo "ENGINE_COMMIT=${ec:-<empty>} (tip $(git rev-parse --short=12 HEAD))" >>"$L"
  not_inputs=(. ':(exclude).github' ':(exclude)docs' ':(exclude)scripts' ':(exclude)bench')
  eng=1
  ecf=""
  [ -n "$ec" ] && ecf=$(git rev-parse -q --verify "$ec^{commit}" 2>/dev/null)
  if [ -z "$ecf" ]; then
    echo "ENGINE NOT AT TIP: no engine answered or its commit ${ec:-<empty>} is unknown here; this gate does not count" >>"$L"
  elif [ "$ecf" = "$(git rev-parse HEAD)" ]; then
    eng=0
    echo "ENGINE CHECK: engine commit equals the tip" >>"$L"
  elif git diff --quiet "$ecf" HEAD -- "${not_inputs[@]}"; then
    eng=0
    echo "ENGINE CHECK: engine commit $(git rev-parse --short=12 "$ecf") differs from the tip only in .github/docs/scripts/bench (no build input changed): counts" >>"$L"
  else
    echo "ENGINE NOT AT TIP: the engine that served the tests ($(git rev-parse --short=12 "$ecf")) is not the tip's and build inputs differ: $(git diff --name-only "$ecf" HEAD -- "${not_inputs[@]}" | head -5 | tr '\n' ' '); this gate does not count" >>"$L"
  fi

  if [ "$tier" = full ]; then
    # The docs suite runs alone, not in `jk install -g`: one run of several suites records what they
    # read as one, and the docs this suite reads must stay apart from those any other suite reads.
    jk test -s docs -c --no-ansi >"$D/g$n-docs.log" 2>&1
    ds=$?
    echo "DOCS_SUITE_EXIT=$ds $(date +%H:%M:%S)" >>"$L"
    noansi "$D/g$n-docs.log" | grep -E "^(FAIL|T |OK|E )" | tail -4 >>"$L"
    jk test -s release >"$D/g$n-release.log" 2>&1
    r=$?
    echo "RELEASE_SUITE_EXIT=$r $(date +%H:%M:%S)" >>"$L"
    noansi "$D/g$n-release.log" | grep -E "^(FAIL|T |OK|E )" | tail -4 >>"$L"
    # The slow tier holds the framework and language e2e the plugins drive (Android, Grails, ...).
    if [ -n "${GATE_BASE:-}" ]; then
      mods="$(bash scripts/gate/changed-modules.sh "$GATE_BASE" HEAD 2>>"$L")" || red "RED (changed modules since $GATE_BASE)"
      plugins="$(grep -E '^(\*|plugins/)' <<<"$mods" | tr '\n' ' ')"
      if [ -n "$plugins" ]; then
        jk test -s slow -c --no-ansi >"$D/g$n-slow.log" 2>&1
        sl=$?
        echo "SLOW_EXIT=$sl (changed since $GATE_BASE: ${plugins% }) $(date +%H:%M:%S)" >>"$L"
        noansi "$D/g$n-slow.log" | grep -E "^(FAIL|T |OK|E )" | tail -8 >>"$L"
      else
        echo "SLOW skipped: no plugin or root build file changed since $GATE_BASE" >>"$L"
      fi
    fi
    # Every suite has now run: the docs they were seen reading are what a docs-only change may not touch.
    bash scripts/gate/docs-only.sh check >>"$L" 2>&1
    dr=$?
    echo "TEST_READS_EXIT=$dr $(date +%H:%M:%S)" >>"$L"
  fi

  ff=0
  for t in scripts/test-*.sh; do
    bash "$t" >"$D/g$n-fixture-$(basename "$t").log" 2>&1 || { echo "FIXTURE FAIL $t" >>"$L"; ff=1; }
  done
  echo "FIXTURES_FAIL=$ff $(date +%H:%M:%S)" >>"$L"
  for ps in ${POST_SCRIPTS:-}; do
    pl="$D/g$n-post-$(basename "$ps").log"
    bash "$ps" >"$pl" 2>&1
    pe=$?
    echo "POST $ps EXIT=$pe: $(tail -2 "$pl" | tr '\n' ' ' | cut -c1-200)" >>"$L"
    [ "$pe" -ne 0 ] && ff=1
  done
  if [ "$tier" = full ]; then
    # The entry of the release this tree becomes: the one the writing rules hold whole.
    rv=$(bash scripts/release-version.sh 2>>"$L") && bash scripts/release-notes.sh "$rv" >/dev/null 2>>"$L"
    rn=$?
    echo "RELEASE_NOTES_EXIT=$rn (${rv:-no version})" >>"$L"
    w=$(env -u CLAUDECODE -u AI_AGENT jk web -O jsonl 2>/dev/null | head -c 8)
    echo "jk web -O jsonl starts with: $w" >>"$L"
  fi
  quarantine_take
  git status --porcelain >>"$L"

  if [ "$eng" -eq 0 ] && [ "$m" -eq 0 ] && [ "$s" -eq 0 ] && [ "$r" -eq 0 ] && [ "$sl" -eq 0 ] && [ "$ff" -eq 0 ] \
    && [ "$rn" -eq 0 ] && [ "$dr" -eq 0 ] && [ "$ds" -eq 0 ] && [ "$ts" -eq 0 ] && [ -z "$(git status --porcelain)" ]; then
    echo "GREEN $(git rev-parse HEAD)" >>"$L"
  else
    echo "RED" >>"$L"
  fi

  quarantine_review

  # What a no-op build after the gate would still do: a gate that leaves work behind is a finding.
  [ "$tier" = full ] || done_gate
  for ex in "explain -g" "explain"; do
    xl="$D/g$n-noop-${ex// /_}.log"
    # shellcheck disable=SC2086 # $ex is a verb and its flag, split on purpose
    jk $ex >"$xl" 2>&1
    echo "NOOP jk $ex: $(noansi "$xl" | grep -aE 'Rebuild\]|Restore\]|Fully Cached\]' | tr '\n' ' ' | cut -c1-200)" >>"$L"
  done
  echo "engine: $(jk engine status 2>&1 | noansi | grep -aiE 'heap|rss|memory|uptime|pid' | head -5 | tr '\n' ' ')" >>"$L"
  done_gate
}
