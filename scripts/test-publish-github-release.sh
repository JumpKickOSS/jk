#!/usr/bin/env bash
# Fixtures for scripts/publish-github-release.sh against a stub gh that records every call and
# answers `release view` from a marker: a release for the tag gets every asset uploaded (replacing
# same-named ones), its notes edited and, unless it is a pre-release, the latest mark; no release
# for the tag has one created with the assets and the notes (a version with a qualifier as a
# pre-release); a missing asset, an empty notes file and a malformed version are refused before
# gh is asked anything.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-gh-release.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
SCRIPT="$ROOT/scripts/publish-github-release.sh"
LOG="$WORK/gh.log"
STATE="$WORK/state" # absent | release | prerelease — what the stub says the forge holds

mkdir -p "$WORK/bin"
cat >"$WORK/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "gh $*" >>"$GH_LOG"
if [[ "$1 $2" == "release view" ]]; then
  case "$(cat "$GH_STATE")" in
    absent) echo "release not found" >&2; exit 1 ;;
    release) echo false ;;
    prerelease) echo true ;;
  esac
fi
exit 0
EOF
chmod +x "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH" GH_LOG="$LOG" GH_STATE="$STATE" GITHUB_REPOSITORY="owner/repo"

echo "notes" >"$WORK/notes.md"
: >"$WORK/empty.md"
printf 'client\n' >"$WORK/jk-linux-x86_64-1.2.3.xz"
printf 'sums\n' >"$WORK/SHA256SUMS"
printf 'sbom\n' >"$WORK/jk-1.2.3.cdx.json"
ASSETS=("$WORK/jk-linux-x86_64-1.2.3.xz" "$WORK/SHA256SUMS" "$WORK/jk-1.2.3.cdx.json")

reset() { : >"$LOG"; echo "$1" >"$STATE"; }
logged() {
  if ! grep -q -- "$1" "$LOG"; then
    cat "$LOG" >&2
    echo "test-publish-github-release: gh was not asked '$1' ($2)" >&2
    exit 1
  fi
}
not_logged() {
  if grep -q -- "$1" "$LOG"; then
    cat "$LOG" >&2
    echo "test-publish-github-release: gh was asked '$1' ($2)" >&2
    exit 1
  fi
}
refuses() { # expected-text args...
  local expected="$1"; shift
  if "$SCRIPT" "$@" >/dev/null 2>"$WORK/err.txt"; then
    echo "test-publish-github-release: '$*' was accepted ($expected)" >&2
    exit 1
  fi
  grep -q -- "$expected" "$WORK/err.txt" || { cat "$WORK/err.txt" >&2; echo "test-publish-github-release: '$*' refused without saying '$expected'" >&2; exit 1; }
}

# The release exists: every asset uploaded over any same-named one, the notes edited, marked latest.
reset release
"$SCRIPT" 1.2.3 "$WORK/notes.md" "${ASSETS[@]}" >"$WORK/out.txt"
logged "gh release upload v1.2.3 --repo owner/repo --clobber ${ASSETS[*]}" "the assets"
logged "gh release edit v1.2.3 --repo owner/repo --title jk 1.2.3 --notes-file $WORK/notes.md --latest" "the notes and the latest mark"
not_logged "gh release create" "a release that exists is updated, not created"
grep -q 'v1.2.3 holds 3 asset(s)' "$WORK/out.txt" || { cat "$WORK/out.txt"; echo "test-publish-github-release: wrong summary" >&2; exit 1; }

# A pre-release is not marked latest.
reset prerelease
"$SCRIPT" 1.2.3 "$WORK/notes.md" "${ASSETS[@]}" >/dev/null
logged "gh release upload v1.2.3 --repo owner/repo --clobber" "a pre-release still receives the assets"
logged "gh release edit v1.2.3 --repo owner/repo --title jk 1.2.3 --notes-file $WORK/notes.md" "a pre-release still receives the notes"
not_logged "--latest" "a pre-release is never latest"

# No release for the tag: created with the notes and every asset, and marked latest.
reset absent
"$SCRIPT" 1.2.3 "$WORK/notes.md" "${ASSETS[@]}" >"$WORK/out.txt"
logged "gh release create v1.2.3 --repo owner/repo --verify-tag --title jk 1.2.3 --notes-file $WORK/notes.md --latest ${ASSETS[*]}" "the release, its notes and its assets"
not_logged "gh release upload" "a created release already holds its assets"
not_logged "gh release edit" "a created release already holds its notes"
grep -q 'v1.2.3 created with 3 asset(s)' "$WORK/out.txt" || { cat "$WORK/out.txt"; echo "test-publish-github-release: wrong summary" >&2; exit 1; }

# A version with a qualifier is created as a pre-release, never latest.
reset absent
"$SCRIPT" 1.2.3-rc.1 "$WORK/notes.md" "${ASSETS[@]}" >/dev/null
logged "gh release create v1.2.3-rc.1 --repo owner/repo --verify-tag --title jk 1.2.3-rc.1 --notes-file $WORK/notes.md --prerelease" "a qualified version is a pre-release"
not_logged "--latest" "a pre-release is never latest"

# Refused before gh is asked anything.
reset release
refuses "is not a file" 1.2.3 "$WORK/notes.md" "$WORK/missing.xz"
refuses "missing or empty" 1.2.3 "$WORK/empty.md" "${ASSETS[@]}"
refuses "is not a version" "1.2.3;rm" "$WORK/notes.md" "${ASSETS[@]}"
refuses "at least one asset" 1.2.3 "$WORK/notes.md"
[[ ! -s "$LOG" ]] || { cat "$LOG" >&2; echo "test-publish-github-release: a refused request reached gh" >&2; exit 1; }

echo "test-publish-github-release: ok"
