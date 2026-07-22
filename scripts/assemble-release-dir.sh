#!/usr/bin/env bash
# Assemble a versioned release directory from build/dist (JK-1066 layout for jumpkick.build).
#
# Expects a prior `./gradlew clean dist` (native client + engine jar under build/dist).
#
# Usage:
#   scripts/assemble-release-dir.sh [out-dir]
# Env:
#   JK_VERSION   override version (default: JkVersion / project version via git describe or file)
#
# Layout written to out-dir (default: build/release/<version>/):
#   jk-linux-x86_64.xz | jk-macos-aarch64.xz | …   (whatever native binary is present)
#   jk-engine-<version>.jar
#   SHA256SUMS
#   SHA256SUMS.sig   (if JK_RELEASE_SIGNING_KEY is set)
#   ../latest/VERSION pointer is the caller's job (CI).
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
  echo "assemble-release-dir: missing $DIST — run ./gradlew dist first" >&2
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
  *) os=unknown ;;
esac
case "$ARCH" in
  x86_64|amd64) arch=x86_64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) arch=unknown ;;
esac

if [[ -f "$DIST/jk" ]]; then
  name="jk-${os}-${arch}"
  if command -v xz >/dev/null 2>&1; then
    xz -ck9 "$DIST/jk" >"$OUT/${name}.xz"
  else
    cp "$DIST/jk" "$OUT/$name"
  fi
elif [[ -f "$DIST/jk.exe" ]]; then
  name="jk-windows-x86_64"
  if command -v zip >/dev/null 2>&1; then
    (cd "$DIST" && zip -q "$OUT/${name}.zip" jk.exe)
  else
    cp "$DIST/jk.exe" "$OUT/${name}.exe"
  fi
else
  echo "assemble-release-dir: no native jk binary in $DIST" >&2
  exit 2
fi

engine="$(ls "$DIST"/lib/jk-engine-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$engine" ]]; then
  echo "assemble-release-dir: no engine jar under $DIST/lib" >&2
  exit 2
fi
cp "$engine" "$OUT/jk-engine-${VERSION}.jar"

# SHA256SUMS (coreutils format: hash two spaces name)
(
  cd "$OUT"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 * 2>/dev/null | grep -v SHA256SUMS | sed 's/  /  /' >SHA256SUMS
  else
    sha256sum * 2>/dev/null | grep -v SHA256SUMS >SHA256SUMS
  fi
)

if [[ -n "${JK_RELEASE_SIGNING_KEY:-}" ]]; then
  bash "$ROOT/scripts/sign-release.sh" "$OUT/SHA256SUMS"
else
  echo "assemble-release-dir: JK_RELEASE_SIGNING_KEY unset — SHA256SUMS.sig not written" >&2
fi

echo "assemble-release-dir: $OUT"
ls -la "$OUT"
