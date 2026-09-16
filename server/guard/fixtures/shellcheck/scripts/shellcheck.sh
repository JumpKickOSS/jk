#!/usr/bin/env bash
# The lint script of this fixture's checkout: every script under scripts/, the messages the guard
# reads (the script count on every line, "scripts clean" on success) and the same runner choice as
# the repository's own: a working binary or container daemon, else a skip notice on a developer
# machine and a failure under CI. Windows is skipped outright.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TARGETS=()
for script in scripts/*.sh; do
  TARGETS+=("$script")
done

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
    echo "shellcheck: skipped on Windows — ${#TARGETS[@]} scripts" >&2
    exit 0
    ;;
esac

runner_works() {
  "$@" >/dev/null 2>&1
}

RUNNER=()
if command -v shellcheck >/dev/null 2>&1 && runner_works shellcheck --version; then
  RUNNER=(shellcheck)
elif command -v podman >/dev/null 2>&1 && runner_works podman info; then
  RUNNER=(podman run --rm -v "$ROOT:/mnt:ro,Z" -w /mnt docker.io/koalaman/shellcheck:stable)
elif command -v docker >/dev/null 2>&1 && runner_works docker info; then
  RUNNER=(docker run --rm -v "$ROOT:/mnt:ro" -w /mnt koalaman/shellcheck:stable)
elif [[ -n "${CI:-}" ]]; then
  echo "shellcheck: no working shellcheck runner on this CI machine — install the binary or a usable container runtime" >&2
  exit 1
else
  echo "shellcheck: not installed — skipping ${#TARGETS[@]} scripts" >&2
  exit 0
fi

echo "shellcheck: linting ${#TARGETS[@]} scripts with ${RUNNER[0]}" >&2
"${RUNNER[@]}" --severity=info --external-sources "$@" "${TARGETS[@]}"
echo "shellcheck: ${#TARGETS[@]} scripts clean"
