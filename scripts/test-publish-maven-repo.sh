#!/usr/bin/env bash
# Network-free fixture test for publish-maven-repo.sh: the maven-metadata.xml merge and the
# worker-POM check.
#
# A store holding three artifacts is staged against a fixture repository served by a curl on PATH
# that answers from a directory. The staged metadata must carry the union of the repository's
# versions and the staged ones, name the merged maximum, leave alone an artifact the store does not
# hold at JK_VERSION, treat a 404 as a new artifact, and refuse any other failure to read. A plugin
# worker's POM must declare dependencies, and every first-party one it names must be staged.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-maven-repo-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

VERSION=0.13.3
LOCAL="$WORK/store/repos/jk-local/cc/jumpkick"
REPO="$WORK/http/fixture-bucket/repo"   # what https://storage.googleapis.com/fixture-bucket/repo/ serves
mkdir -p "$WORK/bin" "$REPO"

# curl as the script calls it: `-sS -o <file> -w '%{http_code}' <url>`. A file under the served
# tree is a 200; a `.status` file beside its path is answered as that HTTP status; a `.curl-exit`
# file makes curl itself fail with that code; anything else is a 404.
cat >"$WORK/bin/curl" <<'EOF'
#!/usr/bin/env bash
out="" url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -w) shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
src="${FIXTURE_HTTP_ROOT}${url#https://storage.googleapis.com}"
if [[ -f "$src.curl-exit" ]]; then exit "$(cat "$src.curl-exit")"; fi
if [[ -f "$src.status" ]]; then printf '%s' "$(cat "$src.status")"; exit 0; fi
if [[ -f "$src" ]]; then cp "$src" "$out"; printf 200; else : >"$out"; printf 404; fi
EOF
chmod +x "$WORK/bin/curl"

# artifact <group path under cc/jumpkick> <artifact> <version> [pom xml]: a jar + POM pair in the store
artifact() {
  local dir="$LOCAL/$1/$2/$3"
  mkdir -p "$dir"
  printf 'jar %s %s\n' "$2" "$3" >"$dir/$2-$3.jar"
  printf '%s\n' "${4:-<project/>}" >"$dir/$2-$3.pom"
}
# worker_pom <dependency xml...>: a worker POM in the shape the build writes — declared
# dependencies, with the closure pinned under dependencyManagement
worker_pom() {
  cat <<POM
<project>
  <dependencyManagement>
    <dependencies>
      <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.7.1-jre</version></dependency>
      <dependency><groupId>cc.jumpkick</groupId><artifactId>jk-plugin-sdk</artifactId><version>0.1.0</version></dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
$(printf '    %s\n' "$@")
  </dependencies>
</project>
POM
}
# metadata <artifact path under the repo root> <version>...: what the repository already serves
metadata() {
  local dir="$REPO/$1"
  shift
  mkdir -p "$dir"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<metadata><versioning><versions>'
    for v in "$@"; do echo "<version>$v</version>"; done
    echo '</versions></versioning></metadata>'
  } >"$dir/maven-metadata.xml"
}
# publish <stage dir>: a stage-only run against the fixture repository; its output is $WORK/out.log
publish() {
  env PATH="$WORK/bin:$PATH" FIXTURE_HTTP_ROOT="$WORK/http" \
    JK_VERSION="$VERSION" JK_STORE_DIR="$WORK/store" JK_MAVEN_BUCKET=fixture-bucket JK_MAVEN_PREFIX=repo \
    JK_MAVEN_STAGE_ONLY=1 JK_MAVEN_STAGE_DIR="$1" "$ROOT/scripts/publish-maven-repo.sh" >"$WORK/out.log" 2>&1
}
fail() {
  echo "test-publish-maven-repo: $1" >&2
  cat "$WORK/out.log" >&2
  exit 1
}
element() {
  sed -nE "s|.*<$1>([^<]*)</$1>.*|\1|p" "$2"
}

artifact . jk-alpha "$VERSION"          # published at this version; the repository lists two others
artifact . jk-beta 0.13.2               # held at an older version only: metadata untouched
artifact guards spring "$VERSION"       # nested group, new to the repository (its read is a 404)
# A plugin worker (its module is plugins/image-builder in this checkout) with the POM the build
# writes: what it declares, first-party rungs included, plus a test-scope first-party dependency
# that a launch never fetches and the check therefore ignores.
artifact . jk-image-builder "$VERSION" "$(worker_pom \
  '<dependency><groupId>com.google.cloud.tools</groupId><artifactId>jib-core</artifactId><version>0.28.2</version></dependency>' \
  '<dependency><groupId>cc.jumpkick</groupId><artifactId>jk-plugin-sdk</artifactId><version>0.1.0</version></dependency>' \
  '<dependency><groupId>cc.jumpkick</groupId><artifactId>jk-host</artifactId><version>9.9.9</version><scope>test</scope></dependency>')"
artifact . jk-plugin-sdk 0.1.0          # the first-party rung the worker declares, staged
metadata cc/jumpkick/jk-alpha 0.13.1 0.13.10
metadata cc/jumpkick/jk-beta 0.13.1

STAGE="$WORK/stage"
publish "$STAGE" || fail "the stage-only run failed"

alpha="$STAGE/cc/jumpkick/jk-alpha/maven-metadata.xml"
[[ -f "$alpha" ]] || fail "jk-alpha has no staged metadata"
[[ "$(element groupId "$alpha")" == "cc.jumpkick" ]] || fail "jk-alpha groupId: $(element groupId "$alpha")"
[[ "$(element version "$alpha" | paste -sd' ')" == "0.13.1 0.13.3 0.13.10" ]] \
  || fail "jk-alpha versions are not the merged list in dotted numeric order: $(element version "$alpha" | paste -sd' ')"
[[ "$(element latest "$alpha")" == "0.13.10" ]] || fail "jk-alpha latest is not the merged maximum: $(element latest "$alpha")"
[[ "$(element release "$alpha")" == "0.13.10" ]] || fail "jk-alpha release is not the merged maximum: $(element release "$alpha")"

[[ ! -e "$STAGE/cc/jumpkick/jk-beta/maven-metadata.xml" ]] || fail "jk-beta metadata was rewritten though the store lacks $VERSION"
[[ -f "$STAGE/cc/jumpkick/jk-beta/0.13.2/jk-beta-0.13.2.jar" ]] || fail "jk-beta's jar was not staged"

spring="$STAGE/cc/jumpkick/guards/spring/maven-metadata.xml"
[[ -f "$spring" ]] || fail "guards/spring has no staged metadata"
[[ "$(element groupId "$spring")" == "cc.jumpkick.guards" ]] || fail "guards/spring groupId: $(element groupId "$spring")"
[[ "$(element version "$spring" | paste -sd' ')" == "$VERSION" ]] || fail "guards/spring versions: $(element version "$spring" | paste -sd' ')"
[[ "$(element latest "$spring")" == "$VERSION" ]] || fail "guards/spring latest: $(element latest "$spring")"

grep -q "checked jk-image-builder:$VERSION (declares 2 dependencies)" "$WORK/out.log" || fail "the worker POM was not checked, or its test-scope dependency was counted"
[[ -f "$STAGE/cc/jumpkick/jk-image-builder/$VERSION/jk-image-builder-$VERSION.pom" ]] || fail "the worker POM was not staged"
grep -q "jk-alpha -> latest 0.13.10 (3 versions, merged)" "$WORK/out.log" || fail "log does not report the merge"
grep -q "jk-beta unchanged" "$WORK/out.log" || fail "log does not report jk-beta as unchanged"
grep -q "spring -> latest $VERSION (1 versions, new)" "$WORK/out.log" || fail "log does not report guards/spring as new"

# A read that fails for any reason other than absence is refused: a list rebuilt without the
# repository's answer is the drop the merge prevents.
alpha_url="https://storage.googleapis.com/fixture-bucket/repo/cc/jumpkick/jk-alpha/maven-metadata.xml"
echo 403 >"$REPO/cc/jumpkick/jk-alpha/maven-metadata.xml.status"
if publish "$WORK/stage-403"; then fail "an HTTP 403 on the repository's metadata was accepted"; fi
grep -q "cannot read $alpha_url: HTTP 403" "$WORK/out.log" || fail "the 403 was not reported with its URL"
[[ ! -e "$WORK/stage-403/cc/jumpkick/jk-alpha/maven-metadata.xml" ]] || fail "jk-alpha metadata was staged after a failed read"
rm "$REPO/cc/jumpkick/jk-alpha/maven-metadata.xml.status"

echo 6 >"$REPO/cc/jumpkick/jk-alpha/maven-metadata.xml.curl-exit"
if publish "$WORK/stage-curl"; then fail "a curl failure on the repository's metadata was accepted"; fi
grep -q "cannot read $alpha_url (curl exit 6)" "$WORK/out.log" || fail "the curl failure was not reported with its URL"
rm "$REPO/cc/jumpkick/jk-alpha/maven-metadata.xml.curl-exit"

# A worker POM naming a first-party artifact the stage does not hold is refused: the published
# worker would fetch that coordinate at launch and fail to start.
artifact . jk-formatter "$VERSION" "$(worker_pom \
  '<dependency><groupId>cc.jumpkick</groupId><artifactId>jk-core</artifactId><version>0.13.3</version></dependency>')"
if publish "$WORK/stage-missing"; then fail "a worker POM naming an unstaged first-party dependency was accepted"; fi
grep -q "jk-formatter:$VERSION depends on cc.jumpkick:jk-core:0.13.3, which this stage does not hold" "$WORK/out.log" \
  || fail "the missing first-party dependency was not named"
rm -r "$LOCAL/jk-formatter"

# A worker POM that declares nothing is a stub, not the POM the build wrote.
artifact . jk-formatter "$VERSION" '<project><dependencies></dependencies></project>'
if publish "$WORK/stage-stub"; then fail "a worker POM with no declared dependencies was accepted"; fi
grep -q "jk-formatter:$VERSION declares no dependencies" "$WORK/out.log" || fail "the stub worker POM was not refused by name"
rm -r "$LOCAL/jk-formatter"

# A first-party library is not a worker: its POM shape is its own business.
artifact . jk-model "$VERSION" '<project/>'
publish "$WORK/stage-library" || fail "a dependency-free library POM was refused as if it were a worker"

echo "test-publish-maven-repo: ok"
