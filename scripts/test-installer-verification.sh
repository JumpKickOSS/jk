#!/usr/bin/env bash
# Network-free release authentication tests for sign-release.sh and install.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# `cd && pwd` drops the `//` macOS's trailing-slash TMPDIR leaves, as the installer does.
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/jk-installer-test.XXXXXX")" && pwd)"
# One line per case. A case is a `pass` call site; the run fails when any case fails, and also when
# a case never ran (an aborted run, a skipped block), since every call site must be reached.
CASES_EXPECTED="$(grep -c '^[[:space:]]*pass "' "${BASH_SOURCE[0]}")"
CASES_PASSED=0
PASSED_SITES=" "
pass() {
  case "$PASSED_SITES" in
    *" ${BASH_LINENO[0]} "*) ;;
    *) PASSED_SITES="$PASSED_SITES${BASH_LINENO[0]} "; CASES_PASSED=$((CASES_PASSED + 1)) ;;
  esac
  echo "ok   $1"
}
finish() {
  local status=$?
  rm -rf "$WORK"
  if [[ "$status" -ne 0 ]]; then
    echo "test-installer-verification: FAILED (exit $status) after $CASES_PASSED of $CASES_EXPECTED case(s)" >&2
    exit "$status"
  fi
  if [[ "$CASES_PASSED" -ne "$CASES_EXPECTED" ]]; then
    echo "test-installer-verification: only $CASES_PASSED of $CASES_EXPECTED case(s) ran" >&2
    exit 1
  fi
}
trap finish EXIT
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
# The fixture installer trusts the throwaway key and ships with the fixture release as its floor.
awk -v key="$TEST_SPKI" '
  /^ *RELEASE_RSA_SPKI=/ { sub(/"[^"]*"/, "\"" key "\""); print; next }
  /^ *RELEASE_FLOOR=/ { sub(/"[^"]*"/, "\"1.0.0\""); print; next }
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
# The fixture client: records every invocation in FIXTURE_JK_LOG and, asked to `activate --yes`,
# writes the installer block into the rc files of $HOME the way the real client does.
write_artifact() {
  cat >"$RELEASE/$ARTIFACT" <<'SH'
#!/usr/bin/env sh
if [ -n "${FIXTURE_JK_LOG:-}" ]; then printf '%s\n' "$*" >>"$FIXTURE_JK_LOG"; fi
case " $* " in
  *" activate --yes "*)
    for rc in "$HOME/.zshrc" "$HOME/.bashrc"; do
      printf '# >>> jk installer >>>\neval "$("%s/bin/jk" activate sh)"\n# <<< jk installer <<<\n' "${JK_HOME:-$HOME/.jk}" >>"$rc"
    done ;;
esac
exit 0
SH
  chmod +x "$RELEASE/$ARTIFACT"
}
write_artifact

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
# $HOME is a fixture user home with seeded rc files, never the developer's; INSTALLER_ARGS are
# the installer's own arguments (`--rc`).
INSTALLER_ARGS=()
seed_user_home() {
  rm -rf "$1" && mkdir -p "$1"
  printf 'export EDITOR=vi\n' >"$1/.zshrc"
  printf 'alias ll="ls -l"\n' >"$1/.bashrc"
}
seed_user_home "$WORK/user-home"
run_installer() {
  local home="$1"
  shift
  env PATH="$WORK/bin:$PATH" \
    FIXTURE_HTTP_ROOT="$WORK/http" \
    FIXTURE_JK_LOG="$WORK/jk-calls" \
    HOME="$WORK/user-home" \
    JK_HOME="$home" \
    JK_RELEASES_URL="https://fixture/releases" \
    CI=1 \
    "$@" \
    bash "$WORK/install.sh" ${INSTALLER_ARGS[@]+"${INSTALLER_ARGS[@]}"} >"$WORK/last-install.log" 2>&1
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
rm -f "$WORK/jk-calls"
run_pinned_installer "$WORK/home-success"
cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-success/bin/jk"
pass "pinned-install"

# ---- the shell rc block ------------------------------------------------------------------------
#
# JK_HOME above is a private home, so the run left the user's rc files exactly as seeded, never
# ran `jk activate --yes`, and printed the eval line for this shell instead.
assert_rc_untouched() {
  [[ "$(cat "$WORK/user-home/.zshrc")" == 'export EDITOR=vi' && "$(cat "$WORK/user-home/.bashrc")" == 'alias ll="ls -l"' ]] || {
    cat "$WORK/user-home/.zshrc" "$WORK/user-home/.bashrc" >&2
    echo "$1: a private JK_HOME rewrote the user's rc files" >&2
    exit 1
  }
}
assert_rc_untouched "private-home"
if grep -q -- "activate --yes" "$WORK/jk-calls"; then
  echo "a private JK_HOME ran 'jk activate --yes'" >&2; exit 1
fi
grep -qF -- "the shell rc files are left alone" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "a private JK_HOME did not say the rc files were left alone" >&2; exit 1; }
grep -qF -- "\"$WORK/home-success/bin/jk\" activate" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "a private JK_HOME did not print the activate line for its own bin" >&2; exit 1; }
pass "private-home-leaves-rc"

# --rc asks for the block on a private home.
rm -f "$WORK/jk-calls"
INSTALLER_ARGS=(--rc)
run_pinned_installer "$WORK/home-rc" || { cat "$WORK/last-install.log" >&2; echo "--rc install failed" >&2; exit 1; }
INSTALLER_ARGS=()
grep -q -- "^activate --yes --rc\$" "$WORK/jk-calls" || { cat "$WORK/jk-calls" >&2; echo "--rc did not run 'jk activate --yes --rc'" >&2; exit 1; }
grep -qF -- "$WORK/home-rc/bin/jk" "$WORK/user-home/.zshrc" || { echo "--rc did not write the rc block" >&2; exit 1; }
seed_user_home "$WORK/user-home"
pass "rc-flag-writes-block"

# The default home ($HOME/.jk, JK_HOME unset) writes the block unasked.
rm -f "$WORK/jk-calls"
run_pinned_installer "" || { cat "$WORK/last-install.log" >&2; echo "default-home install failed" >&2; exit 1; }
grep -q -- "^activate --yes\$" "$WORK/jk-calls" || { cat "$WORK/jk-calls" >&2; echo "the default home did not run 'jk activate --yes'" >&2; exit 1; }
cmp -s "$RELEASE/$ARTIFACT" "$WORK/user-home/.jk/bin/jk" || { echo "the default home did not land under \$HOME/.jk/bin" >&2; exit 1; }
grep -qF -- "$WORK/user-home/.jk/bin/jk" "$WORK/user-home/.bashrc" || { echo "the default home did not write the rc block" >&2; exit 1; }
seed_user_home "$WORK/user-home"
pass "default-home-writes-block"

# A misspelled flag is refused as one, not looked for as a local file, and installs nothing.
INSTALLER_ARGS=(--rcc)
assert_refused_unchanged "unknown-flag"
INSTALLER_ARGS=()
grep -qF -- "unknown option: --rcc" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "an unknown flag was not refused as one" >&2; exit 1; }
pass "unknown-flag"

# ---- a host with wget and no curl ----------------------------------------------------------------
#
# NOCURL_PATH is this PATH with curl taken out of every directory that has one: each such directory
# is replaced by a shadow of links to everything else in it.
NOCURL_PATH=""
shadow=0
IFS=: read -r -a path_dirs <<<"$PATH"
for dir in "${path_dirs[@]}"; do
  [[ -n "$dir" ]] || continue
  if [[ -e "$dir/curl" ]]; then
    shadow=$((shadow + 1))
    mkdir -p "$WORK/nocurl/$shadow"
    for f in "$dir"/*; do
      [[ "${f##*/}" == curl ]] || ln -s "$f" "$WORK/nocurl/$shadow/" 2>/dev/null || true
    done
    dir="$WORK/nocurl/$shadow"
  fi
  NOCURL_PATH="${NOCURL_PATH:+$NOCURL_PATH:}$dir"
done
# A GNU wget fixture, answering from FIXTURE_HTTP_ROOT whatever the scheme. A file beside the one
# asked for, named <file>.redirect, makes that URL a 302 to the Location it holds; with
# --max-redirect=0 wget stops there as GNU wget does (headers on stderr under -S, exit 8), and
# without it the fixture follows, to any scheme, as GNU wget does with or without --https-only.
mkdir -p "$WORK/wget-bin" "$WORK/http/mirror/1.0.0"
cp "$WORK/bin/git" "$WORK/wget-bin/git"
cat >"$WORK/wget-bin/wget" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
out="" url="" follow=1 show=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) echo "GNU Wget 1.25.0 built on linux-gnu."; exit 0 ;;
    -O) out="$2"; shift 2 ;;
    --max-redirect=0) follow=0; shift ;;
    -S) show=1; shift ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if [[ -n "${FIXTURE_WGET_LOG:-}" ]]; then printf '%s\n' "$url" >>"$FIXTURE_WGET_LOG"; fi
  path="${url#*://fixture}"
  src="${FIXTURE_HTTP_ROOT}${path%%\?*}"
  : >"$out"
  if [[ -f "$src.redirect" ]]; then
    location="$(cat "$src.redirect")"
    if [[ "$show" -eq 1 ]]; then printf '  HTTP/1.1 302 Found\r\n  Location: %s\r\n' "$location" >&2; fi
    [[ "$follow" -eq 1 ]] || exit 8
    case "$location" in
      *://*) url="$location" ;;
      //*) url="${url%%://*}:$location" ;;
      /*) url="${url%%://*}://fixture$location" ;;
      *) url="${url%/*}/$location" ;;
    esac
    continue
  fi
  if [[ ! -f "$src" ]]; then
    if [[ "$show" -eq 1 ]]; then printf '  HTTP/1.1 404 Not Found\r\n' >&2; fi
    exit 8
  fi
  if [[ "$show" -eq 1 ]]; then printf '  HTTP/1.1 200 OK\r\n' >&2; fi
  cp "$src" "$out"
  exit 0
done
exit 8
SH
chmod +x "$WORK/wget-bin/wget"
# A GNU Wget2 fixture, serving the same tree. Wget2 prints no headers for a redirect even under -S
# and nothing at all under -q; its own messages on stdout name the status and the redirect it
# stopped at, resolved against the URL that answered, and that stop exits 0.
mkdir -p "$WORK/wget2-bin"
cp "$WORK/bin/git" "$WORK/wget2-bin/git"
cat >"$WORK/wget2-bin/wget" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
out="" url="" follow=1 quiet=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) echo "GNU Wget2 2.3.0 - multithreaded metalink/file/website downloader"; exit 0 ;;
    -O) out="$2"; shift 2 ;;
    --max-redirect=0) follow=0; shift ;;
    -q) quiet=1; shift ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
say() { if [[ "$quiet" -eq 0 ]]; then printf '%s\n' "$1"; fi; }
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if [[ -n "${FIXTURE_WGET_LOG:-}" ]]; then printf '%s\n' "$url" >>"$FIXTURE_WGET_LOG"; fi
  say "[0] Downloading '$url' ..."
  path="${url#*://fixture}"
  src="${FIXTURE_HTTP_ROOT}${path%%\?*}"
  if [[ -f "$src.redirect" ]]; then
    location="$(cat "$src.redirect")"
    case "$location" in
      *://*) next="$location" ;;
      //*) next="${url%%://*}:$location" ;;
      /*) next="${url%%://*}://fixture$location" ;;
      *) next="${url%/*}/$location" ;;
    esac
    say "HTTP response 302 Found [$url]"
    if [[ "$follow" -eq 0 ]]; then
      say "URL '$next' not followed (max redirections exceeded)"
      exit 0
    fi
    url="$next"
    continue
  fi
  if [[ ! -f "$src" ]]; then
    say "HTTP ERROR response 404 Not Found [$url]"
    exit 8
  fi
  say "Saving '$out'"
  say "HTTP response 200 OK [$url]"
  cp "$src" "$out"
  exit 0
done
exit 8
SH
chmod +x "$WORK/wget2-bin/wget"
run_wget_installer() {
  local bin="$1" home="$2" location="$3"
  shift 3
  printf '%s' "$location" >"$WORK/http/mirror/1.0.0/$ARTIFACT.redirect"
  run_installer "$home" JK_VERSION=1.0.0 JK_ARCHIVE_URL="https://fixture/mirror/1.0.0/$ARTIFACT" \
    PATH="$bin:$NOCURL_PATH" FIXTURE_WGET_LOG="$WORK/wget-calls" "$@"
}

# A mirror's archive URL that redirects and stays on HTTPS is followed, whether its Location is
# absolute, network-path, absolute-path or relative; the archive is the signed one.
assert_wget_follows_https_redirect() {
  local bin="$1" location
  for location in "https://fixture/releases/1.0.0/$ARTIFACT" "//fixture/releases/1.0.0/$ARTIFACT" \
      "/releases/1.0.0/$ARTIFACT" "../../releases/1.0.0/$ARTIFACT"; do
    rm -rf "$WORK/home-wget" "$WORK/wget-calls"
    run_wget_installer "$bin" "$WORK/home-wget" "$location" || {
      cat "$WORK/last-install.log" >&2; echo "a wget install redirected to $location failed" >&2; exit 1; }
    cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-wget/bin/jk" || {
      echo "a wget install redirected to $location did not install the release" >&2; exit 1; }
    grep -qx "https://fixture/.*releases/1\.0\.0/$ARTIFACT" "$WORK/wget-calls" || {
      cat "$WORK/wget-calls" >&2; echo "a wget install did not follow $location to the archive" >&2; exit 1; }
  done
}

# A redirect off HTTPS is refused before it is fetched, and the prior install stays.
assert_wget_refuses_redirect_off_https() {
  local bin="$1"
  rm -rf "$WORK/home-wget-http" "$WORK/wget-calls"
  mkdir -p "$WORK/home-wget-http/bin"
  printf 'prior-install' >"$WORK/home-wget-http/bin/jk"
  if run_wget_installer "$bin" "$WORK/home-wget-http" "http://fixture/releases/1.0.0/$ARTIFACT"; then
    cat "$WORK/last-install.log" >&2; echo "a wget install followed a redirect to http" >&2; exit 1
  fi
  [[ "$(cat "$WORK/home-wget-http/bin/jk")" == "prior-install" ]] || {
    echo "a refused wget redirect changed the prior installation" >&2; exit 1; }
  grep -qF "refusing to fetch http://fixture/releases/1.0.0/$ARTIFACT: not HTTPS" "$WORK/last-install.log" || {
    cat "$WORK/last-install.log" >&2; echo "a refused wget redirect does not say why" >&2; exit 1; }
  if grep -q '^http://' "$WORK/wget-calls"; then
    cat "$WORK/wget-calls" >&2; echo "a wget install requested a non-HTTPS URL" >&2; exit 1
  fi
}

assert_wget_follows_https_redirect "$WORK/wget-bin"
pass "wget-follows-https-redirect"
assert_wget_refuses_redirect_off_https "$WORK/wget-bin"
pass "wget-refuses-redirect-off-https"
assert_wget_follows_https_redirect "$WORK/wget2-bin"
pass "wget2-follows-https-redirect"
assert_wget_refuses_redirect_off_https "$WORK/wget2-bin"
pass "wget2-refuses-redirect-off-https"

# A wget that is not GNU Wget (BusyBox's) cannot be stopped from following a redirect, so a remote
# install refuses it and says what would do.
mkdir -p "$WORK/busybox-bin"
cp "$WORK/bin/git" "$WORK/busybox-bin/git"
# shellcheck disable=SC2016 # the fixture's own script text
printf '#!/bin/sh\n[ "$1" = --version ] && { echo "wget: unrecognized option: version" >&2; exit 1; }\nexit 8\n' \
  >"$WORK/busybox-bin/wget"
chmod +x "$WORK/busybox-bin/wget"
mkdir -p "$WORK/home-busybox/bin"
printf 'prior-install' >"$WORK/home-busybox/bin/jk"
if run_installer "$WORK/home-busybox" JK_VERSION=1.0.0 JK_ARCHIVE_URL="https://fixture/releases/1.0.0/$ARTIFACT" \
    PATH="$WORK/busybox-bin:$NOCURL_PATH"; then
  cat "$WORK/last-install.log" >&2; echo "a remote install ran on a wget that cannot refuse a redirect" >&2; exit 1
fi
[[ "$(cat "$WORK/home-busybox/bin/jk")" == "prior-install" ]] || {
  echo "a refused BusyBox wget install changed the prior installation" >&2; exit 1; }
grep -qF "install curl or GNU Wget" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "a BusyBox wget refusal does not say what to install" >&2; exit 1; }
rm -rf "$WORK/nocurl" "$WORK/wget-bin" "$WORK/wget2-bin" "$WORK/busybox-bin" "$WORK/home-wget" "$WORK/home-wget-http" "$WORK/home-busybox" \
  "$WORK/http/mirror" "$WORK/wget-calls"
pass "busybox-wget-refused"

# Where bin/ refuses hardlinks, jkx is a shim that names the client by its path. A home whose path
# carries shell syntax still gets a shim that runs that client with the arguments it was given.
mkdir -p "$WORK/no-ln-bin"
printf '#!/bin/sh\nexit 1\n' >"$WORK/no-ln-bin/ln"
chmod +x "$WORK/no-ln-bin/ln"
odd_home="$WORK/home-odd \$HOME \"q\" \`x\` it's"
rm -f "$WORK/jk-calls"
run_installer "$odd_home" JK_VERSION=1.0.0 JK_ARCHIVE_URL="https://fixture/releases/1.0.0/$ARTIFACT" \
  PATH="$WORK/no-ln-bin:$WORK/bin:$PATH" || {
  cat "$WORK/last-install.log" >&2; echo "an install into a home with shell syntax in its path failed" >&2; exit 1; }
rm -f "$WORK/jk-calls"
FIXTURE_JK_LOG="$WORK/jk-calls" "$odd_home/bin/jkx" hello "two words" >/dev/null 2>&1 || {
  cat "$odd_home/bin/jkx" >&2; echo "the jkx shim does not run" >&2; exit 1; }
[[ "$(cat "$WORK/jk-calls")" == "tool run hello two words" ]] || {
  cat "$odd_home/bin/jkx" "$WORK/jk-calls" >&2; echo "the jkx shim did not run the installed jk's tool run" >&2; exit 1; }
rm -rf "$WORK/no-ln-bin" "$odd_home"
pass "jkx-shim-quotes-home"

# The templates cache is replaced only by a clone that succeeds: offline (the fixture git fails)
# the cache stays; a clone that works takes its place and leaves no stage behind.
tmpl_cache="$WORK/home-templates/store/templates/github.com_jumpkickoss_jk-templates"
mkdir -p "$tmpl_cache"
printf 'cached\n' >"$tmpl_cache/marker"
run_installer "$WORK/home-templates" JK_STORE_DIR= JK_VERSION=1.0.0 JK_ARCHIVE_URL="https://fixture/releases/1.0.0/$ARTIFACT" || {
  cat "$WORK/last-install.log" >&2; echo "an install with a failing git clone failed" >&2; exit 1; }
[[ "$(cat "$tmpl_cache/marker" 2>/dev/null)" == "cached" ]] || {
  echo "a failed templates clone removed the cached templates" >&2; exit 1; }
mkdir -p "$WORK/clone-bin"
# shellcheck disable=SC2016 # the fixture's own script text
printf '#!/usr/bin/env bash\nfor last; do :; done\nmkdir -p "$last" && printf "fresh\\n" >"$last/marker"\n' >"$WORK/clone-bin/git"
chmod +x "$WORK/clone-bin/git"
run_installer "$WORK/home-templates" JK_STORE_DIR= JK_VERSION=1.0.0 JK_ARCHIVE_URL="https://fixture/releases/1.0.0/$ARTIFACT" \
  PATH="$WORK/clone-bin:$WORK/bin:$PATH" || {
  cat "$WORK/last-install.log" >&2; echo "an install with a working git clone failed" >&2; exit 1; }
[[ "$(cat "$tmpl_cache/marker" 2>/dev/null)" == "fresh" ]] || {
  echo "a successful templates clone did not replace the cache" >&2; exit 1; }
# .?*.* and not .*.*: bash 3.2 (stock macOS) matches .. against .*.*
if compgen -G "$WORK/home-templates/store/templates/.?*.*" >/dev/null; then
  ls -la "$WORK/home-templates/store/templates" >&2; echo "the templates clone left its stage behind" >&2; exit 1
fi
rm -rf "$WORK/clone-bin"
pass "templates-cache-swapped-on-success"

write_evidence
printf 'tampered' >>"$RELEASE/$ARTIFACT"
assert_refused_unchanged "tampered-artifact"
pass "tampered-artifact"
write_artifact

write_evidence
printf '%064d  %s\n' 0 "$ARTIFACT" >"$RELEASE/SHA256SUMS"
assert_refused_unchanged "tampered-metadata"
pass "tampered-metadata"

hash="$(openssl dgst -sha256 "$RELEASE/$ARTIFACT" | awk '{print tolower($NF)}')"
write_evidence "$hash  wrong-artifact"$'\n'
assert_refused_unchanged "wrong-name"
pass "wrong-name"

write_evidence "$hash  $ARTIFACT"$'\n'"$hash  $ARTIFACT"$'\n'
assert_refused_unchanged "duplicate-name"
pass "duplicate-name"

# Rollback: a valid manifest from another release, served under this version's directory. The
# artifact name carries the version, so the manifest names nothing this install asks for.
write_evidence "$hash  jk-linux-x86_64-0.9.0"$'\n'
assert_refused_unchanged "other-release-manifest"
pass "other-release-manifest"

# Strict LF: the signer and every verifier agree on the bytes; a CRLF manifest is refused everywhere.
write_evidence "$hash  $ARTIFACT"$'\r\n'
assert_refused_unchanged "crlf-manifest"
pass "crlf-manifest"

write_evidence
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$WORK/other-key.pem" 2>/dev/null
"$ROOT/scripts/sign-release.sh" "$RELEASE/SHA256SUMS" "$WORK/other-key.pem" >/dev/null
assert_refused_unchanged "untrusted-key"
pass "untrusted-key"

write_evidence
rm -f "$RELEASE/SHA256SUMS.sig"
assert_refused_unchanged "missing-signature"
pass "missing-signature"

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
pass "relative-home-refused"

# Without JK_VERSION the installer reads the signed latest/LATEST pointer and takes the host's
# artifact from the release directory it names. A missing pointer (404, DNS) is reported with its
# URL instead of leaving the user with curl's bare exit status. A fixture uname makes the host a
# hosted one whatever runs the suite.
fixture_uname() {
  # shellcheck disable=SC2016 # ${1:-} belongs to the generated uname script, not to this one
  printf '#!/usr/bin/env sh\ncase "${1:-}" in -s) echo %s ;; -m) echo %s ;; *) echo %s ;; esac\n' "$1" "$2" "$1" >"$WORK/bin/uname"
  chmod +x "$WORK/bin/uname"
}
fixture_uname Linux x86_64
LATEST_ARTIFACT="jk-linux-x86_64-1.0.0.gz"
LATEST_DIR="$WORK/http/releases/latest"
write_pointer() {
  rm -rf "$LATEST_DIR"
  "$ROOT/scripts/sign-latest-pointer.sh" "$1" "$LATEST_DIR" "${2:-$WORK/test-key.pem}" >/dev/null
}
if command -v gzip >/dev/null 2>&1; then
  gzip -cn "$RELEASE/$ARTIFACT" >"$RELEASE/$LATEST_ARTIFACT"
  write_pointer 1.0.0
  [[ "$(cat "$LATEST_DIR/VERSION")" == "1.0.0" ]] || { echo "the pointer script did not write the bare VERSION" >&2; exit 1; }
  if ! { grep -qE '^version 1\.0\.0$' "$LATEST_DIR/LATEST" \
      && grep -qE '^issued [0-9]+$' "$LATEST_DIR/LATEST" \
      && grep -qE '^signature [A-Za-z0-9+/]+={0,2}$' "$LATEST_DIR/LATEST"; }; then
    cat "$LATEST_DIR/LATEST" >&2
    echo "the pointer script did not write a three-line LATEST" >&2
    exit 1
  fi
  [[ ! -e "$LATEST_DIR/LATEST.sig" ]] || { echo "the pointer script wrote a detached LATEST.sig" >&2; exit 1; }
  [[ "$(awk 'END { print NR }' "$LATEST_DIR/LATEST")" == "3" ]] || {
    echo "LATEST is not exactly three lines" >&2
    exit 1
  }
  latest_hash="$(openssl dgst -sha256 "$RELEASE/$LATEST_ARTIFACT" | awk '{print tolower($NF)}')"
  write_evidence "$latest_hash  $LATEST_ARTIFACT"$'\n'
  rm -f "$WORK/jk-calls"
  run_installer "$WORK/home-latest" || {
    cat "$WORK/last-install.log" >&2
    echo "latest/LATEST install failed" >&2
    exit 1
  }
  cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-latest/bin/jk" || {
    echo "latest/LATEST install did not unpack the host artifact" >&2
    exit 1
  }
pass "latest-pointer-install"
  # A download install places the client and fetches no engine: the engine it already has, when
  # that is the client's version, is restarted by the new client, and that start is what pairs the
  # client with it in the install record. Stop, then start, after the client is in place.
  if ! awk '$0 == "engine stop --now" { stopped = NR } $0 == "engine start" && stopped { started = 1 } END { exit !started }' \
      "$WORK/jk-calls"; then
    cat "$WORK/jk-calls" >&2
    echo "a download install did not restart the engine through the new client" >&2
    exit 1
  fi
pass "download-install-restarts-engine"
  rm -f "$RELEASE/$LATEST_ARTIFACT"
fi

# Every pointer refusal happens before the version directory is even named, so these cases need
# no artifact. The prior installation must stay untouched each time.
assert_pointer_refused() {
  local case_name="$1" expected="$2"
  local home="$WORK/home-$case_name"
  mkdir -p "$home/bin"
  printf 'prior-install' >"$home/bin/jk"
  if run_installer "$home"; then
    cat "$WORK/last-install.log" >&2
    echo "$case_name unexpectedly installed" >&2
    exit 1
  fi
  grep -qF -- "$expected" "$WORK/last-install.log" || {
    cat "$WORK/last-install.log" >&2
    echo "$case_name was refused, but not for '$expected'" >&2
    exit 1
  }
  [[ "$(cat "$home/bin/jk")" == "prior-install" ]] || {
    echo "$case_name changed the prior installation" >&2
    exit 1
  }
}

write_pointer 1.0.0
awk 'NR<=2' "$LATEST_DIR/LATEST" >"$LATEST_DIR/LATEST.two"
mv "$LATEST_DIR/LATEST.two" "$LATEST_DIR/LATEST"
rm -f "$LATEST_DIR/LATEST.sig"
assert_pointer_refused "unsigned-pointer" "latest-release pointer at https://fixture/releases/latest/LATEST is malformed"
pass "unsigned-pointer"

write_pointer 1.0.0
sed -i.bak 's/^version 1\.0\.0$/version 1.0.1/' "$LATEST_DIR/LATEST"
assert_pointer_refused "tampered-pointer" "latest-release pointer signature verification failed"
pass "tampered-pointer"

# Rollback: an older release's pointer, validly signed, re-served as the latest one.
write_pointer 0.9.0
assert_pointer_refused "rolled-back-pointer" "names 0.9.0, older than the 1.0.0 this installer ships with"
pass "rolled-back-pointer"

# A pre-release of the floor's own version comes before it, so it is a rollback too.
write_pointer 1.0.0-rc1
assert_pointer_refused "pre-release-of-floor-pointer" "names 1.0.0-rc1, older than the 1.0.0 this installer ships with"
pass "pre-release-of-floor-pointer"

write_pointer 1.0.0 "$WORK/other-key.pem"
assert_pointer_refused "untrusted-key-pointer" "latest-release pointer signature verification failed"
pass "untrusted-key-pointer"

# Signed, but not the two-line form the signer writes: CRLF, a bare version, a third line.
write_pointer 1.0.0
printf 'version 1.0.0\r\nissued 1\r\n' >"$LATEST_DIR/LATEST"
"$ROOT/scripts/sign-release.sh" "$LATEST_DIR/LATEST" "$WORK/test-key.pem" >/dev/null
assert_pointer_refused "crlf-pointer" "latest-release pointer at https://fixture/releases/latest/LATEST is malformed"
pass "crlf-pointer"
printf '1.0.0\n' >"$LATEST_DIR/LATEST"
"$ROOT/scripts/sign-release.sh" "$LATEST_DIR/LATEST" "$WORK/test-key.pem" >/dev/null
assert_pointer_refused "bare-version-pointer" "latest-release pointer at https://fixture/releases/latest/LATEST is malformed"
pass "bare-version-pointer"
printf 'version 1.0.0\nissued 1\nversion 0.9.0\n' >"$LATEST_DIR/LATEST"
"$ROOT/scripts/sign-release.sh" "$LATEST_DIR/LATEST" "$WORK/test-key.pem" >/dev/null
assert_pointer_refused "three-line-pointer" "latest-release pointer at https://fixture/releases/latest/LATEST is malformed"
pass "three-line-pointer"

# The bare VERSION is a convenience nothing verifies: on its own it resolves nothing.
rm -rf "$LATEST_DIR" && mkdir -p "$LATEST_DIR" && printf '1.0.0\n' >"$LATEST_DIR/VERSION"
assert_pointer_refused "version-only-pointer" "could not resolve the latest jk version from https://fixture/releases/latest/LATEST"
pass "version-only-pointer"

if run_installer "$WORK/home-no-latest" JK_RELEASES_URL="https://fixture/releases-missing"; then
  cat "$WORK/last-install.log" >&2
  echo "a missing latest/LATEST unexpectedly installed" >&2
  exit 1
fi
grep -q "could not resolve the latest jk version from https://fixture/releases-missing/latest/LATEST" \
  "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2
  echo "a missing latest/LATEST was not reported with its URL" >&2
  exit 1
}
pass "missing-pointer-reported"

# ---- the JVM client ---------------------------------------------------------------------------
#
# A host with no native client installs jk-<version>.jar on the user's JDK: the jar and the engine
# jar are both verified against the signed sums, the jar lands under lib/jk, and the launcher is
# written by the jar itself (`java -jar … self write-launcher`). The fixture `java` stands in for a
# JDK 25: it answers -version, java.home, --version, and writes bin/jk when asked to.
cat >"$WORK/bin/java" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${FIXTURE_JAVA_LOG:-}" ]]; then printf '%s\n' "$*" >>"$FIXTURE_JAVA_LOG"; fi
case " $* " in
  *" -XshowSettings:properties "*) printf '    java.home = %s\n' "$FIXTURE_JAVA_HOME" >&2; printf 'openjdk version "%s" 2025-09-16\n' "${FIXTURE_JAVA_VERSION:-25.0.1}" >&2 ;;
  *" -version "*) printf 'openjdk version "%s" 2025-09-16\n' "${FIXTURE_JAVA_VERSION:-25.0.1}" >&2 ;;
  *" --version "*) printf 'jk 1.0.0\n' ;;
  *" self write-launcher "*)
    # The launcher the jar writes stands in for the installed client: it records what the
    # installer asks of it, like the native fixture.
    mkdir -p "$JK_HOME/bin"
    printf '#!/bin/sh\nif [ -n "${FIXTURE_JK_LOG:-}" ]; then printf "%%s\\n" "$*" >>"$FIXTURE_JK_LOG"; fi\nexit 0\n' >"$JK_HOME/bin/jk"
    chmod +x "$JK_HOME/bin/jk" ;;
esac
exit 0
SH
chmod +x "$WORK/bin/java"
mkdir -p "$WORK/jdk/bin"
touch "$WORK/jdk/bin/javac" && chmod +x "$WORK/jdk/bin/javac"
# A host no release lists a native client for, so the installer must choose the JVM client.
fixture_uname SunOS sun4v

JVM_JAR="jk-1.0.0.jar"
ENGINE_JAR="jk-engine-1.0.0.jar"
printf 'PK fixture client jar\n' >"$RELEASE/$JVM_JAR"
printf 'PK fixture engine jar\n' >"$RELEASE/$ENGINE_JAR"
jvm_hash="$(openssl dgst -sha256 "$RELEASE/$JVM_JAR" | awk '{print tolower($NF)}')"
engine_hash="$(openssl dgst -sha256 "$RELEASE/$ENGINE_JAR" | awk '{print tolower($NF)}')"
write_pointer 1.0.0
write_evidence "$jvm_hash  $JVM_JAR"$'\n'"$engine_hash  $ENGINE_JAR"$'\n'

# JAVA_HOME / JK_JAVA_HOME are blanked so the fixture `java` on the PATH is the JDK the installer finds.
run_jvm_installer() {
  run_installer "$1" JAVA_HOME= JK_JAVA_HOME= FIXTURE_JAVA_HOME="$WORK/jdk" FIXTURE_JAVA_LOG="$WORK/java-calls" "${@:2}"
}

# An unhosted host, nothing asked for: the JVM client, the jar under lib/jk, the engine jar
# materialized through the client, and the launcher written by the jar.
rm -f "$WORK/java-calls"
run_jvm_installer "$WORK/home-jvm" || { cat "$WORK/last-install.log" >&2; echo "JVM install on an unhosted host failed" >&2; exit 1; }
cmp -s "$RELEASE/$JVM_JAR" "$WORK/home-jvm/lib/jk/$JVM_JAR" || { echo "the client jar did not land under lib/jk" >&2; exit 1; }
[[ -x "$WORK/home-jvm/bin/jk" ]] || { echo "no launcher was written" >&2; exit 1; }
grep -q -- "-jar $WORK/home-jvm/lib/jk/$JVM_JAR self write-launcher" "$WORK/java-calls" || {
  cat "$WORK/java-calls" >&2; echo "the launcher was not written through the installed jar" >&2; exit 1; }
grep -q "installing the JVM client" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the fallback was not announced" >&2; exit 1; }
pass "jvm-client-unhosted"

# JK_CLIENT=native on the same host refuses instead of falling back.
if run_jvm_installer "$WORK/home-jvm-refused" JK_CLIENT=native; then
  cat "$WORK/last-install.log" >&2; echo "JK_CLIENT=native unexpectedly installed on an unhosted host" >&2; exit 1
fi
grep -q "jk 1.0.0 publishes no native sunos-sun4v client" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the native refusal did not name the host" >&2; exit 1; }
[[ ! -e "$WORK/home-jvm-refused/bin/jk" ]] || { echo "the refusal installed something" >&2; exit 1; }
pass "jvm-native-refused"

# An Intel Mac: a known OS and architecture with no native client in a release.
fixture_uname Darwin x86_64
home="$WORK/home-jvm-Darwin-x86_64"
run_jvm_installer "$home" || { cat "$WORK/last-install.log" >&2; echo "JVM install on Darwin x86_64 failed" >&2; exit 1; }
cmp -s "$RELEASE/$JVM_JAR" "$home/lib/jk/$JVM_JAR" || { echo "Darwin x86_64 did not install the JVM client" >&2; exit 1; }
pass "jvm-intel-mac"

# The release, not the installer, decides: a host whose native client the sums list gets it,
# whatever the host.
if command -v gzip >/dev/null 2>&1; then
  fixture_uname FreeBSD amd64
  BSD_ARTIFACT="jk-freebsd-x86_64-1.0.0.gz"
  gzip -cn "$RELEASE/$ARTIFACT" >"$RELEASE/$BSD_ARTIFACT"
  bsd_hash="$(openssl dgst -sha256 "$RELEASE/$BSD_ARTIFACT" | awk '{print tolower($NF)}')"
  write_evidence "$jvm_hash  $JVM_JAR"$'\n'"$engine_hash  $ENGINE_JAR"$'\n'"$bsd_hash  $BSD_ARTIFACT"$'\n'
  run_jvm_installer "$WORK/home-native-freebsd" || { cat "$WORK/last-install.log" >&2; echo "a listed FreeBSD client failed to install" >&2; exit 1; }
  cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-native-freebsd/bin/jk" || { echo "a listed FreeBSD client was not installed natively" >&2; exit 1; }
  [[ ! -e "$WORK/home-native-freebsd/lib/jk/$JVM_JAR" ]] || { echo "a listed native client still installed the JVM client" >&2; exit 1; }
  rm -f "$RELEASE/$BSD_ARTIFACT"
  write_evidence "$jvm_hash  $JVM_JAR"$'\n'"$engine_hash  $ENGINE_JAR"$'\n'
  pass "listed-native-client"
fi
fixture_uname SunOS sun4v

# A JDK too old, and a JRE: refused before the client is downloaded or anything is written.
if run_jvm_installer "$WORK/home-jvm-old" FIXTURE_JAVA_VERSION=21.0.4; then
  cat "$WORK/last-install.log" >&2; echo "a Java 21 unexpectedly installed the JVM client" >&2; exit 1
fi
grep -q "is Java 21; the JVM client needs a JDK 25 or newer" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the old JDK was not refused by version" >&2; exit 1; }
[[ ! -e "$WORK/home-jvm-old" ]] || { echo "an old JDK still wrote the home" >&2; exit 1; }
pass "old-jdk-refused"
mkdir -p "$WORK/jre/bin"
if run_jvm_installer "$WORK/home-jvm-jre" FIXTURE_JAVA_HOME="$WORK/jre"; then
  cat "$WORK/last-install.log" >&2; echo "a JRE unexpectedly installed the JVM client" >&2; exit 1
fi
grep -q "is a JRE (no bin/javac)" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the JRE was not refused" >&2; exit 1; }
pass "jre-refused"

# The engine jar is evidence-checked like the client jar: a tampered engine jar is refused whole.
printf 'tampered' >>"$RELEASE/$ENGINE_JAR"
if run_jvm_installer "$WORK/home-jvm-tampered-engine"; then
  cat "$WORK/last-install.log" >&2; echo "a tampered engine jar unexpectedly installed" >&2; exit 1
fi
grep -q "checksum mismatch for $ENGINE_JAR" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the tampered engine jar was not named" >&2; exit 1; }
[[ ! -e "$WORK/home-jvm-tampered-engine/bin/jk" ]] || { echo "a tampered engine jar still installed the launcher" >&2; exit 1; }
pass "tampered-engine-refused"
printf 'PK fixture engine jar\n' >"$RELEASE/$ENGINE_JAR"

# A local jar beside its engine jar installs the JVM client from the dist layout, no network; the
# Maven spy jar beside them lands under the product lib, where `jk mvn` looks for it, and the
# dist's repos/jk-local shelf (the tree's module jars) is shelved through the client.
SPY_JAR="jk-maven-spy-1.0.0.jar"
mkdir -p "$WORK/dist/lib" "$WORK/dist/repos/jk-local/cc/jumpkick/jk-test-runner/1.0.0"
cp "$RELEASE/$JVM_JAR" "$WORK/dist/lib/$JVM_JAR"
cp "$RELEASE/$ENGINE_JAR" "$WORK/dist/lib/$ENGINE_JAR"
printf 'PK fixture spy jar\n' >"$WORK/dist/lib/$SPY_JAR"
printf 'PK fixture worker jar\n' >"$WORK/dist/repos/jk-local/cc/jumpkick/jk-test-runner/1.0.0/jk-test-runner-1.0.0.jar"
printf '<project/>\n' >"$WORK/dist/repos/jk-local/cc/jumpkick/jk-test-runner/1.0.0/jk-test-runner-1.0.0.pom"
rm -f "$WORK/java-calls" "$WORK/jk-calls"
if ! ( env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" JAVA_HOME= JK_JAVA_HOME= FIXTURE_JAVA_HOME="$WORK/jdk" FIXTURE_JAVA_LOG="$WORK/java-calls" \
    FIXTURE_JK_LOG="$WORK/jk-calls" JK_HOME="$WORK/home-jvm-local" JK_RELEASES_URL="https://fixture/releases-missing" CI=1 \
    bash "$WORK/install.sh" "$WORK/dist/lib/$JVM_JAR" >"$WORK/last-install.log" 2>&1 ); then
  cat "$WORK/last-install.log" >&2; echo "a local JVM client install failed" >&2; exit 1
fi
cmp -s "$RELEASE/$JVM_JAR" "$WORK/home-jvm-local/lib/jk/$JVM_JAR" || { echo "the local jar did not land under lib/jk" >&2; exit 1; }
cmp -s "$WORK/dist/lib/$SPY_JAR" "$WORK/home-jvm-local/lib/$SPY_JAR" || { echo "the Maven spy jar did not land under lib/" >&2; exit 1; }
grep -q -- "--version" "$WORK/java-calls" || { echo "a local jar was not asked its version" >&2; exit 1; }
grep -q -- "^self materialize $WORK/home-jvm-local/bin/jk $WORK/dist/lib/$ENGINE_JAR\$" "$WORK/jk-calls" || {
  cat "$WORK/jk-calls" >&2; echo "a local JVM client install did not materialize the engine jar beside the jar" >&2; exit 1; }
grep -q -- "^self shelve $WORK/dist/repos\$" "$WORK/jk-calls" || {
  cat "$WORK/jk-calls" >&2; echo "a local JVM client install did not shelve the dist's repos tree" >&2; exit 1; }
pass "jvm-local-dist"
rm -f "$WORK/bin/uname" "$WORK/bin/java" "$RELEASE/$JVM_JAR" "$RELEASE/$ENGINE_JAR"
write_evidence

# ---- the native client from a local dist --------------------------------------------------------
#
# `bash install.sh <dist>/jk` is how a checkout's own build is installed: the binary is copied, the
# engine jar beside it under lib/ is materialized through the client, and the module jars under
# repos/jk-local are shelved through the client — so the engine this install spawns launches the
# workers built with it. Without the shelf the install says so; without the engine jar it refuses.
cp "$RELEASE/$ARTIFACT" "$WORK/dist/jk"
rm -f "$WORK/jk-calls"
if ! ( env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" FIXTURE_JK_LOG="$WORK/jk-calls" HOME="$WORK/user-home" \
    JK_HOME="$WORK/home-native-local" JK_RELEASES_URL="https://fixture/releases-missing" CI=1 \
    bash "$WORK/install.sh" "$WORK/dist/jk" >"$WORK/last-install.log" 2>&1 ); then
  cat "$WORK/last-install.log" >&2; echo "a local native dist install failed" >&2; exit 1
fi
cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-native-local/bin/jk" || { echo "the local native client did not land under bin/" >&2; exit 1; }
grep -q -- "^self materialize $WORK/home-native-local/bin/jk $WORK/dist/lib/$ENGINE_JAR\$" "$WORK/jk-calls" || {
  cat "$WORK/jk-calls" >&2; echo "the engine jar beside the local binary was not materialized" >&2; exit 1; }
grep -q -- "^self shelve $WORK/dist/repos\$" "$WORK/jk-calls" || {
  cat "$WORK/jk-calls" >&2; echo "the dist's repos tree was not shelved" >&2; exit 1; }
assert_rc_untouched "native-local"
pass "native-local-dist"

# ---- a failed or interrupted reinstall keeps the previous jk -----------------------------------
#
# The new client is unpacked beside the live one and renamed over it only once it answers
# --version, so a truncated archive, a binary that does not run, or a run killed mid-unpack (by
# signal, or outright) leaves the previous jk in place and working, with no parked copy and,
# except after SIGKILL, no stage behind. The next install sweeps a stage a killed run left.
seed_prior_client() {
  rm -rf "$1" && mkdir -p "$1/bin"
  printf '#!/bin/sh\necho prior-jk\n' >"$1/bin/jk"
  chmod +x "$1/bin/jk"
}
run_local_install() {
  local home="$1" file="$2"
  shift 2
  env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" FIXTURE_JK_LOG="$WORK/jk-calls" HOME="$WORK/user-home" \
    JK_HOME="$home" JK_RELEASES_URL="https://fixture/releases-missing" CI=1 "$@" \
    bash "$WORK/install.sh" "$file" >"$WORK/last-install.log" 2>&1
}
assert_prior_client_runs() {
  local case_name="$1" home="$2" stage_allowed="${3:-}"
  [[ "$("$home/bin/jk" 2>&1)" == "prior-jk" ]] || {
    cat "$WORK/last-install.log" >&2; ls -la "$home/bin" >&2
    echo "$case_name: the previous jk no longer runs" >&2; exit 1; }
  [[ ! -e "$home/bin/jk.old" ]] || { echo "$case_name: the previous jk was parked" >&2; exit 1; }
  if [[ -z "$stage_allowed" ]] && compgen -G "$home/bin/.jk-install.*" >/dev/null; then
    ls -la "$home/bin" >&2; echo "$case_name: the staged client was left behind" >&2; exit 1
  fi
}

# A truncated archive: gunzip fails part-way through.
if command -v gzip >/dev/null 2>&1; then
  home="$WORK/home-truncated-archive"
  seed_prior_client "$home"
  head -c 200000 /dev/urandom | gzip -c >"$WORK/whole.gz"
  head -c 5000 "$WORK/whole.gz" >"$WORK/dist/jk-linux-x86_64-0.0.1.gz"
  rm -f "$WORK/whole.gz"
  if run_local_install "$home" "$WORK/dist/jk-linux-x86_64-0.0.1.gz"; then
    cat "$WORK/last-install.log" >&2; echo "a truncated archive installed" >&2; exit 1
  fi
  assert_prior_client_runs "truncated-archive" "$home"
pass "truncated-archive"
  rm -f "$WORK/dist/jk-linux-x86_64-0.0.1.gz"
fi

# A binary that unpacks but does not run.
home="$WORK/home-broken-client"
seed_prior_client "$home"
printf '#!/bin/sh\nexit 3\n' >"$WORK/dist/jk-broken"
chmod +x "$WORK/dist/jk-broken"
if run_local_install "$home" "$WORK/dist/jk-broken"; then
  cat "$WORK/last-install.log" >&2; echo "a client that does not run was installed" >&2; exit 1
fi
grep -qF -- "the new jk does not run" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "the broken client was refused for another reason" >&2; exit 1; }
assert_prior_client_runs "broken-client" "$home"
pass "broken-client"
rm -f "$WORK/dist/jk-broken"

# Killed mid-unpack: a fixture gunzip writes part of the binary, then signals the installer.
if command -v gzip >/dev/null 2>&1; then
  gzip -cn "$RELEASE/$ARTIFACT" >"$WORK/dist/jk-linux-x86_64-1.0.0.gz"
  mkdir -p "$WORK/kill-bin"
  cat >"$WORK/kill-bin/gunzip" <<'SH'
#!/usr/bin/env bash
printf '#!/bin/sh\necho half-written\n'
kill "-$FIXTURE_KILL_SIGNAL" "$PPID"
sleep 1
exit 1
SH
  chmod +x "$WORK/kill-bin/gunzip"
  for sig in INT TERM HUP KILL; do
    home="$WORK/home-killed-$sig"
    seed_prior_client "$home"
    if run_local_install "$home" "$WORK/dist/jk-linux-x86_64-1.0.0.gz" \
        PATH="$WORK/kill-bin:$WORK/bin:$PATH" FIXTURE_KILL_SIGNAL="$sig"; then
      cat "$WORK/last-install.log" >&2; echo "an install killed by SIG$sig reported success" >&2; exit 1
    fi
    if [[ "$sig" == KILL ]]; then
      assert_prior_client_runs "killed-$sig" "$home" stage-allowed
    else
      assert_prior_client_runs "killed-$sig" "$home"
    fi
    pass "killed-$sig"
  done
  # The stage SIGKILL left is swept by the next install, which succeeds.
  home="$WORK/home-killed-KILL"
  compgen -G "$home/bin/.jk-install.*" >/dev/null || { echo "SIGKILL left no stage to sweep (fixture did not run?)" >&2; exit 1; }
  run_local_install "$home" "$WORK/dist/jk-linux-x86_64-1.0.0.gz" || {
    cat "$WORK/last-install.log" >&2; echo "the install after a killed one failed" >&2; exit 1; }
  cmp -s "$RELEASE/$ARTIFACT" "$home/bin/jk" || { echo "the install after a killed one did not install" >&2; exit 1; }
  if compgen -G "$home/bin/.jk-install.*" >/dev/null; then
    ls -la "$home/bin" >&2; echo "the killed run's stage was not swept" >&2; exit 1
  fi
  pass "killed-stage-swept"
  rm -rf "$WORK/kill-bin" "$WORK/dist/jk-linux-x86_64-1.0.0.gz"
fi

# A release-versioned dist into a home whose shelf already holds that version's -SNAPSHOT line is
# refused before anything is written: the shelved release would hide the snapshot, and every later
# `jk install` in that home would refuse. The refusal names `jk install` as the way forward.
home="$WORK/home-snapshot-line"
mkdir -p "$home/bin" "$home/store/repos/jk-local/cc/jumpkick/jk-test-runner/1.0.0-SNAPSHOT"
printf 'prior-install' >"$home/bin/jk"
rm -f "$WORK/jk-calls"
if ( env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" FIXTURE_JK_LOG="$WORK/jk-calls" HOME="$WORK/user-home" \
    JK_HOME="$home" JK_RELEASES_URL="https://fixture/releases-missing" CI=1 \
    bash "$WORK/install.sh" "$WORK/dist/jk" >"$WORK/last-install.log" 2>&1 ); then
  cat "$WORK/last-install.log" >&2; echo "a release dist over the home's snapshot line was installed" >&2; exit 1
fi
grep -qF -- "holds release 1.0.0 of 1 module(s) (first: cc/jumpkick/jk-test-runner)" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "the snapshot-line refusal did not name the module and version" >&2; exit 1; }
grep -qF -- "'jk install'" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "the snapshot-line refusal did not name jk install" >&2; exit 1; }
[[ "$(cat "$home/bin/jk")" == "prior-install" ]] || { echo "the snapshot-line refusal replaced the client" >&2; exit 1; }
[[ ! -e "$WORK/jk-calls" ]] || { cat "$WORK/jk-calls" >&2; echo "the snapshot-line refusal ran the client" >&2; exit 1; }
pass "snapshot-line-refused"
# Another version's snapshot line is no conflict: the dist installs over it.
mv "$home/store/repos/jk-local/cc/jumpkick/jk-test-runner/1.0.0-SNAPSHOT" \
  "$home/store/repos/jk-local/cc/jumpkick/jk-test-runner/1.0.1-SNAPSHOT"
if ! ( env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" FIXTURE_JK_LOG="$WORK/jk-calls" HOME="$WORK/user-home" \
    JK_HOME="$home" JK_RELEASES_URL="https://fixture/releases-missing" CI=1 \
    bash "$WORK/install.sh" "$WORK/dist/jk" >"$WORK/last-install.log" 2>&1 ); then
  cat "$WORK/last-install.log" >&2; echo "a release dist beside another version's snapshot line was refused" >&2; exit 1
fi
cmp -s "$RELEASE/$ARTIFACT" "$home/bin/jk" || { echo "the dist did not install beside another snapshot line" >&2; exit 1; }
pass "other-snapshot-line"

# The same dist without repos/: installed, but the missing shelf is named.
rm -rf "$WORK/dist/repos"
rm -f "$WORK/jk-calls"
if ! ( env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" FIXTURE_JK_LOG="$WORK/jk-calls" HOME="$WORK/user-home" \
    JK_HOME="$WORK/home-native-noshelf" JK_RELEASES_URL="https://fixture/releases-missing" CI=1 \
    bash "$WORK/install.sh" "$WORK/dist/jk" >"$WORK/last-install.log" 2>&1 ); then
  cat "$WORK/last-install.log" >&2; echo "a local dist without repos/ failed to install" >&2; exit 1
fi
if grep -q -- "self shelve" "$WORK/jk-calls"; then echo "a dist without repos/ ran 'jk self shelve'" >&2; exit 1; fi
grep -qF -- "no repos/jk-local beside dist/lib: no workers shelved" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "a dist without repos/ did not name the missing shelf" >&2; exit 1; }
pass "dist-without-shelf"

# A binary with no engine jar beside it is refused whole: installing it would pair the tree's
# client with the released engine.
rm -f "$WORK/dist/lib/$ENGINE_JAR"
if ( env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" HOME="$WORK/user-home" \
    JK_HOME="$WORK/home-native-noengine" JK_RELEASES_URL="https://fixture/releases-missing" CI=1 \
    bash "$WORK/install.sh" "$WORK/dist/jk" >"$WORK/last-install.log" 2>&1 ); then
  cat "$WORK/last-install.log" >&2; echo "a local binary without an engine jar was installed" >&2; exit 1
fi
grep -q -- "no jk-engine-\*.jar in $WORK/dist/lib" "$WORK/last-install.log" || {
  cat "$WORK/last-install.log" >&2; echo "the missing engine jar was not named" >&2; exit 1; }
pass "dist-without-engine"
rm -rf "$WORK/dist"

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
  if [[ "$content" != "prior-install" || "${entries[*]-}" != "$home/bin $home/install.sh $home/bin/jk" ]]; then
    echo "a copy truncated at byte $n touched the prior installation" >&2
    exit 1
  fi
  rm -rf "$home"
}
export -f check_truncated_prefix
export WORK TRUNCATED
installer_size="$(wc -c <"$WORK/install.sh" | tr -d '[:space:]')"
# shellcheck disable=SC2016 # the loop body is bash -c's script; its "$n" expands in the child
seq 1 "$((installer_size - 2))" |
  xargs -P "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" -n 64 \
    bash -c 'for n; do check_truncated_prefix "$n"; done' _
pass "truncated-installer-runs-nothing"

# A release run signs only with the key the installers trust: the test key is not it.
if JK_RELEASE_REQUIRE_TRUSTED_KEY=1 "$ROOT/scripts/sign-release.sh" "$WORK/secret-form-sums" "$WORK/test-key.pem" \
  >/dev/null 2>"$WORK/untrusted.err"; then
  echo "a release run signed with a key the installers do not trust" >&2
  exit 1
fi
grep -q "not the release key the installers trust" "$WORK/untrusted.err" \
  || { echo "the untrusted-key refusal does not say why" >&2; exit 1; }
pass "untrusted-release-key-refused"

echo "Shell installer verification fixtures passed: $CASES_PASSED of $CASES_EXPECTED case(s)."
