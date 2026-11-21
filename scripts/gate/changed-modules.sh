#!/usr/bin/env bash
# The workspace members a range of commits changed, one per line, as the root jk.toml's
# `[workspace] modules` names them: the member holding each changed file. A file under docs/,
# scripts/, .github/ or bench/, or a Markdown file at the root, selects nothing; any other file
# outside every member (the root jk.toml, jk-lock.toml, .jk/, templates/, ...) selects every member,
# printed as one `*`. Prints nothing when the range changed no member.
#
# Usage: scripts/gate/changed-modules.sh <base> [<tip>]   (tip defaults to HEAD; run in the checkout)
set -euo pipefail

base="${1:?usage: changed-modules.sh <base> [<tip>]}"
tip="${2:-HEAD}"
root="$(git rev-parse --show-toplevel)"

# The quoted entries of the root jk.toml's `modules = [ ... ]` under [workspace], comments skipped.
members="$(awk '
  /^\[workspace\][[:space:]]*$/ { ws = 1; next }
  /^\[/ { ws = 0; inlist = 0 }
  ws && /^[[:space:]]*modules[[:space:]]*=[[:space:]]*\[/ { inlist = 1 }
  inlist {
    line = $0
    sub(/#.*/, "", line)
    done = line ~ /\]/
    while (match(line, /"[^"]+"/)) {
      print substr(line, RSTART + 1, RLENGTH - 2)
      line = substr(line, RSTART + RLENGTH)
    }
    if (done) inlist = 0
  }
' "$root/jk.toml")"
[[ -n "$members" ]] || {
  echo "changed-modules: $root/jk.toml names no [workspace] modules" >&2
  exit 2
}

changed="$(git diff --name-only "$base" "$tip")"
[[ -n "$changed" ]] || exit 0

# Longest member prefix of each path, so server/guard/packs/spring wins over server/guard.
MEMBERS="$members" awk '
  BEGIN { n = split(ENVIRON["MEMBERS"], m, "\n") }
  {
    best = ""
    for (i = 1; i <= n; i++)
      if (index($0, m[i] "/") == 1 && length(m[i]) > length(best)) best = m[i]
    if (best != "") hit[best] = 1
    else if ($0 !~ /^((docs|scripts|\.github|bench)\/|[^\/]+\.md$)/) all = 1
  }
  END {
    if (all) { print "*"; exit }
    for (b in hit) print b
  }
' <<<"$changed" | sort
