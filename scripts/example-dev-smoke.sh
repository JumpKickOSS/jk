#!/usr/bin/env bash
# Run an example under `jk dev --output json` until it is ready, fetch every front door it names,
# fetch each extra check through the first one, then Ctrl-C the session and require that nothing
# outlives it and that no committed npm lock moved.
#
#   scripts/example-dev-smoke.sh <dir> [<path>=<text>]...
#   scripts/example-dev-smoke.sh --examples <jk-examples checkout>
#
# One example: <dir> is run with `jk dev` (plus JK_DEV_ARGS); each <path>=<text> is fetched from the
# first ready URL and its body must contain <text>, e.g.
#
#   scripts/example-dev-smoke.sh docs/user/examples/vite-sidecar '/api/hello=hello from the JVM'
#
# --examples: every web/<scenario> of a jk-examples checkout that has a jk.toml, each built
# beforehand (its node_modules installed by `jk build`), with the checks listed below.
#
# `jk` is taken from JK_BIN (default: jk on PATH). A node build's dependencies are installed by
# `jk dev` itself; an example with a hand-written sidecar needs its own node_modules already.
set -euo pipefail

jk="${JK_BIN:-jk}"
wait_seconds=180

# The API checks of the jk-examples web/ scenarios: a scenario not listed is checked by its front
# doors alone.
web_checks() {
  case "$1" in
    vite-react | two-frontends) echo '/api/hello=Hello, world!' ;;
    *) ;;
  esac
}

fail() {
  echo "$1" >&2
  [[ -n "${log:-}" && -f "$log" ]] && cat "$log" >&2
  exit 1
}

# Fetch $1 until it answers with a body containing $2 (any body when $2 is empty), within the wait.
fetch_until() {
  local url="$1" text="$2" deadline=$((SECONDS + wait_seconds)) body
  while true; do
    if body="$(curl -fsS --max-time 10 "$url" 2>/dev/null)" && [[ -n "$body" ]] \
        && { [[ -z "$text" ]] || grep -qF -- "$text" <<<"$body"; }; then
      return 0
    fi
    (( SECONDS > deadline )) && return 1
    sleep 1
  done
}

smoke() {
  local example="$1"; shift
  log="$(mktemp -t jk-dev-smoke.XXXXXX)"
  (
    cd "$example"
    # Job control on: a background job in a non-interactive shell otherwise starts with SIGINT
    # ignored, the JVM leaves that disposition alone, and the Ctrl-C below would reach nothing.
    set -m
    # shellcheck disable=SC2086 # JK_DEV_ARGS is a word list on purpose (e.g. "-m app")
    "$jk" dev --output json --no-ansi ${JK_DEV_ARGS:-} >"$log" 2>&1 &
    jk_pid=$!
    set +m

    deadline=$((SECONDS + wait_seconds))
    until grep -q '"type":"dev-ready"' "$log"; do
      kill -0 "$jk_pid" 2>/dev/null || fail "jk dev in $example ended before it was ready:"
      if (( SECONDS > deadline )); then
        kill -INT "$jk_pid"
        fail "jk dev in $example was not ready within $wait_seconds s:"
      fi
      sleep 1
    done

    ready="$(grep -m1 '"type":"dev-ready"' "$log")"
    mapfile -t urls < <(sed -E 's/.*"urls":\[([^]]*)\].*/\1/' <<<"$ready" | tr ',' '\n' | tr -d '"' | sed '/^$/d')
    (( ${#urls[@]} > 0 )) || fail "dev-ready in $example named no URL:"
    for url in "${urls[@]}"; do
      fetch_until "$url/" "" || { kill -INT "$jk_pid"; fail "$url did not answer in $example:"; }
    done
    for check in "$@"; do
      path="${check%%=*}" text="${check#*=}"
      fetch_until "${urls[0]}$path" "$text" \
        || { kill -INT "$jk_pid"; fail "${urls[0]}$path did not answer with '$text' in $example:"; }
    done

    mapfile -t pids < <(grep -E '"type":"(app|sidecar)-started"' "$log" | sed -E 's/.*"pid":([0-9]+).*/\1/')
    kill -INT "$jk_pid"
    set +e
    wait "$jk_pid"; exit_code=$?
    set -e
    (( exit_code == 130 )) || fail "jk dev in $example exited $exit_code after Ctrl-C, expected 130:"
    for _ in $(seq 1 50); do
      alive=()
      for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null && alive+=("$pid"); done
      (( ${#alive[@]} == 0 )) && break
      sleep 0.2
    done
    if (( ${#alive[@]} > 0 )); then
      kill -KILL "${alive[@]}" 2>/dev/null || true
      fail "a process outlived the session in $example: ${alive[*]}"
    fi
    # Neither the dev servers nor a frozen install touch the lock; a committed package-lock.json
    # that reads differently now was rewritten by something else (an `npm install`, a Node upgrade)
    # and would fail the next frozen install.
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      npm_drift="$(git diff --name-only -- '*package-lock.json')"
      [[ -z "$npm_drift" ]] || fail "an npm lock changed in $example: $npm_drift (commit the lock package.json needs, or restore it)"
    fi
    echo "dev smoke ok: $example served ${urls[*]}${*:+ and $*}, Ctrl-C left nothing behind"
  )
  rm -f "$log"
}

if [[ "${1:-}" == "--examples" ]]; then
  checkout="${2:?jk-examples checkout}"
  found=0
  for manifest in "$checkout"/web/*/jk.toml; do
    [[ -f "$manifest" ]] || continue
    scenario="$(dirname "$manifest")"
    found=1
    mapfile -t checks < <(web_checks "$(basename "$scenario")")
    smoke "$scenario" "${checks[@]}"
  done
  (( found )) || fail "no web/<scenario>/jk.toml under $checkout"
else
  example="${1:?example directory, or --examples <jk-examples checkout>}"
  shift
  smoke "$example" "$@"
fi
