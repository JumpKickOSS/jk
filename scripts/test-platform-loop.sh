#!/usr/bin/env bash
# Fixtures for the Windows and macOS runs beside the Full tier, in scratch repositories with a
# stand-in `jk` and `ka`. scripts/gate/platform-run.sh makes its own checkout and home, runs the fast
# tier, each test class with an OS condition by module, and the product smoke, and says which steps
# were red; a second run on the same directory is refused while the first holds it.
# scripts/gate/platform-loop.sh runs the newest tip the Full loop took, once; a red run files one
# ticket, comments on it when the failures change and when the platform is green again. A trailing
# platform does not hold green; one green for a week holds it, so green waits for its verdict and
# moves when it passes (scripts/gate/green.sh).
set -euo pipefail

unset "${!GATE_@}" "${!FULL_@}" "${!QUEUE_@}" "${!PLATFORM_@}" "${!GREEN_@}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-platform.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "test-platform-loop: $*" >&2
  exit 1
}

export GIT_AUTHOR_NAME=dev GIT_AUTHOR_EMAIL=dev@example.invalid
export GIT_COMMITTER_NAME=dev GIT_COMMITTER_EMAIL=dev@example.invalid
commit() { # <repo> <file> <text>: one commit that writes the file
  mkdir -p "$(dirname "$1/$2")"
  echo "$3" >"$1/$2"
  git -C "$1" add "$2"
  git -C "$1" commit -qm "$3"
}

git init -q --bare -b main "$WORK/origin.git"
git clone -q "$WORK/origin.git" "$WORK/dev" 2>/dev/null
dev="$WORK/dev"

# --- platform-run.sh: a tree whose CI scripts and jk are stand-ins that log what they were asked ---
# The bootstrap makes the home's jk; `jk test -m <module>` fails when the tree carries
# fail-<module>.txt (slashes as dashes), and `jk test` of every module when it carries fail-fast.txt.
mkdir -p "$dev/scripts"
cat >"$dev/scripts/ci-bootstrap.sh" <<'BOOT'
#!/usr/bin/env bash
mkdir -p "$JK_HOME/bin"
cat >"$JK_HOME/bin/jk" <<'JK'
#!/usr/bin/env bash
echo "$*" >>"$JK_HOME/../jk.log"
case "$1" in
  test)
    m=fast
    while [ $# -gt 0 ]; do [ "$1" = -m ] && m="$2"; shift; done
    [ -f "fail-${m//\//-}.txt" ] && { echo "FAIL $m"; exit 1; }
    ;;
esac
exit 0
JK
chmod +x "$JK_HOME/bin/jk"
echo bootstrap >>"$JK_HOME/../jk.log"
BOOT
cat >"$dev/scripts/ci-takeover.sh" <<'TAKE'
#!/usr/bin/env bash
echo takeover >>"$JK_HOME/../jk.log"
TAKE
cat >"$dev/scripts/ci-product-smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
"$JK_BIN" new smoke-app
SMOKE
commit "$dev" core/src/test/java/cc/example/WinPathTest.java '@EnabledOnOs(OS.WINDOWS) class WinPathTest {}'
commit "$dev" core/src/test/java/cc/example/PlainTest.java 'class PlainTest {}'
commit "$dev" clients/cli/src/test/java/cc/example/MacWatchTest.java 'boolean mac = System.getProperty("os.name").contains("Mac");'
commit "$dev" core/src/test/java/cc/example/OsSupport.java 'boolean windows = isWindows();'
commit "$dev" core/src/test/java/cc/example/MacNetworkTest.java '@Tag("network") @EnabledOnOs(OS.MAC) class MacNetworkTest {}'
git -C "$dev" add scripts && git -C "$dev" commit -qm "scripts"
git -C "$dev" push -q origin HEAD:main
tip="$(git -C "$dev" rev-parse HEAD)"

pd="$WORK/pd"
out="$(PLATFORM_DIR="$pd" bash "$ROOT/scripts/gate/platform-run.sh" macos "$tip" "$WORK/origin.git" 2>&1)" || fail "the run exited red: $out"
[[ "$(tail -1 <<<"$out")" == "PLATFORM GREEN $tip" ]] || fail "a green run did not say so: $out"
{ grep -qx 'bootstrap' "$pd/jk.log" && grep -qx 'takeover' "$pd/jk.log"; } || fail "a new home was not bootstrapped: $(cat "$pd/jk.log")"
grep -qx 'test -c --no-ansi' "$pd/jk.log" || fail "the fast tier did not run: $(cat "$pd/jk.log")"
grep -qx 'test -A -c --no-ansi -m core --class cc.example.WinPathTest' "$pd/jk.log" || fail "core's OS class did not run alone: $(cat "$pd/jk.log")"
grep -qx 'test -A -c --no-ansi -m clients/cli --class cc.example.MacWatchTest' "$pd/jk.log" || fail "clients/cli's OS class did not run: $(cat "$pd/jk.log")"
grep -q 'PlainTest\|OsSupport\|MacNetworkTest' "$pd/jk.log" && fail "a class with no OS condition, no test, or of the network tier ran: $(cat "$pd/jk.log")"
grep -qx 'new smoke-app' "$pd/jk.log" || fail "the product smoke did not run: $(cat "$pd/jk.log")"
grep -q '^OS_CLASSES 2 in 2 modules$' <<<"$out" || fail "the run did not count the OS classes: $out"

# A home that exists installs the commit, without a new bootstrap; the red steps are named.
commit "$dev" fail-clients-cli.txt "breaks clients/cli's OS class"
commit "$dev" fail-fast.txt "breaks the fast tier"
git -C "$dev" push -q origin HEAD:main
red="$(git -C "$dev" rev-parse HEAD)"
: >"$pd/jk.log"
out="$(PLATFORM_DIR="$pd" bash "$ROOT/scripts/gate/platform-run.sh" macos "$red" "$WORK/origin.git" 2>&1)" || fail "the red run exited red: $out"
[[ "$(tail -1 <<<"$out")" == "PLATFORM RED $red: fast, os-clients-cli" ]] || fail "a red run did not name its red steps: $out"
grep -q '^FAIL clients/cli$' <<<"$out" || fail "a red step's FAIL line was not printed: $out"
grep -qx 'install -S --no-ansi' "$pd/jk.log" || fail "the existing home did not install the commit: $(cat "$pd/jk.log")"
grep -qx bootstrap "$pd/jk.log" && fail "an existing home was bootstrapped again"

# A run while another holds the directory runs nothing.
mkdir "$pd/run.lock" && echo $$ >"$pd/run.lock/pid"
out="$(PLATFORM_DIR="$pd" bash "$ROOT/scripts/gate/platform-run.sh" macos "$tip" "$WORK/origin.git" 2>&1)"
[[ "$out" == "PLATFORM BUSY: run $$ holds $pd" ]] || fail "a second run was not refused: $out"
rm -rf "$pd/run.lock"

# --- platform-loop.sh: the stand-in run is green unless the commit carries win-red.txt ---
cat >"$WORK/run.sh" <<'RUN'
#!/usr/bin/env bash
cat >/dev/null
echo "$2" >>"$RUNS"
if git -C "$ORIGIN" ls-tree -r --name-only "$2" | grep -qx win-red.txt; then
  echo "STEP fast EXIT=1"
  echo "FAIL cc.example.$(git -C "$ORIGIN" show "$2:win-red.txt")"
  echo "PLATFORM RED $2: fast"
else
  echo "PLATFORM GREEN $2"
fi
RUN
mkdir -p "$WORK/bin"
cat >"$WORK/bin/ka" <<'KA'
#!/usr/bin/env bash
printf '%s|' "$@" | tr '\n' ' ' >>"$KA_LOG"
echo >>"$KA_LOG"
case "$1" in
  new) echo '{"id":"TK-PLAT1"}' ;;
  show) echo "{\"status\":\"$(cat "$KA_STATUS" 2>/dev/null || echo ready)\"}" ;;
esac
KA
chmod +x "$WORK/run.sh" "$WORK/bin/ka"
git clone -q "$WORK/origin.git" "$WORK/pco"
export PATH="$WORK/bin:$PATH" KA_LOG="$WORK/ka.log" KA_STATUS="$WORK/ka-status" RUNS="$WORK/runs" ORIGIN="$WORK/origin.git"
export QUEUE_REPO="$WORK/queue" PLATFORM_CHECKOUT="$WORK/pco" PLATFORM_RUN="$WORK/run.sh" PLATFORM_LOG_DIR="$WORK/logs"
: >"$KA_LOG"
: >"$RUNS"
mkdir -p "$QUEUE_REPO"
loop() { bash "$ROOT/scripts/gate/platform-loop.sh" windows --once; }
origin() { git -C "$WORK/origin.git" rev-parse -q --verify "$1"; }
# take <sha> <GREEN|RED>: the Full loop took the tip and judged it, as full-loop.sh writes both.
take() {
  echo "$1" >>"$QUEUE_REPO/full-taken"
  echo "$1 $2 f$(wc -l <"$QUEUE_REPO/full-taken" | tr -d ' ')" >>"$QUEUE_REPO/full-verdicts"
}
land() { # <file> <text>: a commit on origin/main; prints its sha
  commit "$dev" "$1" "$2" && git -C "$dev" push -q origin HEAD:main && git -C "$dev" rev-parse HEAD
}

# Trailing: a red Windows run files one ticket and does not hold green.
a="$(land win-red.txt WinPathTest)"
take "$a" GREEN
loop
grep -q "^$a windows RED " "$QUEUE_REPO/platform-verdicts" || fail "the red run was not recorded: $(cat "$QUEUE_REPO/platform-verdicts")"
[[ "$(origin green)" == "$a" ]] || fail "a trailing platform's red held green: $(cat "$QUEUE_REPO/platform-runs.log")"
[[ "$(grep -c '^new|' "$KA_LOG")" -eq 1 ]] || fail "the red run filed no ticket: $(cat "$KA_LOG")"
grep -q '^new|.*-label|platform-windows|.*FAIL cc.example.WinPathTest' "$KA_LOG" || fail "the ticket does not say what failed: $(cat "$KA_LOG")"
loop
[[ "$(wc -l <"$RUNS")" -eq 1 ]] || fail "a tip with a verdict ran again"

# The same failures on the next tip say nothing more; other failures comment on the open ticket.
take "$(land other.txt other)" GREEN
loop
[[ "$(grep -c '^\(new\|comment\)|' "$KA_LOG")" -eq 1 ]] || fail "the same failures were told again: $(cat "$KA_LOG")"
take "$(land win-red.txt MacWatchTest)" GREEN
loop
[[ "$(grep -c '^new|' "$KA_LOG")" -eq 1 ]] || fail "an open ticket got a second one: $(cat "$KA_LOG")"
grep -q '^comment|TK-PLAT1|.*MacWatchTest' "$KA_LOG" || fail "the open ticket was not told the new failures: $(cat "$KA_LOG")"

# Green again: the ticket is told; the platform still trails.
git -C "$dev" rm -q win-red.txt && git -C "$dev" commit -qm fixed && git -C "$dev" push -q origin HEAD:main
g1="$(git -C "$dev" rev-parse HEAD)"
take "$g1" GREEN
loop
grep -q '^comment|TK-PLAT1|.*is green on daybreak' "$KA_LOG" || fail "the ticket was not told the platform is green: $(cat "$KA_LOG")"
[[ -f "$QUEUE_REPO/platform-windows-blocking" ]] && fail "a platform green for minutes holds green"

# Green for a week: it holds green from then on.
sed -i -E "s/^($g1 windows GREEN) [0-9]+/\1 $(($(date +%s) - 8 * 86400))/" "$QUEUE_REPO/platform-verdicts"
take "$(land week.txt week)" GREEN
loop
[[ -f "$QUEUE_REPO/platform-windows-blocking" ]] || fail "a week green did not make the platform hold green: $(cat "$QUEUE_REPO/platform-runs.log")"

# Holding: a tip the Full tier passed waits for Windows; a red Windows run keeps green where it was,
# and a later green tip moves it.
before="$(origin green)"
held="$(land more.txt more)"
take "$held" GREEN
out="$(QUEUE_REPO="$QUEUE_REPO" GREEN_CHECKOUT="$WORK/pco" bash "$ROOT/scripts/gate/green.sh")"
[[ "$(origin green)" == "$before" ]] || fail "green moved past a tip Windows has not run"
grep -q "waits: windows has not run it" <<<"$out" || fail "green.sh did not say why green waits: $out"
r="$(land win-red.txt WinPathTest)"
take "$r" GREEN
loop
[[ "$(origin green)" == "$before" ]] || fail "a red run on a holding platform moved green"
git -C "$dev" rm -q win-red.txt && git -C "$dev" commit -qm "fixed again" && git -C "$dev" push -q origin HEAD:main
g2="$(git -C "$dev" rev-parse HEAD)"
take "$g2" GREEN
loop
[[ "$(origin green)" == "$g2" ]] || fail "a tip green on Linux and Windows did not move green: $(tail -3 "$QUEUE_REPO/platform-runs.log")"

# A closed ticket is not reused: the next red files a new one.
echo "done" >"$KA_STATUS"
take "$(land win-red.txt WinPathTest)" GREEN
loop
[[ "$(grep -c '^new|' "$KA_LOG")" -eq 2 ]] || fail "a red run after the ticket closed filed none: $(cat "$KA_LOG")"

echo "test-platform-loop: ok ($(wc -l <"$RUNS" | tr -d ' ') platform runs)"
