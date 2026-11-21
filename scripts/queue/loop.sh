#!/usr/bin/env bash
# The merge queue, the only writer to origin/main. Each batch, under the host's gate lock: fetch
# origin/main, cherry-pick every waiting branch onto it in submission order (a branch that
# conflicts or carries a merge commit is ejected), gate that commit with scripts/gate/gate.sh and,
# when it is GREEN, push it to origin/main as a fast-forward. It never force-pushes: when
# origin/main moved during the gate the push is refused, the batch waits again and is applied to the
# new origin/main. A red batch of one branch ejects it with the gate's failing lines; a red batch of
# several gates each branch alone, one per batch. The next batch starts as soon as one ends.
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
#   QUEUE_LOG_DIR   the gate logs (default /var/tmp/jk-queue)
#   QUEUE_POLL      seconds between looks at an empty queue (default 60)
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

  cd "$co" || exit 2
  [ -z "$(git status --porcelain)" ] || { echo "queue: $co is not clean" >&2; exit 2; }
  git fetch -q origin main || { echo "queue: fetching origin main failed" >&2; exit 2; }
  git fetch -q --prune "$repo" "+refs/queue/*:refs/queue-in/*" || { echo "queue: fetching $repo failed" >&2; exit 2; }
  base="$(git rev-parse refs/remotes/origin/main)"

  # Waiting branches, oldest tip first; a branch split out of a red batch is gated alone.
  names=() shas=() alone=""
  while read -r name sha; do
    [ -n "$name" ] || continue
    if [ -z "$alone" ] && [ "$(head -1 "$S/$name" 2>/dev/null | cut -d' ' -f1-3)" = "waiting $sha alone:" ]; then
      alone="$name $sha"
    fi
    names+=("$name") shas+=("$sha")
  done < <(git for-each-ref --sort=committerdate --format='%(refname:strip=2) %(objectname)' refs/queue-in)
  [ "${#names[@]}" -gt 0 ] || exit 3
  if [ -n "$alone" ]; then
    read -r a1 a2 <<<"$alone"
    names=("$a1") shas=("$a2")
  fi
  echo "$n" >"$repo/queue-batch-n"

  git checkout -q --detach "$base" || exit 2
  members=() msha=() ejected=()
  for i in "${!names[@]}"; do
    b="${names[$i]}" sha="${shas[$i]}" before="$(git rev-parse HEAD)"
    if [ "$(git rev-list --merges --count "$base..$sha")" -gt 0 ]; then
      record "$b" "ejected $sha batch $n: it carries a merge commit; rebase it onto origin/main and submit again"
      unqueue "$b" "$sha"; ejected+=("$b@$(short "$sha")"); continue
    fi
    # The branch's commits that nothing in the batch so far already carries, oldest first.
    # shellcheck disable=SC2207 # commit ids, one word each
    picks=($(git rev-list --reverse --no-merges --cherry-pick --right-only "HEAD...$sha"))
    if [ "${#picks[@]}" -gt 0 ] && ! git cherry-pick --empty=drop "${picks[@]}" >/dev/null 2>&1; then
      files="$(git diff --name-only --diff-filter=U | head -5 | tr '\n' ' ')"
      git cherry-pick --abort >/dev/null 2>&1
      git reset -q --hard "$before"
      record "$b" "ejected $sha batch $n: it conflicts with origin/main or a branch before it in the batch" "conflicts: ${files:-<none named>}"
      unqueue "$b" "$sha"; ejected+=("$b@$(short "$sha")"); continue
    fi
    members+=("$b") msha+=("$sha")
  done
  tip="$(git rev-parse HEAD)"
  line="batch $n $(date -u +%Y-%m-%dT%H:%M:%SZ) base $(short "$base") tip $(short "$tip")"
  members_text=""
  for i in "${!members[@]}"; do members_text+=" ${members[$i]}@$(short "${msha[$i]}")"; done
  [ "${#ejected[@]}" -eq 0 ] || members_text+=" ejected:${ejected[*]}"
  if [ "${#members[@]}" -eq 0 ]; then
    echo "$line EJECTED-ALL$members_text" >>"$repo/queue-batches.log"
    exit 0
  fi
  for i in "${!members[@]}"; do
    record "${members[$i]}" "gating ${msha[$i]} batch $n on origin/main $(short "$base") with ${#members[@]} branches, since $(date -u +%H:%M:%SZ)"
  done

  gate="${QUEUE_GATE:-$co/scripts/gate/gate.sh}"
  GATE_CHECKOUT="$co" GATE_LOG_DIR="$D" bash "$gate" "$n" "$tip" </dev/null >/dev/null 2>&1
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
          record "${members[$i]}" "waiting ${msha[$i]} origin/main moved while batch $n gated; it is applied again"
        done
        echo "$line MOVED$members_text" >>"$repo/queue-batches.log"
      fi
      ;;
    RED*)
      fails="$(grep -aE '^(FAIL|E |T )|FAILED |^RED|^GUARD|^ENGINE NOT|^FIXTURE FAIL' "$L" | head -20)"
      if [ "${#members[@]}" -eq 1 ]; then
        record "${members[0]}" "ejected ${msha[0]} batch $n: the gate is red; log $L" "$fails"
        unqueue "${members[0]}" "${msha[0]}"
        echo "$line RED-EJECTED$members_text" >>"$repo/queue-batches.log"
      else
        for i in "${!members[@]}"; do
          record "${members[$i]}" "waiting ${msha[$i]} alone: batch $n was red, so each of its branches is gated by itself"
        done
        echo "$line RED-SPLIT$members_text" >>"$repo/queue-batches.log"
      fi
      ;;
    *)
      for i in "${!members[@]}"; do
        record "${members[$i]}" "waiting ${msha[$i]} batch $n did not gate (${verdict:-no verdict}); the queue stopped"
      done
      echo "$line NOT-GATED (${verdict:-no verdict}; $L)$members_text" >>"$repo/queue-batches.log"
      exit 2
      ;;
  esac
  exit 0
}
