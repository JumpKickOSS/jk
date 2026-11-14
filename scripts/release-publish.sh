#!/usr/bin/env bash
# Sign and publish a merged release (scripts/release-merge.sh) by hand, one step at a time, in the
# order the installers need: the version tree and repo/ first, the signed pointer next (the release
# goes live there), the installers after the pointer they will read, the GitHub Release last.
#
# Usage (from a checkout of the release tag; its jk.toml names the version):
#   scripts/release-publish.sh sign     <out> <private-key.pem>   SHA256SUMS.sig, checked with the key the installers trust
#   scripts/release-publish.sh upload   <out>                     releases/<version>/ (rsync -d), then repo/ (additive)
#   scripts/release-publish.sh pointer  <out> <private-key.pem>   sign latest/LATEST now and copy it (no-cache); VERSION beside it
#   scripts/release-publish.sh verify   <out> <public-key.pem>    the pointer and SHA256SUMS as jumpkick.build serves them
#   scripts/release-publish.sh hosting                            firebase deploy of hosting/public, from the tag only
#   scripts/release-publish.sh github   <out>                     the GitHub Release, with the tree, the SBOM and the notes
#   scripts/release-publish.sh install-check                      curl … install.sh | bash into a clean home: jk <version>
# Env:
#   JK_RELEASE_BUCKET   default jumpkick
#   JK_RELEASE_DRY_RUN  1 prints every gsutil, firebase and gh command instead of running it
#   JK_RELEASES_URL     default https://jumpkick.build/releases (verify, hosting, install-check)
#   FIREBASE_PROJECT    default jkbuild
# Credentials are the caller's: gcloud's active account must write the bucket, the firebase CLI must
# be logged in to an account on the project, and gh must be able to write releases on the repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STEP="${1:?usage: release-publish.sh sign|upload|pointer|verify|hosting|github|install-check …}"
shift
VER="$(bash "$ROOT/scripts/release-version.sh")"
TAG="v$VER"
BUCKET="${JK_RELEASE_BUCKET:-jumpkick}"
RELEASES="${JK_RELEASES_URL:-https://jumpkick.build/releases}"
REPO_NAME="${GITHUB_REPOSITORY:-JumpKickOSS/jk}"

run() {
  if [[ "${JK_RELEASE_DRY_RUN:-}" == 1 ]]; then
    echo "dry-run: $*"
  else
    "$@"
  fi
}
die() { echo "release-publish: $*" >&2; exit 1; }
need_tree() {
  [[ -f "$1/$VER/SHA256SUMS" ]] || die "no merged tree at $1/$VER (scripts/release-merge.sh)"
}
at_tag() {
  [[ "$(git -C "$ROOT" rev-parse HEAD)" == "$(git -C "$ROOT" rev-parse -q --verify "$TAG^{commit}" 2>/dev/null || true)" ]] \
    || die "HEAD is not $TAG: run from a checkout of the release tag"
  git -C "$ROOT" diff --quiet HEAD -- install.sh install.ps1 hosting/ || die "install.sh, install.ps1 or hosting/ differ from $TAG"
}
# The version a served or written LATEST names.
pointer_version() { sed -n '1s/^version //p' "$1"; }
version_le() { [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$1" ]]; }

case "$STEP" in
  sign)
    OUT="${1:?usage: release-publish.sh sign <out> <private-key.pem>}"; KEY="${2:?private key}"
    need_tree "$OUT"
    bash "$ROOT/scripts/release-key-matches.sh" "$KEY" PEM
    JK_RELEASE_REQUIRE_TRUSTED_KEY=1 bash "$ROOT/scripts/sign-release.sh" "$OUT/$VER/SHA256SUMS" "$KEY"
    pub="$(mktemp)"; sig="$(mktemp)"; trap 'rm -f "$pub" "$sig"' EXIT
    openssl pkey -in "$KEY" -pubout -out "$pub" 2>/dev/null
    openssl base64 -d -A -in "$OUT/$VER/SHA256SUMS.sig" -out "$sig"
    openssl dgst -sha256 -verify "$pub" -signature "$sig" "$OUT/$VER/SHA256SUMS"
    ;;
  upload)
    OUT="${1:?usage: release-publish.sh upload <out>}"
    need_tree "$OUT"
    [[ -f "$OUT/$VER/SHA256SUMS.sig" ]] || die "$OUT/$VER is unsigned: run the sign step first"
    [[ -d "$OUT/repo/cc" ]] || die "no staged repository at $OUT/repo"
    echo "release-publish: gcloud account $(gcloud config get-value account 2>/dev/null || echo '?')"
    run gsutil -m rsync -r -d "$OUT/$VER/" "gs://$BUCKET/releases/$VER/"
    run gsutil -m rsync -r "$OUT/repo/" "gs://$BUCKET/repo/"
    for art in jk-host jk-plugin-sdk jk-test-runner jk-java-compiler; do
      run gsutil -q stat "gs://$BUCKET/repo/cc/jumpkick/$art/$VER/$art-$VER.pom"
    done
    ;;
  pointer)
    OUT="${1:?usage: release-publish.sh pointer <out> <private-key.pem>}"; KEY="${2:?private key}"
    bash "$ROOT/scripts/release-key-matches.sh" "$KEY" PEM
    rm -rf "$OUT/latest"
    bash "$ROOT/scripts/sign-latest-pointer.sh" "$VER" "$OUT/latest" "$KEY"
    run gsutil -h "Cache-Control:no-cache,max-age=0" cp "$OUT/latest/LATEST" "gs://$BUCKET/releases/latest/LATEST"
    run gsutil -h "Cache-Control:no-cache,max-age=0" cp "$OUT/latest/VERSION" "gs://$BUCKET/releases/latest/VERSION"
    run gsutil -q rm "gs://$BUCKET/releases/latest/LATEST.sig" || true
    ;;
  verify)
    OUT="${1:?usage: release-publish.sh verify <out> <public-key.pem>}"; PUB="${2:?public key}"
    t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT
    curl -fsSL "$RELEASES/latest/LATEST?nocache=$RANDOM" -o "$t/LATEST"
    head -n 2 "$t/LATEST" >"$t/body"
    awk 'NR==3 { sub(/^signature /,""); print }' "$t/LATEST" | openssl base64 -d -A >"$t/sig"
    openssl dgst -sha256 -verify "$PUB" -signature "$t/sig" "$t/body"
    [[ "$(pointer_version "$t/LATEST")" == "$VER" ]] || die "the served pointer names $(pointer_version "$t/LATEST"), not $VER"
    curl -fsSL "$RELEASES/$VER/SHA256SUMS" | cmp - "$OUT/$VER/SHA256SUMS" || die "the served SHA256SUMS is not the signed one"
    echo "release-publish: jumpkick.build serves $VER, signed, with this tree's SHA256SUMS"
    ;;
  hosting)
    # The installers carry a floor and refuse any pointer older than it, so hosting is deployed only
    # from the release tag and only once the pointer it is about to serve is at least that floor.
    at_tag
    floor="$(sed -n 's/^ *RELEASE_FLOOR="\([^"]*\)".*/\1/p' "$ROOT/hosting/public/install.sh" | head -1)"
    # shellcheck disable=SC2016 # $ReleaseFloor is PowerShell text matched literally
    ps_floor="$(sed -n 's/^\$ReleaseFloor = "\([^"]*\)".*/\1/p' "$ROOT/hosting/public/install.ps1" | head -1)"
    [[ "$floor" == "$VER" && "$ps_floor" == "$VER" ]] || die "hosting/public floors are $floor / $ps_floor, not $VER"
    t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT
    curl -fsSL "$RELEASES/latest/LATEST?nocache=$RANDOM" -o "$t/LATEST"
    served="$(pointer_version "$t/LATEST")"
    version_le "$floor" "$served" \
      || die "the served pointer names $served, older than the installers' floor $floor: publish the pointer first"
    deploy=(npx --yes firebase-tools@15.30.2 deploy --only hosting --project "${FIREBASE_PROJECT:-jkbuild}" --non-interactive)
    if command -v npx >/dev/null 2>&1; then
      (cd "$ROOT" && run "${deploy[@]}")
    else
      (cd "$ROOT" && run jk node exec --node "$(tr -d '[:space:]' <"$ROOT/.nvmrc")" -- "${deploy[@]}")
    fi
    ;;
  github)
    OUT="${1:?usage: release-publish.sh github <out>}"
    need_tree "$OUT"
    git -C "$ROOT" ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null || die "$TAG is not pushed"
    [[ -f "$OUT/$VER/SHA256SUMS.sig" ]] || die "$OUT/$VER is unsigned"
    if [[ "${JK_RELEASE_DRY_RUN:-}" == 1 ]]; then
      echo "dry-run: GITHUB_REPOSITORY=$REPO_NAME scripts/publish-github-release.sh $VER $OUT/RELEASE_NOTES.md $OUT/$VER/* $OUT/sbom/jk-$VER.cdx.json"
    else
      GITHUB_REPOSITORY="$REPO_NAME" bash "$ROOT/scripts/publish-github-release.sh" "$VER" "$OUT/RELEASE_NOTES.md" \
        "$OUT/$VER"/* "$OUT/sbom/jk-$VER.cdx.json"
    fi
    ;;
  install-check)
    # What a new user runs, from a clean home: the hosted installer, the served pointer, this version.
    s="$(mktemp -d "${TMPDIR:-/tmp}/jk-install-check.XXXXXX")"; trap 'rm -rf "$s"' EXIT
    mkdir -p "$s/home"
    curl -fsSL "${RELEASES%/releases}/install.sh" -o "$s/install.sh"
    env HOME="$s/home" JK_HOME="$s/home/.jk" JK_NONINTERACTIVE=1 JK_SELF_UPDATE=never TMPDIR="$s" \
      bash "$s/install.sh" </dev/null >"$s/install.log" 2>&1 || { cat "$s/install.log" >&2; die "the hosted installer failed from a clean home"; }
    got="$(env HOME="$s/home" JK_HOME="$s/home/.jk" "$s/home/.jk/bin/jk" --version --no-ansi | head -1)"
    env HOME="$s/home" JK_HOME="$s/home/.jk" "$s/home/.jk/bin/jk" engine stop --now --no-ansi >/dev/null 2>&1 || true
    [[ "$got" == "jk $VER" ]] || die "a clean install reports '$got', not jk $VER"
    echo "release-publish: curl … install.sh | bash from a clean home installs jk $VER"
    ;;
  *) die "unknown step $STEP" ;;
esac
