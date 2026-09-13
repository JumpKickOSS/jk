#!/usr/bin/env bash
# The Gradle-vs-jk wall measurement the weekly workflow schedules; its result is row.jsonl.
set -euo pipefail
mkdir -p build/dogfood-wall
printf '{"shapes":"%s"}\n' "$*" > build/dogfood-wall/row.jsonl
