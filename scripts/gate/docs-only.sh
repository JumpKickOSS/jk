#!/usr/bin/env bash
# Which changes are docs-only: every file they touch is a doc that no test reads, other than the docs
# suite's (`jk test -s docs`), which the docs tier runs. A doc is a file under docs/ or hosting/, or a
# Markdown file at the root. The docs tests read are scripts/gate/test-reads.txt, derived from what
# the suites were seen reading: jk records each file a suite reads (docs/user/test.md, "What a suite
# read: discovered inputs") in its cache, under the module and the selection that ran, and `write`
# collects those of every [workspace] member, leaving out what `jk test -s docs` alone read. A test
# that scans a directory of docs reads every file in it, so it belongs in the docs suite.
#
# A host's cache holds only the suites it ran, so `write` adds what it finds to the list and drops
# only a doc git no longer tracks; a doc no test reads any more leaves the list by hand.
#
# Usage: scripts/gate/docs-only.sh range <base> [<tip>]   exit 0 when base..tip is docs-only
#        scripts/gate/docs-only.sh reads                  the docs the recordings show tests read
#        scripts/gate/docs-only.sh write                  add the recorded reads to test-reads.txt
#        scripts/gate/docs-only.sh check                  exit 1 naming each read the list lacks
#
# Run `write` and `check` in the checkout, after the suites whose reads they should see. The
# recordings are jk's, under ${JK_CACHE_DIR:-${JK_HOME:-~/.jk}/cache}; a tree with no project-id in
# its jk-lock.toml has none.
set -euo pipefail
export LC_ALL=C

root="$(git rev-parse --show-toplevel)"
list=scripts/gate/test-reads.txt

is_doc() { grep -E '^(docs|hosting)/|^[^/]+\.md$'; }

# The workspace's members, as the root jk.toml's `[workspace] modules` names them.
members() {
  awk '
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
  ' "$root/jk.toml"
}

sha12() { { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12; }

# The tracked docs some suite of a member was recorded reading, but for the docs suite run alone. A
# member's recordings are under projects/m-<tag>, the tag the first 12 hex of
# sha256("<project-id>\n<member path>"), one file per selection: test-inputs-<sel>, <sel> the first 12
# hex of sha256 of the selection's identity, "suites=docs;+tag=;-tag=" for `jk test -s docs`.
reads() {
  local id cache m tag docs
  id="$(sed -n 's/^project-id *= *"\([^"]*\)".*/\1/p' "$root/jk-lock.toml" 2>/dev/null | head -1 || true)"
  [ -n "$id" ] || return 0
  cache="${JK_CACHE_DIR:-${JK_HOME:-$HOME/.jk}/cache}"
  docs="test-inputs-$(printf '%s' 'suites=docs;+tag=;-tag=' | sha12)"
  for m in $(members); do
    tag="$(printf '%s\n%s' "$id" "$m" | sha12)"
    for f in "$cache/projects/m-$tag/suite-reads/"test-inputs-*; do
      if [ -f "$f" ] && [ "${f##*/}" != "$docs" ]; then sed -n 's/^file ws://p' "$f"; fi
    done
  done | sort -u | comm -12 - <(git -C "$root" ls-files | is_doc | sort)
}

case "${1:-}" in
  range)
    base="${2:?usage: docs-only.sh range <base> [<tip>]}"
    tip="${3:-HEAD}"
    git cat-file -e "$tip:$list" 2>/dev/null || {
      echo "docs-only: $tip has no $list" >&2
      exit 1
    }
    known="$(git show "$tip:$list" | grep -v '^#' || true)"
    while read -r f; do
      [ -n "$f" ] || continue
      if ! is_doc <<<"$f" >/dev/null; then
        echo "docs-only: $f is not a doc" >&2
        exit 1
      fi
      if grep -Fxq -- "$f" <<<"$known"; then
        echo "docs-only: a test reads $f" >&2
        exit 1
      fi
    done < <(git diff --no-renames --name-only "$base" "$tip")
    ;;
  reads) reads ;;
  write)
    {
      echo "# The docs a test reads, so a change to one is not docs-only (scripts/gate/docs-only.sh)."
      echo "# Written by \`scripts/gate/docs-only.sh write\` from what the suites were seen reading."
      { grep -v '^#' "$root/$list" 2>/dev/null || true; reads; } | sort -u |
        comm -12 - <(git -C "$root" ls-files | is_doc | sort)
    } >"$root/$list.tmp"
    mv "$root/$list.tmp" "$root/$list"
    ;;
  check)
    missing="$(reads | comm -23 - <(grep -v '^#' "$root/$list" 2>/dev/null | sort))"
    [ -z "$missing" ] && exit 0
    sed 's/^/docs-only: a test reads /; s/$/, which test-reads.txt does not list/' <<<"$missing" >&2
    echo "docs-only: run scripts/gate/docs-only.sh write and commit $list" >&2
    exit 1
    ;;
  *)
    echo "usage: docs-only.sh range <base> [<tip>] | reads | write | check" >&2
    exit 2
    ;;
esac
