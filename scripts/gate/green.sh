#!/usr/bin/env bash
# Moves origin's `green` branch to the newest commit every gate that holds it passed. The Full tier
# always holds it; Windows and macOS hold it once they have been green for a week
# (scripts/gate/platform-loop.sh writes <QUEUE_REPO>/platform-<platform>-blocking then), and trail
# until then. A candidate is a tip the Full loop took (full-taken) that it judged GREEN
# (full-verdicts) and that each holding platform judged GREEN (platform-verdicts). `green` only moves
# forward: a candidate that is not a descendant of it is passed over.
#
# Usage: scripts/gate/green.sh
#
#   QUEUE_REPO      where the loops keep their state, `host:path` over ssh or a local path (default
#                   ~/src/jk-queue.git)
#   GREEN_CHECKOUT  a clone whose origin holds main and green; only fetched and pushed, with this
#                   host's credentials (required)
#
# Prints one line about the branch, never a verdict: `green at <sha>, the newest tip the Full tier
# passed`, or `green at <sha>` naming an older one, then why the newer tips wait; `NOT pushed to green (...)` when the
# push failed. Exits 0 unless the checkout cannot be read.
{
  set -uo pipefail
  repo="${QUEUE_REPO:-$HOME/src/jk-queue.git}"
  co="${GREEN_CHECKOUT:?GREEN_CHECKOUT: a clone whose origin holds green}"
  # shellcheck source=scripts/queue/common.sh
  JK_QUEUE_REMOTE="$repo" . "$(cd "$(dirname "$0")" && pwd)/../queue/common.sh"
  cd "$co" || exit 2
  # The state this reads, in one look at the queue's repository: `holding <file>` for each platform
  # that holds green, then each line of full-taken, full-verdicts and platform-verdicts after its
  # file's name.
  # shellcheck disable=SC2016 # the command runs in the queue's repository, which expands it
  state="$(queue_sh 'for f in platform-*-blocking; do [ -f "$f" ] && echo "holding $f"; done
    for f in full-taken full-verdicts platform-verdicts; do [ ! -e $f ] || sed "s/^/$f /" $f; done' </dev/null)" \
    || { echo "green unchanged: $repo cannot be reached"; exit 0; }
  section() { awk -v f="$1" '$1 == f { sub(/^[^ ]* /, ""); print }' <<<"$state"; }
  short() { git rev-parse --short=12 "$1"; }

  green=""
  if git ls-remote --exit-code -q origin refs/heads/green >/dev/null 2>&1; then
    git fetch -q origin "+refs/heads/green:refs/remotes/origin/green" || exit 2
    green="$(git rev-parse refs/remotes/origin/green)"
  fi
  holding=()
  while read -r f; do
    [ -n "$f" ] || continue
    f="${f#platform-}"
    holding+=("${f%-blocking}")
  done < <(section holding)
  taken="$(section full-taken)" full="$(section full-verdicts)" platform="$(section platform-verdicts)"

  # The tips the Full tier passed, newest first, each once.
  passed=()
  while read -r sha; do
    [ -n "$sha" ] || continue
    awk -v s="$sha" '$1 == s && $2 == "GREEN" { found = 1 } END { exit !found }' <<<"$full" && passed+=("$sha")
  done < <(awk '!seen[$1]++ { print $1 }' <<<"$taken" | sed -n '1!G;h;$p')
  [ "${#passed[@]}" -gt 0 ] || { echo "green unchanged: the Full tier has passed no tip it took"; exit 0; }

  why=""
  pick=""
  for sha in "${passed[@]}"; do
    git cat-file -e "$sha^{commit}" 2>/dev/null || git fetch -q origin main || exit 2
    if [ -n "$green" ] && ! git merge-base --is-ancestor "$green" "$sha" 2>/dev/null; then continue; fi
    wait=""
    for p in ${holding[@]+"${holding[@]}"}; do
      v="$(awk -v s="$sha" -v p="$p" '$1 == s && $2 == p { v = $3 } END { print v }' <<<"$platform")"
      case "$v" in
        GREEN) ;;
        RED) wait="${wait:+$wait, }$p is red on it" ;;
        *) wait="${wait:+$wait, }$p has not run it" ;;
      esac
    done
    if [ -z "$wait" ]; then
      pick="$sha"
      break
    fi
    [ -n "$why" ] || why="$(short "$sha") waits: $wait"
  done

  if [ -z "$pick" ]; then
    echo "green held${green:+ at $(short "$green")}${why:+; $why}"
    exit 0
  fi
  if [ "$pick" != "$green" ]; then
    git push -q origin "$pick:refs/heads/green" 2>/dev/null \
      || { echo "NOT pushed to green (not a fast-forward of it?)"; exit 0; }
  fi
  if [ "$pick" = "${passed[0]}" ]; then
    echo "green at $(short "$pick"), the newest tip the Full tier passed"
  else
    echo "green at $(short "$pick")${why:+; $why}"
  fi
  exit 0
}
