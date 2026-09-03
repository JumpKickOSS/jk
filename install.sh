#!/usr/bin/env bash
#
# jk installer
#
# Usage:
#   curl -fsSL https://jumpkick.build/install.sh | bash
#   wget -qO- https://jumpkick.build/install.sh | bash
#   bash install.sh [/path/to/jk[.xz|.zip]]
#
# Environment variables:
#   JK_ARCHIVE_URL   Override the archive URL to download. JK_VERSION is required
#                    with this override; verification evidence still comes from
#                    JK_RELEASES_URL/<version>/. Supports .xz and .zip.
#   JK_RELEASES_URL  Override the release site root (mirrors).
#   JK_VERSION       Install a specific version instead of the latest.
#   JK_HOME          jk's home directory. Default $HOME/.jk; everything jk
#                    owns lives under it, on every platform. The client is
#                    installed to $JK_HOME/bin.
#
set -euo pipefail

# One home, one bin. `jk activate` writes $JK_HOME/bin/jk into the shell profile
# and `jk self update` replaces the binary there, so this has to be the same
# directory JkDirs.binDirectory() resolves — which it is, by having one answer.
JK_HOME_DIR="${JK_HOME:-${HOME}/.jk}"
INSTALL_DIR="${JK_HOME_DIR}/bin"
# One immutable directory per version (jk-<os>-<arch>-<version>[.xz] + jk-engine-<version>.jar
# + SHA256SUMS); `latest/VERSION` is the only mutable pointer. The version is
# resolved ONCE and both artifacts come from the frozen directory, so a release
# published mid-install can never hand out a binary and an engine jar that
# disagree (the client refuses to launch a version-skewed jar).
RELEASES_URL="${JK_RELEASES_URL:-https://jumpkick.build/releases}"

# Optional positional argument: local path to jk, jk.xz, or jk.zip.
LOCAL_FILE="${1:-}"

# ---- terminal / interactivity detection ------------------------------------
#
# Two independent signals, resolved once:
#   * ansi — gated on stdout being a real terminal (and not NO_COLOR / a dumb
#     or unset TERM), so piped/redirected output stays clean. Also drives
#     which glyphs we print: the ● / ✖ marks assume a UTF-8-ish terminal that
#     can also do escape codes, so a non-ANSI terminal gets plain ASCII
#     stand-ins instead of risking a `?`/tofu box.
#   * interactivity — whether a controlling terminal is reachable at all. Under
#     `curl … | bash`, fd 0 is the PIPE feeding bash the script, NOT a tty, so
#     `[ -t 0 ]` is the wrong probe — /dev/tty is. This drives TTY_IN, the stdin
#     every child `jk` command is given: a real terminal when one exists (so
#     jk's own prompts / console detection work), else /dev/null. Routing
#     children away from the script's stdin also stops a child from consuming
#     the rest of the piped script — the classic `curl | bash` truncation.
#
# Honour CI and JK_NONINTERACTIVE to force the non-interactive path explicitly.

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ -n "${TERM:-}" ] && [ "${TERM:-}" != "dumb" ]; then
  BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; RESET=$'\033[0m'
  DOT="●"; CROSS="✖"
else
  BOLD=""; RED=""; GREEN=""; YELLOW=""; DIM=""; RESET=""
  DOT="*"; CROSS="x"
fi

if [ -z "${CI:-}" ] && [ -z "${JK_NONINTERACTIVE:-}" ] && { : </dev/tty; } 2>/dev/null; then
  INTERACTIVE=1; TTY_IN=/dev/tty
else
  INTERACTIVE=0; TTY_IN=/dev/null
fi

info()  { printf '%s%s%s %s\n' "$GREEN" "$DOT" "$RESET" "$*"; }
note()  { printf '%s    %s%s\n' "$DIM" "$*" "$RESET"; }
err()   { printf '%s%s%s %s\n' "$RED" "$CROSS" "$RESET" "$*" >&2; }
die()   { err "$@"; exit 1; }

# Run a child `jk` with its stdin bound to the terminal (or /dev/null) — never
# the (possibly piped) script stdin. Callers add their own stdout/stderr redirs.
# ($JK_BIN is resolved at call time, after the install step sets it.)
run_jk() { "$JK_BIN" "$@" 0<"$TTY_IN"; }

# ---- detect tools ----------------------------------------------------------

have() { command -v "$1" >/dev/null 2>&1; }

RELEASE_RSA_SPKI="MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA62bXAMmyIpPgiFzT9lcuIPWvvXHmWfGDPbMJAG1lRlbSJ9EFRahqkie0LQaFtXn8W3l2BP/9D0DwdXztS/eVo8WqSNMOZo/srBKrViVJGEOFm0fDmhqrlA3bCZz43+DgFjj7SacI2nJVB4PRjV5jvRwBnZrIUwcvynIQmx2SoWoKgudoje7vNM7UkYmEnZExfmiPQaPmSYCKzXA4pP5KPWD+49bo7o3cLeiO5/Shc27OC0IvK+Vj8CUe4URSt5zHjHUpiE+h4SVTMrGoJg9rgWmRgMHdshsq3aoAkA3jC/YB5SzLwUJeObWGP8I9w7yj8uiSTNIt3KslbRfVtb4vbNoZ4zKPMkCaYhy5ar0sGOqxW97wobIWBiX5pT+knluZErrsJFWpx2dQtRtb2wPovihL7Z9Q18vZb371Gx+rzkNi7jdFvWaGYgsraf01l63Gg2bfy1bleSLhDKmh94yGMoHfszEcE1785xteYOdVSwawUPwWgx8iZ7a4lqOL0MrrAgMBAAE="

# Pick a downloader (not needed when a local file is provided).
if [ -z "$LOCAL_FILE" ]; then
  if have curl; then
    download() { curl -fsSL "$1" -o "$2"; }
    fetch_text() { curl -fsSL "$1"; }
  elif have wget; then
    download() { wget -q "$1" -O "$2"; }
    fetch_text() { wget -qO- "$1"; }
  else
    die "neither curl nor wget found on PATH; cannot download jk."
  fi
fi

# Release artifacts are named jk-<os>-<arch> — the same vocabulary jk itself
# uses (HostPlatform): linux|macos × x86_64|aarch64. Windows uses
# install.ps1 (irm|iex); this script never runs there.
detect_target() {
  local os arch
  case "$(uname -s)" in
    Linux)  os="linux" ;;
    Darwin) os="macos" ;;
    *) die "unsupported OS: $(uname -s) (this script supports Linux and macOS)" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) arch="x86_64" ;;
    aarch64|arm64) arch="aarch64" ;;
    *) die "unsupported architecture: $(uname -m) (supported: x86_64, aarch64)" ;;
  esac
  printf '%s-%s' "$os" "$arch"
}

# Archive format for auto URL resolution: Linux/macOS releases are .xz
# only (docs/releases.md). Windows uses install.ps1 and a .zip — this
# script never runs there. JK_ARCHIVE_URL / a local file may still be
# .zip. Missing xz must not fall through to a .zip we do not host.
#
# Stock macOS ships no xz binary; its /usr/bin/compression_tool decodes the
# xz container (Compression framework LZMA), so Darwin falls back to it.
can_unxz() {
  have xz && return 0
  [ "$(uname -s)" = "Darwin" ] && [ -x /usr/bin/compression_tool ]
}

# unxz <in.xz> <out> — xz when present, else Apple's compression_tool.
unxz_file() {
  if have xz; then
    xz -dc "$1" > "$2"
  else
    /usr/bin/compression_tool -decode -A lzma -i "$1" -o "$2"
  fi
}

detect_ext() {
  can_unxz || die "cannot decompress .xz: install xz and re-run (Linux: xz-utils; macOS: brew install xz)."
  printf 'xz'
}

# ---- resolve source (URL or local file) ------------------------------------

# Sets decompress() based on the file/URL extension.
# Plain binary (no .xz/.zip — the local dist flow) is installed with cp.
infer_decompress() {
  case "$1" in
    *.xz)
      can_unxz || die "'$1' is a .xz file but xz is not installed (Linux: xz-utils; macOS: brew install xz)."
      decompress() { unxz_file "$1" "$2"; } ;;
    *.zip)
      have unzip || die "'$1' is a .zip file but unzip is not installed."
      # Single-entry archive: -p streams the binary to stdout.
      decompress() { unzip -p "$1" > "$2"; } ;;
    *)
      decompress() { cp "$1" "$2"; } ;;
  esac
}

if [ -n "$LOCAL_FILE" ]; then
  [ -f "$LOCAL_FILE" ] || die "local file not found: $LOCAL_FILE"
  infer_decompress "$LOCAL_FILE"
elif [ -n "${JK_ARCHIVE_URL:-}" ]; then
  [ -n "${JK_VERSION:-}" ] || die "JK_VERSION is required when JK_ARCHIVE_URL is set."
  VERSION="$JK_VERSION"
  ARCHIVE_URL="$JK_ARCHIVE_URL"
  infer_decompress "${ARCHIVE_URL%%\?*}"
else
  TARGET="$(detect_target)"
  EXT="$(detect_ext)"
  VERSION="${JK_VERSION:-$(fetch_text "$RELEASES_URL/latest/VERSION" | tr -d '[:space:]')}"
  [ -n "$VERSION" ] || die "could not resolve the latest jk version from $RELEASES_URL/latest/VERSION"
  ARCHIVE_URL="$RELEASES_URL/$VERSION/jk-$TARGET-$VERSION.$EXT"
  infer_decompress "$ARCHIVE_URL"
fi

if [ -z "$LOCAL_FILE" ]; then
  case "$VERSION" in
    ""|*[!A-Za-z0-9._-]*) die "invalid release version: $VERSION" ;;
  esac
  ARTIFACT_NAME="${ARCHIVE_URL%%\?*}"
  ARTIFACT_NAME="${ARTIFACT_NAME##*/}"
  case "$ARTIFACT_NAME" in
    ""|*[!A-Za-z0-9._-]*) die "archive URL must end in a plain release artifact filename." ;;
  esac
  RELEASE_VERSION_URL="${RELEASES_URL%/}/$VERSION"
fi

# ---- download & install ----------------------------------------------------

TMPDIR_JK="$(mktemp -d "${TMPDIR:-/tmp}/jk-install.XXXXXX")"
cleanup() { rm -rf "$TMPDIR_JK"; }
trap cleanup EXIT

if [ -n "$LOCAL_FILE" ]; then
  ARCHIVE_FILE="$LOCAL_FILE"
else
  ARCHIVE_FILE="$TMPDIR_JK/jk.archive"
  download "$ARCHIVE_URL" "$ARCHIVE_FILE" \
    || die "failed to download $ARCHIVE_URL"
  download "$RELEASE_VERSION_URL/SHA256SUMS" "$TMPDIR_JK/SHA256SUMS" \
    || die "failed to download release checksum evidence."
  download "$RELEASE_VERSION_URL/SHA256SUMS.sig" "$TMPDIR_JK/SHA256SUMS.sig" \
    || die "failed to download release signature evidence."

  have openssl || die "OpenSSL is required to authenticate remote JumpKick releases."
  {
    printf '%s\n' "-----BEGIN PUBLIC KEY-----"
    printf '%s' "$RELEASE_RSA_SPKI" | fold -w 64
    printf '\n'
    printf '%s\n' "-----END PUBLIC KEY-----"
  } >"$TMPDIR_JK/release-public.pem"

  SIG_TEXT="$(tr -d '\r' <"$TMPDIR_JK/SHA256SUMS.sig")"
  SIG_LINES="$(wc -l <"$TMPDIR_JK/SHA256SUMS.sig" | tr -d '[:space:]')"
  if { [ "$SIG_LINES" != "0" ] && [ "$SIG_LINES" != "1" ]; } ||
    [ "${#SIG_TEXT}" -ne 512 ] ||
    ! printf '%s' "$SIG_TEXT" | LC_ALL=C grep -Eq '^[A-Za-z0-9+/]+={0,2}$'; then
    die "release signature is malformed."
  fi
  printf '%s' "$SIG_TEXT" | openssl base64 -d -A >"$TMPDIR_JK/SHA256SUMS.sig.bin" 2>/dev/null \
    || die "release signature is not valid base64."
  [ "$(wc -c <"$TMPDIR_JK/SHA256SUMS.sig.bin" | tr -d '[:space:]')" = "384" ] \
    || die "release signature has the wrong RSA-3072 length."
  openssl dgst -sha256 -verify "$TMPDIR_JK/release-public.pem" \
    -signature "$TMPDIR_JK/SHA256SUMS.sig.bin" "$TMPDIR_JK/SHA256SUMS" >/dev/null 2>&1 \
    || die "release signature verification failed; refusing the download."

  if ! EXPECTED_SHA="$(awk -v wanted="$ARTIFACT_NAME" '
    {
      hash = substr($0, 1, 64)
      sep = substr($0, 65, 2)
      name = substr($0, 67)
      if (length(hash) != 64 || hash !~ /^[0-9A-Fa-f]+$/ || sep != "  " ||
          name !~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/ || seen[name]++) bad = 1
      if (name == wanted) {
        matches++
        found = tolower(hash)
      }
    }
    END {
      if (bad || matches != 1) exit 1
      print found
    }
  ' "$TMPDIR_JK/SHA256SUMS")"; then
    die "release SHA256SUMS is malformed, duplicated, or has no exact entry for $ARTIFACT_NAME."
  fi
  ACTUAL_SHA="$(openssl dgst -sha256 "$ARCHIVE_FILE" | awk '{print tolower($NF)}')"
  [ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] \
    || die "release archive checksum mismatch for $ARTIFACT_NAME; refusing the download."
fi

# Prefer a ~ display when the install dir lives under $HOME (uv-style).
case "$INSTALL_DIR" in
  "$HOME"/*) INSTALL_DIR_DISPLAY="~${INSTALL_DIR#"$HOME"}" ;;
  *) INSTALL_DIR_DISPLAY="$INSTALL_DIR" ;;
esac
printf '\n'
info "Installing JumpKick into $INSTALL_DIR_DISPLAY"
mkdir -p "$INSTALL_DIR"

park_if_present() {
  local f="$1"
  if [ -e "$f" ] || [ -L "$f" ]; then
    mv -f "$f" "${f}.old" 2>/dev/null || rm -f "$f"
  fi
}

JK_BIN="$INSTALL_DIR/jk"
# Park a previous client so a still-running `jk` keeps its inode; GC removes .old later.
park_if_present "$JK_BIN"
decompress "$ARCHIVE_FILE" "$JK_BIN" \
  || die "failed to install jk"
chmod +x "$JK_BIN"

# `jkx` — uvx-style alias for `jk tool run`, shipped as a real executable so
# `#!/usr/bin/env jkx` shebangs and CI steps work without shell integration.
# A hardlink to the jk binary (argv[0] dispatch; zero extra disk); `ln -f`
# also refreshes a stale jkx left by a previous install. Falls back to an
# exec shim when the filesystem refuses hardlinks.
JKX_BIN="$INSTALL_DIR/jkx"
park_if_present "$JKX_BIN"
if ! ln -f "$JK_BIN" "$JKX_BIN" 2>/dev/null; then
  printf '#!/bin/sh\n# jkx — `jk tool run` launcher (generated by jk; do not edit)\nexec "%s" tool run "$@"\n' "$JK_BIN" > "$JKX_BIN" \
    || die "failed to install jkx"
  chmod +x "$JKX_BIN"
fi

# Clear only resident engines positively identified in the superseded platform default.
run_jk self retire-old-engines >/dev/null 2>&1 \
  || note "an engine from the superseded install location could not be stopped"

# The engine ships as a single fat jar, jk-engine-<version>.jar (see
# docs/architecture.md "Ship layout" / client+engine split; the engine is a JVM app,
# not a second native binary). The live copy is <home>/lib/jk-engine/ — materialized below for local dists;
# download installs self-fetch it on first engine spawn.
if [ -n "$LOCAL_FILE" ]; then
  SRC_LIB="$(cd "$(dirname "$LOCAL_FILE")" && pwd)/lib"
fi

# ---- product-lib engine (docs/architecture.md "Versioning") ----------------
#
# Local dist installs (binary + engine jar together) also install
# the engine jar under the product lib — through the client itself
# (`jk self materialize`), which ingests the jar into the CAS first.
# Download installs skip this: the client self-fetches its engine jar on first
# spawn and materializes then. Best-effort by design.
if [ -n "$LOCAL_FILE" ]; then
  ENGINE_JAR=""
  for f in "$SRC_LIB"/jk-engine-*.jar; do
    [ -f "$f" ] && ENGINE_JAR="$f" && break
  done
  if [ -n "$ENGINE_JAR" ]; then
    run_jk self materialize "$JK_BIN" "$ENGINE_JAR" >/dev/null 2>&1 \
      || note "engine materialization skipped (jk self materialize failed; the client re-fetches on demand)"
  fi
  # Seed root-level nerd-font = "auto"; detection then runs per launch. Never fail install.
  run_jk self setup-terminal >/dev/null 2>&1 \
    || note "terminal setup skipped (run 'jk self setup-terminal' later)"
fi

# ---- activate --------------------------------------------------------------

info "Running \`jk activate\`... This may download a JDK and optimize your installation"
# --yes: write shell integration without the interactive Yes/No wizard. install.sh
# used to call bare `jk activate`, which opened a TUI over /dev/tty and waited for
# a keypress even on automated/local installs. Failure must not abort warm-up —
# the binary is already installed.
run_jk activate --yes || note "'jk activate --yes' failed; run 'jk activate' (or 'jk activate <shell>') manually."

# ---- preemptive payload warm-up (jk-templates, jk-libraries, jdks.json) -------
#
# Populate the template/library/JDK catalog caches now so the first real
# `jk new` / `jk lock` / `jk jdk install` doesn't pay cold-start latency.
# Best-effort: never fail the install. Replaces existing cache entries
# (shallow clone depth 1, conditional GET with ETag). Works for both
# `curl|bash` and `bash install.sh build/dist/jk` (local) flows.
# The store root, exactly as JkDirs.storeDir() resolves it. Prefetch dests must
# match JkDirs.templates() (<store>/templates) and JkDirs.libraryRegistry()
# (<store>/libs.global.toml) — do not write a second copy under cache/.
_jk_store_root="${JK_STORE_DIR:-${JK_HOME_DIR}/store}"

if have git; then
  # jk-templates: shallow clone (replace if exists) – mirrors OfficialTemplatesFreshen dest
  _jk_templates_url="${JK_TEMPLATES_URL:-https://github.com/JumpKickOSS/jk-templates.git}"
  _jk_store_templates="${_jk_store_root}/templates"
  # Derive cache key similar to OfficialTemplatesFreshen (sanitize URL)
  _jk_tmpl_key="$(printf '%s' "$_jk_templates_url" | tr '[:upper:]' '[:lower:]' | sed -e 's|^https*://||' -e 's|^git@||' -e 's|\.git$||' -e 's|[^a-z0-9._-]|_|g')"
  _jk_tmpl_dest="$_jk_store_templates/$_jk_tmpl_key"
  mkdir -p "$(dirname "$_jk_tmpl_dest")" || true
  if [ -d "$_jk_tmpl_dest" ]; then
    rm -rf "$_jk_tmpl_dest" || true
  fi
  GIT_TERMINAL_PROMPT=0 git clone --depth 1 "$_jk_templates_url" "$_jk_tmpl_dest" >/dev/null 2>&1 \
    || note "templates prefetch skipped (git clone failed – will lazy-clone on jk new)"
  unset _jk_templates_url _jk_store_templates _jk_tmpl_key _jk_tmpl_dest
fi
# jk-libraries: one copy at JkDirs.libraryRegistry() (ETag-aware revalidation later)
if have curl; then
  _jk_libs_url="${JK_LIBRARIES_URL:-https://raw.githubusercontent.com/JumpKickOSS/jk-libraries/refs/heads/main/libraries.toml}"
  _jk_libs_dest="${_jk_store_root}/libs.global.toml"
  mkdir -p "$(dirname "$_jk_libs_dest")" || true
  curl -fsSL "$_jk_libs_url" -o "$_jk_libs_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_libs_dest.tmp" "$_jk_libs_dest" 2>/dev/null || rm -f "$_jk_libs_dest.tmp" 2>/dev/null || true
  unset _jk_libs_url _jk_libs_dest
elif have wget; then
  _jk_libs_url="${JK_LIBRARIES_URL:-https://raw.githubusercontent.com/JumpKickOSS/jk-libraries/refs/heads/main/libraries.toml}"
  _jk_libs_dest="${_jk_store_root}/libs.global.toml"
  mkdir -p "$(dirname "$_jk_libs_dest")" || true
  wget -q "$_jk_libs_url" -O "$_jk_libs_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_libs_dest.tmp" "$_jk_libs_dest" 2>/dev/null || rm -f "$_jk_libs_dest.tmp" 2>/dev/null || true
  unset _jk_libs_url _jk_libs_dest
fi
# jdks.json: one-shot fetch to store (JdkCatalogClient will revalidate with If-Modified-Since)
if have curl; then
  _jk_jdks_url="${JK_JDKS_URL:-https://download.jetbrains.com/jdk/feed/v1/jdks.json}"
  _jk_jdks_dest="${_jk_store_root}/jdks.json"
  mkdir -p "$(dirname "$_jk_jdks_dest")" || true
  curl -fsSL "$_jk_jdks_url" -o "$_jk_jdks_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_jdks_dest.tmp" "$_jk_jdks_dest" 2>/dev/null || rm -f "$_jk_jdks_dest.tmp" 2>/dev/null || true
  unset _jk_jdks_url _jk_jdks_dest
elif have wget; then
  _jk_jdks_url="${JK_JDKS_URL:-https://download.jetbrains.com/jdk/feed/v1/jdks.json}"
  _jk_jdks_dest="${_jk_store_root}/jdks.json"
  mkdir -p "$(dirname "$_jk_jdks_dest")" || true
  wget -q "$_jk_jdks_url" -O "$_jk_jdks_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_jdks_dest.tmp" "$_jk_jdks_dest" 2>/dev/null || rm -f "$_jk_jdks_dest.tmp" 2>/dev/null || true
  unset _jk_jdks_url _jk_jdks_dest
fi
unset _jk_store_root

# ---- warm the engine -------------------------------------------------------
#
# Pre-pay the engine's cold-start costs now so the first real build doesn't:
# `jk engine start` installs the JDK that hosts the engine when none
# qualifies, and on a download install triggers the client's own engine-jar
# fetch (which writes under <home>/lib/jk-engine/). The engine serves immediately
# and manages its own AOT training sidecar off to the side
# (docs/architecture.md), so ONE start is the whole warm-up. Best-effort by
# design: a failed warm-up never fails the install. Skipped only for a local
# dist install that carried no engine jar.
if [ -z "$LOCAL_FILE" ] || [ -n "${ENGINE_JAR:-}" ]; then
  # Local dogfood reinstalls keep the same version string while replacing the
  # engine jar. A still-running engine would keep serving the old jar until stop.
  run_jk engine stop --force >/dev/null 2>&1 || true
  # Engine self-heals missing worker AOT + host calibration on idle (and every 12h).
  # A successful start GCs parked jk.old / engine *.jar.old when the drain is done.
  run_jk engine start >/dev/null 2>&1 \
    || note "Engine warm-up skipped; it will start on first build"
fi
rm -f "${JK_BIN}.old" "${JKX_BIN}.old" 2>/dev/null || true

# PATH entrypoints stay real files — not pointers into product data — so
# deleting product data does not uninstall jk.

# ---- ready -----------------------------------------------------------------
#
# Never block on a keypress here: install is finished. Cases that used to
# `read` from /dev/tty (curl|bash) or silently `exec $SHELL` (local tty) made
# the script feel hung after `jk activate`. Print how to pick up PATH/hooks;
# the user reloads when ready. `jk activate` already closed with the human
# envelope's trailing blank — do not add another spacer before this section.
case "${SHELL##*/}" in
  zsh)  SOURCE_HINT='source ~/.zshrc' ;;
  bash) SOURCE_HINT='source ~/.bashrc' ;;
  fish) SOURCE_HINT='source ~/.config/fish/config.fish' ;;
  *)    SOURCE_HINT='source ~/.zshrc' ;;
esac
info "JumpKick is ready! Hi-ya!"
printf '%s%s%s Run %s%s%s or %s%s%s to start using %s%s%s\n' \
  "$GREEN" "$DOT" "$RESET" \
  "$YELLOW" 'exec $SHELL' "$RESET" \
  "$YELLOW" "$SOURCE_HINT" "$RESET" \
  "$YELLOW" 'jk' "$RESET"
printf '\n'
