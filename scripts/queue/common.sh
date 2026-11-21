#!/usr/bin/env bash
# Shared by the merge-queue scripts; sourced, not run.
#
#   JK_QUEUE_REMOTE  the queue's git repository: `host:path` over ssh, or a local path
#                    (default bocabox:src/jk-queue.git). A waiting branch is a ref refs/queue/<branch>
#                    there; the queue writes where each branch stands under <path>/queue-state/.

QUEUE_REMOTE="${JK_QUEUE_REMOTE:-bocabox:src/jk-queue.git}"

# Run a shell command in the queue repository's directory, over ssh when the remote names a host.
queue_sh() {
  if [[ "$QUEUE_REMOTE" =~ ^([^/:]+):(.*)$ ]]; then
    # shellcheck disable=SC2029 # the path is quoted here on purpose; the command runs there
    ssh "${BASH_REMATCH[1]}" "cd $(printf %q "${BASH_REMATCH[2]}") && $1"
  else
    (cd "$QUEUE_REMOTE" && bash -c "$1")
  fi
}
