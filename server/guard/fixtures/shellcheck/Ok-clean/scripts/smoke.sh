#!/usr/bin/env bash
# Clean: the test quotes its expansion and the loop reads its glob as paths.
set -euo pipefail

target="${1:-build}"
if [ -d "$target" ]; then
  for jar in "$target"/*.jar; do
    [ -e "$jar" ] || continue
    echo "found $jar"
  done
fi
