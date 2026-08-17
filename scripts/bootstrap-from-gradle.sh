#!/usr/bin/env bash
# Bootstrap a usable `jk` from this dual-build tree (Gradle dist → install), then print dogfood steps.
# Usage (from this repo, or pass another checkout that has gradlew):
# ./scripts/bootstrap-from-gradle.sh
# ./scripts/bootstrap-from-gradle.sh /path/to/oss/jk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PRIMARY="${1:-}"
if [ -z "$PRIMARY" ]; then
  if [ -x "$ROOT/../jk/gradlew" ]; then
    PRIMARY="$(cd "$ROOT/../jk" && pwd)"
  elif [ -x "$ROOT/gradlew" ]; then
    PRIMARY="$ROOT"
  else
    echo "usage: $0 [path-to-gradle-jk-checkout]" >&2
    echo "Could not find ../jk/gradlew or ./gradlew" >&2
    exit 1
  fi
fi

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
echo "  See docs/self-host.md"
