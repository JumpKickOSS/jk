#!/usr/bin/env bash
# Windows or macOS beside the Full tier: each cycle takes the newest tip the Full loop took
# (<QUEUE_REPO>/full-taken, written before its Linux gate starts) and runs it on the platform's host
# with scripts/gate/platform-run.sh: the fast tier, every test class with an OS condition, and the
# product smoke. A platform run checks one fixed commit, so it takes no lock on the gate host; the
# run itself holds its host to one run at a time. A tip the platform has a verdict on is not run
# again; while the platform is busy the Full loop may take newer tips, and the next cycle skips to
# the newest.
#
# GREEN and RED go to platform-verdicts as `<sha> <platform> GREEN|RED <epoch> <run>`.
# RED files a ticket with what failed (bug, P2, label platform-<platform>), once: while that ticket
# is open, a red run with other failures comments on it instead, and a green run says so on it.
# The platform trails at first: its verdicts do not hold `green`. Once it has been green for
# PLATFORM_WEEK seconds with no red run between, the loop writes platform-<platform>-blocking, and
# from then on `green` moves only to tips this platform passed too (scripts/gate/green.sh). Removing
# that file makes it trail again. Each cycle ends by moving `green` as far as every gate allows and
# logs the run's verdict with where the branch stands: `run <n> RED; branch green at <sha>, ...`.
#
# Usage: scripts/gate/platform-loop.sh <windows|macos> [--once]
#
#   QUEUE_REPO          where the Full loop keeps its state (default ~/src/jk-queue.git); this loop's
#                       is written beside it: platform-verdicts, platform-runs.log (one line per run),
#                       platform-<platform>-n, -ticket, -failed and -blocking
#   PLATFORM_CHECKOUT   a clone whose origin holds main and green, only fetched and pushed (required)
#   PLATFORM_HOST       the host to run on (default daybreak for windows, bocamacmini for macos)
#   PLATFORM_REPO       the URL the host fetches the commit from (default PLATFORM_CHECKOUT's origin,
#                       as https when it is a GitHub ssh URL)
#   PLATFORM_RUN        runs platform-run.sh, fed on stdin, as <run> <platform> <sha> <repo-url>
#                       (default: over ssh on PLATFORM_HOST, in Git Bash on Windows)
#   PLATFORM_LOG_DIR    each run's output, platform-<platform>-<n>.log (default /var/tmp/jk-platform)
#   PLATFORM_TIMEOUT    seconds a run may take (default 14400)
#   PLATFORM_WEEK       seconds green after which the platform holds green (default 604800)
#   PLATFORM_KA         the ka command that files and reads tickets (default ka)
#   PLATFORM_PARENT     a parent for each ticket it files (default none)
#   PLATFORM_POLL       seconds between looks when there is nothing new (default 300)
#
# The whole body is one brace group, so bash has read all of it before anything changes this file.
{
  set -uo pipefail
  p="${1:?usage: platform-loop.sh <windows|macos> [--once]}"
  case "$p" in
    windows) host="${PLATFORM_HOST:-daybreak}" ;;
    macos) host="${PLATFORM_HOST:-bocamacmini}" ;;
    *) echo "platform-loop: unknown platform $p (windows or macos)" >&2 && exit 2 ;;
  esac
  repo="${QUEUE_REPO:-$HOME/src/jk-queue.git}"
  co="${PLATFORM_CHECKOUT:?PLATFORM_CHECKOUT: a clone whose origin holds main and green}"
  D="${PLATFORM_LOG_DIR:-/var/tmp/jk-platform}"
  H="$(cd "$(dirname "$0")" && pwd)"
  ka="${PLATFORM_KA:-ka}"
  mkdir -p "$repo" "$D" || exit 2
  url="${PLATFORM_REPO:-$(git -C "$co" remote get-url origin)}" || exit 2
  case "$url" in git@github.com:*) url="https://github.com/${url#git@github.com:}" ;; esac

  short() { git -C "$co" rev-parse --short=12 "$1" 2>/dev/null || echo "${1:0:12}"; }
  log() { echo "$p $(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >>"$repo/platform-runs.log"; }
  title() { [ "$p" = windows ] && echo Windows || echo macOS; }
  status() { "$ka" show "$1" -json </dev/null 2>/dev/null | jq -r '.status // empty' 2>/dev/null; }

  # cycle: exit status 0 when a run reached a verdict, 3 when there was nothing to run or no verdict.
  cycle() {
    local sha n L line v failed ticket body st now start
    sha="$(tail -1 "$repo/full-taken" 2>/dev/null)"
    [ -n "$sha" ] || return 3
    awk -v s="$sha" -v p="$p" '$1 == s && $2 == p { found = 1 } END { exit !found }' "$repo/platform-verdicts" 2>/dev/null \
      && return 3
    n="$(($(cat "$repo/platform-$p-n" 2>/dev/null || echo 0) + 1))"
    echo "$n" >"$repo/platform-$p-n"
    L="$D/platform-$p-$n.log"
    # The host runs platform-run.sh from stdin; on Windows, ssh lands in PowerShell, so Git Bash is
    # named. No `--` before the arguments: PowerShell would swallow it.
    local run=(ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=60 "$host")
    if [ -n "${PLATFORM_RUN:-}" ]; then
      run=("$PLATFORM_RUN" "$p" "$sha" "$url")
    elif [ "$p" = windows ]; then
      run+=("& 'C:\\Program Files\\Git\\bin\\bash.exe' -l -s $p $sha $url")
    else
      run+=("bash -l -s $p $sha $url")
    fi
    timeout "${PLATFORM_TIMEOUT:-14400}" "${run[@]}" <"$H/platform-run.sh" 2>&1 | tr -d '\r' >"$L"
    line="$(grep -aE '^PLATFORM (GREEN|RED|BUSY)' "$L" | tail -1)"
    case "$line" in
      "PLATFORM GREEN $sha") v=GREEN ;;
      "PLATFORM RED $sha"*) v=RED ;;
      *)
        log "$(short "$sha") run $n NOT-RUN on $host (${line:-no verdict}; $L)"
        return 3
        ;;
    esac
    now="$(date +%s)"
    echo "$sha $p $v $now $n" >>"$repo/platform-verdicts"
    failed="$(grep -aE '^(STEP .* EXIT=[1-9]|FAIL|T |E )|FAILED ' "$L" | head -20)"
    log "$(short "$sha") run $n $v on $host${line#"PLATFORM $v $sha"}; $L"

    ticket="$(cat "$repo/platform-$p-ticket" 2>/dev/null)"
    st=""
    [ -z "$ticket" ] || st="$(status "$ticket")"
    case "$st" in done | canceled) ticket="" ;; esac
    if [ "$v" = RED ]; then
      body="$(printf '%s\n\n%s\n\n%s\n\n%s\n' \
        "The $(title) platform run of \`$(short "$sha")\` (origin/main, the tip the Full tier took) is red${line#"PLATFORM RED $sha"}. It runs the fast tier, every test class with an OS condition and the product smoke on $host, beside the Linux Full tier." \
        "What failed:" \
        "$(awk '{ print "    " $0 }' <<<"${failed:-(no FAIL line; see the log)}")" \
        "Log: $L on $(hostname); the steps' own logs are in ~/jk-platform/logs on $host.")"
      if [ -z "$ticket" ]; then
        local args=(new -project jk -kind bug -priority P2 -label "platform-$p" -json
          -t "$(title) run red on main: ${line#"PLATFORM RED $sha: "}"
          -accept "The $(title) platform run (scripts/gate/platform-loop.sh $p) is green on main" -m "$body")
        [ -z "${PLATFORM_PARENT:-}" ] || args+=(-parent "$PLATFORM_PARENT")
        # `ka new -json` answers {"created": [{"id": ...}], ...}; its complaint, when it has one, is
        # kept beside the run's log.
        ticket="$("$ka" "${args[@]}" </dev/null 2>"$L.ka" | jq -r '.created[0].id // empty' 2>/dev/null)"
        if [ -n "$ticket" ]; then
          echo "$ticket" >"$repo/platform-$p-ticket"
          log "$(short "$sha") run $n filed $ticket"
        else
          log "$(short "$sha") run $n could not file a ticket: $(head -1 "$L.ka" 2>/dev/null | cut -c1-200)"
        fi
      elif [ "$failed" != "$(cat "$repo/platform-$p-failed" 2>/dev/null)" ]; then
        "$ka" comment "$ticket" --type progress -m "$body" </dev/null >/dev/null 2>&1 || log "$(short "$sha") run $n could not comment on $ticket"
      fi
      echo "$failed" >"$repo/platform-$p-failed"
    elif [ -n "$ticket" ] && [ -s "$repo/platform-$p-failed" ]; then
      "$ka" comment "$ticket" --type progress -m "The $(title) platform run of \`$(short "$sha")\` is green on $host. Log: $L on $(hostname)." \
        </dev/null >/dev/null 2>&1 || log "$(short "$sha") run $n could not comment on $ticket"
      : >"$repo/platform-$p-failed"
    fi

    # Green for a week with no red between: from now on this platform holds green.
    if [ "$v" = GREEN ] && [ ! -f "$repo/platform-$p-blocking" ]; then
      start="$(awk -v p="$p" '$2 == p { if ($3 == "RED") s = ""; else if (s == "") s = $4 } END { print s }' "$repo/platform-verdicts")"
      if [ -n "$start" ] && [ $((now - start)) -ge "${PLATFORM_WEEK:-604800}" ]; then
        echo "green since $(date -u -d "@$start" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo "$start"), holds green from $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
          >"$repo/platform-$p-blocking"
        log "$(short "$sha") run $n: green for a week, so $p now holds green"
      fi
    fi
    log "$(short "$sha") run $n $v; branch $(QUEUE_REPO="$repo" GREEN_CHECKOUT="$co" bash "$H/green.sh")"
    return 0
  }

  while :; do
    cycle
    rc=$?
    [ "${2:-}" = --once ] && exit 0
    [ "$rc" -eq 0 ] || sleep "${PLATFORM_POLL:-300}"
  done
}
