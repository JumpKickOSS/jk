#!/usr/bin/env bash
# Write the signed update manifest of a jk-updates checkout: updates.json, which names the version,
# file and SHA-256 of each data file beside it and the JumpKick release pins.json holds, and
# updates.json.sig, the release key's signature over its exact bytes (base64 RSA/SHA-256 PKCS#1
# v1.5, one line), as the engine verifies them.
#
# Usage:
#   scripts/assemble.sh [dir] [private-key.pem]
#   JK_RELEASE_RSA_SIGNING_KEY='<base64 PKCS#8 DER>' scripts/assemble.sh [dir]
# dir defaults to the checkout this script sits in.
#
# Refused (exit 1, nothing written):
#   - a prices.toml without a published date-time or models, a libraries.toml that is not TOML,
#     a jdks.json that is not the JDK feed (an object whose jdks is an array), a malformed pins.json;
#   - a prices.toml whose bytes changed while its published date did not move past the table the
#     current updates.json lists: an engine keeps a table only when it is newer than its own;
#   - under JK_UPDATES_REQUIRE_TRUSTED_KEY=1, a key whose public half is not release-key.spki
#     (JK_UPDATES_RELEASE_SPKI overrides that file).
# When every entry and the release are the ones updates.json already lists, and it is signed,
# nothing is written. Otherwise published is now (JK_UPDATES_PUBLISHED overrides it) and must be
# after the current manifest's.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$HERE}"
KEY_FILE="${2:-${JK_RELEASE_RSA_SIGNING_KEY_FILE:-}}"

if [[ ! -d "$DIR" ]]; then
  echo "assemble: not a directory: $DIR" >&2
  exit 2
fi
for tool in python3 openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "assemble: $tool is required" >&2; exit 2; }
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-updates-assemble.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
umask 077

KEY_FORM=PEM
if [[ -n "$KEY_FILE" ]]; then
  [[ -f "$KEY_FILE" ]] || { echo "assemble: private key is not a file" >&2; exit 2; }
elif [[ -n "${JK_RELEASE_RSA_SIGNING_KEY:-}" ]]; then
  KEY_FILE="$WORK/release-key.der"
  printf '%s' "$JK_RELEASE_RSA_SIGNING_KEY" | openssl base64 -d -A >"$KEY_FILE" 2>/dev/null \
    || { echo "assemble: JK_RELEASE_RSA_SIGNING_KEY is not valid base64" >&2; exit 2; }
  KEY_FORM=DER
else
  echo "assemble: pass a private-key.pem or set JK_RELEASE_RSA_SIGNING_KEY" >&2
  exit 2
fi

signing="$(openssl pkey -inform "$KEY_FORM" -in "$KEY_FILE" -pubout -outform DER 2>/dev/null | openssl base64 -A)"
[[ -n "$signing" ]] || { echo "assemble: the private key does not load" >&2; exit 2; }
if [[ "${JK_UPDATES_REQUIRE_TRUSTED_KEY:-}" == 1 ]]; then
  trusted="${JK_UPDATES_RELEASE_SPKI:-$(tr -d '\r\n' <"$HERE/release-key.spki")}"
  if [[ "$signing" != "$trusted" ]]; then
    echo "assemble: the signing key is not the release key (release-key.spki); refusing to sign" >&2
    exit 1
  fi
fi

set +e
python3 - "$DIR" "$WORK/updates.json" <<'PY'
import datetime, hashlib, json, os, re, sys, tomllib

src, out = sys.argv[1], sys.argv[2]


def fail(message):
    print(f"assemble: {message}", file=sys.stderr)
    sys.exit(1)


def read(name):
    path = os.path.join(src, name)
    return open(path, "rb").read() if os.path.isfile(path) else None


def instant(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


def iso(moment):
    return moment.astimezone(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


held = None
held_bytes = read("updates.json")
if held_bytes is not None:
    try:
        held = json.loads(held_bytes)
        instant(held["published"])
    except (ValueError, KeyError, TypeError, AttributeError):
        fail("updates.json is not a manifest; restore it from the published copy")

data = {}

prices = read("prices.toml")
if prices is None:
    fail("no prices.toml")
try:
    table = tomllib.loads(prices.decode("utf-8"))
except (UnicodeDecodeError, tomllib.TOMLDecodeError) as e:
    fail(f"prices.toml is not TOML: {e}")
published = table.get("published")
if not isinstance(published, datetime.datetime) or published.tzinfo is None:
    fail("prices.toml: published must be an offset date-time")
if not isinstance(table.get("models"), dict) or not table["models"]:
    fail("prices.toml has no [models.\"<id>\"] tables")
data["prices"] = {"version": iso(published), "file": "prices.toml", "sha256": sha256(prices)}
was = (held or {}).get("data", {}).get("prices")
if was and was.get("sha256") != data["prices"]["sha256"] and not published > instant(was["version"]):
    fail(f"prices.toml changed but published {iso(published)} is not after {was['version']}; move it forward")

libraries = read("libraries.toml")
if libraries is not None:
    try:
        tomllib.loads(libraries.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as e:
        fail(f"libraries.toml is not TOML: {e}")
    data["libraries"] = {"version": sha256(libraries)[:12], "file": "libraries.toml", "sha256": sha256(libraries)}

jdks = read("jdks.json")
if jdks is not None:
    try:
        feed = json.loads(jdks)
    except ValueError as e:
        fail(f"jdks.json is not JSON: {e}")
    if not isinstance(feed, dict) or not isinstance(feed.get("jdks"), list):
        fail("jdks.json is not the JDK feed: no jdks array")
    data["jdks"] = {"version": sha256(jdks)[:12], "file": "jdks.json", "sha256": sha256(jdks)}

releases = {}
pins_bytes = read("pins.json")
if pins_bytes is not None:
    try:
        pins = json.loads(pins_bytes)
    except ValueError as e:
        fail(f"pins.json is not JSON: {e}")
    commit = pins.get("templates")
    if commit is not None:
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            fail("pins.json: templates must be a 40-hex commit")
        data["templates"] = {"version": commit}
    for channel, release in (pins.get("jk") or {}).items():
        if (not isinstance(release, dict)
                or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", str(release.get("version")))
                or not re.fullmatch(r"[0-9a-f]{64}", str(release.get("sha256")))):
            fail(f"pins.json: jk.{channel} needs a release version and the sha256 of its SHA256SUMS")
        releases[channel] = {"version": release["version"], "sha256": release["sha256"]}

if (held is not None
        and held.get("data") == data
        and (held.get("jk") or {}) == releases
        and os.path.isfile(os.path.join(src, "updates.json.sig"))):
    sys.exit(3)

now = os.environ.get("JK_UPDATES_PUBLISHED") or iso(
    datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0))
if held is not None and not instant(now) > instant(held["published"]):
    fail(f"published {now} is not after the current manifest's {held['published']}")

manifest = {"published": now}
if releases:
    manifest["jk"] = releases
manifest["data"] = data
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write(json.dumps(manifest, indent=2) + "\n")
print(f"assemble: updates.json published {now}: {', '.join(data)}"
      + (f", jk {', '.join(r['version'] for r in releases.values())}" if releases else ""))
PY
status=$?
set -e
if [[ $status -eq 3 ]]; then
  echo "assemble: updates.json already lists these files; nothing to sign"
  exit 0
fi
[[ $status -eq 0 ]] || exit "$status"

openssl dgst -sha256 -keyform "$KEY_FORM" -sign "$KEY_FILE" -out "$WORK/signature.bin" "$WORK/updates.json"
openssl pkey -inform "$KEY_FORM" -in "$KEY_FILE" -pubout -out "$WORK/public.pem"
openssl dgst -sha256 -verify "$WORK/public.pem" -signature "$WORK/signature.bin" "$WORK/updates.json" >/dev/null
{
  openssl base64 -A -in "$WORK/signature.bin"
  printf '\n'
} >"$WORK/updates.json.sig"
chmod 644 "$WORK/updates.json" "$WORK/updates.json.sig"
mv "$WORK/updates.json.sig" "$DIR/updates.json.sig"
mv "$WORK/updates.json" "$DIR/updates.json"
