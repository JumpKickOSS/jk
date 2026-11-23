#!/usr/bin/env bash
# Fixture test for scripts/ci-takeover.sh, network-free: the home's jk and the jk of another home,
# first on the caller's PATH, both log every command; only the home's may run. The first install is
# always a snapshot, so an older jk's build of the tree never holds a release's shelf slots:
# `jk snapshot` by a jk that has the verb, else a `jk install` of the tree outside git when the
# checkout is a tagged clean one; the second is `jk install`.
# With --release the checkout must be a clean one at its tag before anything runs, and the installed
# jk must report the release.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/jk-ci-takeover-test.XXXXXX")" && pwd)"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "test-ci-takeover: $1" >&2
  [[ -f "$WORK/jk.log" ]] && cat "$WORK/jk.log" >&2
  [[ -f "$WORK/out.log" ]] && cat "$WORK/out.log" >&2
  exit 1
}

mkdir -p "$WORK/repo/scripts" "$WORK/path" "$WORK/home/bin"
cp "$ROOT/scripts/ci-takeover.sh" "$ROOT/scripts/release-session.sh" "$ROOT/scripts/release-version.sh" "$WORK/repo/scripts/"
printf 'version = "9.8.7"\n' >"$WORK/repo/jk.toml"
# Each jk logs its commands; --version reports FIXTURE_REPORTS (default the release).
for who in path home/bin; do
  cat >"$WORK/$who/jk" <<SH
#!/usr/bin/env bash
if [[ "\$1" == --version ]]; then echo "jk \${FIXTURE_REPORTS:-9.8.7}"; exit 0; fi
if [[ "\$1" == snapshot && "\$2" == --help ]]; then [[ -n "\${FIXTURE_HAS_SNAPSHOT:-}" ]]; exit; fi
echo "${who%%/*} \$*" >>"$WORK/jk.log"
pwd >>"$WORK/jk.cwd"
SH
  chmod +x "$WORK/$who/jk"
done
g() { git -C "$WORK/repo" -c user.name=t -c user.email=t@localhost -c commit.gpgsign=false -c tag.gpgsign=false "$@"; }
g init -q -b main && g add -A && g commit -q -m "release 9.8.7"

takeover() {
  : >"$WORK/jk.log"
  : >"$WORK/jk.cwd"
  local args=() envs=()
  for a in "$@"; do if [[ "$a" == *=* ]]; then envs+=("$a"); else args+=("$a"); fi; done
  (cd "$WORK/repo" && env JK_HOME="$WORK/home" PATH="$WORK/path:$PATH" ${envs[@]+"${envs[@]}"} bash scripts/ci-takeover.sh "${args[@]}") \
    >"$WORK/out.log" 2>&1
}

takeover --install-only --yes FIXTURE_HAS_SNAPSHOT=1 || fail "the takeover failed"
! grep -q '^path ' "$WORK/jk.log" || fail "the jk of another home, first on the caller's PATH, ran"
[[ "$(sed -n 1p "$WORK/jk.log")" == "home snapshot --skip-tests --yes --no-ansi" ]] \
  || fail "a jk with the snapshot verb did not install a snapshot"
[[ "$(sed -n 2p "$WORK/jk.log")" == "home install --skip-tests --yes --no-ansi" ]] \
  || fail "the second install was not jk install by the home's jk"
echo "ok   a jk with the snapshot verb: jk snapshot, then jk install, both by the home's jk alone"

# An untagged checkout: a jk without the verb installs, which is a snapshot already, in the checkout.
takeover --install-only --yes || fail "the takeover failed"
[[ "$(sed -n 1p "$WORK/jk.log")" == "home install --skip-tests --yes --no-ansi" ]] \
  || fail "a jk without the snapshot verb did not install the untagged checkout"
[[ "$(sed -n 1p "$WORK/jk.cwd")" == "$WORK/repo" ]] || fail "the untagged first install did not run in the checkout"
echo "ok   a jk without the verb installs an untagged checkout as it is"

if takeover --install-only --release; then fail "a release takeover of an untagged checkout ran"; fi
grep -q "HEAD carries no tag v9.8.7: fetch tags" "$WORK/out.log" || fail "the missing tag was not named"
[[ ! -s "$WORK/jk.log" ]] || fail "a release takeover of an untagged checkout installed something"
echo "ok   --release refuses an untagged checkout before anything installs"

g tag -a v9.8.7 -m "jk 9.8.7"
takeover --install-only --release || fail "the release takeover failed"
[[ "$(sed -n 1p "$WORK/jk.log")" == "home install --skip-tests --no-ansi" ]] \
  || fail "the first install of a tagged tree was not a jk install"
first_cwd="$(sed -n 1p "$WORK/jk.cwd")"
[[ "$first_cwd" != "$WORK/repo" && ! -d "$first_cwd" ]] \
  || fail "a jk without the verb installed the tagged tree in place, where it would be the release"
echo "ok   a jk without the verb installs a tagged tree from a copy outside git, removed after"
takeover --install-only --release FIXTURE_HAS_SNAPSHOT=1 || fail "the release takeover with the verb failed"
[[ "$(sed -n 1p "$WORK/jk.log")" == "home snapshot --skip-tests --no-ansi" ]] \
  || fail "a jk with the verb did not snapshot the tagged tree"
[[ "$(sed -n 2p "$WORK/jk.log")" == "home install --skip-tests --no-ansi" ]] \
  || fail "the release takeover's second install was not jk install by the home's jk"
echo "ok   --release on a clean checkout at its tag installs, then checks the release"

if takeover --install-only --release FIXTURE_REPORTS=9.8.7-SNAPSHOT; then fail "a takeover that installed a snapshot passed --release"; fi
grep -q "reports 'jk 9.8.7-SNAPSHOT', not the release jk 9.8.7" "$WORK/out.log" || fail "the snapshot install was not named"
echo "ok   --release fails when the installed jk is a snapshot"

if takeover --install-only --bogus; then fail "an unknown argument was accepted"; fi
echo "ok   an unknown argument is refused"

echo "test-ci-takeover: ok"
