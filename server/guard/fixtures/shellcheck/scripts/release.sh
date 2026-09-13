#!/usr/bin/env bash
# A clean script every case starts with: quoted expansions, a guarded cd, a checked argument.
set -euo pipefail

version="${1:?usage: release.sh <version>}"
out="dist/jk-${version}"
mkdir -p "$out"
if [ -n "${out}" ]; then
  echo "staging ${version} under ${out}"
fi
