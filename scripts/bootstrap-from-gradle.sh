#!/usr/bin/env bash
# Bootstrap a usable `jk` on a host with no hosted client: the Gradle bootstrap's dist, then
# install.sh, then the dogfood steps. jk is the gate; Gradle only produces the first client.
# Usage (this checkout by default, or pass another checkout that has gradlew):
# ./scripts/bootstrap-from-gradle.sh
# ./scripts/bootstrap-from-gradle.sh /path/to/oss/jk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# The checkout this script lives in is the tree it builds; a sibling checkout is built only when
# named, so a worktree never bootstraps its neighbour's tree by accident.
PRIMARY="${1:-$ROOT}"

if [ ! -x "$PRIMARY/gradlew" ]; then
  echo "not a jk gradle checkout: $PRIMARY" >&2
  exit 1
fi

echo "* Building dist + installLocal in $PRIMARY"
(
  cd "$PRIMARY"
  ./gradlew dist installLocal
  ./install.sh build/dist/jk
)

echo
echo "* Bootstrap complete. Dogfood:"
echo "    export PATH=\"\$HOME/.jk/bin:\$PATH\""
echo "    cd $ROOT && jk lock && jk build --skip-tests"
echo "  See docs/contributors/self-host.md"
