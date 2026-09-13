#!/usr/bin/env bash
# The lint script of this fixture's checkout: every script under scripts/, the messages the guard
# reads (the script count on every line, "scripts clean" on success) and the same runner choice as
# the repository's own: the binary, else a container runtime, else a skip notice on a developer
# machine and a failure under CI.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TARGETS=()
for script in scripts/*.sh; do
  TARGETS+=("$script")
done

if command -v shellcheck >/dev/null 2>&1; then
  RUNNER=(shellcheck)
elif command -v podman >/dev/null 2>&1; then
  RUNNER=(podman run --rm -v "$ROOT:/mnt:ro,Z" -w /mnt docker.io/koalaman/shellcheck:stable)
elif command -v docker >/dev/null 2>&1; then
  RUNNER=(docker run --rm -v "$ROOT:/mnt:ro" -w /mnt koalaman/shellcheck:stable)
elif [[ -n "${CI:-}" ]]; then
  echo "shellcheck: no shellcheck binary and no container runtime on this CI runner — install one" >&2
  exit 1
else
  echo "shellcheck: not installed — skipping ${#TARGETS[@]} scripts" >&2
  exit 0
fi

echo "shellcheck: linting ${#TARGETS[@]} scripts with ${RUNNER[0]}" >&2
"${RUNNER[@]}" --severity=info --external-sources "$@" "${TARGETS[@]}"
echo "shellcheck: ${#TARGETS[@]} scripts clean"
