# Releases

How JumpKick ships installable binaries. For day-to-day use see [user install](../user/install.md).

## Versioning

| Line | Meaning |
|------|---------|
| **`0.13.4`** | Current product version (no `-SNAPSHOT` on `main`) |
| Tag **`v0.13.4`** | Next public release cut from that line |
| Prior | **`0.13.3`** — previous tagged release; **`0.10.1`** first public |
| Later | Semver-ish: `0.13.4`, `0.14.0`, … |

Bump `JkVersion.VERSION`, the workspace `jk.toml` `version` and the installers' pointer floor
(`RELEASE_FLOOR` in `install.sh`, `$ReleaseFloor` in `install.ps1`, mirrored under
`hosting/public/`) together — search for the old version string.
`InstallerCopyTest` fails when the floor and `JkVersion` disagree.

## Highlights

One `### <version>` entry per release, newest first, written before the tag is pushed: what a
user deciding whether to update needs to know, in a handful of bullets. `scripts/release-notes.sh
<version>` puts the entry at the top of the GitHub Release notes, ahead of the commit list since the
previous tag, and refuses a version that has none — a release whose notes are only a commit list
has nothing to say. This section is the one home for release highlights; there is no CHANGELOG.

### 0.13.4

- **The JVM client.** `jk-<version>.jar` ships beside the engine jar, and the installers install
  it on every host with no native client — macOS on Intel, Windows on ARM (`JK_CLIENT=jvm`),
  Linux on ARM, FreeBSD, Solaris, anything a JDK 25 runs on — as `bin/jk` (`bin/jk.bat`) over the
  JDK you provide. `jk self update` moves that install too.
- **One build system.** jk builds, tests, guards and releases itself with jk alone; the Gradle
  build of the product tree is gone, and CI has one gate.
- **Compile keys are ABI tokens.** Java, Kotlin and Groovy compiles key their classpath on each
  entry's ABI, so a body-only change in a dependency is a cache hit downstream, and `jk explain`
  says before compiling whether an edit is likely to leave its consumers' keys intact.
- **`jk install` is a build with a copy at the end**: the same live region, bar and countdown as
  `jk build`, then the install lines above one wedge. Installing the product tree twice reaches a
  fixed point, the shelf records which engine packaged each worker, and `jk doctor` compares it
  with the home's engine.
- **`jk publish --sbom`** writes CycloneDX 1.6 and SPDX documents; every SBOM jk writes comes from
  one writer, and a release ships the SBOM jk wrote of itself with provenance attestations.
- **CI and workflows are held by guards**: every action pinned to a commit, every token
  read-only unless a job names the scope it writes, the lock audited at HIGH on every pull request.
- Smaller: `-m a -m b` accumulates; `jk test --class` names a class its tag filter dropped and the
  flag that runs it; `jk dev` waits for the app's own `[dev]` ready probe; the JUnit XML report
  carries each class's console; `jk mvn` and `jk gradle` document the options they keep.

### 0.13.3

- `jk audit` is a gate: it exits non-zero on any unignored finding at or above `--severity`, names
  OSV's lowest fixed version above the locked one, and prints one JSON line per finding.
  `[audit] ignore` in `jk.toml` accepts an advisory with a reason and an optional expiry.
- `jk test --coverage` runs every suite under the JaCoCo agent with a report per module, and the
  coverage ratchet is a `jk-guards.toml` rule. `jk test --class` runs only the named classes;
  `jk test --debug-jvm` and `jk run --debug-jvm` start the JVM with a JDWP listener.
- The engine logs through one leveled facade to a capped log that rolls itself; `jk engine status`
  reports the log's size and a connection that never speaks is closed and counted.
- Workers start from an environment allow-list; `[env] inherit` opts a module back in.
- Every jk-built Java module compiles under NullAway with JSpecify null-marking, in jk's own build.
- The release workflow ships what jk builds of itself, bootstrapped from the hosted release
  `.jk/ci-bootstrap-version` pins.

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
    LATEST                  # signed pointer: `version 0.13.4` + `issued <unix-seconds>`, LF each
    LATEST.sig              # base64 RSA/SHA-256 signature over the exact LATEST bytes
    VERSION                 # bare version — a redirect-compatible convenience nothing verifies
                            # (all three: Cache-Control: no-cache)
  0.13.4/
    jk-linux-x86_64-0.13.4.xz
    jk-linux-aarch64-0.13.4.xz
    jk-macos-x86_64-0.13.4.xz
    jk-macos-aarch64-0.13.4.xz
    jk-windows-x86_64-0.13.4.xz    # self-update (engine inflates; no system xz needed)
    jk-windows-x86_64-0.13.4.zip   # install.ps1 / jk.bat only
    jk-engine-0.13.4.jar
    jk-0.13.4.jar                  # the JVM client: every host with no native client (install.sh
                                   # falls back to it; install.ps1 on JK_CLIENT=jvm)
    SHA256SUMS              # coreutils: <hex>  <filename>
    SHA256SUMS.sig          # base64 RSA/SHA-256 signature over exact SHA256SUMS bytes
```

`install.sh` and the Unix `jk` wrapper fetch `jk-<os>-<arch>-<version>.xz`. `jk.bat` /
`install.ps1` fetch the Windows `.zip`. On a host with no native client, `install.sh` fetches
`jk-<version>.jar` and `jk-engine-<version>.jar` instead (so does `install.ps1` on
`JK_CLIENT=jvm`, and `jk self update` from a JVM install); both are platform-neutral, built by
every platform job, and `scripts/flatten-release.sh` takes linux-x86_64's copy after checking the
others are byte-identical. Every artifact name carries the version: the
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

The workflow's token is read-only except where a job names the scope it writes, and every action
it uses is pinned to a commit SHA with the tag in a trailing comment — a floating tag in the job
that holds the signing key would be a signed release someone else cut. The
`workflow-pins-permissions` guard refuses a floating tag or a writing top-level `permissions:`
under `jk guard`, `scripts/check-workflows.sh` refuses the same in CI's workflow-lint job, and
`.github/dependabot.yml` moves the pins weekly.

1. Push tag `v0.13.4` (must match `JkVersion` without the `v` prefix, or set `JK_VERSION`).
2. Matrix builds native client + engine jar per OS/arch — with jk itself (`jk build`, the layout
   under `target/dist`). The jk that builds is the hosted release `.jk/ci-bootstrap-version` pins,
   so the matrix has a row for every platform jumpkick.build serves a client for at that pin
   ([below](#platforms-without-a-hosted-client)). The linux-x86_64 lane also runs `jk install`,
   so the first-party plugins it stages for `repo/` are the commit's own.
3. `scripts/assemble-release-dir.sh` (with `DIST_DIR` naming the dist) produces per-platform dirs +
   `SHA256SUMS` + `.sig`.
4. The publish job first writes the release notes (`scripts/release-notes.sh <version>`: the
   version's entry under [Highlights](#highlights), then the commits since the previous tag) and
   refuses a version with no entry before anything is downloaded.
5. It flattens the five trees into one (`scripts/flatten-release.sh`, refusing a partial matrix
   or a differing engine jar), re-signs the combined `SHA256SUMS`, takes the CycloneDX SBOM the
   linux-x86_64 build wrote of the engine (`jk publish --sbom --dry-run` in `server/engine`,
   which leaves `target/server/engine/sbom/jk-engine-<version>.cdx.json` at the workspace root —
   the document the engine jar embeds
   under `META-INF/sbom/`, derived from `jk-lock.toml`) as `out/sbom/jk-<version>.cdx.json` —
   beside the tree, so the signed `SHA256SUMS` the installers verify is untouched — and drafts the
   GitHub Release for the tag with the tree, the SBOM and the notes
   (`scripts/publish-github-release.sh draft`; the tag must exist, the script never cuts one).
6. **`gsutil rsync`** to GCS when secrets are set, then the pointer
   (`scripts/sign-latest-pointer.sh`): `LATEST.sig` first, then `LATEST`, then `VERSION`, all
   with no-cache headers. A client reading between the two copies gets a signature refusal and
   retries; it never gets an unverified version.
7. `actions/attest-build-provenance` stores one build-provenance attestation per client, the
   engine jar and the SBOM (`gh attestation verify <file> --repo <owner>/<repo>` checks one), and
   on a tag push the draft is published and marked latest (`publish-github-release.sh publish`).
   A `workflow_dispatch` run leaves the draft in place: that is the dry run.
8. Bump `.jk/ci-bootstrap-version` to the new release and add a matrix row for every platform
   it shipped a client for ([self-host](self-host.md#the-bootstrap-pin)).

### Platforms without a hosted client

jumpkick.build serves native clients for **linux-x86_64** and **macos-aarch64**. Linux aarch64,
macOS x86_64 and Windows x86_64 have no hosted native client, so no CI job can bootstrap jk on
them: their release rows and the Windows product smoke are absent until a first client exists, and
`scripts/flatten-release.sh` refuses to publish a tree short of the five clients, so releases stay
manual until then.

Users on those hosts — and on every host no native client will ever be built for — install the
**JVM client** instead: `jk-<version>.jar` is the CLI module's assembly (`[application] assembly =
true` in `clients/cli/jk.toml`), shipped under that name by `.jk/after-build-dist.kts` and
`scripts/assemble-release-dir.sh`, installed by both installers as `~/.jk/lib/jk/jk-<version>.jar`
with a launcher (`bin/jk`, `bin/jk.bat`) that `jk self write-launcher` writes over the JDK the
installer verified ([user install](../user/install.md#the-jvm-client)). It pairs with the engine
jar of its own version from the same release directory, so the pairing gap the closure on
`repo/` has does not apply to it.

The first client for a platform is produced by the owner on a machine of that architecture: a
JDK 25 with GraalVM, this checkout, and a jk to build it — the client module is a plain JVM
program (`cc.jumpkick.cli.Jk`, published as `cc.jumpkick:jk-cli:<version>` with its POM on
`jumpkick.build/repo/` by `jk install` + `scripts/publish-maven-repo.sh`), so on a machine with no
native client it runs from that closure on a JVM, and `jk install` on this checkout writes it as
`bin/jk-jvm` ([self-host](self-host.md#the-jvm-client)). The closure must be published for the
same version as the hosted engine: a JVM client pairs only with its own version's engine, and
`repo/` at 0.13.2 beside `releases/` at 0.13.3 pairs with nothing. `jk build --skip-tests` then
writes the native client for the host under `target/dist`, `DIST_DIR=target/dist
scripts/assemble-release-dir.sh` assembles it, and it is signed and uploaded beside the other
platforms' artifacts. Once
`releases/<version>/SHA256SUMS` lists the platform, its row joins `release.yml` (and, for
Windows and macOS x86_64, the nightly `os-smoke` matrix) with the pin bump.

### Required secrets

| Secret | Role |
|--------|------|
| `JK_RELEASE_RSA_SIGNING_KEY` | Base64 PKCS#8 DER RSA-3072 private key (signing) |
| `JK_RELEASE_GCS_BUCKET` | GCS bucket name only (no `gs://`), e.g. `jumpkick` |
| `JK_RELEASE_GCS_SA_JSON` | Service account JSON with object create/overwrite on that bucket |
| `GITHUB_TOKEN` (automatic) | Read-only in every job but `publish`, which holds `contents: write` for the GitHub Release and `id-token`/`attestations: write` for the provenance attestations |

Until GCS secrets exist, the workflow still **builds and signs** artifacts as GitHub Actions
workflow artifacts for a staged dry-run.

### Manual upload (ops)

Releases are published by hand today; the workflow is not dispatched. Order matters: the
version tree, then the pointer (signature before pointer), then the website — a freshly
deployed `install.sh` carries the new floor and refuses the old pointer until step 2 is done.

```bash
# 1. After assemble-release-dir.sh / flatten-release.sh (or the merged workflow artifact):
gsutil -m rsync -r -d build/release/0.13.4/ gs://$BUCKET/releases/0.13.4/

# 2. The signed pointer: LATEST.sig, then LATEST, then the VERSION convenience.
scripts/sign-latest-pointer.sh 0.13.4 build/release/latest /owner-only/path/release-key.pem
for object in LATEST.sig LATEST VERSION; do
  gsutil -h "Cache-Control:no-cache,max-age=0" cp "build/release/latest/$object" \
    "gs://$BUCKET/releases/latest/$object"
done

# 3. Verify through the public edge the installers use, with the baked-in public key:
curl -fsSL https://jumpkick.build/releases/latest/LATEST -o LATEST
curl -fsSL https://jumpkick.build/releases/latest/LATEST.sig | openssl base64 -d -A >LATEST.sig.bin
openssl dgst -sha256 -verify release-public.pem -signature LATEST.sig.bin LATEST   # "Verified OK"
cat LATEST                                                                        # version 0.13.4 / issued …

# 4. Deploy hosting/public (install.sh / install.ps1 with the matching floor).

# 5. The GitHub Release, from the same tree: the highlights entry must exist (step 4 of the CI
#    flow refuses without it), the tag must be pushed, GH_TOKEN must be able to write releases.
scripts/release-notes.sh 0.13.4 > RELEASE_NOTES.md
(cd server/engine && jk publish --sbom --dry-run)   # prints the path it wrote, under the root's target/
cp target/server/engine/sbom/jk-engine-0.13.4.cdx.json jk-0.13.4.cdx.json
scripts/publish-github-release.sh draft 0.13.4 RELEASE_NOTES.md build/release/0.13.4/* jk-0.13.4.cdx.json
scripts/publish-github-release.sh publish 0.13.4
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
