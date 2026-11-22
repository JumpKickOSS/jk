#!/usr/bin/env bash
# Runs named test classes again on one commit, to learn whether they fail there. It detaches a
# clean checkout at the commit, reinstalls it without tests (the integration classes run against
# the JumpKick of the tree they test), starts a fresh engine, and runs one `jk test -A` per module
# that names each of its classes with --class. A class whose source the commit does not have is
# skipped: it cannot fail where it does not exist.
#
# Usage: scripts/gate/rerun-classes.sh <sha> <classes>
#
#   <classes>      a file of `<module> <class>` lines, as `scripts/gate/flaky.sh failed` prints them;
#                  the module is its directory or the `name` its jk.toml declares
#   GATE_CHECKOUT  the clean checkout to run in (required)
#
# Exits 0 when every class passes, 1 when one fails, and 2 when they could not run there: a dirty
# checkout, an unknown commit or module, a red reinstall, or a run red for anything but a test.
#
# The whole body is one brace group, so bash has read all of it before the checkout changes this file.
{
  set -uo pipefail
  export PATH="$HOME/.jk/bin:$PATH"
  sha="${1:?usage: rerun-classes.sh <sha> <classes>}"
  list="${2:?usage: rerun-classes.sh <sha> <classes>}"
  here="$(cd "$(dirname "$0")" && pwd)"
  out="$(mktemp -d "${TMPDIR:-/var/tmp}/jk-rerun.XXXXXX")" || exit 2
  trap 'rm -rf "$out"' EXIT

  cant() {
    echo "rerun: $*"
    exit 2
  }
  # The directory of module $1 at this commit: the name itself, or the member whose jk.toml says so.
  module_dir() {
    local f
    [ -f "$1/jk.toml" ] && echo "$1" && return 0
    while read -r f; do
      if [ "$(sed -n 's/^name *= *"\([^"]*\)".*/\1/p' "$f" | head -1)" = "$1" ]; then
        dirname "$f"
        return 0
      fi
    done < <(git ls-files '*jk.toml')
    return 1
  }

  [ -s "$list" ] || cant "no classes in $list"
  cd "${GATE_CHECKOUT:?GATE_CHECKOUT: the checkout to run in}" || cant "no checkout"
  [ -z "$(git status --porcelain)" ] || cant "$GATE_CHECKOUT is not clean"
  git checkout -q --detach "$sha" || cant "cannot check out $sha"
  echo "rerun at $(git rev-parse --short=12 HEAD): $(awk '{ print $2 }' "$list" | tr '\n' ' ')"
  jk install -S >"$out/install.log" 2>&1 </dev/null || { tail -20 "$out/install.log"; cant "the reinstall is red"; }
  jk engine stop >/dev/null 2>&1

  rc=0
  while read -r module; do
    dir="$(module_dir "$module")" || cant "no module $module here"
    args=()
    for class in $(awk -v m="$module" '$1 == m { print $2 }' "$list"); do
      if git ls-files "$dir" | grep -qE "/${class//.//}\.(java|kt|groovy)$"; then
        args+=(--class "$class")
      else
        echo "SKIP $class: not in $dir here"
      fi
    done
    [ "${#args[@]}" -gt 0 ] || continue
    jk test -A -c --no-ansi -m "$dir" "${args[@]}" >"$out/test.log" 2>&1 </dev/null
    e=$?
    echo "RERUN $dir EXIT=$e"
    [ "$e" -eq 0 ] && continue
    grep -aE '^(FAIL|T |E )|FAILED ' "$out/test.log" | tail -20
    jk results -O jsonl >"$out/test.jsonl" 2>/dev/null </dev/null
    bash "$here/flaky.sh" failed "$out/test.jsonl" >/dev/null 2>&1 || cant "$dir is red for something other than a test"
    rc=1
  done < <(awk '{ print $1 }' "$list" | sort -u)
  exit "$rc"
}
