# Releases

How JumpKick ships installable binaries (JK-1066). For day-to-day use see [guide.md](guide.md).

## Versioning

| Line | Meaning |
|------|---------|
| **`0.10.1`** | Current product version (no `-SNAPSHOT` on `main`) |
| Tag **`v0.10.1`** | First public release cut from that line |
| Later | Semver-ish: `0.10.2`, `0.11.0`, … |

Bump `JkVersion.VERSION`, Gradle `version` in plugin conventions, and workspace `jk.toml`
coordinates together (search for the old version string).

## Hosting (GCS + Firebase CDN)

| Layer | Role |
|-------|------|
| **GCS** | Object storage for release blobs (`gs://jkbuild-releases/releases/<ver>/…`) |
| **Firebase CDN** | Public edge for `https://jumpkick.build` (wire later on Blaze) |
| **install.sh** | Fetches `https://jumpkick.build/releases/…` once Firebase fronts the bucket |

**Until Firebase Hosting is live**, the public HTTPS origin is:

```text
https://storage.googleapis.com/jkbuild-releases/releases/
```

Example install against GCS directly:

```bash
export JK_RELEASES_URL=https://storage.googleapis.com/jkbuild-releases/releases
curl -fsSL https://jumpkick.build/install.sh | bash   # or install.sh from repo once URL is swapped
# or: bash install.sh with JK_RELEASES_URL set
```

Layout under the bucket (and under the CDN path `/releases`):

```text
releases/
  latest/
    VERSION                 # single line, e.g. 0.10.1  (Cache-Control: no-cache)
  0.10.1/
    jk-linux-x86_64.xz
    jk-linux-aarch64.xz
    jk-macos-x86_64.xz
    jk-macos-aarch64.xz
    jk-windows-x86_64.zip   # or .exe
    jk-engine-0.10.1.jar
    SHA256SUMS              # coreutils: <hex>  <filename>
    SHA256SUMS.sig          # base64 Ed25519 signature over SHA256SUMS bytes
```

`install.sh` and self-update read `latest/VERSION`, then fetch **only** from that version
directory so a mid-install publish cannot mix artifacts.

Wire Firebase Hosting (or Firebase CDN / load balancer) so `jumpkick.build/releases/*` is
served from the GCS prefix `releases/*` (custom domain + backend bucket, or Hosting rewrites
to Cloud Storage — either is fine as long as the URL layout above is public HTTPS).

## Signing

- Algorithm: **Ed25519** over the raw `SHA256SUMS` file bytes.
- Public key: baked into `ReleaseVerifier.BUILT_IN_KEY` (base64 X.509/SPKI).
- Private key: GitHub Actions secret **`JK_RELEASE_SIGNING_KEY`** (PKCS#8 DER, base64).
- Local sign: `JK_RELEASE_SIGNING_KEY=… scripts/sign-release.sh path/to/SHA256SUMS`
- Additional host keys: `[release] trusted-keys` in `~/.config/jk/config.toml`.

Verification is **fail-closed** for release installs/self-update when a signature is present
or required. Do not ship releases without `.sig` once the baked-in key is non-empty.

## CI release (tag-triggered)

Workflow: [`.github/workflows/release.yml`](../.github/workflows/release.yml)

1. Push tag `v0.10.1` (must match `JkVersion` without the `v` prefix, or set `JK_VERSION`).
2. Matrix builds native client + engine jar per OS/arch.
3. `scripts/assemble-release-dir.sh` produces per-platform dirs + `SHA256SUMS` + `.sig`.
4. Merge job re-signs the combined tree, then **`gsutil rsync`** to GCS when secrets are set.
5. Update `releases/latest/VERSION` (no-cache headers).

### Required secrets

| Secret | Role |
|--------|------|
| `JK_RELEASE_SIGNING_KEY` | PKCS#8 base64 Ed25519 private key (signing) |
| `JK_RELEASE_GCS_BUCKET` | GCS bucket name only (no `gs://`), e.g. `jumpkick-releases` |
| `JK_RELEASE_GCS_SA_JSON` | Service account JSON with object create/overwrite on that bucket |

Until GCS secrets exist, the workflow still **builds and signs** artifacts as GitHub Actions
workflow artifacts for a staged dry-run.

### Manual upload (ops)

```bash
# After assemble-release-dir.sh (or downloading the merged workflow artifact):
gsutil -m rsync -r -d build/release/0.10.1/ gs://$BUCKET/releases/0.10.1/
echo 0.10.1 | gsutil -h "Cache-Control:no-cache,max-age=0" cp - \
  gs://$BUCKET/releases/latest/VERSION
```

## Local dry-run

```bash
./gradlew clean dist
export JK_RELEASE_SIGNING_KEY='…'   # optional for .sig
scripts/assemble-release-dir.sh
# inspect build/release/0.10.1/
```

## Rotation

1. Generate a new Ed25519 keypair.
2. Add the new public key to `ReleaseVerifier` (support both keys during transition if needed).
3. Store the new private key as the Actions secret; retire the old secret after all supported
   clients carry the new public key.
