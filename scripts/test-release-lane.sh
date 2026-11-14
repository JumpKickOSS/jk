#!/usr/bin/env bash
# Fixture test for scripts/release-lane.sh, network-free: a throwaway checkout tagged v9.8.7 whose
# bootstrap, takeover, repo stage and assembly are stubs, and a jk on PATH that writes a dist. A lane
# whose every step succeeds reports itself done; a lane whose assembly fails after writing part of
# its tree must fail, never print "== lane … done", and name the log.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/jk-release-lane-test.XXXXXX")" && pwd)"
trap 'rm -rf "$WORK"' EXIT
V=9.8.7

fail() {
  echo "test-release-lane: $1" >&2
  [[ -f "$WORK/out.log" ]] && cat "$WORK/out.log" >&2
  exit 1
}
g() { git -C "$REPO" -c user.name=t -c user.email=t@localhost -c commit.gpgsign=false "$@"; }

REPO="$WORK/repo"
mkdir -p "$REPO/scripts" "$WORK/bin"
cp "$ROOT/scripts/release-lane.sh" "$ROOT/scripts/release-version.sh" "$REPO/scripts/"
printf 'version = "%s"\n' "$V" >"$REPO/jk.toml"
for s in ci-bootstrap ci-takeover; do printf '#!/usr/bin/env bash\necho "%s ok"\n' "$s" >"$REPO/scripts/$s.sh"; done
# The repo stage: one jar.
cat >"$REPO/scripts/publish-maven-repo.sh" <<'SH'
#!/usr/bin/env bash
d="$JK_MAVEN_STAGE_DIR/cc/jumpkick/jk-x/9.8.7"
mkdir -p "$d" && printf 'x\n' >"$d/jk-x-9.8.7.jar"
SH
# The assembly: the engine jar stamped with HEAD and the digest list, then, when asked, a failure
# (as release-notes.sh stops it after the jars are copied).
cat >"$REPO/scripts/assemble-release-dir.sh" <<'SH'
#!/usr/bin/env bash
out="$1"; mkdir -p "$out"
python3 -I - "$out/jk-engine-9.8.7.jar" "$(git rev-parse HEAD)" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBuild-Commit: " + sys.argv[2] + "\n")
PY
(cd "$JK_REPO_STAGE_DIR" 2>/dev/null && sha256sum cc/jumpkick/jk-x/9.8.7/jk-x-9.8.7.jar) >"$out/jk-repo-9.8.7.sha256" || :
if [[ -n "${FIXTURE_ASSEMBLE_FAILS:-}" ]]; then echo "release-notes: 9.8.7 has 14 bullets; pick one to five" >&2; exit 2; fi
echo assembled
SH
cat >"$WORK/bin/jk" <<'SH'
#!/usr/bin/env bash
case "$1" in
  build) mkdir -p target/dist/lib ;;
  engine) ;;
esac
exit 0
SH
cat >"$WORK/bin/uname" <<'SH'
#!/usr/bin/env sh
case "${1:-}" in -s) echo Linux ;; -m) echo x86_64 ;; *) echo Linux ;; esac
SH
chmod +x "$REPO"/scripts/*.sh "$WORK/bin/jk" "$WORK/bin/uname"
g init -q -b main && g add -A && g commit -q -m "release $V" && g tag -a "v$V" -m "jk $V"

lane() { (cd "$REPO" && env PATH="$WORK/bin:$PATH" "$@" bash scripts/release-lane.sh "$WORK/lane") >"$WORK/out.log" 2>&1; }

lane || fail "a lane whose steps all succeed failed"
grep -q "== lane linux-x86_64 done" "$WORK/lane/lane.log" || fail "the passing lane did not report itself done"
echo "ok   a lane whose steps succeed reports itself done"

if lane FIXTURE_ASSEMBLE_FAILS=1; then fail "a lane whose assembly failed exited 0"; fi
if grep -q "== lane .* done" "$WORK/lane/lane.log"; then fail "a lane whose assembly failed reported itself done"; fi
grep -q "release-lane: linux-x86_64 failed" "$WORK/out.log" || fail "the failed lane did not say so"
grep -q "14 bullets" "$WORK/out.log" || fail "the failed lane did not show the cause"
echo "ok   a lane whose assembly fails stops there and fails"

echo "test-release-lane: ok"
