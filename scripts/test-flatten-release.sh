#!/usr/bin/env bash
# Network-free fixtures for scripts/flatten-release.sh: the complete matrix flattens to one signed-ready tree; a
# missing platform, an engine jar whose bytes differ and two trees carrying one file name are each refused by name.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-flatten-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
VER="1.0.0"
PLATFORMS=(linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64 windows-x86_64)

# A staging tree as the release matrix uploads it: one directory per platform holding that platform's client
# archive(s), the engine jar every platform builds, and the per-platform manifest the flatten discards.
stage() {
  local staging="$1"
  rm -rf "$staging"
  for platform in "${PLATFORMS[@]}"; do
    local dir="$staging/release-$VER-$platform"
    mkdir -p "$dir"
    printf 'client %s\n' "$platform" >"$dir/jk-$platform-$VER.xz"
    [[ "$platform" == "windows-x86_64" ]] && printf 'client zip\n' >"$dir/jk-$platform-$VER.zip"
    printf 'engine bytes\n' >"$dir/jk-engine-$VER.jar"
    printf 'per-platform manifest\n' >"$dir/SHA256SUMS"
    printf 'per-platform signature\n' >"$dir/SHA256SUMS.sig"
  done
}

run_flatten() {
  "$ROOT/scripts/flatten-release.sh" "$1" "$2" "$VER" >"$WORK/last.log" 2>&1
}

# Asserts the flatten of $1 fails and its output names the cause $2.
assert_refused() {
  local staging="$1" expected="$2" out="$WORK/out-refused"
  rm -rf "$out"
  if run_flatten "$staging" "$out"; then
    cat "$WORK/last.log" >&2
    echo "test-flatten-release: '$expected' was accepted" >&2
    exit 1
  fi
  grep -qF -- "$expected" "$WORK/last.log" || {
    cat "$WORK/last.log" >&2
    echo "test-flatten-release: refused, but not for '$expected'" >&2
    exit 1
  }
}

# ---- complete matrix ------------------------------------------------------------------------
stage "$WORK/staging"
run_flatten "$WORK/staging" "$WORK/out" || { cat "$WORK/last.log" >&2; echo "test-flatten-release: complete matrix was refused" >&2; exit 1; }
expected_listing="SHA256SUMS
jk-engine-$VER.jar
jk-linux-aarch64-$VER.xz
jk-linux-x86_64-$VER.xz
jk-macos-aarch64-$VER.xz
jk-macos-x86_64-$VER.xz
jk-windows-x86_64-$VER.xz
jk-windows-x86_64-$VER.zip"
[[ "$(cd "$WORK/out" && printf '%s\n' * | LC_ALL=C sort)" == "$expected_listing" ]] || {
  (cd "$WORK/out" && printf '%s\n' *) >&2
  echo "test-flatten-release: the flattened tree does not hold exactly the seven artifacts plus SHA256SUMS" >&2
  exit 1
}
[[ ! -e "$WORK/out/SHA256SUMS.sig" ]] || { echo "test-flatten-release: a per-platform signature leaked into the tree" >&2; exit 1; }
# The manifest names every artifact once, in coreutils form, and nothing else.
[[ "$(wc -l <"$WORK/out/SHA256SUMS" | tr -d '[:space:]')" == "7" ]] || {
  cat "$WORK/out/SHA256SUMS" >&2
  echo "test-flatten-release: SHA256SUMS does not hold seven entries" >&2
  exit 1
}
grep -vqE '^[0-9a-f]{64}  [A-Za-z0-9][A-Za-z0-9._-]*$' "$WORK/out/SHA256SUMS" && {
  cat "$WORK/out/SHA256SUMS" >&2
  echo "test-flatten-release: SHA256SUMS has a line that is not '<hex>  <name>'" >&2
  exit 1
}
grep -q "  SHA256SUMS$" "$WORK/out/SHA256SUMS" && { echo "test-flatten-release: SHA256SUMS lists itself" >&2; exit 1; }
(cd "$WORK/out" && sha256sum -c --quiet SHA256SUMS) || { echo "test-flatten-release: SHA256SUMS does not verify" >&2; exit 1; }
cmp -s "$WORK/staging/release-$VER-linux-x86_64/jk-engine-$VER.jar" "$WORK/out/jk-engine-$VER.jar" || {
  echo "test-flatten-release: the engine jar is not the linux-x86_64 one" >&2
  exit 1
}

# ---- a missing platform ---------------------------------------------------------------------
stage "$WORK/staging"
rm -rf "$WORK/staging/release-$VER-macos-aarch64"
assert_refused "$WORK/staging" "missing client jk-macos-aarch64-$VER.xz — the macos-aarch64 build did not finish"
grep -q "1 artifact(s) missing: a partial matrix is not a release" "$WORK/last.log" || {
  cat "$WORK/last.log" >&2
  echo "test-flatten-release: the missing-platform count was not reported" >&2
  exit 1
}

stage "$WORK/staging"
rm -f "$WORK/staging/release-$VER-windows-x86_64/jk-windows-x86_64-$VER.zip"
assert_refused "$WORK/staging" "missing jk-windows-x86_64-$VER.zip"

stage "$WORK/staging"
rm -rf "$WORK/staging/release-$VER-linux-x86_64"
assert_refused "$WORK/staging" "the linux-x86_64 build did not produce the engine jar"

# ---- an engine jar whose bytes differ -------------------------------------------------------
stage "$WORK/staging"
printf 'different engine bytes\n' >"$WORK/staging/release-$VER-macos-x86_64/jk-engine-$VER.jar"
assert_refused "$WORK/staging" "the platforms built different engines"
grep -q "release-$VER-macos-x86_64/jk-engine-$VER.jar differs from" "$WORK/last.log" || {
  cat "$WORK/last.log" >&2
  echo "test-flatten-release: the differing engine jar was not named" >&2
  exit 1
}

# ---- two trees carrying one name ------------------------------------------------------------
stage "$WORK/staging"
cp "$WORK/staging/release-$VER-linux-x86_64/jk-linux-x86_64-$VER.xz" "$WORK/staging/release-$VER-linux-aarch64/"
assert_refused "$WORK/staging" "two platform trees carry jk-linux-x86_64-$VER.xz"

# ---- no staging directory at all -----------------------------------------------------------
assert_refused "$WORK/nowhere" "staging directory not found"

echo "test-flatten-release: ok"
