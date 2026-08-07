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
#   JK_ARCHIVE_URL   Override the archive URL to download. Supports .xz and
#                    .zip (a plain uncompressed binary also works for local
#                    files). Defaults to the latest release matching this
#                    machine's OS/arch and available extractor.
#   JK_RELEASES_URL  Override the release site root (mirrors).
#   JK_VERSION       Install a specific version instead of the latest.
#   JK_INSTALL_DIR   Override the install directory (default: ~/.local/bin,
#                    or $XDG_BIN_HOME / $JK_BIN_DIR when set).
#   JK_BIN_DIR       Same as JK_INSTALL_DIR (product layout env).
#   JK_HOME          Optional single-tree umbrella for product data (tests/CI).
#
set -euo pipefail

# PATH entrypoints live outside product data so wiping cache/state/data does not
# uninstall the CLI. Prefer XDG bin / ~/.local/bin (uv-style).
if [ -n "${JK_INSTALL_DIR:-}" ]; then
  INSTALL_DIR="$JK_INSTALL_DIR"
elif [ -n "${JK_BIN_DIR:-}" ]; then
  INSTALL_DIR="$JK_BIN_DIR"
elif [ -n "${XDG_BIN_HOME:-}" ]; then
  INSTALL_DIR="$XDG_BIN_HOME"
else
  INSTALL_DIR="${HOME}/.local/bin"
fi
# One immutable directory per version (jk-<os>-<arch>[.xz] + jk-engine-<version>.jar
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
  BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; RESET=$'\033[0m'
  DOT="●"; CROSS="✖"
else
  BOLD=""; RED=""; GREEN=""; DIM=""; RESET=""
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
# scripts/install.ps1; its download half waits on the release layout.
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

# Archive format for auto URL resolution: releases publish exactly two
# formats (docs/releases.md) — .xz, and .zip as the fallback for hosts
# without xz. Only needed for the download flow, so failing here must not
# break a local-file install.
detect_ext() {
  if have xz; then
    printf 'xz'
  elif have unzip; then
    printf 'zip'
  else
    die "neither xz nor unzip found on PATH; install one and re-run."
  fi
}

# ---- resolve source (URL or local file) ------------------------------------

# Sets decompress() based on the file/URL extension.
# Plain binary (no .xz/.zip — the local dist flow) is installed with cp.
infer_decompress() {
  case "$1" in
    *.xz)
      have xz || die "'$1' is a .xz file but xz is not installed."
      decompress() { xz -dc "$1" > "$2"; } ;;
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
  ARCHIVE_URL="$JK_ARCHIVE_URL"
  infer_decompress "$ARCHIVE_URL"
else
  TARGET="$(detect_target)"
  EXT="$(detect_ext)"
  VERSION="${JK_VERSION:-$(fetch_text "$RELEASES_URL/latest/VERSION" | tr -d '[:space:]')}"
  [ -n "$VERSION" ] || die "could not resolve the latest jk version from $RELEASES_URL/latest/VERSION"
  ARCHIVE_URL="$RELEASES_URL/$VERSION/jk-$TARGET.$EXT"
  infer_decompress "$ARCHIVE_URL"
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
fi

info "Installing JumpKick into $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"

JK_BIN="$INSTALL_DIR/jk"
# Remove any prior pointer (symlink or file) so decompress writes a real binary.
rm -f "$JK_BIN"
decompress "$ARCHIVE_FILE" "$JK_BIN" \
  || die "failed to install jk"
chmod +x "$JK_BIN"

# `jkx` — uvx-style alias for `jk tool run`, shipped as a real executable so
# `#!/usr/bin/env jkx` shebangs and CI steps work without shell integration.
# A hardlink to the jk binary (argv[0] dispatch; zero extra disk); `ln -f`
# also refreshes a stale jkx left by a previous install. Falls back to an
# exec shim when the filesystem refuses hardlinks.
JKX_BIN="$INSTALL_DIR/jkx"
rm -f "$JKX_BIN"
if ! ln -f "$JK_BIN" "$JKX_BIN" 2>/dev/null; then
  printf '#!/bin/sh\n# jkx — `jk tool run` launcher (generated by jk; do not edit)\nexec "%s" tool run "$@"\n' "$JK_BIN" > "$JKX_BIN" \
    || die "failed to install jkx"
  chmod +x "$JKX_BIN"
fi

# The engine ships as a single fat jar, jk-engine-<version>.jar (see
# docs/architecture.md "Ship layout" / client+engine split; the engine is a JVM app,
# not a second native binary). It lives in the side-by-side version layout under
# the product data root (…/versions/<v>/lib/jk-engine.jar) — materialized below
# for local dists; download installs self-fetch it on first engine spawn.
if [ -n "$LOCAL_FILE" ]; then
  SRC_LIB="$(cd "$(dirname "$LOCAL_FILE")" && pwd)/lib"
fi

# ---- side-by-side version layout (docs/architecture.md "Versioning") --------
#
# Local dist installs (binary + engine jar together) also materialize
# versions/<v>/ under the product data root — through the client itself
# (`jk self materialize`), which ingests both artifacts into the CAS first.
# Download installs skip this: the client self-fetches its engine jar on first
# spawn and materializes then. Best-effort by design.
if [ -n "$LOCAL_FILE" ]; then
  ENGINE_JAR=""
  for f in "$SRC_LIB"/jk-engine-*.jar; do
    [ -f "$f" ] && ENGINE_JAR="$f" && break
  done
  if [ -n "$ENGINE_JAR" ]; then
    run_jk self materialize "$JK_BIN" "$ENGINE_JAR" >/dev/null 2>&1 \
      || note "versions/ materialization skipped (jk self materialize failed; the client re-fetches on demand)"
  fi
  # Nerd Font probe → user config [global].nerdfont; never fail install.
  run_jk self setup-terminal >/dev/null 2>&1 \
    || note "terminal setup skipped (run 'jk self setup-terminal' later)"
fi

# ---- activate --------------------------------------------------------------

info "Running \`jk activate\`... This may download a JDK and optimize your installation."
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
if have git; then
  # jk-templates: shallow clone (replace if exists) – mirrors OfficialTemplatesFreshen dest
  _jk_templates_url="${JK_TEMPLATES_URL:-https://github.com/jkbuild/jk-templates.git}"
  _jk_cache_templates=""
  if [ -d "${HOME}/.jk/cache/templates" ] || [ -d "${HOME}/.jk" ]; then
    _jk_cache_templates="${HOME}/.jk/cache/templates"
  else
    _jk_cache_templates="${XDG_CACHE_HOME:-${HOME}/.cache}/jk/templates"
    # Respect JK_CACHE_DIR if set (engine/cache root)
    if [ -n "${JK_CACHE_DIR:-}" ]; then
      _jk_cache_templates="${JK_CACHE_DIR}/templates"
    fi
    if [ -n "${JK_HOME:-}" ]; then
      _jk_cache_templates="${JK_HOME}/cache/templates"
    fi
  fi
  # Derive cache key similar to OfficialTemplatesFreshen (sanitize URL)
  _jk_tmpl_key="$(printf '%s' "$_jk_templates_url" | tr '[:upper:]' '[:lower:]' | sed -e 's|^https*://||' -e 's|^git@||' -e 's|\.git$||' -e 's|[^a-z0-9._-]|_|g')"
  _jk_tmpl_dest="$_jk_cache_templates/$_jk_tmpl_key"
  mkdir -p "$(dirname "$_jk_tmpl_dest")" || true
  if [ -d "$_jk_tmpl_dest" ]; then
    rm -rf "$_jk_tmpl_dest" || true
  fi
  GIT_TERMINAL_PROMPT=0 git clone --depth 1 "$_jk_templates_url" "$_jk_tmpl_dest" >/dev/null 2>&1 \
    || note "templates prefetch skipped (git clone failed – will lazy-clone on jk new)"
  unset _jk_templates_url _jk_cache_templates _jk_tmpl_key _jk_tmpl_dest
fi
# jk-libraries: conditional fetch of libraries.toml (ETag-aware in LibraryRegistrySync, but prefetch raw)
if have curl; then
  _jk_libs_url="${JK_LIBRARIES_URL:-https://raw.githubusercontent.com/jkbuild/jk-libraries/refs/heads/main/libraries.toml}"
  _jk_libs_dest=""
  if [ -n "${JK_CACHE_DIR:-}" ]; then
    _jk_libs_dest="${JK_CACHE_DIR}/libs.global.toml"
  elif [ -d "${HOME}/.jk" ]; then
    _jk_libs_dest="${HOME}/.jk/cache/libs.global.toml"
  else
    _jk_libs_dest="${XDG_CACHE_HOME:-${HOME}/.cache}/jk/libs.global.toml"
  fi
  # Also try store location (StoreFeedRefresh uses store/libs.global.toml)
  _jk_store_libs="${HOME}/.local/share/jk/store/libs.global.toml"
  if [ -n "${JK_STORE_DIR:-}" ]; then _jk_store_libs="${JK_STORE_DIR}/libs.global.toml"; fi
  if [ -n "${JK_HOME:-}" ]; then _jk_store_libs="${JK_HOME}/store/libs.global.toml"; fi
  mkdir -p "$(dirname "$_jk_libs_dest")" "$(dirname "$_jk_store_libs")" || true
  curl -fsSL "$_jk_libs_url" -o "$_jk_libs_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_libs_dest.tmp" "$_jk_libs_dest" 2>/dev/null && cp -f "$_jk_libs_dest" "$_jk_store_libs" 2>/dev/null || rm -f "$_jk_libs_dest.tmp" 2>/dev/null || true
  unset _jk_libs_url _jk_libs_dest _jk_store_libs
elif have wget; then
  _jk_libs_url="${JK_LIBRARIES_URL:-https://raw.githubusercontent.com/jkbuild/jk-libraries/refs/heads/main/libraries.toml}"
  _jk_libs_dest="${JK_CACHE_DIR:-${XDG_CACHE_HOME:-${HOME}/.cache}/jk}/libs.global.toml"
  _jk_store_libs="${HOME}/.local/share/jk/store/libs.global.toml"
  if [ -n "${JK_STORE_DIR:-}" ]; then _jk_store_libs="${JK_STORE_DIR}/libs.global.toml"; fi
  if [ -n "${JK_HOME:-}" ]; then _jk_store_libs="${JK_HOME}/store/libs.global.toml"; fi
  mkdir -p "$(dirname "$_jk_libs_dest")" "$(dirname "$_jk_store_libs")" || true
  wget -q "$_jk_libs_url" -O "$_jk_libs_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_libs_dest.tmp" "$_jk_libs_dest" 2>/dev/null && cp -f "$_jk_libs_dest" "$_jk_store_libs" 2>/dev/null || rm -f "$_jk_libs_dest.tmp" 2>/dev/null || true
  unset _jk_libs_url _jk_libs_dest _jk_store_libs
fi
# jdks.json: one-shot fetch to store (JdkCatalogClient will revalidate with If-Modified-Since)
if have curl; then
  _jk_jdks_url="${JK_JDKS_URL:-https://download.jetbrains.com/jdk/feed/v1/jdks.json}"
  _jk_jdks_dest="${JK_STORE_DIR:-${HOME}/.local/share/jk/store}/jdks.json"
  if [ -n "${JK_HOME:-}" ]; then _jk_jdks_dest="${JK_HOME}/store/jdks.json"; fi
  mkdir -p "$(dirname "$_jk_jdks_dest")" || true
  curl -fsSL "$_jk_jdks_url" -o "$_jk_jdks_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_jdks_dest.tmp" "$_jk_jdks_dest" 2>/dev/null || rm -f "$_jk_jdks_dest.tmp" 2>/dev/null || true
  unset _jk_jdks_url _jk_jdks_dest
elif have wget; then
  _jk_jdks_url="${JK_JDKS_URL:-https://download.jetbrains.com/jdk/feed/v1/jdks.json}"
  _jk_jdks_dest="${JK_STORE_DIR:-${HOME}/.local/share/jk/store}/jdks.json"
  if [ -n "${JK_HOME:-}" ]; then _jk_jdks_dest="${JK_HOME}/store/jdks.json"; fi
  mkdir -p "$(dirname "$_jk_jdks_dest")" || true
  wget -q "$_jk_jdks_url" -O "$_jk_jdks_dest.tmp" >/dev/null 2>&1 && mv -f "$_jk_jdks_dest.tmp" "$_jk_jdks_dest" 2>/dev/null || rm -f "$_jk_jdks_dest.tmp" 2>/dev/null || true
  unset _jk_jdks_url _jk_jdks_dest
fi

# ---- warm the engine -------------------------------------------------------
#
# Pre-pay the engine's cold-start costs now so the first real build doesn't:
# `jk engine start` installs the JDK that hosts the engine when none
# qualifies, and on a download install triggers the client's own engine-jar
# fetch (which also completes versions/<v>/ — jar, manifest, AND this client
# binary under the data root). The engine serves immediately and manages its
# own AOT training sidecar off to the side (docs/architecture.md), so ONE start
# is the whole warm-up. Best-effort by design: a failed warm-up never fails the
# install. Skipped only for a local dist install that carried no engine jar.
if [ -z "$LOCAL_FILE" ] || [ -n "${ENGINE_JAR:-}" ]; then
  # Local dogfood reinstalls keep the same version string while replacing the
  # engine jar. A still-running engine would keep serving the old jar until stop.
  run_jk engine stop --force >/dev/null 2>&1 || true
  # Engine self-heals missing worker AOT + host calibration on idle (and every 12h).
  run_jk engine start >/dev/null 2>&1 \
    || note "Engine warm-up skipped; it will start on first build"
fi

# PATH entrypoints stay real files (or hardlinks from self update) — not
# symlinks into versions/ — so deleting product data does not uninstall jk.

printf '\n'

# ---- restart shell ---------------------------------------------------------
#
# Never block on a keypress here: install is finished. Cases that used to
# `read` from /dev/tty (curl|bash) or silently `exec $SHELL` (local tty) made
# the script feel hung after `jk activate`. Print how to pick up PATH/hooks;
# the user reloads when ready.
RELOAD_HINT="Open a new terminal or run 'exec \$SHELL' to start using jk."
note "$RELOAD_HINT"
