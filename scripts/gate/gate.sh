#!/usr/bin/env bash
# The gate a tip must pass, in one of three tiers. Each detaches a checkout at the tip, runs
# `jk guard` and never pushes; the Full and Queue tiers then check that the engine which served the
# tests is the tip's and run the shell fixtures. Both start with the reinstall without tests
# (`jk install -S`), so every test they run runs under the tip's own engine: test verdicts are keyed
# by the engine that ran them, so a change to the engine reruns every suite in the gate that lands
# it, and the gate after it reuses them.
#
# The Full tier (GATE_TIER=full, the default): after the reinstall, `jk install -g -c` (the fast
# tier with the gated tiers, run once) under a freshly started engine, the docs suite (`jk test -s
# docs`, alone), the release suite, the slow tier when a plugin (a member under plugins/) or a root
# build file changed since GATE_BASE, the release notes, and a check that
# scripts/gate/test-reads.txt lists every doc the suites were seen reading
# (scripts/gate/docs-only.sh check). A red `jk install -g` runs once more, only to record the
# classes that pass on that re-run as flaky (scripts/gate/flaky.sh); its verdict stays red.
#
# The Queue tier (GATE_TIER=queue), the one that blocks a push from the merge queue: after the
# reinstall, one install that runs every module's fast tier (`jk install -c`), then, under a
# freshly started engine, the integration suite of each module changed since GATE_BASE
# (scripts/gate/changed-modules.sh; every module when a root build file changed) and the curated
# lane's classes (curated-integration.txt) in the other modules, then the docs suite (`jk test -s
# docs`). A run red only in quarantined classes (`flaky.sh excused`) passes; a run red in other
# classes runs them once more under the same inputs, and passes when they pass there, recorded as
# flaky. Each class still red is a `FAILED <module> <class>` line of the log, its
# failure in g<n>-failures.txt.
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
#   PRE_MODULES       modules whose whole suites run first; PRE_MODULE_SUITES their suites
#   PRE_SCRIPTS       scripts that must pass before guard; POST_SCRIPTS after the fixtures
#   PRE_REDO          non-empty: the pre-run ignores cached verdicts (`jk test -r`)
#   PRE_SESSION       non-empty: log what scripts/release-session.sh says about the tree
#   FLAKY_*           the flaky record and quarantine's settings (scripts/gate/flaky.sh); the
#                     history defaults to $GATE_LOG_DIR/flaky-history.tsv
#
# The whole body is one brace group, so bash has read all of it before the checkout below can
# change this file under it.
{
  set -uo pipefail
  export PATH="$HOME/.jk/bin:$PATH"

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
  done_gate() { echo "GATE$n DONE" >>"$L"; exit 0; }
  red() { echo "$1" >>"$L"; done_gate; }
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

  jk guard >"$D/g$n-guard.log" 2>&1
  g=$?
  echo "GUARD_EXIT=$g $(date +%H:%M:%S)" >>"$L"
  git status --porcelain >>"$L"
  [ -n "$(git status --porcelain)" ] && { echo "GUARD LEFT CHANGES" >>"$L"; g=99; }
  if [ "$g" -ne 0 ]; then
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
    jk test -s docs -c --no-ansi >"$D/g$n-docs.log" 2>&1 </dev/null
    ds=$?
    echo "DOCS_SUITE_EXIT=$ds $(date +%H:%M:%S)" >>"$L"
    [ "$ds" -eq 0 ] || noansi "$D/g$n-docs.log" | grep -aE "^(FAIL|T |E )" | tail -8 >>"$L"
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
  # cannot serve the tests; the next jk starts a fresh one from the jar just installed.
  jk install -S >"$D/g$n-reinstall.log" 2>&1
  m=$?
  echo "REINSTALL_EXIT=$m $(date +%H:%M:%S)" >>"$L"
  if [ "$m" -ne 0 ]; then
    noansi "$D/g$n-reinstall.log" | grep -vE '^\s*$' | tail -20 >>"$L"
    red "RED (reinstall)"
  fi
  jk engine stop >/dev/null 2>&1

  m=0 s=0 r=0 rn=0 sl=0 dr=0 ds=0
  if [ "$tier" = queue ]; then
    # tested <label> <excusable> <jk args...>: one jk run that runs tests, its exit in $e. A red run
    # is excused when <excusable> is set and it failed only in quarantined classes. Otherwise a run
    # red only in test classes runs once more under the same stamp, which runs only those classes:
    # the classes that pass now are recorded as flaky and the second run's verdict counts. A run
    # still red names each failed class in the log as `FAILED <module> <class>`, and the failures'
    # messages and stacks go to g<n>-failures.txt, for the merge queue to tell the branch's ticket.
    tested() {
      local label="$1" excusable="$2" out="$D/g$n-$1"
      shift 2
      jk "$@" >"$out.log" 2>&1 </dev/null
      e=$?
      [ "$e" -eq 0 ] && return
      jk results -O jsonl >"$out.jsonl" 2>/dev/null </dev/null
      if [ -n "$excusable" ] && excused "$label" "$out.jsonl"; then
        e=0
        return
      fi
      if bash scripts/gate/flaky.sh failed "$out.jsonl" >/dev/null 2>&1; then
        out="$D/g$n-$label-rerun"
        jk "$@" >"$out.log" 2>&1 </dev/null
        e=$?
        echo "RERUN $label: its failed classes once more under the same inputs, EXIT=$e $(date +%H:%M:%S)" >>"$L"
        jk results -O jsonl >"$out.jsonl" 2>/dev/null </dev/null
        GATE_LOG_DIR="$D" bash scripts/gate/flaky.sh record "$out.jsonl" "$(git rev-parse HEAD)" >>"$L" 2>&1
        [ "$e" -eq 0 ] && return
        if [ -n "$excusable" ] && excused "$label" "$out.jsonl"; then
          e=0
          return
        fi
      fi
      noansi "$out.log" | grep -aE "^(FAIL|T |E )|FAILED " | tail -20 >>"$L"
      bash scripts/gate/flaky.sh failed "$out.jsonl" 2>/dev/null | sed 's/^/FAILED /' >>"$L"
      jq -rR 'fromjson? // empty | select(.type == "error" and .code == "test-failure")
        | "\(.testClass)#\(.method // "?") (\(.module))", ((.stack // .message // "") | split("\n")[:12][] | "    " + .)' \
        "$out.jsonl" >>"$D/g$n-failures.txt" 2>/dev/null
    }
    # excused <label> <details.jsonl>: the run failed only in quarantined classes.
    excused() {
      GATE_LOG_DIR="$D" bash scripts/gate/flaky.sh excused "$2" >>"$L" 2>&1 || return 1
      echo "EXCUSED $1: red only in quarantined classes" >>"$L"
    }

    # The one install that runs tests: every module's fast tier under the engine just installed,
    # then the reinstall, which installs that same engine again.
    tested install "" install -c
    m=$e
    echo "INSTALL_EXIT=$m $(date +%H:%M:%S)" >>"$L"
    [ "$m" -eq 0 ] || red "RED (install)"
    # The integration runs below start a fresh engine from the jar just installed.
    jk engine stop >/dev/null 2>&1

    # integ <label> <jk test args...>: one integration run, red unless each failure is in a
    # quarantined class or passes when run once more.
    integ() {
      local label="$1" e
      shift
      tested "$label" excusable test "$@"
      echo "INTEG $label EXIT=$e $(date +%H:%M:%S)" >>"$L"
      [ "$e" -eq 0 ] || s=1
    }
    base="${GATE_BASE:-origin/main}"
    mods="$(bash scripts/gate/changed-modules.sh "$base" HEAD 2>>"$L")" || red "RED (changed modules since $base)"
    echo "CHANGED_MODULES since $base: $(tr '\n' ' ' <<<"${mods:-<none>}")" >>"$L"
    if [ "$mods" = "*" ]; then
      integ changed -s integration -c --no-ansi
    else
      margs=()
      for mo in $mods; do margs+=(-m "$mo"); done
      [ "${#margs[@]}" -eq 0 ] || integ changed -s integration -c -X --no-ansi "${margs[@]}"
      # The curated lane, one `jk test` per module, in the modules the run above did not cover.
      while read -r -a cmd; do
        cm=""
        for k in "${!cmd[@]}"; do [ "${cmd[$k]}" = -m ] && cm="${cmd[$((k + 1))]}"; done
        grep -Fxq -- "$cm" <<<"$mods" && continue
        integ "curated-${cm//\//-}" "${cmd[@]:2}"
      done < <(JK=jk bash scripts/curated-integration.sh --print)
    fi
    # The docs suite checks the docs against the code, so a code change can break it too.
    tested docs excusable test -s docs -c --no-ansi
    ds=$e
    echo "DOCS_SUITE_EXIT=$ds $(date +%H:%M:%S)" >>"$L"
  else
    jk install -g -c >"$D/g$n-snapshot.log" 2>&1
    s=$?
    echo "PUSHGATE_EXIT=$s $(date +%H:%M:%S)" >>"$L"
    [ "$s" -ne 0 ] && noansi "$D/g$n-snapshot.log" | grep -aE "FAILED |OutOfMemory" >>"$L"
    noansi "$D/g$n-snapshot.log" | grep -E "^(FAIL|T |OK|E )" | tail -8 >>"$L"
    # A red `jk install -g` runs once more under the same inputs, which runs only the classes that
    # did not pass; those that pass now are flaky, and the history keeps them. The verdict stays red.
    if [ "$s" -ne 0 ]; then
      jk install -g -c >"$D/g$n-rerun.log" 2>&1
      echo "RERUN_EXIT=$? $(date +%H:%M:%S)" >>"$L"
      jk results -O jsonl >"$D/g$n-rerun.jsonl" 2>/dev/null
      GATE_LOG_DIR="$D" bash scripts/gate/flaky.sh record "$D/g$n-rerun.jsonl" "$(git rev-parse HEAD)" >>"$L" 2>&1
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
    bash scripts/release-notes.sh 0.51.0 >/dev/null 2>>"$L"
    rn=$?
    echo "RELEASE_NOTES_EXIT=$rn" >>"$L"
    w=$(env -u CLAUDECODE -u AI_AGENT jk web -O jsonl 2>/dev/null | head -c 8)
    echo "jk web -O jsonl starts with: $w" >>"$L"
  fi
  git status --porcelain >>"$L"

  if [ "$eng" -eq 0 ] && [ "$m" -eq 0 ] && [ "$s" -eq 0 ] && [ "$r" -eq 0 ] && [ "$sl" -eq 0 ] && [ "$ff" -eq 0 ] \
    && [ "$rn" -eq 0 ] && [ "$dr" -eq 0 ] && [ "$ds" -eq 0 ] && [ -z "$(git status --porcelain)" ]; then
    echo "GREEN $(git rev-parse HEAD)" >>"$L"
  else
    echo "RED" >>"$L"
  fi

  # Quarantine the classes past the threshold and release those whose ticket closed.
  GATE_LOG_DIR="$D" bash scripts/gate/flaky.sh review >>"$L" 2>&1 || echo "FLAKY review failed" >>"$L"

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
