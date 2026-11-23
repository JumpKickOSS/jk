#!/usr/bin/env bash
# Fixture test for the jk-updates repository's scripts (hosting/jk-updates/scripts), with a
# throwaway key and local sources: refresh pins the templates revision and a release whose pointer
# and SHA256SUMS verify, keeps the pin when they do not, and takes the first Python with tomllib;
# assemble signs a manifest the key verifies, signs nothing when nothing changed, refuses a changed price table or savings rates file
# whose published date did not move forward, a rates file with no rates, a manifest older than the one held, a JDK file that is not the feed and a
# key that is not the release key; verify-served passes the served copy and fails a stale or
# tampered one. The trusted key there is the one the installers carry.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPDATES="$ROOT/hosting/jk-updates"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-updates-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "test-jk-updates: $1" >&2
  [[ -f "$WORK/last.log" ]] && cat "$WORK/last.log" >&2
  exit 1
}
run() { "$@" >"$WORK/last.log" 2>&1; }
passes() { local what="$1"; shift; run "$@" || fail "$what was refused"; }
refuses_naming() {
  local what="$1" needle="$2"
  shift 2
  if run "$@"; then fail "$what was accepted"; fi
  grep -qF -- "$needle" "$WORK/last.log" || fail "$what failed without naming '$needle'"
}
sha() { openssl dgst -sha256 -r "$1" | cut -d' ' -f1; }
field() { python3 -c 'import json, sys
v = json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."): v = v[k]
print(v)' "$1" "$2"; }

trusted="$(sed -n 's/^ *RELEASE_RSA_SPKI="\([A-Za-z0-9+/=]*\)".*/\1/p' "$ROOT/install.sh" | head -1)"
[[ "$(tr -d '\r\n' <"$UPDATES/release-key.spki")" == "$trusted" ]] \
  || fail "hosting/jk-updates/release-key.spki is not install.sh's RELEASE_RSA_SPKI"

passes "the publish workflow's hygiene" bash "$ROOT/scripts/check-workflows.sh" "$UPDATES/.github/workflows/publish.yml"

# The throwaway release key, and another.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/key.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/other.pem" 2>/dev/null
openssl pkey -in "$WORK/key.pem" -pubout -out "$WORK/public.pem"
export JK_UPDATES_RELEASE_SPKI
JK_UPDATES_RELEASE_SPKI="$(openssl pkey -in "$WORK/key.pem" -pubout -outform DER | openssl base64 -A)"
sign() { # key file -> base64 one line
  openssl dgst -sha256 -sign "$1" "$2" | openssl base64 -A
}

# Local sources: the registry, the feed, a templates repository and a release directory.
SRC="$WORK/src"
mkdir -p "$SRC/releases/latest" "$SRC/releases/0.60.0" "$SRC/templates"
printf '[libraries.demo]\ncoordinates = "org.demo:demo"\n' >"$SRC/libraries.toml"
printf '{\n  "jdks": []\n}\n' >"$SRC/jdks.json"
git -C "$SRC/templates" init -q -b main
git -C "$SRC/templates" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m one
templates_commit="$(git -C "$SRC/templates" rev-parse HEAD)"
printf 'aaaa  jk-linux-x86_64\n' >"$SRC/releases/0.60.0/SHA256SUMS"
sign "$WORK/key.pem" "$SRC/releases/0.60.0/SHA256SUMS" >"$SRC/releases/0.60.0/SHA256SUMS.sig"
pointer() { # key
  printf 'version 0.60.0\nissued 1795281630\n' >"$WORK/pointer"
  { cat "$WORK/pointer"; printf 'signature %s\n' "$(sign "$1" "$WORK/pointer")"; } >"$SRC/releases/latest/LATEST"
}
pointer "$WORK/key.pem"
export JK_UPDATES_LIBRARIES_URL="file://$SRC/libraries.toml"
export JK_UPDATES_JDKS_URL="file://$SRC/jdks.json"
export JK_UPDATES_TEMPLATES_GIT="$SRC/templates"
export JK_UPDATES_RELEASES_URL="file://$SRC/releases"

REPO="$WORK/jk-updates"
mkdir -p "$REPO"
cat >"$REPO/prices.toml" <<'EOF'
published = 2026-11-21T18:00:00Z

[models."demo-model"]
input = 3.0
output = 15.0
retrieved = "2026-11-21"
EOF
cat >"$REPO/rates.json" <<'EOF'
{
  "published": "2026-11-21T19:00:00Z",
  "measured": "2026-11-21",
  "rates": [
    {"agent": "claude-code", "model": "claude-opus-5-5", "effort": "medium", "measured": "2026-11-21",
     "cacheWrite1h": true, "question": {"input": 10, "output": 20, "cacheRead": 30, "cacheWrite": 40}}
  ]
}
EOF

# Stand-ins for a Python that predates tomllib (macOS ships 3.9): one shadows python3 and passes
# everything else to a real 3.11+, the others shadow python3 and every versioned python3.N on PATH,
# which are the interpreters the scripts try.
mapfile -t pythons < <(echo python3; compgen -c python3. | grep -E '^python3\.[0-9]+$' | sort -t. -k2,2nr -u)
real_python=
for candidate in "${pythons[@]}"; do
  if "$candidate" -c 'import tomllib' >/dev/null 2>&1; then
    real_python="$(command -v "$candidate")"
    break
  fi
done
[[ -n "$real_python" ]] || fail "this test needs Python 3.11 or newer"
mkdir -p "$WORK/old-python3" "$WORK/old-pythons"
old_python() { # path
  printf '#!/bin/sh\ncase "$*" in *tomllib*) echo "No module named tomllib" >&2; exit 1 ;; esac\nexec %s "$@"\n' \
    "$real_python" >"$1"
  chmod +x "$1"
}
old_python "$WORK/old-python3/python3"
for candidate in "${pythons[@]}"; do old_python "$WORK/old-pythons/$candidate"; done

refuses_naming "a refresh with no Python that has tomllib" "Python 3.11 or newer is required" \
  env PATH="$WORK/old-pythons:$PATH" bash "$UPDATES/scripts/refresh.sh" "$REPO"
[[ ! -e "$REPO/pins.json" ]] || fail "a refresh with no usable Python wrote pins.json"

passes "a refresh from sources that answer, past a first python3 with no tomllib" \
  env PATH="$WORK/old-python3:$PATH" bash "$UPDATES/scripts/refresh.sh" "$REPO"
cmp -s "$SRC/libraries.toml" "$REPO/libraries.toml" || fail "refresh did not copy the registry"
cmp -s "$SRC/jdks.json" "$REPO/jdks.json" || fail "refresh did not copy the JDK feed"
[[ "$(field "$REPO/pins.json" templates)" == "$templates_commit" ]] || fail "refresh did not pin the templates revision"
[[ "$(field "$REPO/pins.json" jk.stable.version)" == 0.60.0 ]] || fail "refresh did not pin the release"
[[ "$(field "$REPO/pins.json" jk.stable.sha256)" == "$(sha "$SRC/releases/0.60.0/SHA256SUMS")" ]] \
  || fail "refresh pinned the wrong SHA256SUMS digest"

# A host whose only Python with tomllib is a version newer than any this script knows.
mkdir -p "$WORK/newer-python"
printf '#!/bin/sh\nexec %s "$@"\n' "$real_python" >"$WORK/newer-python/python3.99"
chmod +x "$WORK/newer-python/python3.99"
passes "a refresh whose only Python with tomllib is python3.99" \
  env PATH="$WORK/newer-python:$WORK/old-pythons:$PATH" bash "$UPDATES/scripts/refresh.sh" "$REPO"

cp "$REPO/pins.json" "$WORK/pins.before"
printf 'version 0.61.0\nissued 1795281631\nsignature %s\n' "$(sign "$WORK/other.pem" "$WORK/pointer")" \
  >"$SRC/releases/latest/LATEST"
refuses_naming "a release pointer signed by another key" "not a release pointer signed by the release key" \
  bash "$UPDATES/scripts/refresh.sh" "$REPO"
cmp -s "$WORK/pins.before" "$REPO/pins.json" || fail "a refused pointer moved the release pin"
pointer "$WORK/key.pem"

refuses_naming "a key that is not the release key" "not the release key" \
  env JK_UPDATES_REQUIRE_TRUSTED_KEY=1 JK_UPDATES_RELEASE_SPKI="$trusted" \
  bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"
[[ ! -e "$REPO/updates.json" ]] || fail "a refused key wrote updates.json"

passes "the first manifest, whose only Python with tomllib is python3.99" \
  env PATH="$WORK/newer-python:$WORK/old-pythons:$PATH" JK_UPDATES_REQUIRE_TRUSTED_KEY=1 \
  JK_UPDATES_PUBLISHED=2026-11-22T04:00:00Z bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"
openssl base64 -d -A -in "$REPO/updates.json.sig" -out "$WORK/sig.bin"
openssl dgst -sha256 -verify "$WORK/public.pem" -signature "$WORK/sig.bin" "$REPO/updates.json" >/dev/null \
  || fail "updates.json.sig does not verify"
[[ "$(field "$REPO/updates.json" published)" == 2026-11-22T04:00:00Z ]] || fail "published is not the one asked for"
[[ "$(field "$REPO/updates.json" data.prices.version)" == 2026-11-21T18:00:00Z ]] || fail "prices is not versioned by its published date"
[[ "$(field "$REPO/updates.json" data.prices.sha256)" == "$(sha "$REPO/prices.toml")" ]] || fail "prices sha256 is wrong"
[[ "$(field "$REPO/updates.json" data.rates.version)" == 2026-11-21T19:00:00Z ]] || fail "rates is not versioned by its published instant"
[[ "$(field "$REPO/updates.json" data.rates.sha256)" == "$(sha "$REPO/rates.json")" ]] || fail "rates sha256 is wrong"
[[ "$(field "$REPO/updates.json" data.jdks.sha256)" == "$(sha "$REPO/jdks.json")" ]] || fail "jdks sha256 is wrong"
[[ "$(field "$REPO/updates.json" data.libraries.file)" == libraries.toml ]] || fail "libraries is not listed"
[[ "$(field "$REPO/updates.json" data.templates.version)" == "$templates_commit" ]] || fail "templates is not listed"
[[ "$(field "$REPO/updates.json" jk.stable.version)" == 0.60.0 ]] || fail "the release is not listed"

cp "$REPO/updates.json" "$WORK/manifest.before"
passes "an unchanged checkout" env JK_UPDATES_PUBLISHED=2026-11-22T05:00:00Z \
  bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"
grep -qF "nothing to sign" "$WORK/last.log" || fail "an unchanged checkout did not say so"
cmp -s "$WORK/manifest.before" "$REPO/updates.json" || fail "an unchanged checkout was signed again"

printf '\n[models."new-model"]\ninput = 1.0\noutput = 2.0\nretrieved = "2026-11-22"\n' >>"$REPO/prices.toml"
refuses_naming "a changed table with the same published date" "move it forward" \
  env JK_UPDATES_PUBLISHED=2026-11-22T05:00:00Z bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"
cmp -s "$WORK/manifest.before" "$REPO/updates.json" || fail "a refused table changed updates.json"

sed -i.bak 's/^published = .*/published = 2026-11-22T09:00:00Z/' "$REPO/prices.toml"
cp "$REPO/rates.json" "$WORK/rates.before"
sed -i.bak 's/"input": 10,/"input": 11,/' "$REPO/rates.json"
refuses_naming "changed rates with the same published instant" "rates.json changed but published" \
  env JK_UPDATES_PUBLISHED=2026-11-22T05:00:00Z bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"
printf '{"published": "2026-11-22T09:30:00Z", "rates": []}\n' >"$REPO/rates.json"
refuses_naming "a rates file with no rates" "rates.json has no rates" \
  env JK_UPDATES_PUBLISHED=2026-11-22T05:00:00Z bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"
cmp -s "$WORK/manifest.before" "$REPO/updates.json" || fail "refused rates changed updates.json"
sed 's/"input": 10,/"input": 11,/; s/2026-11-21T19:00:00Z/2026-11-22T09:30:00Z/' "$WORK/rates.before" >"$REPO/rates.json"
refuses_naming "a manifest published before the one held" "is not after the current manifest's" \
  env JK_UPDATES_PUBLISHED=2026-11-22T03:00:00Z bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"
passes "a table whose published date moved forward, signed by the key in the environment" \
  env JK_UPDATES_PUBLISHED=2026-11-22T10:00:00Z \
  JK_RELEASE_RSA_SIGNING_KEY="$(openssl pkey -in "$WORK/key.pem" -outform DER | openssl base64 -A)" \
  bash "$UPDATES/scripts/assemble.sh" "$REPO"
[[ "$(field "$REPO/updates.json" data.prices.version)" == 2026-11-22T09:00:00Z ]] || fail "the new table is not listed"
[[ "$(field "$REPO/updates.json" data.rates.version)" == 2026-11-22T09:30:00Z ]] || fail "the new rates are not listed"

passes "the served copy" env JK_UPDATES_VERIFY_TRIES=1 \
  bash "$UPDATES/scripts/verify-served.sh" "file://$REPO/updates.json" 2026-11-22T10:00:00Z
grep -qF "4 files match" "$WORK/last.log" || fail "verify-served did not check every listed file"
refuses_naming "a stale served copy" "expected 2026-11-22T11:00:00Z" \
  env JK_UPDATES_VERIFY_TRIES=1 bash "$UPDATES/scripts/verify-served.sh" "file://$REPO/updates.json" 2026-11-22T11:00:00Z
printf '# edited after signing\n' >>"$REPO/libraries.toml"
refuses_naming "a file edited after signing" "libraries: file://$REPO/libraries.toml does not match" \
  env JK_UPDATES_VERIFY_TRIES=1 bash "$UPDATES/scripts/verify-served.sh" "file://$REPO/updates.json"

printf '[{"vendor": "x"}]\n' >"$REPO/jdks.json"
refuses_naming "a JDK file that is not the feed" "jdks.json is not the JDK feed" \
  env JK_UPDATES_PUBLISHED=2026-11-22T12:00:00Z bash "$UPDATES/scripts/assemble.sh" "$REPO" "$WORK/key.pem"

echo "test-jk-updates: ok"
