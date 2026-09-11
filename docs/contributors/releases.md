# Releases

How JumpKick ships installable binaries. For day-to-day use see [user install](../user/install.md).

## Versioning

| Line | Meaning |
|------|---------|
| **`0.13.2`** | Current product version (no `-SNAPSHOT` on `main`) |
| Tag **`v0.13.2`** | Next public release cut from that line |
| Prior | **`0.13.0`** — previous tagged release; **`0.10.1`** first public |
| Later | Semver-ish: `0.13.3`, `0.14.0`, … |

Bump `JkVersion.VERSION`, Gradle `version` in plugin conventions, and workspace `jk.toml`
coordinates together (search for the old version string).

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
    VERSION                 # single line, e.g. 0.13.2  (Cache-Control: no-cache)
  0.13.2/
    jk-linux-x86_64-0.13.2.xz
    jk-linux-aarch64-0.13.2.xz
    jk-macos-x86_64-0.13.2.xz
    jk-macos-aarch64-0.13.2.xz
    jk-windows-x86_64-0.13.2.xz    # self-update (engine inflates; no system xz needed)
    jk-windows-x86_64-0.13.2.zip   # install.ps1 / jk.bat only
    jk-engine-0.13.2.jar
    SHA256SUMS              # coreutils: <hex>  <filename>
    SHA256SUMS.sig          # base64 RSA/SHA-256 signature over exact SHA256SUMS bytes
```

`install.sh` and the Unix `jk` wrapper fetch `jk-<os>-<arch>-<version>.xz`. `jk.bat` /
`install.ps1` fetch the Windows `.zip`. Every artifact name carries the version: the
manifest is signed but not bound to its directory, so a valid manifest copied from an older
release into a newer version's directory names only the older artifacts and satisfies no
request for the newer one. `jk self update` prefers `.xz` on every OS
(the engine jar inflates; the native CLI does not link tukaani) and falls back to
`.zip` on Windows when the sums have no xz entry. All three read `latest/VERSION`,
then fetch **only** from that version directory so a mid-install publish cannot mix
artifacts.

Wire Firebase Hosting (or Firebase CDN / load balancer) so `jumpkick.build/releases/*` is
served from the GCS prefix `releases/*` (custom domain + backend bucket, or Hosting rewrites
to Cloud Storage — either is fine as long as the URL layout above is public HTTPS).

## Signing

- Algorithm: **SHA256withRSA**, RSA-3072, PKCS#1 v1.5, over the exact `SHA256SUMS` bytes.
- Public key: baked into `ReleaseVerifier.BUILT_IN_KEY` (base64 X.509/SPKI), with the same
  modulus/exponent embedded in the stock PowerShell verifier.
- Private key: GitHub Actions secret **`JK_RELEASE_RSA_SIGNING_KEY`** (base64 PKCS#8 DER).
- Local sign: `scripts/sign-release.sh path/to/SHA256SUMS /owner-only/path/release-key.pem`
- Additional host keys: `[release] trusted-keys` in `~/.jk/config.toml`.

Remote installers, wrappers, self-update, and engine materialization all require the signature.
They verify the manifest signature first, require one strict exact artifact entry, then verify the
artifact hash before extracting, parking an existing binary, writing, or executing downloaded
bytes. Local file installs remain an explicit unsigned development path.

## CI release (tag-triggered)

Workflow: [`.github/workflows/release.yml`](../../.github/workflows/release.yml)

1. Push tag `v0.13.2` (must match `JkVersion` without the `v` prefix, or set `JK_VERSION`).
2. Matrix builds native client + engine jar per OS/arch.
3. `scripts/assemble-release-dir.sh` produces per-platform dirs + `SHA256SUMS` + `.sig`.
4. Merge job re-signs the combined tree, then **`gsutil rsync`** to GCS when secrets are set.
5. Update `releases/latest/VERSION` (no-cache headers).

### Required secrets

| Secret | Role |
|--------|------|
| `JK_RELEASE_RSA_SIGNING_KEY` | Base64 PKCS#8 DER RSA-3072 private key (signing) |
| `JK_RELEASE_GCS_BUCKET` | GCS bucket name only (no `gs://`), e.g. `jumpkick` |
| `JK_RELEASE_GCS_SA_JSON` | Service account JSON with object create/overwrite on that bucket |

Until GCS secrets exist, the workflow still **builds and signs** artifacts as GitHub Actions
workflow artifacts for a staged dry-run.

### Manual upload (ops)

```bash
# After assemble-release-dir.sh (or downloading the merged workflow artifact):
gsutil -m rsync -r -d build/release/0.13.2/ gs://$BUCKET/releases/0.13.2/
echo 0.13.2 | gsutil -h "Cache-Control:no-cache,max-age=0" cp - \
  gs://$BUCKET/releases/latest/VERSION
```

## Local dry-run

```bash
./gradlew clean dist
export JK_RELEASE_RSA_SIGNING_KEY_FILE=/owner-only/path/release-key.pem
scripts/assemble-release-dir.sh
# inspect build/release/0.13.2/
```

## Rotation

1. Generate a new RSA-3072 keypair in owner-only credential storage.
2. Replace the Java SPKI and installer SPKI/modulus/exponent together.
3. Run the public-key consistency and installer verification fixtures.
4. Replace the Actions secret only after the clients and public installers carrying the new key
   are ready to publish atomically.
