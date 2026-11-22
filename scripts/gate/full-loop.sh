#!/usr/bin/env bash
# The Full tier, back to back on the newest origin/main. Each cycle, under the host's gate lock
# (the merge queue's batches take it between cycles), gates the tip of origin/main with the Full
# tier of scripts/gate/gate.sh, unless that commit was judged already. The gate's base is `green`,
# so it runs the slow tier when a plugin changed since the last commit that passed.
#
# GREEN pushes the tip to origin's `green` branch as a fast-forward: releases are cut from it.
# RED looks for the batch of the merge queue that made it red among those landed since `green`
# (queue-batches.log): it bisects them, gating batch tips with the Full tier until the first red one
# is known (each commit is gated once; full-verdicts keeps every verdict), and names it in
# full-runs.log. Once per batch it then tries to confirm one commit of it: the classes that failed
# its red gate fail again at its tip and pass at its base, and a bisect of its commits with those
# classes finds the one they fail with and pass without (scripts/gate/rerun-classes.sh). It reverts
# that commit on top of the red tip, puts the revert through the Branch tier and submits it to the
# merge queue as full-revert/<ticket>-<sha>, which gates it like any branch; and it comments on the
# commit's ticket and reopens it, so its worker fixes forward and submits again. When no commit is
# confirmed, or its revert conflicts with later work, it reverts nothing and comments on each
# ticket the batch's commits name (a subject `<ticket>: ...`), saying why.
# After FULL_HOLD_AFTER red cycles in a row it writes <QUEUE_REPO>/full-hold, which names the
# batch's tickets and commits; while that file exists the queue lands only a fix: a branch with a
# commit for one of those tickets, or one that reverts one of those commits. The next GREEN removes
# it. A hold that names nothing (no batch was found) lands nothing until a person adds a
# `ticket <id>` or `commit <sha>` line to it.
#
# Usage: scripts/gate/full-loop.sh [--once]
#
#   QUEUE_REPO       the merge queue's repository (default ~/src/jk-queue.git): its queue-batches.log
#                    is read, and this loop's state is written beside it: full-runs.log (one line per
#                    cycle), full-verdicts (`<sha> GREEN|RED <gate>`), full-reds, full-filed, full-hold;
#                    the re-runs and the revert's Branch tier log to full-<batch>.log beside the gate logs
#   FULL_CHECKOUT    the clean checkout the Full tier runs in; its origin holds main and green
#                    (required; not the queue's checkout)
#   FULL_GATE        the gate, run as <gate> <n> <sha> (default scripts/gate/gate.sh of the checkout)
#   FULL_LOG_DIR     the gate logs, integ-gate-f<n>.log (default the queue's, so one flaky history
#                    serves both: ${QUEUE_LOG_DIR:-/var/tmp/jk-queue})
#   FULL_HOLD_AFTER  red cycles in a row after which the queue lands only the fix (default 2)
#   FULL_KA          the ka command that comments on tickets and reopens them (default ka)
#   FULL_RERUN       re-runs failed classes, as <rerun> <sha> <classes> (default scripts/gate/rerun-classes.sh)
#   FULL_POLL        seconds between looks when origin/main has not moved (default 300)
#   FULL_YIELD       seconds to wait after a cycle, so a waiting queue batch takes the lock (default 10)
#   GATE_LOCK        the lock every gate on this host takes (default /var/tmp/fleet-gate.lock)
#
# The whole body is one brace group, so bash has read all of it before a checkout changes this file.
{
  set -uo pipefail
  repo="${QUEUE_REPO:-$HOME/src/jk-queue.git}"
  co="${FULL_CHECKOUT:?FULL_CHECKOUT: the checkout the Full tier runs in}"
  D="${FULL_LOG_DIR:-${QUEUE_LOG_DIR:-/var/tmp/jk-queue}}"

  H="$(cd "$(dirname "$0")" && pwd)" # this script's directory, which holds the scripts it runs

  if [ "${1:-}" != --cycle ]; then
    mkdir -p "$repo" "$D/full-loop/queue" || exit 2
    # Run a copy, so a commit that changes these scripts changes the loop only when it is restarted.
    cp "$0" "$D/full-loop/full-loop.sh" || exit 2
    cp "$H/rerun-classes.sh" "$H/flaky.sh" "$D/full-loop/" || exit 2
    cp "$H/../queue/branch-tier.sh" "$H/../queue/submit.sh" "$H/../queue/common.sh" "$D/full-loop/queue/" || exit 2
    while :; do
      flock -w 7200 "${GATE_LOCK:-/var/tmp/fleet-gate.lock}" bash "$D/full-loop/full-loop.sh" --cycle
      rc=$?
      [ "$rc" -eq 0 ] || [ "$rc" -eq 3 ] || exit "$rc"
      [ "${1:-}" = --once ] && exit 0
      if [ "$rc" -eq 3 ]; then sleep "${FULL_POLL:-300}"; else sleep "${FULL_YIELD:-10}"; fi
    done
  fi

  # --- one cycle; exit 0 when one ran, 3 when origin/main has not moved, 2 when it could not gate ---
  V="$repo/full-verdicts"
  short() { git rev-parse --short=12 "$1"; }
  log() { echo "$*" >>"$repo/full-runs.log"; }

  # judge <sha>: print GREEN or RED for the commit, gating it with the Full tier unless it was
  # judged before; fail when the gate reached no verdict.
  judge() {
    local sha="$1" known n L verdict
    known="$(awk -v s="$sha" '$1 == s { print $2 }' "$V" 2>/dev/null | tail -1)"
    if [ -n "$known" ]; then
      echo "$known"
      return 0
    fi
    n="f$(($(cat "$repo/full-n" 2>/dev/null || echo 0) + 1))"
    echo "${n#f}" >"$repo/full-n"
    GATE_CHECKOUT="$co" GATE_LOG_DIR="$D" GATE_TIER=full GATE_BASE="${green:-$empty}" \
      bash "${FULL_GATE:-$co/scripts/gate/gate.sh}" "$n" "$sha" </dev/null >/dev/null 2>&1
    L="$D/integ-gate-$n.log"
    verdict="$(grep -aE '^(GREEN |RED|CHECKOUT |NO CHECKOUT)' "$L" 2>/dev/null | tail -1)"
    case "$verdict" in
      "GREEN $sha") echo "$sha GREEN $n" >>"$V" && echo GREEN ;;
      RED*) echo "$sha RED $n" >>"$V" && echo RED ;;
      *)
        log "full $n $(date -u +%Y-%m-%dT%H:%M:%SZ) $(short "$sha") NOT-GATED (${verdict:-no verdict}; $L)"
        return 1
        ;;
    esac
  }
  # The slow tier runs when a plugin changed since green; before there is a green, every plugin did.
  empty="$(git hash-object -t tree /dev/null)"
  gate_of() { awk -v s="$1" '$1 == s { g = $3 } END { print g }' "$V"; }

  cd "$co" || exit 2
  [ -z "$(git status --porcelain)" ] || { echo "full-loop: $co is not clean" >&2; exit 2; }
  git fetch -q origin main || { echo "full-loop: fetching origin main failed" >&2; exit 2; }
  if git ls-remote --exit-code -q origin refs/heads/green >/dev/null 2>&1; then
    git fetch -q origin "+refs/heads/green:refs/remotes/origin/green" || exit 2
    green="$(git rev-parse refs/remotes/origin/green)"
  else
    green=""
  fi
  tip="$(git rev-parse refs/remotes/origin/main)"
  [ -z "$(awk -v s="$tip" '$1 == s' "$V" 2>/dev/null)" ] || exit 3

  v="$(judge "$tip")" || exit 2
  n="$(gate_of "$tip")"
  stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  if [ "$v" = GREEN ]; then
    pushed="green"
    git push -q origin "$tip:refs/heads/green" || pushed="NOT pushed to green (not a fast-forward of it?)"
    echo 0 >"$repo/full-reds"
    held=""
    [ -f "$repo/full-hold" ] && held="; the queue's hold is lifted" && rm -f "$repo/full-hold"
    log "full $n $stamp $(short "$tip") GREEN $pushed$held"
    exit 0
  fi

  reds=$(($(cat "$repo/full-reds" 2>/dev/null || echo 0) + 1))
  echo "$reds" >"$repo/full-reds"

  # The batches landed since green, oldest first: `<n> <base> <tip>` of each LANDED line whose tip
  # is on origin/main past green. A batch that gated a group of a red split says `group <g>` before
  # LANDED.
  batches=()
  if [ -n "$green" ]; then
    while read -r bn bbase btip; do
      bt="$(git rev-parse -q --verify "$btip^{commit}" 2>/dev/null)" || continue
      git merge-base --is-ancestor "$bt" "$tip" || continue
      git merge-base --is-ancestor "$bt" "$green" && continue
      batches+=("$(git rev-list --count "$bt") $bn $bbase $bt")
    done < <(awk '$1 == "batch" && ($8 == "LANDED" || $10 == "LANDED") { print $2, $5, $7 }' "$repo/queue-batches.log" 2>/dev/null)
  fi
  culprit=""
  if [ "${#batches[@]}" -gt 0 ]; then
    sorted=()
    while read -r line; do sorted+=("$line"); done < <(printf '%s\n' "${batches[@]}" | sort -n | cut -d' ' -f2-)
    batches=("${sorted[@]}")
    # Bisect: before the first batch is green, past the last is the red tip; find the first red.
    lo=-1 hi="${#batches[@]}"
    while [ $((hi - lo)) -gt 1 ]; do
      mid=$(((lo + hi) / 2))
      read -r _ _ bt <<<"${batches[$mid]}"
      bv="$(judge "$bt")" || exit 2
      if [ "$bv" = RED ]; then hi=$mid; else lo=$mid; fi
    done
    [ "$hi" -lt "${#batches[@]}" ] && culprit="${batches[$hi]}"
  fi

  # confirm: narrow the culprit batch to one commit, $bad, by re-running the classes its red gate
  # failed (in $classes): they must fail at its tip and pass at its base, and a bisect of its
  # commits finds the first they fail with. Else $why says what stopped it.
  confirm() {
    local lo hi mid list=() c
    classes="$D/full-$cn-classes"
    bash "$H/flaky.sh" failed "$D/g$(gate_of "$ctip")-rerun.jsonl" >"$classes" 2>/dev/null && [ -s "$classes" ] \
      || { why="its red gate failed outside a test class, or names none to run again"; return 1; }
    rerun "$ctip"
    case $? in
      1) ;;
      0) why="the classes its gate failed pass when run again at $(short "$ctip")" && return 1 ;;
      *) why="the classes its gate failed could not run again at $(short "$ctip")" && return 1 ;;
    esac
    rerun "$cbase"
    case $? in
      0) ;;
      1) why="the classes its gate failed fail before it too, at $(short "$cbase")" && return 1 ;;
      *) why="the classes its gate failed could not run at $(short "$cbase")" && return 1 ;;
    esac
    for c in $commits; do list+=("$c"); done
    lo=-1 hi=$((${#list[@]} - 1))
    while [ $((hi - lo)) -gt 1 ]; do
      mid=$(((lo + hi) / 2))
      rerun "${list[$mid]}"
      case $? in
        0) lo=$mid ;;
        1) hi=$mid ;;
        *) why="the classes its gate failed could not run at $(short "${list[$mid]}")" && return 1 ;;
      esac
    done
    bad="${list[$hi]}"
  }
  rerun() { # <sha>: 0 when the classes pass there, 1 when one fails, else they could not run
    echo "== $1" >>"$D/full-$cn.log"
    GATE_CHECKOUT="$co" bash "${FULL_RERUN:-$H/rerun-classes.sh}" "$1" "$classes" >>"$D/full-$cn.log" 2>&1 </dev/null
  }
  # revert: revert $bad on top of the red tip as branch $rb, pass the Branch tier and submit it to
  # the merge queue, which gates it like any branch. Else $why says what stopped it.
  revert() {
    local t
    t="$(git log -1 --format=%s "$bad" | sed -nE 's/^([A-Z][A-Z0-9]*-[A-Z0-9]+): .*/\1/p')"
    rb="full-revert/${t:+$t-}$(short "$bad")"
    git checkout -q --detach "$tip" || { why="the checkout could not detach at $(short "$tip")"; return 1; }
    if ! git revert --no-commit "$bad" >/dev/null 2>&1; then
      git revert --abort >/dev/null 2>&1
      git reset -q --hard "$tip"
      why="its revert of $(short "$bad") conflicts with later work on origin/main"
      return 1
    fi
    git commit -q -m "Revert \"$(git log -1 --format=%s "$bad")\"" -m "This reverts commit $bad." \
      -m "The Full tier on origin/main is red from it: these classes fail with it and pass on its parent." \
      -m "$(sed 's/^/    /' "$classes")" || { git reset -q --hard "$tip"; why="the revert could not be committed"; return 1; }
    git checkout -q -B "$rb" || { git reset -q --hard "$tip"; why="no branch $rb could be made"; return 1; }
    echo "== branch tier and submit of $rb" >>"$D/full-$cn.log"
    (bash "$H/queue/branch-tier.sh" && JK_QUEUE_REMOTE="$repo" bash "$H/queue/submit.sh" "$rb") \
      >>"$D/full-$cn.log" 2>&1 </dev/null
    t=$?
    git reset -q --hard && git checkout -q --detach "$tip" && git branch -q -D "$rb"
    [ "$t" -eq 0 ] || { why="its revert of $(short "$bad") did not pass the Branch tier or was not submitted (see $D/full-$cn.log)"; return 1; }
  }

  if [ -z "$culprit" ]; then
    why="the queue landed no batch since green"
    [ "${#batches[@]}" -eq 0 ] || why="every batch landed since green passes at its own tip"
    [ -n "$green" ] || why="origin has no green branch yet"
    log "full $n $stamp $(short "$tip") RED culprit unknown: $why; red cycles $reds"
    hold_lines=("# The Full tier is red on $(short "$tip") and no batch was named: add a" "# \`ticket <id>\` or \`commit <sha>\` line to let a fix through.")
  else
    read -r cn cbase ctip <<<"$culprit"
    cbase="$(git rev-parse "$cbase^{commit}")" || exit 2
    members="$(awk -v b="$cn" '$1 == "batch" && $2 == b { for (i = 8; i <= 10; i++) if ($i == "LANDED") { for (j = i + 1; j <= NF; j++) printf " %s", $j; exit } }' "$repo/queue-batches.log")"
    commits="$(git rev-list --reverse "$cbase..$ctip")"
    tickets="$(git log --format=%s "$cbase..$ctip" | sed -nE 's/^([A-Z][A-Z0-9]*-[A-Z0-9]+): .*/\1/p' | sort -u)"
    cl="$D/integ-gate-$(gate_of "$ctip").log"
    log "full $n $stamp $(short "$tip") RED culprit batch $cn ($(short "$ctip"):$members) tickets $(tr '\n' ' ' <<<"${tickets:-<none>}" | sed 's/ $//'); red cycles $reds; log $cl"
    if ! grep -qx "$cn" "$repo/full-filed" 2>/dev/null; then
      fails="$(grep -aE '^(FAIL|E |T )|FAILED |^GUARD_EXIT=[1-9]|^RED|^FIXTURE FAIL|^ENGINE NOT' "$cl" 2>/dev/null | head -8 | tr '\n' ';' | sed 's/;$//')"
      if confirm && revert; then
        subject="$(git log -1 --format=%s "$bad")"
        told="$(sed -nE 's/^([A-Z][A-Z0-9]*-[A-Z0-9]+): .*/\1/p' <<<"$subject")"
        log "full $n confirmed $(short "$bad") of batch $cn: its failing classes fail with it and pass without; its revert $rb is submitted to the queue"
        for t in ${told:-$tickets}; do
          # shellcheck disable=SC2016 # the backticks are Markdown, for the ticket
          "${FULL_KA:-ka}" comment "$t" --type progress -m "$(printf 'The Full tier is red on origin/main from %s (`%s`), which batch %s of the merge queue landed: %s fail with it and pass on its parent. The Full loop submitted its revert to the merge queue as `%s`. Fix forward on top of the revert, then run scripts/queue/branch-tier.sh and submit again. Log: %s on %s.' \
            "$(short "$bad")" "$subject" "$cn" "$(awk '{ print $2 }' "$classes" | tr '\n' ' ' | sed 's/ $//')" "$rb" "$cl" "$(hostname)")" </dev/null >/dev/null 2>&1 \
            || log "full $n could not comment on $t"
          status="$("${FULL_KA:-ka}" show "$t" -json </dev/null 2>/dev/null | jq -r '.status // empty' 2>/dev/null)"
          case "$status" in
            done | review)
              "${FULL_KA:-ka}" move "$t" ready -m "The Full tier is red from $(short "$bad"); its revert is in the merge queue" </dev/null >/dev/null 2>&1 \
                || log "full $n could not reopen $t"
              ;;
          esac
        done
      else
        log "full $n reverts nothing: $why"
        for t in $tickets; do
          "${FULL_KA:-ka}" comment "$t" --type progress -m "The Full tier is red on origin/main from batch $cn of the merge queue, which landed this ticket's commits as $(short "$ctip"):$members. The first red gate: ${fails:-see the log}. Log: $cl on $(hostname). The Full loop reverts nothing, because $why. Revert them or fix forward through the queue; after ${FULL_HOLD_AFTER:-2} red cycles the queue lands only the fix." </dev/null >/dev/null 2>&1 \
            || log "full $n could not comment on $t"
        done
      fi
      echo "$cn" >>"$repo/full-filed"
    fi
    hold_lines=("# The Full tier is red since batch $cn ($(short "$ctip")): only a fix of it lands.")
    for t in $tickets; do hold_lines+=("ticket $t"); done
    for c in $commits; do hold_lines+=("commit $c"); done
  fi

  if [ "$reds" -ge "${FULL_HOLD_AFTER:-2}" ]; then
    printf '%s\n' "${hold_lines[@]}" >"$repo/full-hold.tmp" && mv "$repo/full-hold.tmp" "$repo/full-hold"
    log "full $n the queue lands only the fix ($repo/full-hold)"
  fi
  exit 0
}
