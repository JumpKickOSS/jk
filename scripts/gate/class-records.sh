#!/usr/bin/env bash
# Carries jk's per-class test records from the Full tier's checkout to the Queue tier's, so the
# Queue tier ranks a class it defers by what that class did when the Full tier last ran it.
#
# jk keeps the records of a workspace member in its cache, keyed by the member directory's real
# path (`<cache>/projects/<first 16 of sha256(path)>/suite-reads/test-classes.ndjson`, the cache
# being JK_CACHE_DIR, else <JK_HOME or ~/.jk>/cache), so two checkouts never share them. A row names
# its class, its launch and what it read relative to the workspace, so it reads the same in any
# checkout of the tree.
#
#   export <dir>               copy each member's record file to <dir>/<member>/test-classes.ndjson
#   import <dir> <member>...   add the rows of <dir>/<member>/test-classes.ndjson to the member's own
#                              records: a class it has no row of gets the shipped one, and a row an
#                              earlier import added is replaced by a newer one. The rows are added
#                              without their key (classKey), so jk never replays a
#                              class on another checkout's run: it ranks by them and runs the class.
#                              A row jk wrote here is never touched.
#   classes <member>           print `<class> here|shipped` for each top-level class the member has a
#                              row of in an integration launch: jk wrote it here, or an import added it
#   path <member>              print where jk keeps the member's records
#
# Run in the checkout, under the JumpKick home its engine uses. Members are the root jk.toml's
# `[workspace] modules`.
set -euo pipefail

usage() {
  echo "usage: class-records.sh export <dir> | import <dir> <member>... | classes <member> | path <member>" >&2
  exit 2
}
[[ $# -ge 2 ]] || usage
root="$(git rev-parse --show-toplevel)"

# The record file of the member at $1 (a path relative to the root).
record_file() {
  local dir hash cache
  dir="$(cd "$root/$1" 2>/dev/null && pwd -P)" || dir="$root/$1"
  if command -v sha256sum >/dev/null 2>&1; then
    hash="$(printf %s "$dir" | sha256sum | cut -c1-16)"
  else
    hash="$(printf %s "$dir" | shasum -a 256 | cut -c1-16)"
  fi
  cache="${JK_CACHE_DIR:-${JK_HOME:-$HOME/.jk}/cache}"
  echo "$cache/projects/$hash/suite-reads/test-classes.ndjson"
}

# The quoted entries of the root jk.toml's `modules = [ ... ]` under [workspace], comments skipped.
members() {
  [[ -f "$root/jk.toml" ]] || return 0
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

case "$1" in
  path)
    record_file "$2"
    ;;
  classes)
    f="$(record_file "$2")"
    [[ -f "$f" ]] || exit 0
    jq -rR 'fromjson? // empty
      | select(type == "object" and (has("kind") | not) and (.launch | type == "string") and (.container | type == "array"))
      | select(.launch | split(":")[1] // "" | split(",") | index("integration"))
      | ((.container[0] | if . == "" then "" else . + "." end) + .container[1]) + " "
        + (if .classKey == null then "shipped" else "here" end)' "$f" | sort -u
    ;;
  export)
    out="$2"
    mkdir -p "$out"
    members | while IFS= read -r m; do
      f="$(record_file "$m")"
      [[ -s "$f" ]] || continue
      mkdir -p "$out/$m"
      cp "$f" "$out/$m/test-classes.ndjson"
    done
    ;;
  import)
    from="$2"
    shift 2
    [[ $# -gt 0 ]] || usage
    for m in "$@"; do
      shipped="$from/$m/test-classes.ndjson"
      f="$(record_file "$m")"
      if [[ -s "$shipped" ]]; then
        mkdir -p "$(dirname "$f")"
        [[ -f "$f" ]] || : >"$f"
        # One pass: the shipped rows by launch and class (the newest of each, keys dropped), then
        # the member's own lines, a row an import added replaced by a newer shipped one, then the
        # shipped rows of classes it had no row of. The headers stay the member's own.
        tmp="$f.import.$$"
        jq -nr --rawfile shipped "$shipped" --rawfile own "$f" '
          def row: if type == "object" and (has("kind") | not) and (.launch | type == "string")
            and (.container | type == "array") then .container | length > 0 else false end;
          def key: "\(.launch)|\(.container | tojson)";
          (reduce ($shipped | split("\n")[] | fromjson? // empty | select(row) | del(.classKey)) as $r
            ({}; ($r | key) as $k | if (.[$k].ranAt // -1) < ($r.ranAt // 0) then .[$k] = $r else . end)) as $by
          | ($own | split("\n") | map(select(length > 0))) as $lines
          | (reduce ($lines[] | fromjson? // null | select(row) | key) as $k ({}; .[$k] = true)) as $seen
          | ($lines[] | . as $l | (fromjson? // null) as $o
              | if ($o | row) and $o.classKey == null and (($by[$o | key].ranAt // -1) > ($o.ranAt // 0))
                then $by[$o | key] | tojson else $l end),
            ($by | to_entries[] | select(.key | in($seen) | not) | .value | tojson)
        ' >"$tmp"
        mv "$tmp" "$f"
      fi
    done
    ;;
  *) usage ;;
esac
