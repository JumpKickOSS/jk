# Releases

How JumpKick ships installable binaries. For day-to-day use see [user install](../user/install.md).

## Versioning

| Line | Meaning |
|------|---------|
| **`0.13.3`** | Current product version (no `-SNAPSHOT` on `main`) |
| Tag **`v0.13.3`** | Next public release cut from that line |
| Prior | **`0.13.0`** — previous tagged release; **`0.10.1`** first public |
| Later | Semver-ish: `0.13.3`, `0.14.0`, … |

Bump `JkVersion.VERSION`, Gradle `version` in plugin conventions, workspace `jk.toml`
coordinates and the installers' pointer floor (`RELEASE_FLOOR` in `install.sh`, `$ReleaseFloor`
in `install.ps1`, mirrored under `hosting/public/`) together — search for the old version string.
`InstallerCopyTest` fails when the floor and `JkVersion` disagree.

## Hosting (GCS + Firebase CDN)

| Layer | Role |
|-------|------|
| **GCS** | Object storage for release blobs (`gs://jumpkick/releases/<ver>/…`) |
| **Firebase CDN** | Public edge for `https://jumpkick.build` (wire later on Blaze) |
| **install.sh** | Fetches `https://jumpkick.build/releases/…` (Linux / macOS) |
| **install.ps1** | Fetches the Windows `.zip` from the same tree (`irm … \| iex`) |

The product only knows `https://jumpkick.build/releases/`. Hosting 302s that prefix to
the current object store (GCS today). Override with `JK_RELEASES_URL` for a mirror
or an air-gapped origin — never bake a bucket hostname into the client.

```bash
curl -fsSL https://jumpkick.build/install.sh | bash
# air-gap / mirror:
export JK_RELEASES_URL=https://mirror.example/releases
```

```powershell
irm https://jumpkick.build/install.ps1 | iex
# air-gap / mirror:
$env:JK_RELEASES_URL = "https://mirror.example/releases"
```

Layout under the bucket (and under the CDN path `/releases`):

```text
releases/
  latest/
    LATEST                  # signed pointer: `version 0.13.3` + `issued <unix-seconds>`, LF each
    LATEST.sig              # base64 RSA/SHA-256 signature over the exact LATEST bytes
    VERSION                 # bare version — a redirect-compatible convenience nothing verifies
                            # (all three: Cache-Control: no-cache)
  0.13.3/
    jk-linux-x86_64-0.13.3.xz
    jk-linux-aarch64-0.13.3.xz
    jk-macos-x86_64-0.13.3.xz
    jk-macos-aarch64-0.13.3.xz
    jk-windows-x86_64-0.13.3.xz    # self-update (engine inflates; no system xz needed)
    jk-windows-x86_64-0.13.3.zip   # install.ps1 / jk.bat only
    jk-engine-0.13.3.jar
    SHA256SUMS              # coreutils: <hex>  <filename>
    SHA256SUMS.sig          # base64 RSA/SHA-256 signature over exact SHA256SUMS bytes
```

`install.sh` and the Unix `jk` wrapper fetch `jk-<os>-<arch>-<version>.xz`. `jk.bat` /
`install.ps1` fetch the Windows `.zip`. Every artifact name carries the version: the
manifest is signed but not bound to its directory, so a valid manifest copied from an older
release into a newer version's directory names only the older artifacts and satisfies no
request for the newer one. `jk self update` prefers `.xz` on every OS
(the engine jar inflates; the native CLI does not link tukaani) and falls back to
`.zip` on Windows when the sums have no xz entry. All of them read `latest/LATEST` and
`latest/LATEST.sig`, verify the signature, read the pointer literally (exactly the two lines
above; a CRLF, a bare version or a third line is refused), then fetch **only** from the version
directory it names so a mid-install publish cannot mix artifacts.

The pointer is the one mutable object under `releases/`, so it is the one a bucket writer or an
interposed `JK_RELEASES_URL` mirror would rewrite. Signing it stops an edited pointer; the floor
stops a rolled-back one — a valid old pointer re-served as current. The installers refuse a
pointer older than the release they ship with (`RELEASE_FLOOR` / `$ReleaseFloor`, equal to
`JkVersion.VERSION`); `jk self update` refuses one older than the version it runs. The
committed wrappers refuse one below the lock's `jk-min` floor. `JK_VERSION` and
`jk self update <version>` never read the pointer and remain the deliberate way to a specific
release, down included.

Wire Firebase Hosting (or Firebase CDN / load balancer) so `jumpkick.build/releases/*` is
served from the GCS prefix `releases/*` (custom domain + backend bucket, or Hosting rewrites
to Cloud Storage — either is fine as long as the URL layout above is public HTTPS).

## Signing

- Algorithm: **SHA256withRSA**, RSA-3072, PKCS#1 v1.5, over the exact `SHA256SUMS` bytes and,
  with the same key, over the exact `latest/LATEST` bytes.
- Public key: baked into `ReleaseVerifier.BUILT_IN_KEY` (base64 X.509/SPKI), with the same
  modulus/exponent embedded in the stock PowerShell verifier.
- Private key: GitHub Actions secret **`JK_RELEASE_RSA_SIGNING_KEY`** (base64 PKCS#8 DER).
- Local sign: `scripts/sign-release.sh path/to/SHA256SUMS /owner-only/path/release-key.pem`
- Pointer: `scripts/sign-latest-pointer.sh <version> <out-dir> /owner-only/path/release-key.pem`
  writes `LATEST`, `LATEST.sig` and `VERSION` (fixtures: `scripts/test-installer-verification.sh`
  and `.ps1` cover an unsigned, a tampered, a rolled-back and a malformed pointer).
- Additional host keys: `[release] trusted-keys` in `~/.jk/config.toml`.

Remote installers, wrappers, self-update, and engine materialization all require the signatures.
They verify the pointer signature and its floor first, then the manifest signature, require one
strict exact artifact entry, then verify the artifact hash before extracting, parking an existing
binary, writing, or executing downloaded bytes. Local file installs remain an explicit unsigned
development path. `scripts/test-wrapper-bootstrap.sh` drives the POSIX wrapper through that whole
path, network-free, against a fixture release signed with a throwaway key.

## CI release (tag-triggered)

Workflow: [`.github/workflows/release.yml`](../../.github/workflows/release.yml)

1. Push tag `v0.13.3` (must match `JkVersion` without the `v` prefix, or set `JK_VERSION`).
2. Matrix builds native client + engine jar per OS/arch — with jk itself (`jk build`, the layout
   under `target/dist`), bootstrapped from that commit's Gradle artifacts until a release built this
   way is hosted. Windows still ships from `./gradlew dist` (its self-host lane is not green yet).
3. `scripts/assemble-release-dir.sh` (with `DIST_DIR` naming the dist) produces per-platform dirs +
   `SHA256SUMS` + `.sig`.
4. Merge job flattens the five trees into one (`scripts/flatten-release.sh`, refusing a partial
   matrix or a differing engine jar), re-signs the combined `SHA256SUMS`, then **`gsutil rsync`**
   to GCS when secrets are set.
5. Sign and upload the pointer (`scripts/sign-latest-pointer.sh`): `LATEST.sig` first, then
   `LATEST`, then `VERSION`, all with no-cache headers. A client reading between the two copies
   gets a signature refusal and retries; it never gets an unverified version.

### Required secrets

| Secret | Role |
|--------|------|
| `JK_RELEASE_RSA_SIGNING_KEY` | Base64 PKCS#8 DER RSA-3072 private key (signing) |
| `JK_RELEASE_GCS_BUCKET` | GCS bucket name only (no `gs://`), e.g. `jumpkick` |
| `JK_RELEASE_GCS_SA_JSON` | Service account JSON with object create/overwrite on that bucket |

Until GCS secrets exist, the workflow still **builds and signs** artifacts as GitHub Actions
workflow artifacts for a staged dry-run.

### Manual upload (ops)

Releases are published by hand today; the workflow is not dispatched. Order matters: the
version tree, then the pointer (signature before pointer), then the website — a freshly
deployed `install.sh` carries the new floor and refuses the old pointer until step 2 is done.

```bash
# 1. After assemble-release-dir.sh / flatten-release.sh (or the merged workflow artifact):
gsutil -m rsync -r -d build/release/0.13.3/ gs://$BUCKET/releases/0.13.3/

# 2. The signed pointer: LATEST.sig, then LATEST, then the VERSION convenience.
scripts/sign-latest-pointer.sh 0.13.3 build/release/latest /owner-only/path/release-key.pem
for object in LATEST.sig LATEST VERSION; do
  gsutil -h "Cache-Control:no-cache,max-age=0" cp "build/release/latest/$object" \
    "gs://$BUCKET/releases/latest/$object"
done

# 3. Verify through the public edge the installers use, with the baked-in public key:
curl -fsSL https://jumpkick.build/releases/latest/LATEST -o LATEST
curl -fsSL https://jumpkick.build/releases/latest/LATEST.sig | openssl base64 -d -A >LATEST.sig.bin
openssl dgst -sha256 -verify release-public.pem -signature LATEST.sig.bin LATEST   # "Verified OK"
cat LATEST                                                                        # version 0.13.3 / issued …

# 4. Deploy hosting/public (install.sh / install.ps1 with the matching floor).
```

`release-public.pem` is the SPKI in `ReleaseVerifier.BUILT_IN_KEY` wrapped in
`-----BEGIN PUBLIC KEY-----` / `-----END PUBLIC KEY-----` at 64 columns.

## Local dry-run

```bash
jk build --skip-tests                      # target/dist/jk + target/dist/lib/jk-engine-<ver>.jar
export JK_RELEASE_RSA_SIGNING_KEY_FILE=/owner-only/path/release-key.pem
DIST_DIR=target/dist scripts/assemble-release-dir.sh
# inspect build/release/0.13.3/
```

## Rotation

1. Generate a new RSA-3072 keypair in owner-only credential storage.
2. Replace the Java SPKI and installer SPKI/modulus/exponent together.
3. Run the public-key consistency and installer verification fixtures.
4. Replace the Actions secret only after the clients and public installers carrying the new key
   are ready to publish atomically.
