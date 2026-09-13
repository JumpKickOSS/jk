#!/usr/bin/env bash
# Every first-party worker jar on a home's shelf describes itself: the root jk-plugin.toml inside
# <home>/store/repos/jk-local/cc/jumpkick/<worker>/<version>/<worker>-<version>.jar names the same
# [plugin] table as the module's own descriptor under plugins/. A vendored sibling's descriptor at
# the jar root registers the wrong table owner, so a self-host install is proven here, not assumed.
#
# Usage: scripts/check-shelf-descriptors.sh <JK_HOME> [<tree>]     (the tree defaults to this checkout)
# Exit 0 when every worker with a descriptor is shelved and describes itself; 1 when one is missing
# or describes another plugin; 2 when the tree does not read as jk's.
set -euo pipefail

home="${1:?usage: check-shelf-descriptors.sh <JK_HOME> [<tree>]}"
tree="${2:-$(cd "$(dirname "$0")/.." && pwd)}"

version="$(sed -nE 's/^version *= *"([^"]+)".*/\1/p' "$tree/jk.toml" | head -1)"
[[ -n "$version" ]] || { echo "check-shelf-descriptors: $tree/jk.toml declares no version" >&2; exit 2; }

checked=0
for descriptor in "$tree"/plugins/*/jk-plugin.toml; do
  [[ -f "$descriptor" ]] || continue
  module="$(dirname "$descriptor")"
  name="$(sed -nE 's/^name *= *"([^"]+)".*/\1/p' "$module/jk.toml" | head -1)"
  [[ -n "$name" ]] || { echo "check-shelf-descriptors: $module/jk.toml declares no name" >&2; exit 2; }
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
