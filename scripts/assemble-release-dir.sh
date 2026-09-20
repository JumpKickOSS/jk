#!/usr/bin/env bash
# Assemble a versioned release directory from the dist layout for
# jumpkick.build via GCS + Firebase CDN).
# Expects a dist layout (native client + engine jar): `jk build` writes it under target/dist,
# which is the default; DIST_DIR names another one.
# Usage:
# scripts/assemble-release-dir.sh [out-dir]
# Env:
# JK_VERSION override version (default: JkVersion / project version via git describe or file)
# Layout written to out-dir (default: target/release/<version>/):
# jk-<os>-<arch>-<version>.xz (every platform, including Windows — self-update)
# jk-windows-x86_64-<version>.zip (Windows only — install.ps1 / jk.bat; no system xz)
# The version is part of every artifact name, so a signed manifest copied from another
# release directory cannot name what an installer asks for.
# jk-engine-<version>.jar
# jk-<version>.jar (the JVM client: every host a JDK 25 runs on and no native client is hosted for)
# jk-maven-spy-<version>.jar (the Maven core extension `jk mvn` attaches; a client self-fetches it)
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
OUT="${1:-target/release/$VERSION}"
DIST="${DIST_DIR:-target/dist}"

if [[ ! -d "$DIST" ]]; then
  echo "assemble-release-dir: missing $DIST — run jk build (it writes target/dist) first" >&2
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

# Windows wrapper / install.ps1 have no system xz — also ship a single-entry zip. Git Bash ships
# no zip either, so PowerShell's Compress-Archive writes it there.
if [[ "$os" == "windows" ]]; then
  if command -v zip >/dev/null 2>&1; then
    (cd "$DIST" && zip -q "$OUT/${name}.zip" "$(basename "$src")")
  elif command -v powershell.exe >/dev/null 2>&1; then
    src_win="$(cygpath -w "$src")"
    zip_win="$(cygpath -w "$OUT/${name}.zip")"
    powershell.exe -NoProfile -NonInteractive -Command \
      "\$ErrorActionPreference = 'Stop'; Compress-Archive -LiteralPath '$src_win' -DestinationPath '$zip_win' -Force"
  else
    echo "assemble-release-dir: zip or powershell.exe is required on Windows (jk.bat / install.ps1)" >&2
    exit 2
  fi
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

# The JVM client jar, under its shipped name already (the dist script drops the assembly classifier).
client_jar="$DIST/lib/jk-${VERSION}.jar"
if [[ ! -f "$client_jar" ]]; then
  echo "assemble-release-dir: no JVM client jar at $client_jar (jk build writes it from the CLI assembly)" >&2
  exit 2
fi
cp "$client_jar" "$OUT/jk-${VERSION}.jar"

# The Maven spy, under its shipped name already. Required: a release without it is a `jk mvn`
# that writes no run report, and the client self-fetches it by this exact name.
spy_jar="$DIST/lib/jk-maven-spy-${VERSION}.jar"
if [[ ! -f "$spy_jar" ]]; then
  echo "assemble-release-dir: no Maven spy jar at $spy_jar (jk build writes it from clients/maven-spy)" >&2
  exit 2
fi
cp "$spy_jar" "$OUT/jk-maven-spy-${VERSION}.jar"

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
