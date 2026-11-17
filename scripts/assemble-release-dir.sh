#!/usr/bin/env bash
# Assemble a versioned release directory from the dist layout for
# jumpkick.build via GCS + Firebase CDN).
# Expects a dist layout (native client + engine jar): `jk build` writes it under target/dist,
# which is the default; DIST_DIR names another one.
# Usage:
# scripts/assemble-release-dir.sh [out-dir]
# Env:
# JK_VERSION override version (default: the root jk.toml version)
# Layout written to out-dir (default: target/release/<version>/):
# jk-<os>-<arch>-<version>.xz (every platform, including Windows — self-update; the engine inflates it)
# jk-<os>-<arch>-<version>.gz (Linux and macOS — install.sh; gunzip)
# jk-windows-x86_64-<version>.zip (Windows only — install.ps1 / jk.bat)
# The version is part of every artifact name, so a signed manifest copied from another
# release directory cannot name what an installer asks for.
# jk-engine-<version>.jar
# jk-<version>.jar (the JVM client: every host a JDK 25 runs on and no native client is hosted for)
# jk-maven-spy-<version>.jar (the Maven core extension `jk mvn` attaches; a client self-fetches it)
# jk-whats-new-<version>.md (the release's highlights and the four before it, from
#   docs/user/whats-new.md; a self-update downloads it and shows what is new since the version it replaced;
#   a -SNAPSHOT tree has none)
# jk-repo-<version>.sha256 (the digest of every first-party jar and POM published on repo/ at this
#   version; the engine holds a worker it fetches to it). JK_REPO_STAGE_DIR, when set, names the
#   staged repo/ and adds what it holds beyond the dist's shelf.
# SHA256SUMS
# SHA256SUMS.sig (if JK_RELEASE_RSA_SIGNING_KEY or its file variant is set)
# The ../latest/ pointer (LATEST, the one signed object, and VERSION) is the caller's job:
# scripts/sign-latest-pointer.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${JK_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' jk.toml | head -1)"
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
  name="jk-windows-${arch}-${VERSION}"
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

# install.sh fetches gzip. -n omits the source name and mtime, so the same binary gzips to the
# same bytes. Windows install.ps1 fetches the zip below, so a Windows tree gets no .gz.
if [[ "$os" != "windows" ]]; then
  if ! command -v gzip >/dev/null 2>&1; then
    echo "assemble-release-dir: gzip is required (install.sh fetches .gz on Linux and macOS)" >&2
    exit 2
  fi
  gzip -cn9 "$src" >"$OUT/${name}.gz"
fi

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

# The highlights a self-update shows. Required of a release: release-notes.sh refuses a version with
# no entry, or one that breaks the writing rules, so a release cannot ship without them. A -SNAPSHOT
# is never a release (release-lane.sh refuses a tree that names one) and has no entry: its tree, such
# as sign-windows.yml's signing dry run, carries no what's-new file.
if [[ "$VERSION" == *-SNAPSHOT ]]; then
  echo "assemble-release-dir: $VERSION is a snapshot, not a release — jk-whats-new-${VERSION}.md not written" >&2
else
  bash "$ROOT/scripts/release-notes.sh" --whats-new "$VERSION" >"$OUT/jk-whats-new-${VERSION}.md"
fi

# The digest of every first-party jar and POM on repo/ at this version, from the shelf the dist
# carries (the jars the engine beside it was built with): `<sha256>  <Maven-layout path>`. The engine
# refuses a worker the official repository or a mirror serves unless it hashes to its line here, and
# SHA256SUMS lists this file, so the release signature covers every worker.
shelf="$DIST/repos/jk-local"
repo_sums="$OUT/jk-repo-${VERSION}.sha256"
if [[ ! -d "$shelf" ]]; then
  echo "assemble-release-dir: no shelf at $shelf (jk build writes it beside the engine)" >&2
  exit 2
fi
sha_line() { if command -v sha256sum >/dev/null 2>&1; then sha256sum -- "$1"; else shasum -a 256 -- "$1"; fi; }
version_files() { (cd "$1" && find . -type f \( -name "*-${VERSION}.jar" -o -name "*-${VERSION}.pom" \) | sed 's|^\./||' | LC_ALL=C sort); }
(cd "$shelf" && version_files . | while IFS= read -r f; do sha_line "$f"; done) >"$repo_sums"
# JK_REPO_STAGE_DIR names the repository staged for repo/ (publish-maven-repo.sh's stage). Every jar
# and POM it holds at this version is listed too, so the file covers all of repo/ at the version:
# a staged artifact the dist's shelf also carries must be the same bytes, and one the shelf lacks
# (the IntelliJ plugin, which no engine fetches) is listed from the stage.
if [[ -n "${JK_REPO_STAGE_DIR:-}" ]]; then
  if [[ ! -d "$JK_REPO_STAGE_DIR" ]]; then
    echo "assemble-release-dir: JK_REPO_STAGE_DIR $JK_REPO_STAGE_DIR is not a directory" >&2
    exit 2
  fi
  extra="$(mktemp "${TMPDIR:-/tmp}/jk-repo-extra.XXXXXX")"
  while IFS= read -r f; do
    staged="$(cd "$JK_REPO_STAGE_DIR" && sha_line "$f")"
    if listed="$(grep -F "  $f" "$repo_sums" | awk -v p="$f" '$2 == p')" && [[ -n "$listed" ]]; then
      if [[ "${listed%% *}" != "${staged%% *}" ]]; then
        rm -f "$extra"
        echo "assemble-release-dir: $f differs between the dist's shelf and $JK_REPO_STAGE_DIR; stage the repository from the pass that built the dist" >&2
        exit 2
      fi
    else
      printf '%s\n' "$staged" >>"$extra"
    fi
  done < <(version_files "$JK_REPO_STAGE_DIR")
  cat "$repo_sums" "$extra" | LC_ALL=C sort -k2 >"$repo_sums.tmp" && mv "$repo_sums.tmp" "$repo_sums"
  rm -f "$extra"
fi
if ! grep -q "/jk-test-runner-${VERSION}\.jar$" "$repo_sums"; then
  echo "assemble-release-dir: $shelf holds no jk-test-runner at $VERSION; the workers' digests would be missing" >&2
  exit 2
fi

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
