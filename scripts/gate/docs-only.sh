#!/usr/bin/env bash
# Which changes are docs-only: every file they touch is a doc that no test reads. A doc is a file
# under docs/ or hosting/, or a Markdown file at the root. The docs tests read are
# scripts/gate/test-reads.txt, derived from what the suites were seen reading: jk records each file a
# suite reads (docs/user/test.md, "What a suite read: discovered inputs") in its cache, under the
# module, and `write` collects those of every [workspace] member. A test that scans a directory of
# docs reads every file in it, so each of them is in the list.
#
# Usage: scripts/gate/docs-only.sh range <base> [<tip>]   exit 0 when base..tip is docs-only
#        scripts/gate/docs-only.sh reads                  the docs the recordings show tests read
#        scripts/gate/docs-only.sh write                  rewrite test-reads.txt from the recordings
#        scripts/gate/docs-only.sh check                  exit 1 naming each read the list lacks
#
# Run `write` and `check` in the checkout, after a run of every suite (the Full tier). The
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

# The tracked docs some suite of a member was recorded reading. A member's recordings are under
# projects/m-<tag>, the tag the first 12 hex of sha256("<project-id>\n<member path>").
reads() {
  local id cache m tag
  id="$(sed -n 's/^project-id *= *"\([^"]*\)".*/\1/p' "$root/jk-lock.toml" 2>/dev/null | head -1)"
  [ -n "$id" ] || return 0
  cache="${JK_CACHE_DIR:-${JK_HOME:-$HOME/.jk}/cache}"
  for m in $(members); do
    tag="$(printf '%s\n%s' "$id" "$m" | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12)"
    for f in "$cache/projects/m-$tag/suite-reads/"test-inputs-*; do
      if [ -f "$f" ]; then sed -n 's/^file ws://p' "$f"; fi
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
      reads
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
