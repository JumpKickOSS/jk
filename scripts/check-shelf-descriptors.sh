#!/usr/bin/env bash
# Every first-party worker jar on a home's shelf describes itself: the root jk-plugin.toml inside
# <home>/store/repos/jk-local/cc/jumpkick/<worker>/<version>/<worker>-<version>.jar names the same
# [plugin] table as the module's own descriptor under plugins/. A vendored sibling's descriptor at
# the jar root registers the wrong table owner, so a self-host install is proven here, not assumed.
#
# The version each jar is shelved at is the one jk builds it as, which is jk.toml's only for a
# release (a clean checkout whose HEAD carries the version tag) and <version>-SNAPSHOT otherwise, as
# on an untagged CI checkout. It is asked of jk, not read from jk.toml: `jk inspect package-jar`
# names every module's jar as this build writes it.
#
# Usage: scripts/check-shelf-descriptors.sh <JK_HOME> [<tree>]     (the tree defaults to this checkout)
#   JK_BIN   the jk to ask (default: jk on PATH)
# Exit 0 when every worker with a descriptor is shelved and describes itself; 1 when one is missing
# or describes another plugin; 2 when the tree does not read as jk's or jk names no jar for a worker.
set -euo pipefail

home="${1:?usage: check-shelf-descriptors.sh <JK_HOME> [<tree>]}"
tree="${2:-$(cd "$(dirname "$0")/.." && pwd)}"
tree="$(cd "$tree" && pwd -P)"

# One line per module: "<module dir>\t<jar file name>", from jk's own plan of the build.
jars="$("${JK_BIN:-jk}" -C "$tree" inspect package-jar -O jsonl --no-ansi 2>/dev/null \
  | sed -nE 's/.*"moduleDir":"([^"]*)","output":"([^"]*)".*/\1\t\2/p')" \
  || { echo "check-shelf-descriptors: ${JK_BIN:-jk} could not plan $tree" >&2; exit 2; }

checked=0
for descriptor in "$tree"/plugins/*/jk-plugin.toml; do
  [[ -f "$descriptor" ]] || continue
  module="$(dirname "$descriptor")"
  name="$(sed -nE 's/^name *= *"([^"]+)".*/\1/p' "$module/jk.toml" | head -1)"
  [[ -n "$name" ]] || { echo "check-shelf-descriptors: $module/jk.toml declares no name" >&2; exit 2; }
  built="$(awk -F'\t' -v dir="$(cd "$module" && pwd -P)" '$1 == dir { print $2; exit }' <<<"$jars")"
  file="${built##*/}"
  version="${file#"$name"-}"
  version="${version%.jar}"
  if [[ -z "$built" || "$file" != "$name-$version.jar" || -z "$version" ]]; then
    echo "check-shelf-descriptors: ${JK_BIN:-jk} names no jar for $name (got '${built:-nothing}')" >&2
    exit 2
  fi
  jar="$home/store/repos/jk-local/cc/jumpkick/$name/$version/$name-$version.jar"
  [[ -f "$jar" ]] || { echo "check-shelf-descriptors: $name is not on the shelf: $jar" >&2; exit 1; }
  want="$(sed -nE 's/^table *= *"([^"]+)".*/\1/p' "$descriptor" | head -1)"
  have="$(unzip -p "$jar" jk-plugin.toml 2>/dev/null | sed -nE 's/^table *= *"([^"]+)".*/\1/p' | head -1)"
  if [[ "$have" != "$want" ]]; then
    echo "check-shelf-descriptors: $jar describes table [${have:-none}]; its module declares [$want]" >&2
    exit 1
  fi
  checked=$((checked + 1))
done
echo "check-shelf-descriptors: $checked worker jars describe themselves"
