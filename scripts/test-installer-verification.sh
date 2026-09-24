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
    bash "$WORK/install.sh" "${INSTALLER_ARGS[@]}" >"$WORK/last-install.log" 2>&1
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

# --rc asks for the block on a private home.
rm -f "$WORK/jk-calls"
INSTALLER_ARGS=(--rc)
run_pinned_installer "$WORK/home-rc" || { cat "$WORK/last-install.log" >&2; echo "--rc install failed" >&2; exit 1; }
INSTALLER_ARGS=()
grep -q -- "^activate --yes --rc\$" "$WORK/jk-calls" || { cat "$WORK/jk-calls" >&2; echo "--rc did not run 'jk activate --yes --rc'" >&2; exit 1; }
grep -qF -- "$WORK/home-rc/bin/jk" "$WORK/user-home/.zshrc" || { echo "--rc did not write the rc block" >&2; exit 1; }
seed_user_home "$WORK/user-home"

# The default home ($HOME/.jk, JK_HOME unset) writes the block unasked.
rm -f "$WORK/jk-calls"
run_pinned_installer "" || { cat "$WORK/last-install.log" >&2; echo "default-home install failed" >&2; exit 1; }
grep -q -- "^activate --yes\$" "$WORK/jk-calls" || { cat "$WORK/jk-calls" >&2; echo "the default home did not run 'jk activate --yes'" >&2; exit 1; }
cmp -s "$RELEASE/$ARTIFACT" "$WORK/user-home/.jk/bin/jk" || { echo "the default home did not land under \$HOME/.jk/bin" >&2; exit 1; }
grep -qF -- "$WORK/user-home/.jk/bin/jk" "$WORK/user-home/.bashrc" || { echo "the default home did not write the rc block" >&2; exit 1; }
seed_user_home "$WORK/user-home"

write_evidence
printf 'tampered' >>"$RELEASE/$ARTIFACT"
assert_refused_unchanged "tampered-artifact"
write_artifact

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

# Without JK_VERSION the installer reads the signed latest/LATEST pointer and takes the host's
# artifact from the release directory it names. A missing pointer (404, DNS) is reported with its
# URL instead of leaving the user with curl's bare exit status.
host_os="$(uname -s)"
case "$host_os" in Linux) host_os=linux ;; Darwin) host_os=macos ;; esac
host_arch="$(uname -m)"
case "$host_arch" in x86_64|amd64) host_arch=x86_64 ;; aarch64|arm64) host_arch=aarch64 ;; esac
LATEST_ARTIFACT="jk-$host_os-$host_arch-1.0.0.xz"
LATEST_DIR="$WORK/http/releases/latest"
write_pointer() {
  rm -rf "$LATEST_DIR"
  "$ROOT/scripts/sign-latest-pointer.sh" "$1" "$LATEST_DIR" "${2:-$WORK/test-key.pem}" >/dev/null
}
if command -v xz >/dev/null 2>&1; then
  xz -kc "$RELEASE/$ARTIFACT" >"$RELEASE/$LATEST_ARTIFACT"
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
  run_installer "$WORK/home-latest" || {
    cat "$WORK/last-install.log" >&2
    echo "latest/LATEST install failed" >&2
    exit 1
  }
  cmp -s "$RELEASE/$ARTIFACT" "$WORK/home-latest/bin/jk" || {
    echo "latest/LATEST install did not unpack the host artifact" >&2
    exit 1
  }
  rm -f "$RELEASE/$LATEST_ARTIFACT"
fi

# Every pointer refusal happens before the version directory is even named, so these cases need
# no artifact and no xz. The prior installation must stay untouched each time.
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

write_pointer 1.0.0
sed -i.bak 's/^version 1\.0\.0$/version 1.0.1/' "$LATEST_DIR/LATEST"
assert_pointer_refused "tampered-pointer" "latest-release pointer signature verification failed"

# Rollback: an older release's pointer, validly signed, re-served as the latest one.
write_pointer 0.9.0
assert_pointer_refused "rolled-back-pointer" "names 0.9.0, older than the 1.0.0 this installer ships with"

write_pointer 1.0.0 "$WORK/other-key.pem"
assert_pointer_refused "untrusted-key-pointer" "latest-release pointer signature verification failed"

# Signed, but not the two-line form the signer writes: CRLF, a bare version, a third line.
write_pointer 1.0.0
printf 'version 1.0.0\r\nissued 1\r\n' >"$LATEST_DIR/LATEST"
"$ROOT/scripts/sign-release.sh" "$LATEST_DIR/LATEST" "$WORK/test-key.pem" >/dev/null
assert_pointer_refused "crlf-pointer" "latest-release pointer at https://fixture/releases/latest/LATEST is malformed"
printf '1.0.0\n' >"$LATEST_DIR/LATEST"
"$ROOT/scripts/sign-release.sh" "$LATEST_DIR/LATEST" "$WORK/test-key.pem" >/dev/null
assert_pointer_refused "bare-version-pointer" "latest-release pointer at https://fixture/releases/latest/LATEST is malformed"
printf 'version 1.0.0\nissued 1\nversion 0.9.0\n' >"$LATEST_DIR/LATEST"
"$ROOT/scripts/sign-release.sh" "$LATEST_DIR/LATEST" "$WORK/test-key.pem" >/dev/null
assert_pointer_refused "three-line-pointer" "latest-release pointer at https://fixture/releases/latest/LATEST is malformed"

# The bare VERSION is a convenience nothing verifies: on its own it resolves nothing.
rm -rf "$LATEST_DIR" && mkdir -p "$LATEST_DIR" && printf '1.0.0\n' >"$LATEST_DIR/VERSION"
assert_pointer_refused "version-only-pointer" "could not resolve the latest jk version from https://fixture/releases/latest/LATEST"

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
# A fixture uname: a host neither installer table knows, so the installer must choose the JVM client.
cat >"$WORK/bin/uname" <<'SH'
#!/usr/bin/env sh
case "${1:-}" in -s) echo SunOS ;; -m) echo sun4v ;; *) echo SunOS ;; esac
SH
chmod +x "$WORK/bin/uname"

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

# JK_CLIENT=native on the same host refuses instead of falling back.
if run_jvm_installer "$WORK/home-jvm-refused" JK_CLIENT=native; then
  cat "$WORK/last-install.log" >&2; echo "JK_CLIENT=native unexpectedly installed on an unhosted host" >&2; exit 1
fi
grep -q "no native jk client for SunOS/sun4v" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the native refusal did not name the host" >&2; exit 1; }
[[ ! -e "$WORK/home-jvm-refused/bin/jk" ]] || { echo "the refusal installed something" >&2; exit 1; }

# A JDK too old, and a JRE: refused before anything is downloaded or written.
if run_jvm_installer "$WORK/home-jvm-old" FIXTURE_JAVA_VERSION=21.0.4; then
  cat "$WORK/last-install.log" >&2; echo "a Java 21 unexpectedly installed the JVM client" >&2; exit 1
fi
grep -q "is Java 21; the JVM client needs a JDK 25 or newer" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the old JDK was not refused by version" >&2; exit 1; }
[[ ! -e "$WORK/home-jvm-old" ]] || { echo "an old JDK still wrote the home" >&2; exit 1; }
mkdir -p "$WORK/jre/bin"
if run_jvm_installer "$WORK/home-jvm-jre" FIXTURE_JAVA_HOME="$WORK/jre"; then
  cat "$WORK/last-install.log" >&2; echo "a JRE unexpectedly installed the JVM client" >&2; exit 1
fi
grep -q "is a JRE (no bin/javac)" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the JRE was not refused" >&2; exit 1; }

# The engine jar is evidence-checked like the client jar: a tampered engine jar is refused whole.
printf 'tampered' >>"$RELEASE/$ENGINE_JAR"
if run_jvm_installer "$WORK/home-jvm-tampered-engine"; then
  cat "$WORK/last-install.log" >&2; echo "a tampered engine jar unexpectedly installed" >&2; exit 1
fi
grep -q "checksum mismatch for $ENGINE_JAR" "$WORK/last-install.log" || { cat "$WORK/last-install.log" >&2; echo "the tampered engine jar was not named" >&2; exit 1; }
[[ ! -e "$WORK/home-jvm-tampered-engine/bin/jk" ]] || { echo "a tampered engine jar still installed the launcher" >&2; exit 1; }
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
  if [[ "$content" != "prior-install" || "${entries[*]}" != "$home/bin $home/install.sh $home/bin/jk" ]]; then
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

echo "Shell installer verification fixtures passed."
