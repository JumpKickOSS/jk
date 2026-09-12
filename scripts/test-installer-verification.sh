#!/usr/bin/env bash
# Network-free release authentication tests for sign-release.sh and install.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-installer-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/http/releases/1.0.0" "$WORK/bin"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$WORK/test-key.pem" 2>/dev/null
TEST_SPKI="$(openssl pkey -in "$WORK/test-key.pem" -pubout -outform DER 2>/dev/null | openssl base64 -A)"
openssl pkey -in "$WORK/test-key.pem" -pubout -out "$WORK/test-public.pem" 2>/dev/null
printf 'fixture\n' >"$WORK/secret-form-sums"
TEST_PKCS8="$(openssl pkcs8 -topk8 -nocrypt -in "$WORK/test-key.pem" -outform DER 2>/dev/null | openssl base64 -A)"
JK_RELEASE_RSA_SIGNING_KEY="$TEST_PKCS8" \
  "$ROOT/scripts/sign-release.sh" "$WORK/secret-form-sums" >/dev/null
openssl base64 -d -A -in "$WORK/secret-form-sums.sig" -out "$WORK/secret-form-sums.sig.bin"
openssl dgst -sha256 -verify "$WORK/test-public.pem" \
  -signature "$WORK/secret-form-sums.sig.bin" "$WORK/secret-form-sums" >/dev/null
awk -v key="$TEST_SPKI" '
  /^ *RELEASE_RSA_SPKI=/ { sub(/"[^"]*"/, "\"" key "\""); print; next }
  { print }
' "$ROOT/install.sh" >"$WORK/install.sh"
chmod +x "$WORK/install.sh"

cat >"$WORK/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
out=""
url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
if [[ -n "${FIXTURE_CURL_LOG:-}" ]]; then printf '%s\n' "$url" >>"$FIXTURE_CURL_LOG"; fi
path="${url#https://fixture}"
src="${FIXTURE_HTTP_ROOT}${path}"
[[ -f "$src" ]] || exit 22
if [[ -n "$out" ]]; then cp "$src" "$out"; else printf '%s' "$(cat "$src")"; fi
SH
cat >"$WORK/bin/git" <<'SH'
#!/usr/bin/env sh
exit 1
SH
chmod +x "$WORK/bin/curl" "$WORK/bin/git"

ARTIFACT="jk-linux-x86_64-1.0.0"
RELEASE="$WORK/http/releases/1.0.0"
cat >"$RELEASE/$ARTIFACT" <<'SH'
#!/usr/bin/env sh
exit 0
SH
chmod +x "$RELEASE/$ARTIFACT"

write_evidence() {
  local body="${1:-}"
  if [[ -n "$body" ]]; then
    printf '%s' "$body" >"$RELEASE/SHA256SUMS"
  else
    local hash
    hash="$(openssl dgst -sha256 "$RELEASE/$ARTIFACT" | awk '{print tolower($NF)}')"
    printf '%s  %s\n' "$hash" "$ARTIFACT" >"$RELEASE/SHA256SUMS"
  fi
  "$ROOT/scripts/sign-release.sh" "$RELEASE/SHA256SUMS" "$WORK/test-key.pem" >/dev/null
}

# Runs the installer against the fixture site; extra NAME=VALUE arguments override the defaults.
run_installer() {
  local home="$1"
  shift
  env PATH="$WORK/bin:$PATH" \
    FIXTURE_HTTP_ROOT="$WORK/http" \
    JK_HOME="$home" \
    JK_RELEASES_URL="https://fixture/releases" \
    CI=1 \
    "$@" \
    bash "$WORK/install.sh" >"$WORK/last-install.log" 2>&1
}

# The pinned flow (explicit version and archive URL) that every refusal case exercises.
run_pinned_installer() {
  run_installer "$1" JK_VERSION=1.0.0 JK_ARCHIVE_URL="https://fixture/releases/1.0.0/$ARTIFACT"
}

assert_refused_unchanged() {
  local case_name="$1"
  local home="$WORK/home-$case_name"
  mkdir -p "$home/bin"
  printf 'prior-install' >"$home/bin/jk"
  if run_pinned_installer "$home"; then
    cat "$WORK/last-install.log" >&2
    echo "$case_name unexpectedly installed" >&2
    exit 1
  fi
  [[ "$(cat "$home/bin/jk")" == "prior-install" ]] || {
    echo "$case_name changed the prior installation" >&2
    exit 1
  }
}

write_evidence
run_pinned_installer "$WORK/home-success"
cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-success/bin/jk"

write_evidence
printf 'tampered' >>"$RELEASE/$ARTIFACT"
assert_refused_unchanged "tampered-artifact"
git_artifact="$RELEASE/$ARTIFACT"
printf '%s\n' '#!/usr/bin/env sh' 'exit 0' >"$git_artifact"
chmod +x "$git_artifact"

write_evidence
printf '%064d  %s\n' 0 "$ARTIFACT" >"$RELEASE/SHA256SUMS"
assert_refused_unchanged "tampered-metadata"

hash="$(openssl dgst -sha256 "$RELEASE/$ARTIFACT" | awk '{print tolower($NF)}')"
write_evidence "$hash  wrong-artifact"$'\n'
assert_refused_unchanged "wrong-name"

write_evidence "$hash  $ARTIFACT"$'\n'"$hash  $ARTIFACT"$'\n'
assert_refused_unchanged "duplicate-name"

# Rollback: a valid manifest from another release, served under this version's directory. The
# artifact name carries the version, so the manifest names nothing this install asks for.
write_evidence "$hash  jk-linux-x86_64-0.9.0"$'\n'
assert_refused_unchanged "other-release-manifest"

# Strict LF: the signer and every verifier agree on the bytes; a CRLF manifest is refused everywhere.
write_evidence "$hash  $ARTIFACT"$'\r\n'
assert_refused_unchanged "crlf-manifest"

write_evidence
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$WORK/other-key.pem" 2>/dev/null
"$ROOT/scripts/sign-release.sh" "$RELEASE/SHA256SUMS" "$WORK/other-key.pem" >/dev/null
assert_refused_unchanged "untrusted-key"

write_evidence
rm -f "$RELEASE/SHA256SUMS.sig"
assert_refused_unchanged "missing-signature"

# A relative JK_HOME is refused before anything is downloaded or written (JkDirs would refuse it
# on every later step, so an installer that accepted it reported success over a dead install).
write_evidence
if ( cd "$WORK" && PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" JK_HOME="rel-home" JK_VERSION=1.0.0 \
    JK_ARCHIVE_URL="https://fixture/releases/1.0.0/$ARTIFACT" JK_RELEASES_URL="https://fixture/releases" CI=1 \
    bash "$WORK/install.sh" >"$WORK/last-install.log" 2>&1 ); then
  cat "$WORK/last-install.log" >&2
  echo "relative JK_HOME unexpectedly installed" >&2
  exit 1
fi
grep -q "JK_HOME must be an absolute path" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2
  echo "relative JK_HOME was refused for another reason" >&2
  exit 1
}
[[ ! -e "$WORK/rel-home" ]] || { echo "relative JK_HOME created a directory" >&2; exit 1; }

# Without JK_VERSION the installer reads latest/VERSION and takes the host's artifact from that
# release directory. A missing pointer (404, DNS) is reported with its URL instead of leaving the
# user with curl's bare exit status.
host_os="$(uname -s)"
case "$host_os" in Linux) host_os=linux ;; Darwin) host_os=macos ;; esac
host_arch="$(uname -m)"
case "$host_arch" in x86_64|amd64) host_arch=x86_64 ;; aarch64|arm64) host_arch=aarch64 ;; esac
LATEST_ARTIFACT="jk-$host_os-$host_arch-1.0.0.xz"
if command -v xz >/dev/null 2>&1; then
  xz -kc "$RELEASE/$ARTIFACT" >"$RELEASE/$LATEST_ARTIFACT"
  mkdir -p "$WORK/http/releases/latest"
  printf '1.0.0\n' >"$WORK/http/releases/latest/VERSION"
  latest_hash="$(openssl dgst -sha256 "$RELEASE/$LATEST_ARTIFACT" | awk '{print tolower($NF)}')"
  write_evidence "$latest_hash  $LATEST_ARTIFACT"$'\n'
  run_installer "$WORK/home-latest" || {
    cat "$WORK/last-install.log" >&2
    echo "latest/VERSION install failed" >&2
    exit 1
  }
  cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-latest/bin/jk" || {
    echo "latest/VERSION install did not unpack the host artifact" >&2
    exit 1
  }
  rm -f "$RELEASE/$LATEST_ARTIFACT"
fi

if run_installer "$WORK/home-no-latest" JK_RELEASES_URL="https://fixture/releases-missing"; then
  cat "$WORK/last-install.log" >&2
  echo "a missing latest/VERSION unexpectedly installed" >&2
  exit 1
fi
grep -q "could not resolve the latest jk version from https://fixture/releases-missing/latest/VERSION" \
  "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2
  echo "a missing latest/VERSION was not reported with its URL" >&2
  exit 1
}

# `curl | bash` executes whatever has arrived, so a download cut short must run nothing. Every
# strict prefix of the installer (the full file minus its final newline is the complete script)
# runs against its own seeded prior install: none may print, park or replace the prior client,
# create anything else under the home, or reach the network.
TRUNCATED="$WORK/truncated"
mkdir -p "$TRUNCATED"
check_truncated_prefix() {
  local n="$1" home="$TRUNCATED/home-$1" out content entries
  mkdir -p "$home/bin"
  printf 'prior-install' >"$home/bin/jk"
  head -c "$n" "$WORK/install.sh" >"$home/install.sh"
  out="$(PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" FIXTURE_CURL_LOG="$home/curl-calls" \
    JK_HOME="$home" JK_RELEASES_URL="https://fixture/releases" CI=1 \
    bash "$home/install.sh" 2>/dev/null </dev/null)" || true
  if [[ -n "$out" ]]; then
    echo "a copy truncated at byte $n printed: ${out:0:120}" >&2
    exit 1
  fi
  read -r -d '' content <"$home/bin/jk" || true
  shopt -s nullglob dotglob
  entries=("$home"/* "$home"/bin/*)
  if [[ "$content" != "prior-install" || "${entries[*]}" != "$home/bin $home/install.sh $home/bin/jk" ]]; then
    echo "a copy truncated at byte $n touched the prior installation" >&2
    exit 1
  fi
  rm -rf "$home"
}
export -f check_truncated_prefix
export WORK TRUNCATED
installer_size="$(wc -c <"$WORK/install.sh" | tr -d '[:space:]')"
seq 1 "$((installer_size - 2))" |
  xargs -P "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" -n 64 \
    bash -c 'for n; do check_truncated_prefix "$n"; done' _

echo "Shell installer verification fixtures passed."
