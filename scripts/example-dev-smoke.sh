#!/usr/bin/env bash
# Run one doc example under `jk dev --output json` until its sidecar is ready, fetch the front
# door and the proxied API through it, then Ctrl-C the session and require that nothing outlives
# it. Arguments: the example directory, the front-door URL, the API path to fetch through it.
#
#   scripts/example-dev-smoke.sh docs/user/examples/vite-sidecar http://localhost:5173 /api/hello
#
# `jk` is taken from JK_BIN (default: jk on PATH). The example's web/ must already have its
# node_modules (`npm ci`).
set -euo pipefail

example="${1:?example directory}"
front_door="${2:?front-door URL}"
api_path="${3:?API path fetched through the front door}"
jk="${JK_BIN:-jk}"
log="$(mktemp -t jk-dev-smoke.XXXXXX)"
trap 'rm -f "$log"' EXIT

cd "$example"
# Job control on: a background job in a non-interactive shell otherwise starts with SIGINT
# ignored, the JVM leaves that disposition alone, and the Ctrl-C below would reach nothing.
set -m
"$jk" dev --output json --no-ansi >"$log" 2>&1 &
jk_pid=$!
set +m

deadline=$((SECONDS + 180))
until grep -q '"type":"sidecar-ready"' "$log"; do
  if ! kill -0 "$jk_pid" 2>/dev/null; then
    echo "jk dev ended before the sidecar was ready:" >&2; cat "$log" >&2; exit 1
  fi
  if (( SECONDS > deadline )); then
    echo "no sidecar-ready within 180 s:" >&2; cat "$log" >&2; kill -INT "$jk_pid"; exit 1
  fi
  sleep 1
done
until grep -q '"type":"app-output".*listening on' "$log"; do sleep 1; done

web_pid="$(grep -m1 '"type":"sidecar-started"' "$log" | sed -E 's/.*"pid":([0-9]+).*/\1/')"
app_pid="$(grep -m1 '"type":"app-started"' "$log" | sed -E 's/.*"pid":([0-9]+).*/\1/')"

curl -fsS "$front_door/" | grep -q . || { echo "front door did not answer" >&2; cat "$log" >&2; exit 1; }
curl -fsS "$front_door$api_path" | grep -q 'hello from the JVM' \
  || { echo "the API did not answer through the sidecar's proxy" >&2; cat "$log" >&2; exit 1; }

kill -INT "$jk_pid"
set +e
wait "$jk_pid"; exit_code=$?
set -e
if (( exit_code != 130 )); then
  echo "jk dev exited $exit_code after Ctrl-C, expected 130:" >&2; cat "$log" >&2; exit 1
fi
for _ in $(seq 1 50); do
  kill -0 "$web_pid" 2>/dev/null || kill -0 "$app_pid" 2>/dev/null || break
  sleep 0.2
done
if kill -0 "$web_pid" 2>/dev/null || kill -0 "$app_pid" 2>/dev/null; then
  echo "a process outlived the session (web=$web_pid app=$app_pid)" >&2
  kill -KILL "$web_pid" "$app_pid" 2>/dev/null || true
  exit 1
fi
# The README's `npm ci` does not touch the lock, and neither does the dev server; a committed
# package-lock.json that reads differently now was rewritten by something else (an `npm install`,
# a Node upgrade) and would fail the next `npm ci` — say so here, where the sample was just run.
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  npm_drift="$(git diff --name-only -- '*package-lock.json')"
  if [[ -n "$npm_drift" ]]; then
    echo "the sample's npm lock changed: $npm_drift (commit the lock package.json needs, or restore it)" >&2
    exit 1
  fi
fi
echo "dev smoke ok: $example served $front_door and $api_path, Ctrl-C left nothing behind"
