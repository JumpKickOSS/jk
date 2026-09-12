#!/usr/bin/env bash
# One release tree from the per-platform trees the release matrix produced.
#
# Usage:
#   scripts/flatten-release.sh <staging> <out> <version>
# <staging> holds one directory per platform, `release-<version>-<os>-<arch>/`, each written by
# scripts/assemble-release-dir.sh. <out> receives every platform's client plus the engine jar
# from linux-x86_64 alone — every platform builds the same jar, and one whose bytes differ is a
# build to explain, not a copy to overwrite — and a fresh SHA256SUMS over the result. The
# per-platform SHA256SUMS and .sig are dropped; the combined manifest is signed by the caller.
#
# Refused, each by name: a missing canonical engine jar, an engine jar whose sha differs from the
# canonical one, two trees carrying one file name, and a tree missing any of the five clients or
# the Windows zip. A partial matrix is not a release.
set -euo pipefail

STAGING="${1:?usage: flatten-release.sh <staging> <out> <version>}"
OUT="${2:?usage: flatten-release.sh <staging> <out> <version>}"
VER="${3:?usage: flatten-release.sh <staging> <out> <version>}"
ENGINE="jk-engine-${VER}.jar"

if [[ ! -d "$STAGING" ]]; then
  echo "flatten-release: staging directory not found: $STAGING" >&2
  exit 1
fi
mkdir -p "$OUT"

canonical="$STAGING/release-${VER}-linux-x86_64/${ENGINE}"
if [[ ! -f "$canonical" ]]; then
  echo "missing $canonical — the linux-x86_64 build did not produce the engine jar" >&2
  exit 1
fi
canonical_sha="$(sha256sum "$canonical" | awk '{print $1}')"

while IFS= read -r -d '' f; do
  base="$(basename "$f")"
  case "$base" in
    SHA256SUMS|SHA256SUMS.sig) continue ;;
    "$ENGINE")
      sha="$(sha256sum "$f" | awk '{print $1}')"
      if [[ "$sha" != "$canonical_sha" ]]; then
        echo "$f differs from $canonical ($sha vs $canonical_sha) — the platforms built different engines" >&2
        exit 1
      fi
      [[ "$f" == "$canonical" ]] || continue
      ;;
  esac
  if [[ -e "$OUT/$base" ]]; then
    echo "two platform trees carry $base — the flatten would pick one by listing order" >&2
    exit 1
  fi
  cp -f "$f" "$OUT/$base"
done < <(find "$STAGING" -type f -print0)

missing=0
for client in linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64 windows-x86_64; do
  if [[ ! -f "$OUT/jk-${client}-${VER}.xz" ]]; then
    echo "missing client jk-${client}-${VER}.xz — the ${client} build did not finish" >&2
    missing=$((missing + 1))
  fi
done
[[ -f "$OUT/jk-windows-x86_64-${VER}.zip" ]] || { echo "missing jk-windows-x86_64-${VER}.zip" >&2; missing=$((missing + 1)); }
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
