#!/usr/bin/env bash
# Lombok is compile-time only: jk.java-conventions wires it as compileOnly +
# annotationProcessor (and the test variants). It must never reach a runtime,
# fat-jar, or native-image classpath — no runtime-reaching Gradle configuration
# and no jk.toml dependency entry outside provided-/processor-dependencies.
# Usage: scripts/check-lombok-diet.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

gradle_runtime="$(git grep -inE \
  '^[[:space:]]*"?(implementation|api|runtimeOnly|testImplementation|testRuntimeOnly)"?\(.*lombok' \
  -- '*.gradle' '*.gradle.kts' || true)"
if [ -n "$gradle_runtime" ]; then
  printf '%s\n' "$gradle_runtime" >&2
  echo "error: lombok in a runtime-reaching Gradle configuration (compileOnly/annotationProcessor only)" >&2
  exit 1
fi

manifest_runtime="$(git ls-files '*jk.toml' | while IFS= read -r f; do
  awk -v f="$f" '
    /^\[/ { table = $0 }
    tolower($0) ~ /^[[:space:]]*"?lombok"?[[:space:]]*[=.]/ {
      if (table !~ /provided-dependencies|processor-dependencies/) printf "%s:%d:%s\n", f, NR, $0
    }' "$f"
done)"
if [ -n "$manifest_runtime" ]; then
  printf '%s\n' "$manifest_runtime" >&2
  echo "error: lombok in a runtime-reaching jk.toml scope (provided-/processor-dependencies only)" >&2
  exit 1
fi

echo "ok: lombok stays compile-time only (no runtime/fat-jar/native-image scope)"
