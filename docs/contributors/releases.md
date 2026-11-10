# Releases

How JumpKick ships installable binaries. For day-to-day use see [user install](../user/install.md).

## Versioning

What a release may change in the formats projects depend on: [Compatibility](compatibility.md).

| Line | Meaning |
|------|---------|
| **`version`** in the root `jk.toml` | The next release, never written with `-SNAPSHOT` |
| Tag **`v<version>`** | Cut from that line when it is released |
| Prior | The newest entry in [What's new](../user/whats-new.md); **`0.10.1`** was the first public release |
| Later | Semver-ish: `0.30.1`, `0.31.0`, … |

The root `jk.toml` `version` is the one place the version is written, and it always names the
next release. `JkVersion.VERSION` is stamped from it by the build (`jk-version.properties` in
`jk-model`, filtered from `${project.version}`), so a `jk snapshot` of the tree reports
`<version>-SNAPSHOT` everywhere — `-V`, the wire handshake, worker coordinates, first-party lock
pins — and `jk install` of the same tree reports `<version>`. Bump the manifest and the installers'
pointer floor (`RELEASE_FLOOR` in `install.sh`, `$ReleaseFloor` in `install.ps1`, mirrored under
`hosting/public/`) together — search for the old version string, which also finds the third-party
plugin sample (`docs/user/examples/third-party-plugin`) and the SDK pin in `plugins.md`. `InstallerCopyTest` fails when the
floor and the manifest version disagree. The release scripts read the version from `jk.toml`.

## Highlights

Each release's highlights are written for users in [What's new](../user/whats-new.md): one `## <version>`
entry per release, newest first, written before the tag is pushed. That page is their only home; there
is no CHANGELOG. They go to three places:

- the top of the GitHub Release notes, ahead of the commit list (folded under a `<details>`), via
  `scripts/release-notes.sh <version>`;
- the release tree, as `jk-whats-new-<version>.md` (this entry and the four before it),
  listed in the signed `SHA256SUMS`. A self-update downloads it with the engine jar, and
  JumpKick shows the entries newer than the version it replaced;
- the page itself, for anyone browsing the docs.

`scripts/release-notes.sh` refuses a version with no entry, and refuses an entry that breaks the
rules below. The commit log is for contributors; this entry is for someone deciding whether to update.

### Writing an entry

- **One to five bullets.** Pick the changes a user will notice and leave the rest to the commit list.
  Lead with the biggest one: the in-app "What's new" shows the first bullets first.
- **Every bullet opens with a bold hook**, a short sentence that names the benefit in the user's
  words: `- **Cold builds download in half the time.**` Not the mechanism (`- **Parallel sync
  staging.**`), not a ticket (`- Fixed JK-4XQ2R`).
- **Then at most one or two plain sentences** saying what changed and what to do, if anything.
  Hard limits: 40 to 320 characters per bullet, no ticket ids. Name commands, flags and config keys
  in backticks; don't name classes, internal steps or wire formats.
- **Fixes say what used to go wrong**, from the user's side: "`jk test` no longer hangs when a test
  prints without a newline", not "fix non-hermetic index repro".
- **A breaking change's bullet starts with `**Breaking:**`** and says how to migrate
  ([Compatibility](compatibility.md#announcing)).
- **Read it out loud.** If it sounds like a commit message, rewrite it. If it needs a second
  paragraph, it belongs in the docs and the bullet links there.

## Benchmarks each release

After the release is installed on the benchmark host, re-run the agent loop and bank it:

```bash
bench/agent-loop/scenario --baseline && bench/agent-loop/scenario --verify --jobs 2 --report bench/agent-loop/VERIFY.md
bench/agent-loop/harness --driver <llm driver> --results bench/agent-loop/results/<date>-<version>
```

Then update the turns-to-green table in [Why JumpKick](../user/why.md#agents-reach-green)
with the commit, the tool versions and the host, and the headline numbers in the README and
[Comparison](../user/comparison.md). A release whose table regresses says so in its highlights.

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
    LATEST                  # one signed object, LF each, Cache-Control: no-cache:
                            #   version <v>
                            #   issued <unix-seconds>
                            #   signature <base64>   # over the exact first two lines
    VERSION                 # bare version — a redirect-compatible convenience nothing verifies
  0.13.6/
    jk-linux-x86_64-0.13.6.gz      # install.sh (gunzip)
    jk-linux-x86_64-0.13.6.xz      # self-update (engine inflates)
    jk-linux-aarch64-0.13.6.gz
    jk-linux-aarch64-0.13.6.xz
    jk-macos-x86_64-0.13.6.gz
    jk-macos-x86_64-0.13.6.xz
    jk-macos-aarch64-0.13.6.gz
    jk-macos-aarch64-0.13.6.xz
    jk-windows-x86_64-0.13.6.xz    # self-update (engine inflates; no system xz needed)
    jk-windows-x86_64-0.13.6.zip   # install.ps1 / jk.bat only
    jk-engine-0.13.6.jar
    jk-0.13.6.jar                  # the JVM client: every host these sums list no native
                                   # client for (installers and wrappers fall back to it)
    jk-maven-spy-0.13.6.jar        # the Maven core extension `jk mvn` attaches; a client fetches
                                   # its own version's on the first `jk mvn` (native and JVM alike)
    SHA256SUMS              # coreutils: <hex>  <filename>
    SHA256SUMS.sig          # base64 RSA/SHA-256 signature over exact SHA256SUMS bytes
```

`install.sh` fetches `jk-<os>-<arch>-<version>.gz` and inflates it with `gunzip`. `jk.bat` /
`install.ps1` fetch the Windows `.zip`. The Unix `jk` wrapper and `jk self update` fetch the
`.xz` beside that `.gz`. Each reads the signed `SHA256SUMS` first and takes the native client only
when it lists `jk-<os>-<arch>-<version>` for the host; on any other host the installers fetch
`jk-<version>.jar` and `jk-engine-<version>.jar` instead, the wrappers fetch `jk-<version>.jar`
(the client fetches its engine), and `jk self update` from a JVM install updates the jar, or swaps
in the native client once a release lists one for its host (unless `JK_CLIENT=jvm`). Publishing
a native client for a new platform needs no installer or wrapper change. No installer fetches
`jk-maven-spy-<version>.jar`: the client does, from the same version directory and against the
same signed sums, the first time `jk mvn` runs and finds none under `~/.jk/lib/` (a dist install
copies it there). All three jars are platform-neutral, built by every platform job, and
`scripts/flatten-release.sh` takes linux-x86_64's copy after checking the others are
byte-identical. That identity assumes one bootstrap: the embedded SBOM
(`META-INF/sbom/application.cdx.json`) names the JumpKick that packaged the jar as its tool, and nothing
else in an assembly varies between builds of one commit, so every lane packaging with the pinned
`.jk/ci/ci-bootstrap-version` writes the same bytes, while a dogfood tree whose install re-shelved
under the newly built engine writes a jar that differs by that one field. A release directory is
assembled from one build, never from a native binary of one and a jar of another. Every artifact
name carries the version: the
manifest is signed but not bound to its directory, so a valid manifest copied from an older
release into a newer version's directory names only the older artifacts and satisfies no
request for the newer one. `jk self update` prefers `.xz` on every OS
(the engine jar inflates; the native CLI does not link tukaani) and falls back to
`.zip` on Windows when the sums have no xz entry. All of them read `latest/LATEST`, one object
whose third line is the signature over the exact first two, verify that signature, read the
pointer literally (a CRLF, a bare version, a missing signature line or a fourth line is refused),
then fetch **only** from the version directory it names so a mid-install publish cannot mix artifacts.

The pointer is the one mutable object under `releases/`, so it is the one a bucket writer or an
interposed `JK_RELEASES_URL` mirror would rewrite. Signing it stops an edited pointer; the floor
stops a rolled-back one — a valid old pointer re-served as current. The installers refuse a
pointer older than the release they ship with (`RELEASE_FLOOR` / `$ReleaseFloor`, equal to
`JkVersion.VERSION`); `jk self update` refuses one older than the version it runs. The
committed wrappers refuse one below the lock's `jk-min` floor. `JK_VERSION` and
`jk self update <version>` never read the pointer and remain the deliberate way to a specific
release, down included.

The engine's own update (`SelfUpdateScheduler`, policy in `SelfUpdatePolicy`) fetches nothing
itself: each step is `jk self update --check`, `--download <version>` or `<version>` run on the
PATH client, so a scheduled update is held to exactly the evidence a manual one is. The scheduler
only exists in an engine running the jar its home installed, at a non-`-SNAPSHOT` version; test
sandboxes also carry `JK_SELF_UPDATE=never`. A download is recorded in
`lib/jk-engine/jk-update.toml` (`PreparedUpdate`) and pinned against the store GC until it
installs.

Wire Firebase Hosting (or Firebase CDN / load balancer) so `jumpkick.build/releases/*` is
served from the GCS prefix `releases/*` (custom domain + backend bucket, or Hosting rewrites
to Cloud Storage — either is fine as long as the URL layout above is public HTTPS).

## Signing

- Algorithm: **SHA256withRSA**, RSA-3072, PKCS#1 v1.5, over the exact `SHA256SUMS` bytes and,
  with the same key, over the exact first two lines of `latest/LATEST`.
- Public key: baked into `ReleaseVerifier.BUILT_IN_KEY` (base64 X.509/SPKI), with the same
  modulus/exponent embedded in the stock PowerShell verifier.
- Private key: GitHub Actions secret **`JK_RELEASE_RSA_SIGNING_KEY`** (base64 PKCS#8 DER).
- Local sign: `scripts/sign-release.sh path/to/SHA256SUMS /owner-only/path/release-key.pem`
- Pointer: `scripts/sign-latest-pointer.sh <version> <out-dir> /owner-only/path/release-key.pem`
  writes `LATEST` (the three-line object) and `VERSION` (fixtures:
  `scripts/test-installer-verification.sh` and `.ps1` cover an unsigned, a tampered, a rolled-back
  and a malformed pointer).
- Additional host keys: `[release] trusted-keys` in `~/.jk/config.toml`.

Remote installers, wrappers, self-update, and engine materialization all require the signatures.
They verify the pointer signature and its floor first, then the manifest signature, require one
strict exact artifact entry, then verify the artifact hash before extracting, parking an existing
binary, writing, or executing downloaded bytes. Local file installs remain an explicit unsigned
development path. `scripts/test-wrapper-bootstrap.sh` drives the POSIX wrapper through that whole
path, network-free, against a fixture release signed with a throwaway key.

## Cutting a release

A release starts with `jk release`, by hand (`jk release --push`) or from the **Cut release**
workflow (`cut-release.yml`, run from the Actions tab; `next` picks the segment that moves after the
release). It builds and tests the version `jk.toml` declares, installs it, tags the commit with the
`[release] tag` template, and commits the next version to `jk.toml`, the lock and the workspace
members before pushing the branch and the tag. Local development then continues on the next
version; `jk snapshot` is the inner loop for it.

CI and CD are two workflows. The pushed tag starts **CI** (`ci.yml`), which runs every test and
gate once and fails fast: a red run is the end of that release. A green CI run on a release tag
starts **CD** (`release.yml`), which builds, signs and publishes and re-runs none of the tests. A
push made with a workflow's `GITHUB_TOKEN` starts nothing, so the Cut release workflow (which skips
the tests: CI owns them) dispatches CI on the new tag itself. CI runs on no push to a branch and on
no pull request.

The `jk release` that Cut release runs is the pinned bootstrap release
([self-host](self-host.md#the-bootstrap-pin)), so a `jk release` feature, and a `jk.toml` key that
only a newer JumpKick reads, takes effect for CI one release after it ships.

## CI and CD (started by the release tag)

Workflows: [`ci.yml`](../../.github/workflows/ci.yml) (CI) and
[`release.yml`](../../.github/workflows/release.yml) (CD).

Nothing reaches the bucket, the pointer, the installers or the release page from a developer
machine.

```bash
jk release --patch --push        # tests, tags vX.Y.Z, commits "Start X.Y.Z+1", pushes both
gh run watch                     # CI, then CD: build, merge, and publish waiting for approval
```

**CI** (`ci.yml`, started by the tag push or a dispatch on the tag): the tag equals the version
`jk.toml` declares (`scripts/release-version.sh`), the commit authorship scan, the shell fixtures,
the self-host job, the curated integration lane, the release test tier (`jk test -p release`) and
the audit. This is the only place the tests
run in the cloud, and a red run stops the release.

**CD** (`release.yml`, started by CI finishing green on a release tag; a `workflow_dispatch` is the
dry run and never publishes):

1. **gate** — the CI run was green and its tag names the commit it tested.
2. **build** — one row per platform: linux-x86_64, linux-aarch64 (the arm runner of a public
   repository), macos-aarch64 and windows-x86_64, each skipping tests. No secret reaches these jobs.
3. **merge** — the version tree, the SBOM and the notes, with no secret. The dry run ends here with
   the unsigned tree as a workflow artifact.
4. **publish** — the one job that names the `release` environment ([below](#the-release-environment)),
   so the one that holds the secrets, and it starts only once a required reviewer approves the run.

The workflow's token is read-only except where a job names the scope it writes, and every action
it uses is pinned to a commit SHA with the tag in a trailing comment — a floating tag in the job
that holds the signing key would be a signed release someone else cut. The
`workflow-pins-permissions` guard refuses a floating tag or a writing top-level `permissions:`
under `jk guard`, `scripts/check-workflows.sh` refuses the same in CI's workflow-lint job, and
`.github/dependabot.yml` moves the pins weekly.

1. CI is green on the tag `v0.14.0` (`jk release --push`). The version must equal what `jk.toml` declares;
   `scripts/release-version.sh` refuses any other tag before anything is built.
2. Matrix builds native client + engine jar per OS/arch — with JumpKick itself (`jk build`, the layout
   under `target/dist`). The JumpKick that builds is the hosted release `.jk/ci/ci-bootstrap-version` pins,
   installed as a user installs it: the native client on every row. The linux-x86_64
   lane also runs `jk install`, so the first-party plugins it stages for `repo/` are the commit's
   own.
3. `scripts/assemble-release-dir.sh` (with `DIST_DIR` naming the dist) produces per-platform dirs —
   the client archive(s), the engine jar, the JVM client jar, the Maven spy jar and
   `jk-whats-new-<version>.md` — + `SHA256SUMS`.
4. The merge job first writes the release notes (`scripts/release-notes.sh <version>`: the
   version's entry in [What's new](../user/whats-new.md), then the commits since the previous tag)
   and refuses a version with no entry, or one that breaks the [writing rules](#writing-an-entry),
   before anything is downloaded.
5. It flattens every tree into one (`scripts/flatten-release.sh`; `JK_RELEASE_PLATFORMS` names the
   four platforms, and a tree short of one, or a platform-neutral file whose bytes differ
   between platforms, is refused) and takes the CycloneDX SBOM the linux-x86_64 build wrote of the
   engine (`jk publish -S -U` in `server/engine`, which leaves
   `server/engine/target/sbom/jk-engine-<version>.cdx.json` — the document the engine jar embeds
   under `META-INF/sbom/`, derived from `jk-lock.toml`) as `out/sbom/jk-<version>.cdx.json` —
   beside the tree, so the signed `SHA256SUMS` the installers verify is untouched.
6. The publish job signs the combined `SHA256SUMS` with the release key (the key must be the one
   the installers trust: `scripts/release-key-matches.sh`), builds the plugin SDK's Central bundles
   and signs them with the release GPG key ([below](#maven-central)).
7. **`gsutil rsync`** to GCS, then the pointer (`scripts/sign-latest-pointer.sh`): one `gsutil cp`
   of `LATEST` with a no-cache header. The object carries its own signature, so the publish is
   atomic. `VERSION` is copied beside it; nothing verifies it. A leftover `LATEST.sig` is removed.
   The run then requires the jk-host and jk-plugin-sdk POMs to answer at the version on
   jumpkick.build and releases both to Maven Central ([below](#maven-central)).
8. `firebase deploy --only hosting` puts `hosting/public` — the installers with this release's
   floor and public key — on jumpkick.build, after the pointer they will read.
9. `actions/attest-build-provenance` stores one build-provenance attestation per client, the
   engine jar and the SBOM (`gh attestation verify <file> --repo <owner>/<repo>` checks one).
10. Last, `scripts/publish-github-release.sh` creates the GitHub Release for the tag with the tree,
    the SBOM and the notes, and marks it latest (a version with a qualifier is a pre-release). The
    release page announces nothing before the installers can fetch it. A re-run updates the
    release and replaces the assets, as the rsync replaced the tree. The very last step waits for
    the SDK to resolve from repo1.maven.org.
11. Bump `.jk/ci/ci-bootstrap-version` to the new release
    ([self-host](self-host.md#the-bootstrap-pin)). When the release shipped a first native client
    for a platform, that platform's row joins the matrix and `JK_RELEASE_PLATFORMS`.

### Platforms without a hosted client

jumpkick.build serves native clients for **linux-x86_64**, **linux-aarch64**, **macos-aarch64** and
**windows-x86_64**, beside the engine jar and the JVM client. Each has a row in the release
matrix, and `JK_RELEASE_PLATFORMS` requires all four. macOS on Intel has no native
client and never will. The installers put the JVM client on such a host, and a contributor there builds the tree with it and
lets the checkout's own JumpKick take over ([self-host](self-host.md#bootstrap)).

Users on those hosts — and on every host no native client will ever be built for — install the
**JVM client** instead: `jk-<version>.jar` is the CLI module's assembly (`[application] assembly =
true` in `clients/cli/jk.toml`), shipped under that name by `.jk/scripts/after-build-dist.kts` and
`scripts/assemble-release-dir.sh`, installed by both installers as `~/.jk/lib/jk/jk-<version>.jar`
with a launcher (`bin/jk`, `bin/jk.bat`) that `jk self write-launcher` writes over the JDK the
installer verified ([user install](../user/install.md#the-jvm-client)). It pairs with the engine
jar of its own version from the same release directory, so the pairing gap the closure on
`repo/` has does not apply to it.

The first client for a platform is produced by the owner on a machine of that architecture: a
JDK 25 with GraalVM, this checkout, and a JumpKick to build it — the client module is a plain JVM
program (`cc.jumpkick.cli.Jk`, published as `cc.jumpkick:jk-cli:<version>` with its POM on
`jumpkick.build/repo/` by `jk install` + `scripts/publish-maven-repo.sh`), so on a machine with no
native client it runs from that closure on a JVM, and `jk install` on this checkout writes it as
`bin/jk-jvm` ([self-host](self-host.md#the-jvm-client)). The closure must be published for the
same version as the hosted engine: a JVM client pairs only with its own version's engine, and
`repo/` at 0.13.2 beside `releases/` at 0.13.3 pairs with nothing. `jk build -S` then
writes the native client for the host under `target/dist`, `DIST_DIR=target/dist
scripts/assemble-release-dir.sh` assembles it, and it is signed and uploaded beside the other
platforms' artifacts. Once
`releases/<version>/SHA256SUMS` lists the platform, its row joins `release.yml` and
`JK_RELEASE_PLATFORMS` (and, for macOS x86_64, the nightly `os-smoke` matrix) with the pin bump.

### The release environment

The secrets below are secrets of a GitHub **environment** named `release`, not of the repository.
`publish` is the only job that names it, so no other job (and no pull request or branch push) can
read them, and the environment has a required reviewer: the publish job waits for an approval
click on the run. It deploys from `v*` tags only. `scripts/release-environment.sh` sets it up and
loads the values; each goes from its file or stdin straight into `gh secret set`, and nothing
prints one.

```bash
scripts/release-environment.sh create                      # the environment, its reviewer, tags only
scripts/release-environment.sh signing-key ~/release.pem   # refuses a key the installers do not trust
scripts/release-environment.sh secret JK_RELEASE_GCS_BUCKET < bucket.txt
scripts/release-environment.sh status                      # which secrets are set, which are missing
```

The release key's public half is baked into the installers (`RELEASE_RSA_SPKI`); rotating the key
is a release that ships the new public half, signed by the old key, before the secret changes.

### Required secrets

| Secret | Role |
|--------|------|
| `JK_RELEASE_RSA_SIGNING_KEY` | Base64 PKCS#8 DER RSA-3072 private key (signing) |
| `JK_RELEASE_GCS_BUCKET` | GCS bucket name only (no `gs://`), e.g. `jumpkick` |
| `JK_RELEASE_GCS_SA_JSON` | Key of a service account holding `roles/storage.objectAdmin` on that bucket and `roles/firebasehosting.admin` on the Firebase project that serves jumpkick.build (`jk-release-uploader@jkbuild`) |
| `JK_CENTRAL_GPG_KEY` | Armored release GPG secret key; signs the plugin SDK's Central bundles (public key on keyserver.ubuntu.com and keys.openpgp.org) |
| `JK_CENTRAL_GPG_PASSPHRASE` | Its passphrase |
| `JK_REPO_CENTRAL_USERNAME` / `JK_REPO_CENTRAL_PASSWORD` | Central Portal user token (central.sonatype.com → account → Generate User Token) on the account that owns the `cc.jumpkick` namespace |
| `GITHUB_TOKEN` (automatic) | Read-only in every job but `publish`, which holds `contents: write` for the GitHub Release and `id-token`/`attestations: write` for the provenance attestations |

Central refuses unsigned artifacts, so the GPG key and passphrase belong to the same environment as
the release key: the signature is made wherever the SDK is published from.

### Manual upload (ops)

The workflow is the publisher; this is the same sequence by hand, for a release cut on a machine
when CI cannot run. Order matters: the version tree, then the pointer, then the website — a
freshly deployed `install.sh` carries the new floor and refuses the old pointer until step 2 is
done. The pointer publish is one copy.

Before any of it, the tree is built and installed in the order the CI lane keeps: the
**previous** release's client and engine — the ones the home names before the bump — run
`jk install -S` on the new tree. That pass shelves the new version's workers, installs
the new engine and client into the home, and re-shelves under the new engine (the fixed point);
only then can the new engine compile anything, because it looks for workers of its own version
and the repository does not serve them yet. (`install.sh target/dist/jk` shelves the dist's
`repos/jk-local/` too, so a dist install is never an engine without workers; the release is still
cut from the `jk install` pass, whose shelf the new engine packaged.) Assemble
`target/release/<version>/` from the `target/dist` that pass leaves, so the released bytes are the
installed ones; stage the first-party repository from the shelf afterwards (`JK_MAVEN_STAGE_ONLY=1
JK_MAVEN_STAGE_DIR=target/release/repo scripts/publish-maven-repo.sh`) and upload it beside the
version tree. The stage holds the version being released (`JK_VERSION`, by default the tree's)
and nothing else, so the older versions a developer's shelf still holds never reach the bucket or
its worker-POM check; `JK_MAVEN_STAGE_ALL=1` stages every version the shelf holds instead.

```bash
# 1. After assemble-release-dir.sh / flatten-release.sh (or the merged workflow artifact):
gsutil -m rsync -r -d target/release/0.13.6/ gs://$BUCKET/releases/0.13.6/

# 2. The signed pointer: one copy of LATEST. VERSION is an unsigned convenience.
scripts/sign-latest-pointer.sh 0.13.6 target/release/latest /owner-only/path/release-key.pem
gsutil -h "Cache-Control:no-cache,max-age=0" cp target/release/latest/LATEST \
  "gs://$BUCKET/releases/latest/LATEST"
gsutil -h "Cache-Control:no-cache,max-age=0" cp target/release/latest/VERSION \
  "gs://$BUCKET/releases/latest/VERSION"
gsutil rm "gs://$BUCKET/releases/latest/LATEST.sig" || true

# 3. Verify through the public edge the installers use, with the baked-in public key.
# The signature covers the first two lines, not the signature line.
curl -fsSL https://jumpkick.build/releases/latest/LATEST -o LATEST
head -n 2 LATEST >LATEST.body
awk 'NR==3 { sub(/^signature /,""); print }' LATEST | openssl base64 -d -A >LATEST.sig.bin
openssl dgst -sha256 -verify release-public.pem -signature LATEST.sig.bin LATEST.body   # "Verified OK"
head -n 2 LATEST                                                                        # version 0.13.6 / issued …

# 4. Deploy hosting/public (install.sh / install.ps1 with the matching floor).

# 5. The GitHub Release, from the same tree: the highlights entry must exist (step 4 of the CI
#    flow refuses without it); the tag must be pushed, and GH_TOKEN must be able to write
#    releases. The script creates the release.
scripts/release-notes.sh 0.13.6 > RELEASE_NOTES.md
(cd server/engine && jk publish -S -U)   # prints the path it wrote, under the root's target/
cp server/engine/target/sbom/jk-engine-0.13.6.cdx.json jk-0.13.6.cdx.json
scripts/publish-github-release.sh 0.13.6 RELEASE_NOTES.md target/release/0.13.6/* jk-0.13.6.cdx.json
```

`release-public.pem` is the SPKI in `ReleaseVerifier.BUILT_IN_KEY` wrapped in
`-----BEGIN PUBLIC KEY-----` / `-----END PUBLIC KEY-----` at 64 columns.

### Maven Central

The plugin SDK is a public coordinate: `cc.jumpkick:jk-plugin-sdk` and its one dependency
`cc.jumpkick:jk-host` go to Maven Central through the Central Portal at the release version, from
the same tree the release was built from. JumpKick itself resolves `cc.jumpkick` from `jumpkick.build`
alone; Central serves plugin authors who build with Maven or Gradle.

`release.yml` does it in three steps of `scripts/publish-central.sh`:

1. **bundle** — the publish job builds the two modules and runs `jk publish -c -I -U` in
   `shared/host` and `shared/plugin-sdk`: each module's jar, POM, sources and javadoc jars, every
   file signed with the release GPG key, as one Portal bundle per module.
2. **upload** — the publish job, once `repo/` is live and both POMs answer on jumpkick.build,
   uploads jk-host then jk-plugin-sdk with `publishingType=AUTOMATIC` and polls each deployment to
   `PUBLISHED`. A version the Portal already lists as published is skipped, so a re-run passes.
3. **await** — the job's last step polls `repo1.maven.org` until both POMs answer and
   jk-plugin-sdk's `maven-metadata.xml` lists the version (Central syncs minutes to an hour after
   it publishes).

A Central version is immutable. To prove a key, a token or the POM metadata without publishing,
run the first two steps by hand against the real Portal with `JK_CENTRAL_VALIDATE_ONLY=1`: each
deployment is parked user-managed, polled to `VALIDATED` and then dropped.

```bash
jk build -S -m shared/plugin-sdk
JK_CENTRAL_GPG_KEY_FILE=/owner-only/path/release-gpg.asc JK_GPG_PASSPHRASE=… \
  scripts/publish-central.sh bundle target/central
JK_REPO_CENTRAL_USERNAME=… JK_REPO_CENTRAL_PASSWORD=… JK_CENTRAL_VALIDATE_ONLY=1 \
  scripts/publish-central.sh upload target/central
```

The release GPG key is its own key, not the RSA key that signs `SHA256SUMS`: Central accepts
OpenPGP signatures only, checked against a public key it finds on keyserver.ubuntu.com or
keys.openpgp.org.

Central validates the POM's `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>` and
`<scm>`: they come from the `[publish]` table the workspace root declares once for every member
(a member's own table would win wholesale):

```toml
[publish]
url = "https://jumpkick.build"
licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
developers = [{ id = "bsant", name = "Bryan Sant" }]
scm = { url = "https://github.com/JumpKickOSS/jk", connection = "scm:git:https://github.com/JumpKickOSS/jk.git", developer-connection = "scm:git:ssh://git@github.com/JumpKickOSS/jk.git" }
```

A `--dry-run` writes the bundle to `target/shared/plugin-sdk/publish/central-bundle.zip` and lists
it, for a look before the first real upload.

## Local dry-run

```bash
jk build -S    # target/dist/jk + lib/jk-engine-<ver>.jar + lib/jk-<ver>.jar + lib/jk-maven-spy-<ver>.jar
export JK_RELEASE_RSA_SIGNING_KEY_FILE=/owner-only/path/release-key.pem
DIST_DIR=target/dist scripts/assemble-release-dir.sh
# inspect target/release/0.13.6/
```

## Rotation

1. Generate a new RSA-3072 keypair in owner-only credential storage.
2. Replace the Java SPKI and installer SPKI/modulus/exponent together.
3. Run the public-key consistency and installer verification fixtures.
4. Replace the Actions secret only after the clients and public installers carrying the new key
   are ready to publish atomically.
