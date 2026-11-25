#!/usr/bin/env bash
# The tests CI runs on Windows (Git Bash) and macOS (bash 3.2) once the checkout's own jk has taken
# the home over: every module's fast tier, then each test class whose source names an OS, fast or
# integration (those tagged live, slow, network or bench are left to the runs that own those
# tiers). A JUnit 3 class (one that extends a *TestCase) is not one the JUnit Platform discovers:
# the module's test classes that name it run it, so those stand in for it, and it stays itself
# when none does. Every step runs; the script exits non-zero when any was red.
#
# Usage: bash scripts/ci-platform-tests.sh
set -u
cd "$(dirname "$0")/.." || exit 2
red=""

jk test -c --no-ansi || red="${red:+$red, }fast tier"

junit3='class[[:space:]]+[[:alnum:]_]+([[:space:]]+extends[[:space:]]+|[[:space:]]*:[[:space:]]*)[[:alnum:]_.]*TestCase([^[:alnum:]_]|$)'
untiered() { xargs grep -LE '@Tag\("(live|slow|network|bench)"\)' 2>/dev/null; }
classes="$(mktemp)"
trap 'rm -f "$classes"' EXIT
git ls-files '*/src/test/*.java' '*/src/test/*.kt' '*/src/test/*.groovy' \
  | xargs grep -lE '@(Enabled|Disabled)OnOs|OS\.(WINDOWS|MAC|LINUX)|IS_OS_|isWindows|isMac|os\.name' 2>/dev/null \
  | grep -E '(Test|Tests|IT)\.(java|kt|groovy)$' | untiered \
  | while read -r f; do
    if ! grep -qE "$junit3" "$f"; then
      echo "$f"
      continue
    fi
    hosts=""
    for h in $(git ls-files "${f%%/src/test/*}/src/test/*" | grep -E '(Test|Tests|IT)\.(java|kt|groovy)$'); do
      [ "$h" != "$f" ] && grep -qw "$(basename "${f%.*}")" "$h" && ! grep -qE "$junit3" "$h" && hosts="${hosts:+$hosts
}$h"
    done
    if [ -z "$hosts" ]; then echo "$f"; else echo "$hosts" | untiered; fi
  done \
  | sed -nE 's#^(.*)/src/test/(java|kotlin|groovy)/(.*)\.(java|kt|groovy)$#\1 \3#p' \
  | awk '{ gsub("/", ".", $2); print }' | sort -u >"$classes"
echo "OS-conditioned classes: $(wc -l <"$classes" | tr -d ' ') in $(awk '{ print $1 }' "$classes" | sort -u | wc -l | tr -d ' ') modules"

while read -r module; do
  set -- jk test -A -c --no-ansi -m "$module"
  while read -r class; do set -- "$@" --class "$class"; done < <(awk -v m="$module" '$1 == m { print $2 }' "$classes")
  "$@" </dev/null || red="${red:+$red, }$module"
done < <(awk '{ print $1 }' "$classes" | sort -u)

if [ -n "$red" ]; then
  echo "red: $red"
  exit 1
fi
