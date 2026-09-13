#!/usr/bin/env bash
# The finding the guard exists for: an unquoted $var in a [ ] test (SC2086) splits on spaces and globs.
set -euo pipefail

target="${1:-build}"
if [ -d $target ]; then
  echo "found $target"
fi
