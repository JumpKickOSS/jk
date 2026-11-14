#!/usr/bin/env bash
# Network-free fixture test for scripts/release-merge.sh and scripts/release-publish.sh on a throwaway
# checkout tagged v9.8.7, with fake platform lanes, a throwaway signing key trusted by the fixture's
# install.sh, and a curl on PATH that serves a fixture jumpkick.build.
# - merge: refuses a lane whose copy does not match its sums; flattens four lanes into one tree whose
#   SHA256SUMS lists jk-repo; writes the SBOM from the engine jar and the notes.
# - sign: refuses a key the installers do not trust; signs with the trusted one.
# - upload / pointer: dry-run prints the gsutil copies, the version tree before repo/.
# - hosting: refused off the tag, refused while the served pointer is older than the installers'
#   floor, allowed (dry-run) once it is not.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/jk-release-publish-test.XXXXXX")" && pwd)"
trap 'rm -rf "$WORK"' EXIT
V=9.8.7

fail() {
  echo "test-release-publish: $1" >&2
  [[ -f "$WORK/out.log" ]] && cat "$WORK/out.log" >&2
  exit 1
}
ok() { echo "ok   $1"; }
g() { git -C "$REPO" -c user.name=t -c user.email=t@localhost -c commit.gpgsign=false "$@"; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$WORK/trusted.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$WORK/other.pem" 2>/dev/null
openssl pkey -in "$WORK/trusted.pem" -pubout -out "$WORK/trusted.pub.pem" 2>/dev/null
SPKI="$(openssl pkey -in "$WORK/trusted.pem" -pubout -outform DER 2>/dev/null | openssl base64 -A)"

# The checkout: the scripts under test, a manifest at $V, installers trusting the fixture key with
# floor $V, tagged v$V.
REPO="$WORK/repo"
mkdir -p "$REPO/scripts" "$REPO/hosting/public" "$REPO/docs/user"
for s in release-merge release-publish release-sbom release-version release-notes flatten-release \
  sign-release sign-latest-pointer release-key-matches publish-github-release; do
  cp "$ROOT/scripts/$s.sh" "$REPO/scripts/"
done
printf 'version = "%s"\n' "$V" >"$REPO/jk.toml"
printf '  RELEASE_RSA_SPKI="%s"\n  RELEASE_FLOOR="%s"\n' "$SPKI" "$V" >"$REPO/install.sh"
cp "$REPO/install.sh" "$REPO/hosting/public/install.sh"
# shellcheck disable=SC2016 # PowerShell text, written literally
printf '$ReleaseFloor = "%s"\n' "$V" >"$REPO/install.ps1"
cp "$REPO/install.ps1" "$REPO/hosting/public/install.ps1"
printf '24\n' >"$REPO/.nvmrc"
cat >"$REPO/docs/user/whats-new.md" <<EOF
# What's new

## $V

- **Fixture releases merge from four machines.** One signed list of sums covers every platform's client and the workers.
EOF
g init -q -b main && g add -A && g commit -q -m "release $V" && g tag -a "v$V" -m "jk $V"
g commit -q --allow-empty -m "Start 9.8.8"

sums() { if command -v sha256sum >/dev/null 2>&1; then sha256sum -- "$@"; else shasum -a 256 -- "$@"; fi; }

# Four lanes as release-lane.sh leaves them, and linux-x86_64's staged repository.
STAGING="$WORK/staging"
mkdir -p "$STAGING/repo/cc/jumpkick/jk-test-runner/$V"
printf 'runner\n' >"$STAGING/repo/cc/jumpkick/jk-test-runner/$V/jk-test-runner-$V.jar"
printf '<project/>\n' >"$STAGING/repo/cc/jumpkick/jk-test-runner/$V/jk-test-runner-$V.pom"
python3 -I - "$WORK/engine.jar" <<'PY'
import json, sys, zipfile
doc = {"bomFormat": "CycloneDX", "specVersion": "1.6", "metadata": {"component": {"name": "jk-engine"}},
       "components": [{"name": "tomlj"}]}
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("META-INF/sbom/application.cdx.json", json.dumps(doc))
PY
for label in linux-x86_64 linux-aarch64 macos-aarch64 windows-x86_64; do
  d="$STAGING/release-$V-$label"
  mkdir -p "$d"
  cp "$WORK/engine.jar" "$d/jk-engine-$V.jar"
  printf 'jvm client\n' >"$d/jk-$V.jar"
  printf 'spy\n' >"$d/jk-maven-spy-$V.jar"
  printf 'notes\n' >"$d/jk-whats-new-$V.md"
  (cd "$STAGING/repo" && sums "cc/jumpkick/jk-test-runner/$V/jk-test-runner-$V.jar" "cc/jumpkick/jk-test-runner/$V/jk-test-runner-$V.pom") >"$d/jk-repo-$V.sha256"
  printf '%s client\n' "$label" >"$d/jk-$label-$V.xz"
  if [[ "$label" == windows-* ]]; then printf 'zip\n' >"$d/jk-$label-$V.zip"; else printf 'gz\n' >"$d/jk-$label-$V.gz"; fi
  (cd "$d" && sums ./* >SHA256SUMS)
done
# Git Bash's shasum writes ' *name'.
sed -i.bak 's/  / */' "$STAGING/release-$V-windows-x86_64/SHA256SUMS" && rm "$STAGING/release-$V-windows-x86_64/SHA256SUMS.bak"

pub() { (cd "$REPO" && env PATH="$WORK/bin:$PATH" FIXTURE_ROOT="$WORK/site" JK_RELEASES_URL=https://fixture/releases \
  JK_RELEASE_DRY_RUN=1 bash scripts/release-publish.sh "$@") >"$WORK/out.log" 2>&1; }

# merge: a lane copy that does not match its sums is refused.
g checkout -q "v$V"
cp "$STAGING/release-$V-macos-aarch64/jk-macos-aarch64-$V.gz" "$WORK/gz.keep"
printf 'truncated' >"$STAGING/release-$V-macos-aarch64/jk-macos-aarch64-$V.gz"
if (cd "$REPO" && bash scripts/release-merge.sh "$STAGING" "$WORK/bad") >"$WORK/out.log" 2>&1; then fail "a damaged lane copy was merged"; fi
grep -q "release-$V-macos-aarch64/ does not match its SHA256SUMS" "$WORK/out.log" || fail "the damaged lane was not named"
cp "$WORK/gz.keep" "$STAGING/release-$V-macos-aarch64/jk-macos-aarch64-$V.gz"
ok "merge refuses a lane copy that does not match its sums"

OUT="$WORK/out"
(cd "$REPO" && bash scripts/release-merge.sh "$STAGING" "$OUT") >"$WORK/out.log" 2>&1 || fail "the merge failed"
[[ "$(wc -l <"$OUT/$V/SHA256SUMS")" -eq 13 ]] || fail "the merged SHA256SUMS does not list 13 files"
grep -q "  jk-repo-$V.sha256\$" "$OUT/$V/SHA256SUMS" || fail "the merged SHA256SUMS does not list jk-repo"
[[ -f "$OUT/sbom/jk-$V.cdx.json" && -s "$OUT/RELEASE_NOTES.md" && -d "$OUT/repo/cc" ]] || fail "the SBOM, notes or repo/ are missing"
ok "merge writes one tree of four platforms, the SBOM, the notes and repo/"

# sign: an untrusted key is refused; the trusted key signs.
if pub sign "$OUT" "$WORK/other.pem"; then fail "an untrusted key signed the release"; fi
grep -q "not the release key the installers trust" "$WORK/out.log" || fail "the untrusted key was not named"
pub sign "$OUT" "$WORK/trusted.pem" || fail "the trusted key did not sign"
grep -q "Verified OK" "$WORK/out.log" || fail "the signature was not verified"
ok "sign refuses an untrusted key and signs with the trusted one"

# The fixture jumpkick.build: curl answers from $WORK/site.
mkdir -p "$WORK/bin" "$WORK/site/releases/latest"
cat >"$WORK/bin/curl" <<'SH'
#!/usr/bin/env bash
out=""; url=""
while [[ $# -gt 0 ]]; do case "$1" in -o) out="$2"; shift 2 ;; -*) shift ;; *) url="$1"; shift ;; esac; done
path="${url#https://fixture}"; path="${path%%\?*}"
src="$FIXTURE_ROOT$path"
[[ -f "$src" ]] || exit 22
if [[ -n "$out" ]]; then cp "$src" "$out"; else cat "$src"; fi
SH
chmod +x "$WORK/bin/curl"

# upload and pointer: the tree before repo/, the pointer with no-cache.
pub upload "$OUT" || fail "the upload dry run failed"
tree_line="$(grep -n "rsync -r -d $OUT/$V/ gs://jumpkick/releases/$V/" "$WORK/out.log" | cut -d: -f1)"
repo_line="$(grep -n "rsync -r $OUT/repo/ gs://jumpkick/repo/" "$WORK/out.log" | cut -d: -f1)"
[[ -n "$tree_line" && -n "$repo_line" && "$tree_line" -lt "$repo_line" ]] || fail "upload does not copy the tree, then repo/"
grep -q "expect 15 objects under gs://jumpkick/releases/$V/" "$WORK/out.log" || fail "upload does not derive the object count from the tree"
# The pointer waits until every file of the version directory is served as signed.
mkdir -p "$WORK/site/releases/$V"
cp "$OUT/$V"/* "$WORK/site/releases/$V/"
rm "$WORK/site/releases/$V/jk-macos-aarch64-$V.gz"
if pub pointer "$OUT" "$WORK/trusted.pem"; then fail "the pointer moved while a file of the version was not served"; fi
grep -q "releases/$V/jk-macos-aarch64-$V.gz is not served: not moving the pointer" "$WORK/out.log" || fail "the unserved file was not named"
cp "$OUT/$V/jk-macos-aarch64-$V.gz" "$WORK/site/releases/$V/"
printf 'other' >"$WORK/site/releases/$V/jk-windows-x86_64-$V.zip"
if pub pointer "$OUT" "$WORK/trusted.pem"; then fail "the pointer moved while a served file did not match the sums"; fi
grep -q "a served file does not match SHA256SUMS" "$WORK/out.log" || fail "the mismatching file was not refused"
cp "$OUT/$V/jk-windows-x86_64-$V.zip" "$WORK/site/releases/$V/"
pub pointer "$OUT" "$WORK/trusted.pem" || fail "the pointer dry run failed"
grep -q "serves all 15 files of $V, as signed" "$WORK/out.log" || fail "the pointer did not confirm the served tree"
grep -q "Cache-Control:no-cache,max-age=0 cp $OUT/latest/LATEST gs://jumpkick/releases/latest/LATEST" "$WORK/out.log" \
  || fail "the pointer is not copied with no-cache"
[[ "$(sed -n 1p "$OUT/latest/LATEST")" == "version $V" ]] || fail "the pointer does not name $V"
ok "upload copies the tree before repo/ and counts it; the pointer waits for the served tree, then goes up signed with no-cache"

printf 'version 9.8.6\nissued 1\nsignature x\n' >"$WORK/site/releases/latest/LATEST"
g checkout -q main
if pub hosting; then fail "hosting deployed from a commit that is not the tag"; fi
grep -q "HEAD is not v$V" "$WORK/out.log" || fail "the off-tag refusal did not name the tag"
g checkout -q "v$V"
if pub hosting; then fail "hosting deployed while the served pointer is older than the installers' floor"; fi
grep -q "the served pointer names 9.8.6, older than the installers' floor $V" "$WORK/out.log" || fail "the floor refusal was not explained"
cp "$OUT/latest/LATEST" "$WORK/site/releases/latest/LATEST"
pub hosting || fail "hosting was refused from the tag with the pointer at the floor"
grep -q "dry-run:.*firebase-tools@15.30.2 deploy --only hosting --project jkbuild" "$WORK/out.log" || fail "the deploy was not the firebase deploy"
ok "hosting deploys only from the tag, after the pointer reaches the installers' floor"

# github: notes over GitHub's 125000-character body limit keep their highlights and link the rest.
g remote add origin "$REPO"
{
  printf '## Highlights\n\n- **A highlight users read first.** It stays in the release body.\n\n<details>\n<summary>Every change since v9.8.6 (3000 commits)</summary>\n\n'
  for i in $(seq 1 3000); do printf -- '- a long commit subject that says what changed and why, at length, number %s\n' "$i"; done
  printf '\n</details>\n'
} >"$OUT/RELEASE_NOTES.md"
pub github "$OUT" || fail "the github dry run failed"
grep -q "publish-github-release.sh $V $OUT/RELEASE_NOTES.github.md " "$WORK/out.log" || fail "the release body is not the shortened notes"
grep -q "A highlight users read first" "$OUT/RELEASE_NOTES.github.md" || fail "the shortened notes lost the highlights"
grep -q "Every change since v9.8.6 (3000 commits): https://github.com/JumpKickOSS/jk/compare/v9.8.6...v$V" "$OUT/RELEASE_NOTES.github.md" \
  || fail "the shortened notes do not link the full change list"
if grep -q '<details>' "$OUT/RELEASE_NOTES.github.md"; then fail "the shortened notes still fold the commit list"; fi
ok "github shortens notes over GitHub's body limit to the highlights and a compare link"

echo "test-release-publish: ok"
