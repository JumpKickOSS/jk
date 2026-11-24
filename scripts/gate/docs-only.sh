#!/usr/bin/env bash
# Which changes are docs-only: every file they touch is a doc that no test reads, other than the docs
# suite's (`jk test -s docs`), which the docs tier runs. A doc is a file under docs/ or hosting/, or a
# Markdown file at the root. The docs tests read are scripts/gate/test-reads.txt, derived from what
# the suites were seen reading: jk records each file a suite reads (docs/user/test.md, "What a suite
# read: discovered inputs") in its cache, under the module and the selection that ran, and `write`
# collects those of every [workspace] member under the selections a gate runs: the default suite,
# each suite the root jk.toml declares but the docs suite, its guard-suites (`-g`) and `--all`. An
# ad-hoc run (`--class`, `-I`, a combination of suites no gate runs) records under a selection of its
# own, which the list never takes in, so a scratch probe that reads the whole checkout leaves no
# trace in it. A test that scans a directory of docs reads every file in it, so it belongs in the
# docs suite.
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

# The quoted entries of the list `$2 = [ ... ]` under the root jk.toml's table `[$1]`, comments skipped.
toml_list() {
  awk -v table="[$1]" -v key="$2" '
    /^\[/ { line = $0; sub(/[[:space:]]*(#.*)?$/, "", line); tb = line == table; inlist = 0; next }
    tb && !inlist && $0 ~ "^[[:space:]]*" key "[[:space:]]*=[[:space:]]*\\[" { inlist = 1 }
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

# The workspace's members, as the root jk.toml's `[workspace] modules` names them.
members() { toml_list workspace modules; }

# The selections a gate runs, as jk names them (TestSelection.identityToken): the default suite,
# each declared suite but the docs suite, the guard suites (the root's guard-suites, else jk's
# default of test and integration) and every suite (`--all`).
selections() {
  local guard
  echo 'suites=test;+tag=;-tag='
  sed -n 's/^\[test\.suites\."\{0,1\}\([A-Za-z0-9_-]*\)"\{0,1\}\][[:space:]]*\(#.*\)\{0,1\}$/\1/p' "$root/jk.toml" |
    grep -vx docs | sed 's/.*/suites=&;+tag=;-tag=/'
  guard="$(toml_list test guard-suites | paste -sd, -)"
  echo "suites=${guard:-test,integration};+tag=;-tag="
  echo 'suites=*;+tag=;-tag='
}

sha12() { { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12; }

# The tracked docs a member was recorded reading under a selection a gate runs. A member's
# recordings are under projects/m-<tag>, the tag the first 12 hex of sha256("<project-id>\n<member
# path>"), one file per selection: test-inputs-<sel>, <sel> the first 12 hex of sha256 of the
# selection's identity.
reads() {
  local id cache m tag sel
  local -a files=()
  id="$(sed -n 's/^project-id *= *"\([^"]*\)".*/\1/p' "$root/jk-lock.toml" 2>/dev/null | head -1 || true)"
  [ -n "$id" ] || return 0
  cache="${JK_CACHE_DIR:-${JK_HOME:-$HOME/.jk}/cache}"
  while read -r sel; do files+=("test-inputs-$(printf '%s' "$sel" | sha12)"); done < <(selections | sort -u)
  for m in $(members); do
    tag="$(printf '%s\n%s' "$id" "$m" | sha12)"
    for sel in "${files[@]}"; do
      if [ -f "$cache/projects/m-$tag/suite-reads/$sel" ]; then sed -n 's/^file ws://p' "$cache/projects/m-$tag/suite-reads/$sel"; fi
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
