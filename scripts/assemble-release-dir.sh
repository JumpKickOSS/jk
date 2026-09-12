#!/usr/bin/env bash
# Assemble a versioned release directory from build/dist layout for
# jumpkick.build via GCS + Firebase CDN).
# Expects a dist layout (native client + engine jar): `jk build` writes it under target/dist,
# `./gradlew dist` under build/dist. DIST_DIR names which one (default build/dist).
# Usage:
# scripts/assemble-release-dir.sh [out-dir]
# Env:
# JK_VERSION override version (default: JkVersion / project version via git describe or file)
# Layout written to out-dir (default: build/release/<version>/):
# jk-<os>-<arch>-<version>.xz (every platform, including Windows — self-update)
# jk-windows-x86_64-<version>.zip (Windows only — install.ps1 / jk.bat; no system xz)
# The version is part of every artifact name, so a signed manifest copied from another
# release directory cannot name what an installer asks for.
# jk-engine-<version>.jar
# SHA256SUMS
# SHA256SUMS.sig (if JK_RELEASE_RSA_SIGNING_KEY or its file variant is set)
# The ../latest/ pointer (LATEST, LATEST.sig, VERSION) is the caller's job: scripts/sign-latest-pointer.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${JK_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E 'VERSION = "' shared/jk-api/src/main/java/cc/jumpkick/model/JkVersion.java | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
OUT="${1:-build/release/$VERSION}"
DIST="${DIST_DIR:-build/dist}"

if [[ ! -d "$DIST" ]]; then
  echo "assemble-release-dir: missing $DIST — run jk build (target/dist) or ./gradlew dist (build/dist) first" >&2
  exit 2
fi

mkdir -p "$OUT"
rm -f "$OUT"/* 2>/dev/null || true

# Detect OS/arch for the native binary present in dist (CI builds one platform per job).
OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS" in
  Linux)  os=linux ;;
  Darwin) os=macos ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT) os=windows ;;
  * ) os=unknown;;
esac
case "$ARCH" in
  x86_64|amd64) arch=x86_64 ;;
  aarch64|arm64) arch=aarch64 ;;
  * ) arch=unknown;;
esac

# jk.exe first: bash on Windows answers `-f jk` with yes when only jk.exe exists, so the Unix
# branch would name a file that is not there.
if [[ -f "$DIST/jk.exe" ]]; then
  name="jk-windows-x86_64-${VERSION}"
  src="$DIST/jk.exe"
elif [[ -f "$DIST/jk" ]]; then
  name="jk-${os}-${arch}-${VERSION}"
  src="$DIST/jk"
else
  echo "assemble-release-dir: no native jk binary in $DIST" >&2
  exit 2
fi

if ! command -v xz >/dev/null 2>&1; then
  echo "assemble-release-dir: xz is required (self-update fetches .xz on every OS)" >&2
  exit 2
fi
xz -ck9 "$src" >"$OUT/${name}.xz"

# Windows wrapper / install.ps1 have no system xz — also ship a single-entry zip.
if [[ "$os" == "windows" ]]; then
  if ! command -v zip >/dev/null 2>&1; then
    echo "assemble-release-dir: zip is required on Windows (jk.bat / install.ps1)" >&2
    exit 2
  fi
  (cd "$DIST" && zip -q "$OUT/${name}.zip" "$(basename "$src")")
fi

engine=""
for candidate in "$DIST"/lib/jk-engine-*.jar; do
  [[ -f "$candidate" ]] && engine="$candidate" && break
done
if [[ -z "$engine" ]]; then
  echo "assemble-release-dir: no engine jar under $DIST/lib" >&2
  exit 2
fi
cp "$engine" "$OUT/jk-engine-${VERSION}.jar"

# SHA256SUMS (coreutils format: hash two spaces name). The file list is fixed before the manifest
# exists, so the manifest never names itself.
(
  cd "$OUT"
  files=(*)
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "${files[@]}" >SHA256SUMS
  else
    sha256sum -- "${files[@]}" >SHA256SUMS
  fi
)

if [[ -n "${JK_RELEASE_RSA_SIGNING_KEY:-}" || -n "${JK_RELEASE_RSA_SIGNING_KEY_FILE:-}" ]]; then
  bash "$ROOT/scripts/sign-release.sh" "$OUT/SHA256SUMS"
else
  echo "assemble-release-dir: RSA signing key unset — SHA256SUMS.sig not written" >&2
fi

echo "assemble-release-dir: $OUT"
ls -la "$OUT"
