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

## Artifact layout (`https://jumpkick.build/releases`)

```text
releases/
  latest/
    VERSION                 # single line, e.g. 0.10.1
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

## Signing

- Algorithm: **Ed25519** over the raw `SHA256SUMS` file bytes.
- Public key: baked into `ReleaseVerifier.BUILT_IN_KEY` (base64 X.509/SPKI).
- Private key: GitHub Actions secret **`JK_RELEASE_SIGNING_KEY`** (PKCS#8 DER, base64).
- Local sign: `JK_RELEASE_SIGNING_KEY=… scripts/sign-release.sh path/to/SHA256SUMS`
- Additional host keys: `[release] trusted-keys` in `~/.jk/config.toml`.

Verification is **fail-closed** for release installs/self-update when a signature is present
or required. Do not ship releases without `.sig` once the baked-in key is non-empty.

## CI release (tag-triggered)

Workflow: [`.github/workflows/release.yml`](../.github/workflows/release.yml)

1. Push tag `v0.10.1` (must match `JkVersion` without the `v` prefix, or set `JK_VERSION`).
2. Matrix builds native client + engine jar per OS/arch.
3. `scripts/assemble-release-dir.sh` produces per-platform dirs + `SHA256SUMS` + `.sig`.
4. Upload to **jumpkick.build** object storage (configure secrets — see workflow comments).
5. Update `latest/VERSION`.

### Required secrets (hosting)

| Secret | Role |
|--------|------|
| `JK_RELEASE_SIGNING_KEY` | PKCS#8 base64 Ed25519 private key |
| `JK_RELEASE_UPLOAD_*` | Provider-specific upload credentials (R2/S3/GCS — set when bucket exists) |

Until upload secrets exist, the workflow still **builds and signs** artifacts as workflow
artifacts for a staged dry-run.

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
