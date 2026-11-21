#!/usr/bin/env bash
# The gate's flaky-test record and quarantine. A class that failed and then passed on a re-run with
# no input changed (jk's `test-passed-on-rerun` warning) is appended to the history; past the
# threshold it is quarantined with an open ticket, and it leaves quarantine when that ticket closes.
# The Full tier runs every class whatever this says; the Queue tier leaves quarantined classes out.
#
# Usage:
#   scripts/gate/flaky.sh record <details.jsonl> <tip>   append each class the run passed on re-run
#   scripts/gate/flaky.sh review                         quarantine past the threshold; release closed
#   scripts/gate/flaky.sh list                           the quarantine: `<module> <class> <ticket>`
#   scripts/gate/flaky.sh excused <details.jsonl>        exit 0 when the run failed only in quarantine
#   scripts/gate/flaky.sh failed <details.jsonl>         `<module> <class>` of each failed class; exit
#                                                        0 when the run failed only in test classes
#
#   FLAKY_HISTORY     the history (default ${GATE_LOG_DIR:-/var/tmp/jk-gate}/flaky-history.tsv), one
#                     row per event: `<UTC time> <event> <module> <class> <tip or ticket>`, tab-separated;
#                     events `rerun-pass`, `quarantined`, `released`
#   FLAKY_THRESHOLD   re-run passes that quarantine a class (default 2) ...
#   FLAKY_WINDOW_DAYS ... within this many days, counted since the class last left quarantine (default 30)
#   FLAKY_KA          the ka command that opens and reads the tickets (default ka)
#   FLAKY_PROJECT     the ticket project (default jk); FLAKY_PARENT a parent ticket for each (default none)
#   FLAKY_NOW         the time as Unix seconds (default now)
set -euo pipefail

HISTORY="${FLAKY_HISTORY:-${GATE_LOG_DIR:-/var/tmp/jk-gate}/flaky-history.tsv}"
THRESHOLD="${FLAKY_THRESHOLD:-2}"
WINDOW_DAYS="${FLAKY_WINDOW_DAYS:-30}"
KA="${FLAKY_KA:-ka}"
NOW="${FLAKY_NOW:-$(date +%s)}"

die() {
  echo "flaky: $*" >&2
  exit 2
}

# The UTC time of Unix second $1, in the history's format; GNU date, then BSD date.
utc() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }

append() { printf '%s\t%s\t%s\t%s\t%s\n' "$(utc "$NOW")" "$1" "$2" "$3" "$4" >>"$HISTORY"; }

# The JSON lines of $1; a line that is not JSON is skipped.
rows() { jq -cR 'fromjson? // empty' "$1"; }

# The quarantine as the history leaves it: `<module> <class> <ticket>`, a class's last
# `quarantined` row not followed by a `released` one.
quarantine() {
  [[ -f "$HISTORY" ]] || return 0
  awk -F'\t' '
    $2 == "quarantined" { q[$4] = $3 " " $4 " " $5 }
    $2 == "released" { delete q[$4] }
    END { for (c in q) print q[c] }
  ' "$HISTORY" | sort
}

record() {
  local details="${1:?usage: flaky.sh record <details.jsonl> <tip>}" tip="${2:?usage: flaky.sh record <details.jsonl> <tip>}"
  [[ -f "$details" ]] || die "no run transcript at $details"
  mkdir -p "$(dirname "$HISTORY")"
  local module class n=0
  while IFS=$'\t' read -r module class; do
    [[ -n "$class" ]] || continue
    append rerun-pass "$module" "$class" "$tip"
    echo "FLAKY $module $class passed on a re-run at $tip"
    n=$((n + 1))
  done < <(rows "$details" | jq -r '
    select(.type == "warn" and .code == "test-passed-on-rerun")
    | .module as $m | .message | sub("^.*: "; "") | split(", ") | map(sub("\\$.*"; "")) | unique[]
    | [$m, .] | @tsv')
  [[ "$n" -gt 0 ]] || echo "FLAKY no class passed on a re-run"
}

# Open a ticket for class $2 of module $1 with its history rows in it; print its id.
open_ticket() {
  local module="$1" class="$2" body args
  body="$(printf '%s\n\n%s\n\n%s\n' \
    "\`$class\` ($module) failed and then passed on a re-run with no input changed $THRESHOLD times in $WINDOW_DAYS days, so the gate quarantined it: the Queue tier leaves it out and the Full tier still runs it. It leaves quarantine when this ticket closes." \
    "History (\`scripts/gate/flaky.sh\`):" \
    "$(awk -F'\t' -v c="$class" '$4 == c { print "    " $0 }' "$HISTORY")")"
  args=(new -project "${FLAKY_PROJECT:-jk}" -kind bug -priority P2 -label flake -json
    -t "Flaky test ${class##*.}: it fails and passes on re-run with no input changed"
    -accept "${class##*.} passes 20 runs in a row under jk test -r" -m "$body")
  [[ -n "${FLAKY_PARENT:-}" ]] && args+=(-parent "$FLAKY_PARENT")
  "$KA" "${args[@]}" </dev/null | jq -r '.id // empty'
}

review() {
  [[ -f "$HISTORY" ]] || {
    echo "FLAKY no history at $HISTORY"
    return 0
  }
  command -v "$KA" >/dev/null 2>&1 || die "$KA not found: the quarantine needs it to open and read tickets"
  local module class ticket status since
  # Release first, so a class whose ticket closed counts only the passes after it.
  while read -r module class ticket; do
    status="$("$KA" show "$ticket" -json </dev/null | jq -r '.status // empty')" || status=""
    case "$status" in
      done | canceled)
        append released "$module" "$class" "$ticket"
        echo "FLAKY $class left quarantine: $ticket is $status"
        ;;
      "") echo "FLAKY $class: $ticket unreadable; still quarantined" ;;
    esac
  done < <(quarantine)
  since="$(utc $((NOW - WINDOW_DAYS * 86400)))"
  while read -r module class; do
    ticket="$(open_ticket "$module" "$class")" || ticket=""
    if [[ -z "$ticket" ]]; then
      echo "FLAKY $class past the threshold, but no ticket opened; not quarantined"
      continue
    fi
    append quarantined "$module" "$class" "$ticket"
    echo "FLAKY $class quarantined: $ticket"
  done < <(awk -F'\t' -v since="$since" -v threshold="$THRESHOLD" '
    $2 == "rerun-pass" && $1 >= since { n[$4]++; m[$4] = $3 }
    $2 == "released" { delete n[$4] }
    $2 == "quarantined" { q[$4] = 1 }
    $2 == "released" { delete q[$4] }
    END { for (c in n) if (n[c] >= threshold && !(c in q)) print m[c], c }
  ' "$HISTORY" | sort)
}

# Exit 0 when every error of the run is a test failure in a quarantined class, and there is one.
excused() {
  local details="${1:?usage: flaky.sh excused <details.jsonl>}" failed held class bad=0
  [[ -f "$details" ]] || die "no run transcript at $details"
  failed="$(rows "$details" | jq -r '
    select(.type == "error") | if .code == "test-failure" and .testClass then .testClass else "-" end' \
    | sed 's/\$.*//' | sort -u)"
  [[ -n "$failed" ]] || return 1
  held="$(quarantine | awk '{ print $2 }')"
  while read -r class; do
    if [[ "$class" == "-" ]]; then
      echo "FLAKY the run failed outside a test class"
      bad=1
    elif ! grep -Fxq -- "$class" <<<"$held"; then
      echo "FLAKY not quarantined: $class"
      bad=1
    fi
  done <<<"$failed"
  return "$bad"
}

# The classes the run failed in, `<module> <class>` (a nested class as its outermost one). Exit 0
# when there is one and every error of the run is a failure in a class: only then can running the
# failed classes again turn the run green.
failed() {
  local details="${1:?usage: flaky.sh failed <details.jsonl>}" all
  [[ -f "$details" ]] || die "no run transcript at $details"
  all="$(rows "$details" | jq -r '
    select(.type == "error")
    | if .code == "test-failure" and .testClass then "\(.module // "-") \(.testClass | sub("\\$.*"; ""))" else "-" end' \
    | sort -u)"
  [[ -z "$all" ]] || grep -v '^-$' <<<"$all" || true
  [[ -n "$all" ]] && ! grep -qx -- - <<<"$all"
}

case "${1:-}" in
  record) record "${@:2}" ;;
  failed) failed "${@:2}" ;;
  review) review ;;
  list) quarantine ;;
  excused) excused "${@:2}" ;;
  *) die "usage: flaky.sh record <details.jsonl> <tip> | review | list | excused <details.jsonl> | failed <details.jsonl>" ;;
esac
