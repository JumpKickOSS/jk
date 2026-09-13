#!/usr/bin/env bash
# Fixtures for scripts/publish-github-release.sh against a stub gh that records every call and
# answers `release view` from a marker: no release yet creates a draft with --verify-tag, the
# notes and every asset; an existing draft gets its assets replaced and its notes edited; a
# published release is refused; publish flips the draft and marks it latest, refuses when there
# is nothing to publish, and is idempotent on a published one; a missing asset, an empty notes
# file and a malformed version are refused before gh is asked anything.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-gh-release.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
SCRIPT="$ROOT/scripts/publish-github-release.sh"
LOG="$WORK/gh.log"
STATE="$WORK/state" # absent | draft | published — what the stub says the forge holds

mkdir -p "$WORK/bin"
cat >"$WORK/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "gh $*" >>"$GH_LOG"
if [[ "$1 $2" == "release view" ]]; then
  case "$(cat "$GH_STATE")" in
    absent) echo "release not found" >&2; exit 1 ;;
    draft) echo true ;;
    published) echo false ;;
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
logged() { grep -q -- "$1" "$LOG" || { cat "$LOG" >&2; echo "test-publish-github-release: gh was not asked '$1' ($2)" >&2; exit 1; }; }
not_logged() { grep -q -- "$1" "$LOG" && { cat "$LOG" >&2; echo "test-publish-github-release: gh was asked '$1' ($2)" >&2; exit 1; } || true; }
refuses() { # expected-text args...
  local expected="$1"; shift
  if "$SCRIPT" "$@" >/dev/null 2>"$WORK/err.txt"; then
    echo "test-publish-github-release: '$*' was accepted ($expected)" >&2
    exit 1
  fi
  grep -q -- "$expected" "$WORK/err.txt" || { cat "$WORK/err.txt" >&2; echo "test-publish-github-release: '$*' refused without saying '$expected'" >&2; exit 1; }
}

# No release yet: one create, as a draft, against the existing tag, with the notes and every asset.
reset absent
"$SCRIPT" draft 1.2.3 "$WORK/notes.md" "${ASSETS[@]}" >"$WORK/out.txt"
logged "gh release create v1.2.3 --repo owner/repo --draft --verify-tag --title jk 1.2.3 --notes-file $WORK/notes.md ${ASSETS[*]}" "a fresh draft"
not_logged "gh release upload" "a fresh draft uploads through create"
grep -q 'draft v1.2.3 holds 3 asset(s)' "$WORK/out.txt" || { cat "$WORK/out.txt"; echo "test-publish-github-release: wrong draft summary" >&2; exit 1; }

# A draft exists (a re-run): assets replaced, notes edited, nothing created.
reset draft
"$SCRIPT" draft 1.2.3 "$WORK/notes.md" "${ASSETS[@]}" >/dev/null
logged "gh release upload v1.2.3 --repo owner/repo --clobber ${ASSETS[*]}" "a re-run replaces the assets"
logged "gh release edit v1.2.3 --repo owner/repo --title jk 1.2.3 --notes-file $WORK/notes.md" "a re-run rewrites the notes"
not_logged "gh release create" "a re-run must not create a second release"

# Published: refused, and gh is asked nothing but the state.
reset published
refuses "already published" draft 1.2.3 "$WORK/notes.md" "${ASSETS[@]}"
not_logged "gh release create" "a published release is never rewritten"
not_logged "gh release upload" "a published release is never rewritten"

# publish: the draft becomes the latest release.
reset draft
"$SCRIPT" publish 1.2.3 >/dev/null
logged "gh release edit v1.2.3 --repo owner/repo --draft=false --latest" "publishing the draft"

reset absent
refuses "no release for v1.2.3 to publish" publish 1.2.3

reset published
"$SCRIPT" publish 1.2.3 >"$WORK/out.txt"
grep -q 'already published' "$WORK/out.txt" || { echo "test-publish-github-release: publish must be idempotent" >&2; exit 1; }
not_logged "gh release edit" "an already published release is not edited"

# Refusals before any forge call.
reset absent
refuses "is not a file" draft 1.2.3 "$WORK/notes.md" "$WORK/absent.xz"
refuses "missing or empty" draft 1.2.3 "$WORK/empty.md" "${ASSETS[@]}"
refuses "needs at least one asset" draft 1.2.3 "$WORK/notes.md"
refuses "is not a version" draft v1.2.3 "$WORK/notes.md" "${ASSETS[@]}"
refuses "mode must be draft or publish" upload 1.2.3
[[ ! -s "$LOG" ]] || { cat "$LOG" >&2; echo "test-publish-github-release: a refused request reached gh" >&2; exit 1; }

# Without GITHUB_REPOSITORY gh is left to infer the repository from the checkout.
unset GITHUB_REPOSITORY
reset absent
"$SCRIPT" draft 1.2.3 "$WORK/notes.md" "${ASSETS[@]}" >/dev/null
not_logged "--repo" "no slug means no --repo"

echo "test-publish-github-release: ok"
