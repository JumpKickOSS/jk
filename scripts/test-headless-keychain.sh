#!/usr/bin/env bash
# Headless keychain check. In a container with no D-Bus session bus and no secret-tool, busctl or
# gdbus (the CI and build-container case), `jk repo login` must keep the secret in its owner-only
# record without printing a word about a keychain, and `jk lock` must then authenticate with it
# against a loopback repository that serves its one artifact only to that token.
#
# Usage:
#   scripts/test-headless-keychain.sh [path/to/jk]   # default: target/dist/jk, from `jk build`
#
# Runs in podman, else docker; with neither answering it skips with a notice. The jk under test is
# installed into a throwaway home; that home's store hard-links the host's (JK_STORE_DIR, else
# $JK_HOME/store, else ~/.jk/store) when it can, and the host's JDKs (JK_JDKS_DIR, else ~/.jdks) are mounted
# read-only, so a developer machine needs no network beyond the container image.
# JK_HEADLESS_IMAGE overrides the image (default docker.io/library/ubuntu:24.04).
# JK_HEADLESS_REQUIRE_RUNTIME=1 (CI) turns the no-runtime skip into a failure.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK_BIN="${1:-$ROOT/target/dist/jk}"
IMAGE="${JK_HEADLESS_IMAGE:-docker.io/library/ubuntu:24.04}"
TOKEN="tok-headless"

fail() {
  echo "test-headless-keychain: FAIL: $1" >&2
  exit 1
}

RUNTIME=()
if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
  RUNTIME=(podman run --rm --network=host --userns=keep-id --security-opt label=disable)
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  RUNTIME=(docker run --rm --network=host --user "$(id -u):$(id -g)" --security-opt label=disable)
elif [[ "${JK_HEADLESS_REQUIRE_RUNTIME:-}" == 1 ]]; then
  fail "neither podman nor docker answers here, and JK_HEADLESS_REQUIRE_RUNTIME=1 asks for the container check"
else
  echo "test-headless-keychain: skipped — neither podman nor docker answers here (install one to run the container check)" >&2
  exit 0
fi

[[ -x "$JK_BIN" ]] || fail "no jk at $JK_BIN; run \`jk build\` first, or pass the jk to test"
command -v python3 >/dev/null 2>&1 || fail "python3 is required for the loopback repository"

# Under target/ when there is one: the store hard-links only within one file system, and a tmpfs
# /tmp is rarely the one ~/.jk lives on.
SCRATCH="${TMPDIR:-/tmp}"
if [[ -d "$ROOT/target" ]]; then SCRATCH="$ROOT/target"; fi
WORK="$(mktemp -d "$SCRATCH/jk-headless-keychain.XXXXXX")"
STUB_PID=""
cleanup() {
  if [[ -n "$STUB_PID" ]]; then kill "$STUB_PID" 2>/dev/null || true; fi
  # An engine the install or the container left running under the private home still writes there.
  if [[ -x "$WORK/home/bin/jk" ]]; then JK_HOME="$WORK/home" "$WORK/home/bin/jk" engine stop --now >/dev/null 2>&1 || true; fi
  rm -rf "$WORK" 2>/dev/null || { sleep 2; rm -rf "$WORK"; }
}
trap cleanup EXIT

free_port() {
  python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}
REPO_PORT="$(free_port)"
ENGINE_HTTP_PORT="$(free_port)"

# The private home: the jk under test, its rc files left alone.
HOME_DIR="$WORK/home"
mkdir -p "$HOME_DIR"
HOST_STORE="${JK_STORE_DIR:-${JK_HOME:-$HOME/.jk}/store}"
if [[ -d "$HOST_STORE" ]] && ! cp -al "$HOST_STORE" "$HOME_DIR/store" 2>/dev/null; then
  rm -rf "$HOME_DIR/store"
  echo "test-headless-keychain: the store at $HOST_STORE is on another file system; the lock fetches what it needs" >&2
fi
JK_HOME="$HOME_DIR" bash "$ROOT/install.sh" "$JK_BIN" >"$WORK/install.log" 2>&1 \
  || { cat "$WORK/install.log" >&2; fail "install.sh could not install $JK_BIN"; }

JDK_MOUNT=()
JDKS_ENV=()
HOST_JDKS="${JK_JDKS_DIR:-$HOME/.jdks}"
if [[ -d "$HOST_JDKS" ]]; then
  JDK_MOUNT=(-v "$HOST_JDKS:$HOST_JDKS:ro")
  JDKS_ENV=(-e "JK_JDKS_DIR=$HOST_JDKS")
fi

# The loopback repository: one artifact, served only to "Bearer $TOKEN"; every request is logged.
cat >"$WORK/stub.py" <<'PY'
import hashlib, http.server, io, sys, zipfile
port, logpath, token = int(sys.argv[1]), sys.argv[2], sys.argv[3]
log = open(logpath, "a", buffering=1)
base = "/maven/com/acme/probe/probe-lib/1.0.0/probe-lib-1.0.0"
pom = (b'<?xml version="1.0" encoding="UTF-8"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0">'
       b"<modelVersion>4.0.0</modelVersion><groupId>com.acme.probe</groupId>"
       b"<artifactId>probe-lib</artifactId><version>1.0.0</version></project>\n")
buf = io.BytesIO()
with zipfile.ZipFile(buf, "w") as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
files = {base + ".pom": pom, base + ".jar": buf.getvalue()}
for k, v in list(files.items()):
    files[k + ".sha1"] = hashlib.sha1(v).hexdigest().encode()
    files[k + ".sha256"] = hashlib.sha256(v).hexdigest().encode()
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        auth = self.headers.get("Authorization")
        log.write("%s %s auth=%s\n" % (self.command, self.path, auth))
        body = files.get(self.path) if auth == "Bearer " + token else None
        status = 200 if body is not None else (401 if auth != "Bearer " + token else 404)
        self.send_response(status)
        if status == 401:
            self.send_header("WWW-Authenticate", "Bearer")
        self.send_header("Content-Length", str(len(body or b"")))
        self.end_headers()
        if body is not None and self.command == "GET":
            self.wfile.write(body)
    do_HEAD = do_GET
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY
STUB_LOG="$WORK/stub.log"
: >"$STUB_LOG"
python3 -I "$WORK/stub.py" "$REPO_PORT" "$STUB_LOG" "$TOKEN" &
STUB_PID=$!

PROJECT="$WORK/project"
mkdir -p "$PROJECT/src/main/java/p" "$WORK/user-home"
cat >"$PROJECT/jk.toml" <<TOML
group = "com.example"
name = "probe"
version = "1.0.0"
java = 25

[repositories.probe]
url = "http://127.0.0.1:$REPO_PORT/maven/"
groups = ["com.acme.probe"]

[dependencies]
probe-lib = { group = "com.acme.probe", version = "1.0.0" }
TOML
printf 'package p;\n\npublic class A {}\n' >"$PROJECT/src/main/java/p/A.java"

# What runs in the container: prove the session is headless, then log in and lock.
cat >"$WORK/in-container.sh" <<'IN'
set -u
for tool in secret-tool busctl gdbus; do
  if command -v "$tool" >/dev/null 2>&1; then echo "headless: $tool is installed in the image"; exit 2; fi
done
if [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then echo "headless: a session bus is set"; exit 2; fi
cd "$PROJECT"
printf '%s\n' "$TOKEN" | "$JK_HOME/bin/jk" repo login probe -u >"$WORK/login.out" 2>"$WORK/login.err"
echo "login=$?"
timeout 300 "$JK_HOME/bin/jk" lock -x -u >"$WORK/lock.out" 2>&1
echo "lock=$?"
"$JK_HOME/bin/jk" engine stop --now >/dev/null 2>&1 || true
IN

"${RUNTIME[@]}" \
  -v "$WORK:$WORK" "${JDK_MOUNT[@]}" "${JDKS_ENV[@]}" \
  -e "HOME=$WORK/user-home" -e "JK_HOME=$HOME_DIR" -e "JK_HTTP_PORT=$ENGINE_HTTP_PORT" \
  -e "PROJECT=$PROJECT" -e "WORK=$WORK" -e "TOKEN=$TOKEN" \
  "$IMAGE" bash "$WORK/in-container.sh" >"$WORK/container.out" 2>&1 \
  || { cat "$WORK/container.out" >&2; fail "the container run failed"; }

show() {
  for f in container.out login.out login.err lock.out stub.log; do
    echo "--- $f" >&2
    cat "$WORK/$f" >&2 2>/dev/null || true
  done
}

grep -qx 'login=0' "$WORK/container.out" || { show; fail "jk repo login failed in the container"; }
if [[ -s "$WORK/login.err" ]]; then show; fail "jk repo login printed to stderr on a headless machine"; fi
if grep -qi 'keychain' "$WORK/login.out"; then show; fail "jk repo login spoke of a keychain on a headless machine"; fi
RECORD="$HOME_DIR/creds/repo/probe"
[[ -f "$RECORD" ]] || { show; fail "the login left no record at $RECORD"; }
[[ "$(sed -n 2p "$RECORD")" == "$TOKEN" ]] || { show; fail "the record does not hold the secret itself (head: $(head -1 "$RECORD"))"; }
MODE="$(stat -c %a "$RECORD" 2>/dev/null || stat -f %Lp "$RECORD")"
[[ "$MODE" == 600 ]] || { show; fail "the record is not owner-only ($MODE)"; }
grep -qx 'lock=0' "$WORK/container.out" || { show; fail "jk lock failed in the container"; }
grep -q "probe-lib-1.0.0.pom auth=Bearer $TOKEN" "$STUB_LOG" || { show; fail "the lock did not send the login's token"; }
if grep -q "probe-lib.*auth=None" "$STUB_LOG"; then show; fail "the lock asked the repository without the token"; fi

echo "test-headless-keychain: OK — silent file-backed login and an authenticated lock in $IMAGE (${RUNTIME[0]})"
