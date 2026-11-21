#!/usr/bin/env bash
# The merge queue, the only writer to origin/main. Each batch, under the host's gate lock: fetch
# origin/main, cherry-pick every waiting branch onto it in submission order (a branch that
# conflicts or carries a merge commit is ejected), gate that commit with the Queue tier of
# scripts/gate/gate.sh and, when it is GREEN, push it to origin/main as a fast-forward. It never
# force-pushes: when origin/main moved during the gate the push is refused, the batch waits again
# and is applied to the new origin/main. The gate has already run the failed classes once more (a
# flake passes there and is recorded), so a red batch is red for a cause: a red batch of one branch
# ejects it; a red batch of several is split in two groups, gated one after the other before
# anything else waiting. When the gate names failed classes (`FAILED <module> <class>`), the
# branches that changed one of those modules, or a root build file, are the second group and the
# others the first, so the rest lands while the suspects are split further; when none or all did,
# the batch is split in halves in submission order.
#
# An ejected branch's ticket is told why with `ka comment`: the conflicting files, or the failing
# classes and their failures. The ticket is the one the branch is named for (w/JK-XXXXX), else
# every ticket its commit subjects start with. A branch never leaves the queue unrecorded: after
# each batch, every branch it saw is still waiting, landed or ejected, or is recorded as dropped.
#
# Where each branch stands is <QUEUE_REPO>/queue-state/<branch> (scripts/queue/status.sh reads it);
# every batch is one line of <QUEUE_REPO>/queue-batches.log: its number, base, tip, result and
# branches. A batch that could not gate at all (a dirty checkout, a failed fetch, no verdict) puts
# its branches back to waiting and stops the loop for a person to look.
#
# Usage: scripts/queue/loop.sh [--once]
#
#   QUEUE_REPO      the queue's bare repository (default ~/src/jk-queue.git); created when missing
#   QUEUE_CHECKOUT  the clean checkout batches are built and gated in; its origin holds main (required)
#   QUEUE_GATE      the gate, run as <gate> <n> <sha> (default scripts/gate/gate.sh of the batch)
#   QUEUE_TIER      the gate's tier, its GATE_TIER: queue (default) or full; GATE_BASE is the batch's
#                   origin/main
#   QUEUE_LOG_DIR   the gate logs (default /var/tmp/jk-queue)
#   QUEUE_POLL      seconds between looks at an empty queue (default 60)
#   QUEUE_KA        the ka command that tells an ejected branch's ticket (default ka)
#   GATE_LOCK       the lock every gate on this host takes (default /var/tmp/fleet-gate.lock)
#
# The whole body is one brace group, so bash has read all of it before a checkout changes this file.
{
  set -uo pipefail
  repo="${QUEUE_REPO:-$HOME/src/jk-queue.git}"
  co="${QUEUE_CHECKOUT:?QUEUE_CHECKOUT: the checkout batches are gated in}"
  D="${QUEUE_LOG_DIR:-/var/tmp/jk-queue}"
  S="$repo/queue-state"

  if [ "${1:-}" != --batch ]; then
    [ -d "$repo" ] || git init -q --bare "$repo" || exit 2
    mkdir -p "$S" "$D" || exit 2
    # Run a copy, so a batch that changes this script changes the loop only when it is restarted.
    cp "$0" "$D/loop-running.sh" || exit 2
    while :; do
      flock -w 7200 "${GATE_LOCK:-/var/tmp/fleet-gate.lock}" bash "$D/loop-running.sh" --batch
      rc=$?
      [ "$rc" -eq 0 ] || [ "$rc" -eq 3 ] || exit "$rc"
      [ "${1:-}" = --once ] && exit 0
      [ "$rc" -eq 3 ] && sleep "${QUEUE_POLL:-60}"
    done
  fi

  # --- one batch; exit 0 when one ran, 3 when nothing waits, 2 when it could not gate ---
  record() { # <branch> <line>...: where the branch stands now
    mkdir -p "$(dirname "$S/$1")" && printf '%s\n' "${@:2}" >"$S/$1.tmp" && mv "$S/$1.tmp" "$S/$1"
  }
  unqueue() { git --git-dir="$repo" update-ref -d "refs/queue/$1" "$2" 2>/dev/null; } # only the gated tip
  short() { git rev-parse --short=12 "$1"; }
  n=$(($(cat "$repo/queue-batch-n" 2>/dev/null || echo 0) + 1))

  # The tickets branch $1 at $2 carries: the one it is named for, else those its commits name.
  tickets() {
    local t
    t="$(grep -oE '[A-Z][A-Z0-9]*-[0-9A-Z]{5}' <<<"$1" | head -1)"
    if [ -n "$t" ]; then
      echo "$t"
    else
      git log --format=%s "$base..$2" | sed -nE 's/^([A-Z][A-Z0-9]*-[0-9A-Z]{5}): .*/\1/p' | sort -u
    fi
  }
  # eject <branch> <sha> <reason> <details> [<more for the ticket>]: out of the queue, with the
  # reason and details in its record and on its tickets.
  eject() {
    local b="$1" sha="$2" t told="" untold="" body part
    # shellcheck disable=SC2016 # the backticks are Markdown, for the ticket
    body="$(printf 'The merge queue ejected `%s` (%s) in batch %s: %s.' "$b" "$(short "$sha")" "$n" "$3")"
    for part in "$4" "${5:-}"; do
      [ -z "$part" ] || body+=$'\n\n'"$(sed 's/^/    /' <<<"$part")"
    done
    body+=$'\n\nFix it, run scripts/queue/branch-tier.sh again and resubmit.'
    for t in $(tickets "$b" "$sha"); do
      if "${QUEUE_KA:-ka}" comment "$t" -m "$body" </dev/null >/dev/null 2>&1; then told+=" $t"; else untold+=" $t"; fi
    done
    record "$b" "ejected $sha batch $n: $3" "$4" \
      "tickets told:${told:- none}${untold:+; telling failed:$untold}"
    unqueue "$b" "$sha"
    ejected+=("$b@$(short "$sha")")
  }
  # waiting <branch> <sha> <why>: back to waiting, in the group the batch gated ($grp) if any.
  waiting() {
    record "$1" "waiting $2${grp:+ group $grp:} $3"
  }
  # Every branch the batch saw is still waiting (at that tip or a newer one), landed or ejected;
  # any other is recorded as dropped, so none leaves the queue unsaid.
  accounted() {
    local i b sha dropped=""
    for i in "${!names[@]}"; do
      b="${names[$i]}" sha="${shas[$i]}"
      git --git-dir="$repo" rev-parse -q --verify "refs/queue/$b" >/dev/null && continue
      case "$(head -1 "$S/$b" 2>/dev/null)" in "landed $sha "* | "ejected $sha "*) continue ;; esac
      record "$b" "dropped $sha batch $n: it left the queue without landing or an ejection; submit it again"
      dropped+=" $b@$(short "$sha")"
    done
    [ -z "$dropped" ] || echo "batch $n DROPPED$dropped" >>"$repo/queue-batches.log"
  }

  cd "$co" || exit 2
  [ -z "$(git status --porcelain)" ] || { echo "queue: $co is not clean" >&2; exit 2; }
  git fetch -q origin main || { echo "queue: fetching origin main failed" >&2; exit 2; }
  git fetch -q --prune "$repo" "+refs/queue/*:refs/queue-in/*" || { echo "queue: fetching $repo failed" >&2; exit 2; }
  base="$(git rev-parse refs/remotes/origin/main)"

  # Waiting branches, oldest tip first. A group split out of a red batch is gated before anything
  # else, the lowest group first.
  names=() shas=() grouped=()
  while read -r name sha; do
    [ -n "$name" ] || continue
    g="$(head -1 "$S/$name" 2>/dev/null | sed -n "s/^waiting $sha group \([0-9.]*\): .*/\1/p")"
    [ -z "$g" ] || grouped+=("$g")
    names+=("$name") shas+=("$sha")
  done < <(git for-each-ref --sort=committerdate --format='%(refname:strip=2) %(objectname)' refs/queue-in)
  [ "${#names[@]}" -gt 0 ] || exit 3
  grp="" # the group this batch gates; empty when it gates everything waiting
  [ "${#grouped[@]}" -eq 0 ] || grp="$(printf '%s\n' "${grouped[@]}" | sort -V | head -1)"
  echo "$n" >"$repo/queue-batch-n"

  git checkout -q --detach "$base" || exit 2
  members=() msha=() ejected=()
  for i in "${!names[@]}"; do
    b="${names[$i]}" sha="${shas[$i]}" before="$(git rev-parse HEAD)"
    if [ -n "$grp" ] && [ "$(head -1 "$S/$b" 2>/dev/null | cut -d' ' -f1-4)" != "waiting $sha group $grp:" ]; then
      continue
    fi
    if [ "$(git rev-list --merges --count "$base..$sha")" -gt 0 ]; then
      eject "$b" "$sha" "it carries a merge commit" "rebase it onto origin/main and submit again"
      continue
    fi
    # The branch's commits that nothing in the batch so far already carries, oldest first.
    # shellcheck disable=SC2207 # commit ids, one word each
    picks=($(git rev-list --reverse --no-merges --cherry-pick --right-only "HEAD...$sha"))
    if [ "${#picks[@]}" -gt 0 ] && ! git cherry-pick --empty=drop "${picks[@]}" >/dev/null 2>&1; then
      files="$(git diff --name-only --diff-filter=U | head -5 | tr '\n' ' ')"
      git cherry-pick --abort >/dev/null 2>&1
      git reset -q --hard "$before"
      eject "$b" "$sha" "it conflicts with origin/main or a branch before it in the batch" \
        "conflicts: ${files:-<none named>}"
      continue
    fi
    members+=("$b") msha+=("$sha")
  done
  tip="$(git rev-parse HEAD)"
  line="batch $n $(date -u +%Y-%m-%dT%H:%M:%SZ) base $(short "$base") tip $(short "$tip")${grp:+ group $grp}"
  members_text=""
  for i in "${!members[@]}"; do members_text+=" ${members[$i]}@$(short "${msha[$i]}")"; done
  [ "${#ejected[@]}" -eq 0 ] || members_text+=" ejected:${ejected[*]}"
  if [ "${#members[@]}" -eq 0 ]; then
    echo "$line EJECTED-ALL$members_text" >>"$repo/queue-batches.log"
    accounted
    exit 0
  fi
  for i in "${!members[@]}"; do
    record "${members[$i]}" "gating ${msha[$i]} batch $n on origin/main $(short "$base") with ${#members[@]} branches, since $(date -u +%H:%M:%SZ)"
  done

  gate="${QUEUE_GATE:-$co/scripts/gate/gate.sh}"
  GATE_CHECKOUT="$co" GATE_LOG_DIR="$D" GATE_TIER="${QUEUE_TIER:-queue}" GATE_BASE="$base" \
    bash "$gate" "$n" "$tip" </dev/null >/dev/null 2>&1
  L="$D/integ-gate-$n.log"
  verdict="$(grep -aE '^(GREEN |RED|CHECKOUT |NO CHECKOUT)' "$L" 2>/dev/null | tail -1)"

  case "$verdict" in
    "GREEN $tip")
      if git push -q origin "$tip:refs/heads/main"; then
        for i in "${!members[@]}"; do
          record "${members[$i]}" "landed ${msha[$i]} on origin/main as $(short "$tip") in batch $n"
          unqueue "${members[$i]}" "${msha[$i]}"
        done
        echo "$line LANDED$members_text" >>"$repo/queue-batches.log"
      else
        # Someone else wrote origin/main: the verdict is for a tree nobody will push.
        for i in "${!members[@]}"; do
          waiting "${members[$i]}" "${msha[$i]}" "origin/main moved while batch $n gated; it is applied again"
        done
        echo "$line MOVED$members_text" >>"$repo/queue-batches.log"
      fi
      ;;
    RED*)
      fails="$(grep -aE '^(FAIL|E |T )|FAILED |^RED|^GUARD|^INTEG .* EXIT=[1-9]|^ENGINE NOT|^FIXTURE FAIL' "$L" | head -20)"
      if [ "${#members[@]}" -eq 1 ]; then
        eject "${members[0]}" "${msha[0]}" "the gate is red; log $(hostname):$L" "$fails" \
          "$(head -60 "$D/g$n-failures.txt" 2>/dev/null)"
        echo "$line RED-EJECTED$members_text" >>"$repo/queue-batches.log"
      else
        # The suspects: the branches that changed a module a failed class is in (its artifact name
        # is the member's `name`), or a root build file.
        failing="$(grep -a '^FAILED ' "$L" | awk '{ print $2 }' | sed 's/.*://' | sort -u)"
        first=() second=()
        for i in "${!members[@]}"; do
          suspect=""
          if [ -n "$failing" ]; then
            for mo in $(bash "$co/scripts/gate/changed-modules.sh" "$(git merge-base "$base" "${msha[$i]}")" "${msha[$i]}" 2>/dev/null); do
              if [ "$mo" = "*" ] || grep -Fxq -- "$(sed -n 's/^name *= *"\([^"]*\)".*/\1/p' "$co/$mo/jk.toml" 2>/dev/null | head -1)" <<<"$failing"; then
                suspect=1
              fi
            done
          fi
          if [ -n "$suspect" ]; then second+=("$i"); else first+=("$i"); fi
        done
        how="by the modules its failed classes are in"
        if [ "${#first[@]}" -eq 0 ] || [ "${#second[@]}" -eq 0 ]; then
          how="in halves"
          first=() second=()
          for i in "${!members[@]}"; do
            if [ "$i" -lt $(((${#members[@]} + 1) / 2)) ]; then first+=("$i"); else second+=("$i"); fi
          done
        fi
        split=""
        for part in 1 2; do
          if [ "$part" = 1 ]; then idx=("${first[@]}"); else idx=("${second[@]}"); fi
          split+=" $n.$part:"
          for i in "${idx[@]}"; do
            record "${members[$i]}" "waiting ${msha[$i]} group $n.$part: batch $n was red, so it was split $how; this group of ${#idx[@]} is gated before anything else waiting" "$fails"
            split+=" ${members[$i]}"
          done
        done
        echo "$line RED-SPLIT $how;$split;$members_text" >>"$repo/queue-batches.log"
      fi
      ;;
    *)
      for i in "${!members[@]}"; do
        waiting "${members[$i]}" "${msha[$i]}" "batch $n did not gate (${verdict:-no verdict}); the queue stopped"
      done
      echo "$line NOT-GATED (${verdict:-no verdict}; $L)$members_text" >>"$repo/queue-batches.log"
      accounted
      exit 2
      ;;
  esac
  accounted
  exit 0
}
