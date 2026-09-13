#!/usr/bin/env bash
# The scheduled wall measurement of jk on this tree; its result is row.jsonl.
set -euo pipefail
mkdir -p build/dogfood-wall
printf '{"shapes":"%s"}\n' "$*" > build/dogfood-wall/row.jsonl
