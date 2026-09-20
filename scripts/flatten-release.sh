#!/usr/bin/env bash
# One release tree from the per-platform trees the release matrix produced.
#
# Usage:
#   scripts/flatten-release.sh <staging> <out> <version>
# <staging> holds one directory per platform, `release-<version>-<os>-<arch>/`, each written by
# scripts/assemble-release-dir.sh. <out> receives every platform's client plus the three
# platform-neutral jars — the engine jar, the JVM client jar and the Maven spy jar — from
# linux-x86_64 alone: every platform builds the same jars, and one whose bytes differ is a build
# to explain, not a copy to overwrite — and a fresh SHA256SUMS over the result. The per-platform
# SHA256SUMS and .sig are dropped; the combined manifest is signed by the caller.
#
# Refused, each by name: a missing canonical jar, a jar whose sha differs from the canonical one,
# two trees carrying one file name, and a tree missing any required client or, when Windows is
# among them, the Windows zip. A partial matrix is not a release.
#
# JK_RELEASE_PLATFORMS names the required clients, space-separated (`<os>-<arch>`); the release
# workflow sets it to the rows of its build matrix. Unset, every platform jk has a client for.
set -euo pipefail

STAGING="${1:?usage: flatten-release.sh <staging> <out> <version>}"
OUT="${2:?usage: flatten-release.sh <staging> <out> <version>}"
VER="${3:?usage: flatten-release.sh <staging> <out> <version>}"
read -r -a PLATFORMS <<<"${JK_RELEASE_PLATFORMS:-linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64 windows-x86_64}"
if [[ ${#PLATFORMS[@]} -eq 0 || ! " ${PLATFORMS[*]} " == *" linux-x86_64 "* ]]; then
  echo "flatten-release: JK_RELEASE_PLATFORMS must name linux-x86_64 (the platform-neutral jars come from it); got '${JK_RELEASE_PLATFORMS:-}'" >&2
  exit 1
fi
ENGINE="jk-engine-${VER}.jar"
CLIENT_JAR="jk-${VER}.jar"
SPY_JAR="jk-maven-spy-${VER}.jar"

if [[ ! -d "$STAGING" ]]; then
  echo "flatten-release: staging directory not found: $STAGING" >&2
  exit 1
fi
mkdir -p "$OUT"

canonical_dir="$STAGING/release-${VER}-linux-x86_64"
canonical_engine="$canonical_dir/$ENGINE"
canonical_client="$canonical_dir/$CLIENT_JAR"
canonical_spy="$canonical_dir/$SPY_JAR"
if [[ ! -f "$canonical_engine" ]]; then
  echo "missing $canonical_engine — the linux-x86_64 build did not produce the engine jar" >&2
  exit 1
fi
if [[ ! -f "$canonical_client" ]]; then
  echo "missing $canonical_client — the linux-x86_64 build did not produce the JVM client jar" >&2
  exit 1
fi
if [[ ! -f "$canonical_spy" ]]; then
  echo "missing $canonical_spy — the linux-x86_64 build did not produce the Maven spy jar" >&2
  exit 1
fi
engine_sha="$(sha256sum "$canonical_engine" | awk '{print $1}')"
client_sha="$(sha256sum "$canonical_client" | awk '{print $1}')"
spy_sha="$(sha256sum "$canonical_spy" | awk '{print $1}')"

# A platform-neutral jar: its bytes must match linux-x86_64's, and only that copy is taken.
same_as_canonical() {
  local f="$1" canonical="$2" want="$3" what="$4" sha
  sha="$(sha256sum "$f" | awk '{print $1}')"
  if [[ "$sha" != "$want" ]]; then
    echo "$f differs from $canonical ($sha vs $want) — the platforms built different $what" >&2
    exit 1
  fi
  [[ "$f" == "$canonical" ]]
}

while IFS= read -r -d '' f; do
  base="$(basename "$f")"
  case "$base" in
    SHA256SUMS|SHA256SUMS.sig) continue ;;
    "$ENGINE") same_as_canonical "$f" "$canonical_engine" "$engine_sha" "engines" || continue ;;
    "$CLIENT_JAR") same_as_canonical "$f" "$canonical_client" "$client_sha" "JVM clients" || continue ;;
    "$SPY_JAR") same_as_canonical "$f" "$canonical_spy" "$spy_sha" "Maven spies" || continue ;;
  esac
  if [[ -e "$OUT/$base" ]]; then
    echo "two platform trees carry $base — the flatten would pick one by listing order" >&2
    exit 1
  fi
  cp -f "$f" "$OUT/$base"
done < <(find "$STAGING" -type f -print0)

missing=0
for client in "${PLATFORMS[@]}"; do
  if [[ ! -f "$OUT/jk-${client}-${VER}.xz" ]]; then
    echo "missing client jk-${client}-${VER}.xz — the ${client} build did not finish" >&2
    missing=$((missing + 1))
  fi
  if [[ "$client" == windows-* && ! -f "$OUT/jk-${client}-${VER}.zip" ]]; then
    echo "missing jk-${client}-${VER}.zip" >&2
    missing=$((missing + 1))
  fi
done
if [[ "$missing" -gt 0 ]]; then
  echo "$missing artifact(s) missing: a partial matrix is not a release; re-run the failed builds and this job" >&2
  exit 1
fi

(
  cd "$OUT"
  files=(*)
  sha256sum -- "${files[@]}" >SHA256SUMS
)
echo "=== $OUT ==="
ls -la "$OUT"
