#!/usr/bin/env bash
# Fixture test for scripts/assemble-release-dir.sh, network-free: a fake dist (client, engine, JVM
# client, Maven spy, a shelf) assembled into a release directory. jk-repo-<version>.sha256 lists the
# shelf's jars and POMs at the version and SHA256SUMS lists it. With JK_REPO_STAGE_DIR, an artifact
# staged for repo/ that the shelf lacks is listed from the stage, and one staged with bytes other
# than the shelf's is refused.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/jk-assemble-test.XXXXXX")" && pwd)"
trap 'rm -rf "$WORK"' EXIT
V=9.8.7

fail() {
  echo "test-assemble-release-dir: $1" >&2
  [[ -f "$WORK/out.log" ]] && cat "$WORK/out.log" >&2
  exit 1
}

DIST="$WORK/dist"
mkdir -p "$DIST/lib" "$WORK/stage"
printf 'native client\n' >"$DIST/jk"
printf 'engine\n' >"$DIST/lib/jk-engine-$V.jar"
printf 'jvm client\n' >"$DIST/lib/jk-$V.jar"
printf 'spy\n' >"$DIST/lib/jk-maven-spy-$V.jar"
shelve() { # <root> <artifact> <content>
  local d="$1/cc/jumpkick/$2/$V"
  mkdir -p "$d"
  printf '%s jar\n' "$3" >"$d/$2-$V.jar"
  printf '<project>%s</project>\n' "$3" >"$d/$2-$V.pom"
}
shelve "$DIST/repos/jk-local" jk-test-runner runner
shelve "$DIST/repos/jk-local" jk-java-compiler compiler
cat >"$WORK/whats-new.md" <<EOF
# What's new

## $V

- **Fixture builds assemble into one release directory.** The digests of every first-party jar and POM ride along, listed in the signed sums.
EOF

assemble() { # <out> [VAR=value...]
  local out="$1"
  shift
  env DIST_DIR="$DIST" JK_VERSION="$V" RELEASE_NOTES_DOC="$WORK/whats-new.md" "$@" \
    bash "$ROOT/scripts/assemble-release-dir.sh" "$out" >"$WORK/out.log" 2>&1
}
check_sums() { if command -v sha256sum >/dev/null 2>&1; then sha256sum --check --strict --quiet "$1"; else shasum -a 256 --check --quiet "$1"; fi; }
listed() { awk '{print $2}' "$1/jk-repo-$V.sha256" | paste -sd' ' -; }

assemble "$WORK/out" || fail "the plain assembly failed"
[[ "$(listed "$WORK/out")" == "cc/jumpkick/jk-java-compiler/$V/jk-java-compiler-$V.jar cc/jumpkick/jk-java-compiler/$V/jk-java-compiler-$V.pom cc/jumpkick/jk-test-runner/$V/jk-test-runner-$V.jar cc/jumpkick/jk-test-runner/$V/jk-test-runner-$V.pom" ]] \
  || fail "jk-repo does not list the shelf: $(listed "$WORK/out")"
grep -q "  jk-repo-$V.sha256\$" "$WORK/out/SHA256SUMS" || fail "SHA256SUMS does not list jk-repo-$V.sha256"
(cd "$DIST/repos/jk-local" && check_sums "$WORK/out/jk-repo-$V.sha256") || fail "jk-repo digests do not match the shelf"
echo "ok   the shelf is listed and the sums list the file"

# The stage holds what the shelf holds plus an artifact no engine fetches (the IntelliJ plugin).
shelve "$WORK/stage" jk-test-runner runner
shelve "$WORK/stage" jk-java-compiler compiler
shelve "$WORK/stage" jumpkick-intellij ide
assemble "$WORK/out-staged" JK_REPO_STAGE_DIR="$WORK/stage" || fail "the assembly with a stage failed"
listed "$WORK/out-staged" | grep -q "cc/jumpkick/jumpkick-intellij/$V/jumpkick-intellij-$V.jar cc/jumpkick/jumpkick-intellij/$V/jumpkick-intellij-$V.pom" \
  || fail "a staged artifact the shelf lacks is not listed: $(listed "$WORK/out-staged")"
[[ "$(wc -l <"$WORK/out-staged/jk-repo-$V.sha256")" -eq 6 ]] || fail "a staged artifact the shelf also holds is listed twice"
(cd "$WORK/stage" && check_sums "$WORK/out-staged/jk-repo-$V.sha256") || fail "jk-repo does not match the stage"
echo "ok   a staged artifact the shelf lacks is listed from the stage"

# A staged artifact whose bytes differ from the shelf's is a stage from another pass: refused.
printf 'other runner jar\n' >"$WORK/stage/cc/jumpkick/jk-test-runner/$V/jk-test-runner-$V.jar"
if assemble "$WORK/out-diverged" JK_REPO_STAGE_DIR="$WORK/stage"; then fail "a stage that differs from the shelf was accepted"; fi
grep -q "jk-test-runner-$V.jar differs between the dist's shelf and" "$WORK/out.log" || fail "the divergent artifact was not named"
echo "ok   a stage that differs from the shelf is refused"

echo "test-assemble-release-dir: ok"
