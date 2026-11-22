#!/usr/bin/env bash
# The gate's side of jk's test quarantine. jk keeps each class's history and, past the root's
# [test] quarantine threshold, adds the class to .jk/test/quarantine.toml in the gate's checkout; a
# run red only in quarantined classes passes, and every tier still runs them. The gate opens a
# ticket for each class jk adds, writes the ticket's id alone as its `issue`, takes it out when the
# ticket closes, and submits the file to the merge queue, the only writer to origin/main.
#
# Usage:
#   scripts/gate/flaky.sh failed <details.jsonl>   `<module> <class>` of each failed class; exit 0
#                                                  when the run failed only in test classes
#   scripts/gate/flaky.sh take <saved>             save the quarantine file jk changed in this
#                                                  checkout to <saved> and restore HEAD's
#   scripts/gate/flaky.sh review <saved> <log>     open a ticket for each class jk added (in <saved>)
#                                                  or that has none on FLAKY_BASE, and submit
#                                                  FLAKY_BASE's file with their tickets and without
#                                                  the classes whose ticket closed; the Branch tier
#                                                  and the submit write to <log>
#
#   FLAKY_BASE    the commit the quarantine change is made on (default origin/main)
#   FLAKY_LEDGER  each ticket the gate opened, `<module> <class> <reason> <since> <ticket>`,
#                 tab-separated, `-` for empty (default ${GATE_LOG_DIR:-/var/tmp/jk-gate}/quarantine-tickets.tsv);
#                 it carries a class across gates until the queue lands its entry
#   FLAKY_BRANCH  the branch submitted to the merge queue (default gate/quarantine)
#   FLAKY_KA      the ka command that opens and reads the tickets (default ka)
#   FLAKY_PROJECT the ticket project (default jk); FLAKY_PARENT a parent ticket for each (default none)
#   JK_QUEUE_REMOTE  the merge queue's repository (scripts/queue/common.sh)
set -euo pipefail

FILE=.jk/test/quarantine.toml
LEDGER="${FLAKY_LEDGER:-${GATE_LOG_DIR:-/var/tmp/jk-gate}/quarantine-tickets.tsv}"
BRANCH="${FLAKY_BRANCH:-gate/quarantine}"
KA="${FLAKY_KA:-ka}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TICKET_ID='[A-Z][A-Z0-9]*-[0-9A-Z]{5}'

die() {
  echo "flaky: $*" >&2
  exit 2
}

# The JSON lines of $1; a line that is not JSON is skipped.
rows() { jq -cR 'fromjson? // empty' "$1"; }

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

take() {
  local saved="${1:?usage: flaky.sh take <saved>}"
  [[ -n "$(git status --porcelain -- "$FILE")" ]] || return 0
  [[ -f "$FILE" ]] && cp "$FILE" "$saved"
  echo "QUARANTINE jk changed $FILE in this gate; saved as $saved"
  if git cat-file -e "HEAD:$FILE" 2>/dev/null; then git checkout -q HEAD -- "$FILE"; else rm -f "$FILE"; fi
}

# The entries of the quarantine file on stdin, `<module> <class> <reason> <since> <issue> <url>`,
# tab-separated, `-` for empty: module and class bare, the others as the file writes them.
entries() {
  awk -v sq="'" '
    function value(s,   q, i, c, out) {
      sub(/^[ \t]+/, "", s)
      q = substr(s, 1, 1)
      if (q == "\"" || q == sq) {
        out = q
        for (i = 2; i <= length(s); i++) {
          c = substr(s, i, 1)
          out = out c
          if (q == "\"" && c == "\\") { i++; out = out substr(s, i, 1); continue }
          if (c == q) break
        }
        return out
      }
      sub(/[ \t]*#.*$/, "", s)
      sub(/[ \t]+$/, "", s)
      return s
    }
    function bare(s,   q) { q = substr(s, 1, 1); return q == "\"" || q == sq ? substr(s, 2, length(s) - 2) : s }
    function dash(s) { return s == "" ? "-" : s }
    function flush() {
      if (inc && f["name"] != "")
        print dash(bare(f["module"])) "\t" bare(f["name"]) "\t" dash(f["reason"]) "\t" dash(f["since"]) "\t" dash(f["issue"]) "\t" dash(f["issue-url"])
      split("", f)
      inc = 0
    }
    /^[ \t]*\[\[[ \t]*class[ \t]*\]\][ \t]*(#.*)?$/ { flush(); inc = 1; next }
    /^[ \t]*\[/ { flush(); next }
    inc && /^[ \t]*(name|module|reason|since|issue|issue-url)[ \t]*=/ {
      key = $0
      sub(/^[ \t]*/, "", key)
      sub(/[ \t]*=.*/, "", key)
      v = $0
      sub(/^[^=]*=/, "", v)
      f[key] = value(v)
    }
    END { flush() }
  '
}

# The file holding the entries on stdin, as jk writes it: its header, then one [[class]] table per
# entry, sorted by module then class.
render() {
  cat <<'EOF'
# Test classes in quarantine: a failure in one does not fail the run, and each still runs.
# JumpKick adds a class that fails and then passes on a re-run with nothing changed as often
# as [test] quarantine in jk.toml says. Delete an entry to release its class.
EOF
  LC_ALL=C sort -t$'\t' -k1,1 -k2,2 | while IFS=$'\t' read -r module class reason since issue url; do
    printf '\n[[class]]\nname = "%s"\n' "$class"
    [[ "$module" == - ]] || printf 'module = "%s"\n' "$module"
    [[ "$reason" == - ]] || printf 'reason = %s\n' "$reason"
    [[ "$since" == - ]] || printf 'since = %s\n' "$since"
    [[ "$issue" == - ]] || printf 'issue = %s\n' "$issue"
    [[ "$url" == - ]] || printf 'issue-url = %s\n' "$url"
  done
}

# The ticket id an `issue` value names, or nothing.
ticket_of() { grep -oE "$TICKET_ID" <<<"$1" | tail -1 || true; }

# The status of ticket $1, empty when ka cannot read it.
status() { "$KA" show "$1" -json </dev/null 2>/dev/null | jq -r '.status // empty' 2>/dev/null || true; }

closed() {
  case "$(status "$1")" in done | canceled) return 0 ;; *) return 1 ;; esac
}

# The ticket the gate last opened for class $2 of module $1, or nothing.
ledger_ticket() {
  [[ -f "$LEDGER" ]] || return 0
  awk -F'\t' -v m="$1" -v c="$2" '$1 == m && $2 == c { t = $5 } END { if (t != "") print t }' "$LEDGER"
}

# Open a ticket for class $2 of module $1, quarantined for reason $3; print its id.
open_ticket() {
  local module="$1" class="$2" reason="$3" where body history args
  where="module $module"
  [[ "$module" == - ]] && where="every module"
  [[ "$reason" == - ]] && reason=""
  history="$(jk flaky show "$class" --no-ansi </dev/null 2>/dev/null || true)"
  body="$(printf '%s\n\n%s\n\n%s\n' \
    "\`$class\` ($where) failed and then passed on a re-run with nothing changed often enough that jk quarantined it${reason:+: ${reason//\"/}}. A failure in it no longer fails a run, and every tier still runs it. The gate takes it out of \`$FILE\` when this ticket closes." \
    "Its history on $(hostname) (\`jk flaky show\`):" \
    "$(sed 's/^/    /' <<<"${history:-(not available)}")")"
  args=(new -project "${FLAKY_PROJECT:-jk}" -kind bug -priority P2 -label flake -json
    -t "Flaky test ${class##*.}: it fails and passes on re-run with nothing changed"
    -accept "${class##*.} passes 20 runs in a row under jk test -r" -m "$body")
  [[ -n "${FLAKY_PARENT:-}" ]] && args+=(-parent "$FLAKY_PARENT")
  "$KA" "${args[@]}" </dev/null 2>/dev/null | jq -r '.id // empty' 2>/dev/null || true
}

review() {
  local saved="${1:?usage: flaky.sh review <saved> <log>}" log="${2:?usage: flaky.sh review <saved> <log>}"
  local base="${FLAKY_BASE:-origin/main}" tip module class reason since issue url t ops=() first=""
  command -v "$KA" >/dev/null 2>&1 || die "$KA not found: the quarantine needs it to open and read tickets"
  git rev-parse -q --verify "$base^{commit}" >/dev/null || die "no commit $base to make the quarantine change on"
  tip="$(git rev-parse HEAD)"
  work="$(mktemp -d "${TMPDIR:-/tmp}/jk-quarantine.XXXXXX")"
  trap 'rm -rf "$work"' EXIT
  git show "HEAD:$FILE" 2>/dev/null | entries >"$work/head" || true
  git show "$base:$FILE" 2>/dev/null | entries >"$work/base" || true
  : >"$work/now"
  [[ -f "$saved" ]] && entries <"$saved" >"$work/now"

  # A ticket for each class jk added in this gate, and for each entry on the base that names none.
  mkdir -p "$(dirname "$LEDGER")"
  {
    awk -F'\t' -v head="$work/head" '
      BEGIN { while ((getline l < head) > 0) { split(l, a, "\t"); held[a[1] "\t" a[2]] = 1 } }
      !(($1 "\t" $2) in held)' "$work/now"
    awk -F'\t' -v id="$TICKET_ID" '$5 !~ id' "$work/base"
  } | while IFS=$'\t' read -r module class reason since issue url; do
    t="$(ledger_ticket "$module" "$class")"
    if [[ -n "$t" ]] && ! closed "$t"; then continue; fi
    t="$(open_ticket "$module" "$class" "$reason")"
    if [[ -z "$t" ]]; then
      echo "FLAKY $class: no ticket opened, so it stays out of the quarantine"
      continue
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$module" "$class" "$reason" "$since" "$t" >>"$LEDGER"
    echo "FLAKY $class quarantined: $t"
  done

  # The file the base should hold: its entries, each with its ticket's id and none whose ticket
  # closed, then each class the gate holds an open ticket for and the base does not. The id the
  # gate writes is the entry's whole issue, with no URL.
  : >"$work/want"
  while IFS=$'\t' read -r module class reason since issue url; do
    t="$(ticket_of "$issue")"
    [[ -n "$t" ]] || t="$(ledger_ticket "$module" "$class")"
    if [[ -n "$t" ]] && closed "$t"; then
      echo "FLAKY $class leaves the quarantine: $t is $(status "$t")"
      ops+=("$t	$class leaves the quarantine: $t is closed")
      continue
    fi
    if [[ -n "$t" && -z "$(ticket_of "$issue")" ]]; then
      issue="\"$t\""
      url=-
      ops+=("$t	$class is quarantined under $t")
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$module" "$class" "$reason" "$since" "$issue" "$url" >>"$work/want"
  done <"$work/base"
  if [[ -f "$LEDGER" ]]; then
    while IFS=$'\t' read -r module class reason since t; do
      awk -F'\t' -v m="$module" -v c="$class" '$1 == m && $2 == c { found = 1 } END { exit !found }' "$work/base" && continue
      closed "$t" && continue
      printf '%s\t%s\t%s\t%s\t"%s"\t-\n' "$module" "$class" "$reason" "$since" "$t" >>"$work/want"
      ops+=("$t	$class is quarantined under $t")
    done < <(awk -F'\t' '{ last[$1 "\t" $2] = $0 } END { for (k in last) print last[k] }' "$LEDGER" | LC_ALL=C sort)
  fi
  if [[ "${#ops[@]}" -eq 0 ]]; then
    echo "FLAKY the quarantine on $(git rev-parse --short=12 "$base") is current"
    return 0
  fi
  render <"$work/want" >"$work/file"
  [[ -s "$work/want" ]] || : >"$work/file"

  # Run the queue's scripts from copies, since the checkout moves to the base under them.
  cp "$HERE/../queue/branch-tier.sh" "$HERE/../queue/submit.sh" "$HERE/../queue/common.sh" "$work/"
  # shellcheck source=scripts/queue/common.sh
  . "$work/common.sh"
  if git fetch -q "$QUEUE_REMOTE" "refs/queue/$BRANCH" 2>/dev/null; then
    local waiting wanted=none
    waiting="$(git rev-parse -q --verify "FETCH_HEAD:$FILE" 2>/dev/null || echo none)"
    [[ -s "$work/want" ]] && wanted="$(git hash-object "$work/file")"
    if [[ "$waiting" == "$wanted" ]]; then
      echo "FLAKY the merge queue already holds this quarantine as $BRANCH"
      return 0
    fi
  fi

  if [[ -n "$(git status --porcelain)" ]]; then
    echo "FLAKY the checkout is not clean, so the quarantine change waits for the next gate"
    return 1
  fi
  first="$(cut -f1 <<<"${ops[0]}")"
  local subject="$first: The gate's test quarantine follows its tickets"
  [[ "${#ops[@]}" -eq 1 ]] && subject="$first: $(cut -f2 <<<"${ops[0]}")"
  git checkout -q --detach "$base" || die "cannot check out $base"
  mkdir -p "$(dirname "$FILE")"
  if [[ -s "$work/want" ]]; then cp "$work/file" "$FILE"; else rm -f "$FILE"; fi
  git add -A -- "$(dirname "$FILE")"
  git commit -q -m "$subject" -m "$(printf '%s\n' "${ops[@]}" | cut -f2 | sed 's/^/- /')" \
    -m "jk quarantined these classes on the gate's host, or their tickets closed; scripts/gate/flaky.sh review." \
    || {
      git reset -q --hard && git checkout -q --detach "$tip"
      die "the quarantine change could not be committed"
    }
  git checkout -q -B "$BRANCH"
  local rc=0
  (BRANCH_TIER_BASE="$base" bash "$work/branch-tier.sh" && bash "$work/submit.sh" "$BRANCH") >>"$log" 2>&1 </dev/null || rc=$?
  git reset -q --hard && git checkout -q --detach "$tip" && git branch -q -D "$BRANCH"
  if [[ "$rc" -ne 0 ]]; then
    echo "FLAKY the quarantine change did not pass the Branch tier or was not submitted (see $log)"
    return 1
  fi
  echo "FLAKY submitted the quarantine change to the merge queue as $BRANCH"
}

# One brace group, read whole before `review` moves the checkout under this file.
{
  case "${1:-}" in
    failed) failed "${@:2}" ;;
    take) take "${@:2}" ;;
    review) review "${@:2}" ;;
    *) die "usage: flaky.sh failed <details.jsonl> | take <saved> | review <saved> <log>" ;;
  esac
  exit
}
