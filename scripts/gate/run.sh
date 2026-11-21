#!/usr/bin/env bash
# Run one gate under the host's gate lock, so two gates never share a host: scripts/gate/gate.sh
# with the same arguments and environment, once the lock is free (wait at most two hours).
#
# Usage: scripts/gate/run.sh <n> <tip-sha>
#
#   GATE_LOCK   the lock file every gate on this host takes (default /var/tmp/fleet-gate.lock)
set -euo pipefail

exec flock -w 7200 "${GATE_LOCK:-/var/tmp/fleet-gate.lock}" bash "$(cd "$(dirname "$0")" && pwd)/gate.sh" "$@"
