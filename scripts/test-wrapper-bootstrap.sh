#!/usr/bin/env bash
# Network-free bootstrap of the POSIX wrapper `jk wrapper` writes. The committed template, trusting
# a throwaway release key, runs against a fixture release served over file:// — the signed
# latest/LATEST pointer, the signed SHA256SUMS and the host's artifact — and must install and exec
# the version the pointer names, run the installed jk without fetching from then on, and refuse
# every release whose evidence does not verify while leaving a prior installation untouched.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE="$ROOT/clients/cli/src/main/resources/cc/jumpkick/command/toolchain/wrapper/jk.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-wrapper-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "test-wrapper-bootstrap: $1" >&2
  exit 1
}

for tool in openssl curl xz; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool is required"
done
# The wrapper execs any jk it finds on PATH, so it runs with a PATH that holds none: the system
# directories every tool it needs lives in, and nothing an operator installed.
FIXTURE_PATH="/usr/bin:/bin"
if PATH="$FIXTURE_PATH" command -v jk >/dev/null 2>&1; then
  fail "a jk is installed under $FIXTURE_PATH; the fixture needs a PATH without one"
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$WORK/test-key.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$WORK/other-key.pem" 2>/dev/null
TEST_SPKI="$(openssl pkey -in "$WORK/test-key.pem" -pubout -outform DER 2>/dev/null | openssl base64 -A)"

# The checkout: the wrapper as `jk wrapper` writes it, trusting the throwaway key as the release key.
REPO="$WORK/repo"
mkdir -p "$REPO"
awk -v key="$TEST_SPKI" '
  /^RELEASE_RSA_SPKI=/ { sub(/"[^"]*"/, "\"" key "\"") }
  { print }
' "$TEMPLATE" >"$REPO/jk"
chmod +x "$REPO/jk"
grep -qF "$TEST_SPKI" "$REPO/jk" || fail "the wrapper template has no RELEASE_RSA_SPKI line to replace"

# The release host: releases/latest (the signed pointer) and releases/<version> (the signed
# manifest and the host's artifact, an .xz of a script that echoes its arguments).
VERSION="1.0.0"
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$OS" in darwin) OS=macos ;; esac
ARCH="$(uname -m)"
case "$ARCH" in amd64) ARCH=x86_64 ;; arm64) ARCH=aarch64 ;; esac
ARTIFACT="jk-$OS-$ARCH-$VERSION.xz"
RELEASES="$WORK/releases"
RELEASE="$RELEASES/$VERSION"
mkdir -p "$RELEASE"
printf '#!/bin/sh\necho "fixture jk %s: $*"\n' "$VERSION" >"$WORK/fixture-jk"
xz -c "$WORK/fixture-jk" >"$RELEASE/$ARTIFACT"

write_pointer() {
  rm -rf "$RELEASES/latest"
  "$ROOT/scripts/sign-latest-pointer.sh" "$1" "$RELEASES/latest" "${2:-$WORK/test-key.pem}" >/dev/null
}

# SHA256SUMS holding $1 (default: the one exact entry for the artifact), signed by $2 (default: the
# release key).
write_evidence() {
  if [[ -n "${1:-}" ]]; then
    printf '%s' "$1" >"$RELEASE/SHA256SUMS"
  else
    printf '%s  %s\n' "$(artifact_hash)" "$ARTIFACT" >"$RELEASE/SHA256SUMS"
  fi
  "$ROOT/scripts/sign-release.sh" "$RELEASE/SHA256SUMS" "${2:-$WORK/test-key.pem}" >/dev/null
}

artifact_hash() {
  openssl dgst -sha256 "$RELEASE/$ARTIFACT" | awk '{print tolower($NF)}'
}

# The checkout's lock, carrying the jk-min floor $1 (none when empty).
write_lock() {
  if [[ -n "$1" ]]; then
    printf 'jk-min = "%s"\n' "$1" >"$REPO/jk-lock.toml"
  else
    rm -f "$REPO/jk-lock.toml"
  fi
}

# Runs the checkout's wrapper with JK_HOME=$1 and the remaining arguments, in an environment
# holding only what the wrapper documents, with $RELEASES_URL (the fixture) as the release host.
RELEASES_URL="file://$RELEASES"
run_wrapper() {
  local home="$1"
  shift
  mkdir -p "$WORK/tmp"
  env -i PATH="$FIXTURE_PATH" HOME="$WORK/home" TMPDIR="$WORK/tmp" \
    JK_HOME="$home" JK_RELEASES_URL="$RELEASES_URL" \
    "$REPO/jk" "$@" >"$WORK/last-stdout" 2>"$WORK/last-stderr"
}

# A prior 0.9.0 installation under $1: a jk that answers "prior jk" and a VERSION file.
seed_prior() {
  rm -rf "$1"
  mkdir -p "$1/bin"
  printf '#!/bin/sh\necho "prior jk: $*"\n' >"$1/bin/jk"
  chmod +x "$1/bin/jk"
  printf '0.9.0\n' >"$1/bin/VERSION"
}

assert_stdout() {
  [[ "$(cat "$WORK/last-stdout")" == "$1" ]] || {
    cat "$WORK/last-stderr" >&2
    fail "$2 (stdout: $(cat "$WORK/last-stdout"))"
  }
}

assert_installed() {
  local home="$1"
  cmp -s "$WORK/fixture-jk" "$home/bin/jk" || fail "$2: the installed jk is not the release artifact"
  [[ -x "$home/bin/jk" ]] || fail "$2: the installed jk is not executable"
  [[ "$(cat "$home/bin/VERSION")" == "$VERSION" ]] || fail "$2: VERSION does not record $VERSION"
  [[ "$(find "$home/bin" -mindepth 1 | wc -l | tr -d '[:space:]')" == "2" ]] ||
    fail "$2: the bin directory holds more than jk and VERSION: $(find "$home/bin" -mindepth 1 | tr '\n' ' ')"
  [[ -z "$(ls -A "$WORK/tmp")" ]] || fail "$2: the wrapper left its scratch directory behind under TMPDIR"
}

# With the floor at 1.0.0 the seeded 0.9.0 is not enough, so the wrapper must reach the release
# host, refuse it for $2, and leave the prior installation exactly as it was.
assert_refused_unchanged() {
  local case_name="$1" expected="$2" home="$WORK/home-$1"
  seed_prior "$home"
  write_lock "1.0.0"
  if run_wrapper "$home" --version; then
    cat "$WORK/last-stderr" >&2
    fail "$case_name unexpectedly bootstrapped"
  fi
  if [[ -n "$expected" ]]; then
    grep -qF -- "$expected" "$WORK/last-stderr" || {
      cat "$WORK/last-stderr" >&2
      fail "$case_name was refused, but not for '$expected'"
    }
  fi
  [[ "$(cat "$WORK/last-stdout")" == "" ]] || fail "$case_name printed to stdout: $(cat "$WORK/last-stdout")"
  [[ "$("$home/bin/jk" probe)" == "prior jk: probe" ]] || fail "$case_name changed the prior jk"
  [[ "$(cat "$home/bin/VERSION")" == "0.9.0" ]] || fail "$case_name changed the prior VERSION"
  [[ ! -e "$home/bin/jk.old" && ! -e "$home/bin/jk.part" ]] || fail "$case_name left a parked or partial jk"
}

# ---- the bootstrap: pointer, manifest, artifact, install, exec ------------------------------
write_pointer "$VERSION"
write_evidence
write_lock ""
HOME_FRESH="$WORK/home-fresh"
run_wrapper "$HOME_FRESH" hello world || {
  cat "$WORK/last-stderr" >&2
  fail "the bootstrap failed"
}
assert_stdout "fixture jk $VERSION: hello world" "the bootstrap did not exec the installed jk with its arguments"
grep -qF "fetching jk $VERSION" "$WORK/last-stderr" || fail "the bootstrap did not name the version it fetched"
assert_installed "$HOME_FRESH" "the bootstrap"

# From now on the installed jk runs and nothing is fetched, with or without a floor it satisfies.
run_wrapper "$HOME_FRESH" again || fail "the installed jk did not run"
assert_stdout "fixture jk $VERSION: again" "the second run did not exec the installed jk"
grep -q "fetching jk" "$WORK/last-stderr" && fail "the second run fetched again"
write_lock "$VERSION"
run_wrapper "$HOME_FRESH" floored || fail "the installed jk did not run under a floor it satisfies"
assert_stdout "fixture jk $VERSION: floored" "the floor it satisfies did not exec the installed jk"
grep -q "fetching jk" "$WORK/last-stderr" && fail "a satisfied floor fetched again"

# The release host is unreachable, but the installed jk satisfies the floor: it runs regardless.
RELEASES_URL="file://$WORK/no-such-releases"
run_wrapper "$HOME_FRESH" offline || fail "the installed jk did not run while the release host was unreachable"
assert_stdout "fixture jk $VERSION: offline" "the unreachable host did not exec the installed jk"
RELEASES_URL="file://$RELEASES"

# A prior installation below the floor is replaced by the release, and nothing is parked.
HOME_PRIOR="$WORK/home-prior"
seed_prior "$HOME_PRIOR"
write_lock "$VERSION"
run_wrapper "$HOME_PRIOR" upgraded || {
  cat "$WORK/last-stderr" >&2
  fail "the bootstrap over a prior installation failed"
}
assert_stdout "fixture jk $VERSION: upgraded" "the bootstrap over a prior installation did not exec the release"
assert_installed "$HOME_PRIOR" "the bootstrap over a prior installation"

# ---- refusals: the prior installation stays, nothing new runs ---------------------------------
# A floor above the latest release is a hard error naming both versions.
write_lock "2.0.0"
seed_prior "$WORK/home-stale"
if run_wrapper "$WORK/home-stale" --version; then
  fail "a floor above the latest release unexpectedly bootstrapped"
fi
grep -qF "requires jk >= 2.0.0 but the latest release is $VERSION" "$WORK/last-stderr" || {
  cat "$WORK/last-stderr" >&2
  fail "a floor above the latest release was not refused with both versions"
}
[[ "$("$WORK/home-stale/bin/jk" probe)" == "prior jk: probe" ]] || fail "a stale floor changed the prior jk"

# The pointer is signed by a foreign key, or names a version directory the host lacks.
write_pointer "$VERSION" "$WORK/other-key.pem"
assert_refused_unchanged "foreign-pointer" "latest-release pointer signature verification failed"
grep -q "fetching jk" "$WORK/last-stderr" && fail "a foreign-signed pointer named a download"
write_pointer "1.0.1"
assert_refused_unchanged "missing-release" "could not read $RELEASES_URL/1.0.1/"
write_pointer "$VERSION"

# The artifact changes after the manifest is signed.
write_evidence
printf 'tampered' >>"$RELEASE/$ARTIFACT"
assert_refused_unchanged "tampered-artifact" "sha256 mismatch for $ARTIFACT"
xz -c "$WORK/fixture-jk" >"$RELEASE/$ARTIFACT"

# The manifest changes after it is signed, or is signed by a foreign key.
write_evidence
printf '%064d  %s\n' 0 "$ARTIFACT" >"$RELEASE/SHA256SUMS"
assert_refused_unchanged "tampered-manifest" "release signature verification failed"
write_evidence "" "$WORK/other-key.pem"
assert_refused_unchanged "foreign-manifest" "release signature verification failed"

# A validly signed manifest that does not name this host's artifact exactly once.
write_evidence "$(artifact_hash)  jk-$OS-$ARCH-0.9.0.xz"$'\n'
assert_refused_unchanged "other-release-manifest" "lacks one exact $ARTIFACT entry"
write_evidence "$(artifact_hash)  $ARTIFACT"$'\n'"$(artifact_hash)  $ARTIFACT"$'\n'
assert_refused_unchanged "duplicate-entry" "lacks one exact $ARTIFACT entry"
write_evidence "$(artifact_hash)  $ARTIFACT"$'\r\n'
assert_refused_unchanged "crlf-manifest" "lacks one exact $ARTIFACT entry"

# A signature file that is not one base64 line of an RSA-3072 signature, or is missing.
write_evidence
printf 'not a signature\n' >"$RELEASE/SHA256SUMS.sig"
assert_refused_unchanged "malformed-signature" "release signature is malformed"
write_evidence
head -c 511 "$RELEASE/SHA256SUMS.sig" >"$RELEASE/short.sig" && printf '=\n' >>"$RELEASE/short.sig"
mv "$RELEASE/short.sig" "$RELEASE/SHA256SUMS.sig"
assert_refused_unchanged "short-signature" "release signature has the wrong RSA-3072 length"
write_evidence
rm -f "$RELEASE/SHA256SUMS.sig"
assert_refused_unchanged "missing-signature" "could not read $RELEASES_URL/$VERSION/SHA256SUMS.sig"

# A valid release again: the refusals left nothing behind that stops the next bootstrap.
write_evidence
write_lock ""
run_wrapper "$WORK/home-final" recovered || {
  cat "$WORK/last-stderr" >&2
  fail "the bootstrap after the refusals failed"
}
assert_stdout "fixture jk $VERSION: recovered" "the bootstrap after the refusals did not exec the release"
assert_installed "$WORK/home-final" "the bootstrap after the refusals"

echo "test-wrapper-bootstrap: ok"
