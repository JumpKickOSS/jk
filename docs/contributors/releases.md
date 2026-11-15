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
    jk-repo-0.13.6.sha256          # <hex>  <path> of every first-party jar and POM on repo/ at this
                                   # version; the engine refuses a worker that is not listed here
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
- Private key: a PEM file on one signing machine, owner-only (a Linux or WSL home), and the same
  key as the `JK_RELEASE_RSA_SIGNING_KEY` secret (base64 PKCS#8 DER) for the CD workflow. No build
  machine holds it.
- Sign a tree: `scripts/release-publish.sh sign <out> <release-key.pem>` (wraps
  `scripts/sign-release.sh`, refusing a key the installers do not trust)
- Pointer: `scripts/release-publish.sh pointer <out> <release-key.pem>` (wraps
  `scripts/sign-latest-pointer.sh <version> <out-dir> <release-key.pem>`)
  writes `LATEST` (the three-line object) and `VERSION` (fixtures:
  `scripts/test-installer-verification.sh` and `.ps1` cover an unsigned, a tampered, a rolled-back
  and a malformed pointer).
- Additional host keys: `[release] trusted-keys` in `~/.jk/config.toml`.

Remote installers, wrappers, self-update, and engine materialization all require the signatures.
They verify the pointer signature and its floor first, then the manifest signature, require one
strict exact artifact entry, then verify the artifact hash before extracting, parking an existing
binary, writing, or executing downloaded bytes. The installers unpack the client into a stage
beside the live one, run its `--version`, and only then rename it over the live client, so a
failed, interrupted or killed (re)install leaves the previous `jk` working (fixtures in
`scripts/test-installer-verification.sh` and `.ps1`). Local file installs remain an explicit unsigned
development path. `scripts/test-wrapper-bootstrap.sh` drives the POSIX wrapper through that whole
path, network-free, against a fixture release signed with a throwaway key.

## Cutting a release

A release starts with `jk release` on the release commit: it checks the version `jk.toml` declares,
tags the commit with the `[release] tag` template (`v<version>`, annotated), and commits the next
version to `jk.toml`, the lock, the workspace members and the `[release] files` (the installers'
floors). Local development then continues on the next version; `jk snapshot` is the inner loop
for it. `jk release --dry-run` says what it would do.

Releases are cut **by hand**: each platform is built on a machine of that platform, the trees are
merged and signed on the one machine that holds the release key, and the same machine uploads
([Cutting a release by hand](#cutting-a-release-by-hand)). The CI and CD workflows below describe
the same sequence in the cloud; while they are not the publisher, the `Release` workflow is
disabled for the length of a manual release, so a pushed tag cannot publish by itself.

### Re-cutting a release

A release cut here and never pushed can be taken back and cut again. `jk release` left the home
running the release (its engine, its client, its worker jars on the shelf, pinned in
`jk-shelf.toml`), the tree at `Start <next>` and the tag `v<version>` on the commit before. A home
in that state refuses `jk snapshot` of `<version>-SNAPSHOT`, since the release would hide it, and
deleting the shelved release by hand leaves the installed engine without its workers. The way back:

```bash
jk self rollback      # back to the install before the release: its engine, client and shelf
jk release --undo     # drop "Start <next>", uninstall <version>, delete the tag
jk snapshot           # <version>-SNAPSHOT installs and gates again
```

`jk release --undo` refuses while the installed JumpKick still runs the release's own worker jars
(roll back first), when any git remote has the tag or a remote branch has the `Start <next>`
commit, and when the `[publish]` repository already holds the version. Once the tag is pushed, the
version is gone: raise it instead. The undo also drops the rollback record when it names the
release, so a second `jk self rollback` cannot put back an engine whose workers are gone. Each
step can be run again after a failure: the tag goes last.

## CI and CD (started by the release tag)

Workflows: [`ci.yml`](../../.github/workflows/ci.yml) (CI) and
[`release.yml`](../../.github/workflows/release.yml) (CD).

When CD publishes, nothing reaches the bucket, the pointer, the installers or the release page from
a developer machine. A manual release is the other way round, and the two never run together.

```bash
jk release --patch --push        # tests, tags vX.Y.Z, commits "Start X.Y.Z+1", pushes both
gh run watch                     # CI, then CD: build, merge, and publish waiting for approval
```

**CI** (`ci.yml`, started by the tag push or a dispatch on the tag): the tag equals the version
`jk.toml` declares (`scripts/release-version.sh`), the commit authorship scan, the shell fixtures,
the self-host job, the curated integration lane, the release test tier (`jk test -s release`) and
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
   the client archive(s), the engine jar, the JVM client jar, the Maven spy jar,
   `jk-whats-new-<version>.md` and `jk-repo-<version>.sha256` (the digests of the dist's shelf,
   which the engine holds every worker it fetches to, plus, on linux-x86_64, whatever else it
   staged for `repo/`: `JK_REPO_STAGE_DIR`) — + `SHA256SUMS`. The linux-x86_64 lane then checks
   that every jar and POM it staged for `repo/` is the one that file lists, and the flatten keeps
   that lane's copy alone.
4. The merge job first writes the release notes (`scripts/release-notes.sh <version>`: the
   version's entry in [What's new](../user/whats-new.md), then the commits since the previous tag)
   and refuses a version with no entry, or one that breaks the [writing rules](#writing-an-entry),
   before anything is downloaded.
5. It flattens every tree into one (`scripts/flatten-release.sh`; `JK_RELEASE_PLATFORMS` names the
   four platforms, and a tree short of one, or a platform-neutral file whose bytes differ
   between platforms, is refused) and takes the CycloneDX SBOM the linux-x86_64 build wrote of the
   engine (`scripts/release-sbom.sh`, which takes the document the engine jar embeds under
   `META-INF/sbom/`, derived from `jk-lock.toml`) as `out/sbom/jk-<version>.cdx.json` —
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

### Cutting a release by hand

The sequence the owner runs, start to finish. Steps marked **outward** change what users see.

| Step | Where | What |
|------|-------|------|
| 1 | release commit | `scripts/release-floors.sh` lists every floor (manifest, installers, locks, guard rules, bootstrap pin, what jumpkick.build serves) and they agree; gates green (`jk guard`, `jk snapshot`, `jk snapshot -g`, `jk test -s release`), the [What's new](../user/whats-new.md) entry passes `scripts/release-notes.sh <version>`, `scripts/test-*.sh` pass |
| 1b | release commit, installed with `jk snapshot` | `scripts/release-check-routes.sh --examples <jk-examples checkout>`: every template, generated fresh, and every example builds twice with the candidate, the second build a no-op, and each template passes `CI=1 jk guard`; every row of `target/release-check-routes/summary.md` is green (`--skip` only a route this host cannot build, named in the release notes) |
| 2 | any | `jk release --dry-run`, then `jk release --skip-tests`: the tag and the local "Start <next>" commit |
| 3 **outward** | any, `gh` with write access | `gh workflow disable Release`, then `gh workflow list --all` shows it `disabled_manually`. Only then `git push origin v<version>` (the tag alone) |
| 4 | each platform's machine | `scripts/release-lane.sh <work-dir>` from a checkout of the tag |
| 5 | the signing machine | copy every lane's `out/` over, `scripts/release-merge.sh <staging> <out>` |
| 6 | the signing machine | `scripts/release-publish.sh sign <out> <release-key.pem>` |
| 7 **outward** | the signing machine, gcloud | `scripts/release-publish.sh upload <out>`, then `pointer <out> <release-key.pem>` (live here), then `verify <out> <public-key.pem>` |
| 8 **outward** | the signing machine, firebase | `scripts/release-publish.sh hosting` |
| 9 **outward** | jk-templates checkout | fast-forward the catalogue's `main` to the branch synced for this release, push |
| 10 **outward** | the signing machine, `gh` | `scripts/release-publish.sh github <out>` |
| 11 | any | `scripts/release-publish.sh install-check`, then the post-publish matrix below |
| 12 **outward** | any | once the CI run on the tag has finished (or is cancelled), `gh workflow enable Release`; commit the bootstrap pin and push `main` |

**Routes** (`scripts/release-check-routes.sh`). The routes users reach a project by — `jk new` with
each template of `templates/` and of each first-party plugin, and each project of jk-examples — are
built by the candidate before it is tagged, so a release cannot ship a parser that refuses what a
template writes or an example carries. Each template is generated with its defaults into
`target/release-check-routes/templates/`, each example copied (its working tree, ignored files left
out) into `examples/` and committed there as a fresh clone; each builds with the arguments of the
first `jk build` its `scenario.toml` runs (`--args` gives others, e.g. a variant), `jk explain` is
kept in its log, and the second build must name no module to build. A ported checkout's `overlay/`
needs its `setup.sh` and is left out. A failed row names its log. Run it again once a fix lands; the examples repository
takes its own fix before the release, never after.

**Lanes** (`scripts/release-lane.sh`). One per platform: linux-x86_64 and windows-x86_64 on
machines of their own, macos-aarch64 on an Apple-silicon Mac and linux-aarch64 in a Linux VM on the
same Mac (`container machine run`), one heavy lane per machine at a time. Each clones the tag into
`<work-dir>/src`, bootstraps a home of its own (`<work-dir>/home`) from the pinned release with
`scripts/ci-bootstrap.sh`, lets the tree's own JumpKick take it over with `scripts/ci-takeover.sh`, builds
the dist and assembles `out/release-<version>-<os>-<arch>/`. The engine jar's `Build-Commit` must be
the tag's commit — the bridge a bootstrap builds reports the release's version too, so the version
alone does not prove which JumpKick packaged the dist. The linux-x86_64 lane is canonical: it also stages
`out/repo/` and lists it in `jk-repo-<version>.sha256`. Every lane uses the same pinned bootstrap,
which is what makes the platform-neutral jars byte-identical. On Windows the lane runs in Git Bash;
the bootstrap installs through `install.ps1`, which runs under Windows PowerShell 5.1 when there is
no PowerShell 7. Put `TMPDIR` on a disk, not a RAM-backed `/tmp`: the bridge checkout goes there.

The linux-aarch64 VM (a `container` machine on the Mac) needs `git`, `curl`, `xz-utils`, `zip`,
`unzip`, `python3`, a C toolchain with `zlib1g-dev` for `native-image`, and a GraalVM 25 at
`~/.jdks/graalvm-25`; give it at least 6 CPUs, 8 GB of memory and 10 GB of disk, which its sparse
image takes from the Mac's own volume, and keep `TMPDIR` out of its small tmpfs `/tmp`. The Mac's home
is mounted read-write inside it at `/Users/<user>`, which is also where `container machine run`
starts a command: every command run there starts with `cd ~` (the guest's own home), since a Java
tool started in the Mac's home walks all of it. A `container machine run` can block for good when the
guest hangs, so each one runs in the background with a time limit, and files come out by `cat`-ing
a tar from the guest's home through a script on stdin.

**Merge** (`scripts/release-merge.sh`). Each lane's `SHA256SUMS` proves its copy arrived whole (Git
Bash's ` *name` lines are normalized first); `scripts/flatten-release.sh` then makes one version
directory with one `SHA256SUMS` over every platform, refusing a missing platform or a
platform-neutral file whose bytes differ between lanes. The merge also checks the staged `repo/`
against `jk-repo-<version>.sha256`, takes the SBOM out of the engine jar and writes the release
notes.

**Signing** happens on the one machine that holds the release key: a PEM file, owner-only, never
copied by tooling. It needs `openssl`, `bash` and a checkout of the tag (the trusted public key is
read from its `install.sh`). Two byte strings are signed: `SHA256SUMS` (→ `SHA256SUMS.sig`) and the
first two lines of `latest/LATEST` (→ its third line).

**Upload order.** The version tree (`rsync -d`; the step then requires the bucket to hold as many
objects as the tree: the artifacts, `SHA256SUMS` and its signature), then `repo/` (additive), then
the pointer — one `gsutil cp` of `LATEST` with `Cache-Control: no-cache,max-age=0`, `VERSION` beside
it, any old `LATEST.sig` removed. The pointer step first fetches every file of the version directory
through jumpkick.build and refuses unless each is served, `SHA256SUMS` and its signature are this
tree's byte for byte, and every file matches the sums. The release is live once the pointer lands.
The GitHub Release body takes at most 125000 characters; longer notes go up as their highlights and
a compare link (`RELEASE_NOTES.github.md`), the full list staying in `RELEASE_NOTES.md`.

**Hosting only from the tag.** The installers carry a floor and refuse any pointer older than it.
Deployed from the "Start <next>" commit, they would refuse the release just published; deployed
before the pointer, they would refuse the previous one. `release-publish.sh hosting` therefore
refuses unless HEAD is the release tag with `install.sh`, `install.ps1` and `hosting/` unchanged,
and unless the pointer jumpkick.build serves is at least the installers' floor.

**Templates.** When `templates/` changed in a way the released JumpKick needs, the published catalogue
(JumpKickOSS/jk-templates) carries those changes on a branch (`scripts/templates-sync.sh`, checked
with `--check`). Fast-forward its `main` to that branch right after the pointer, not before (older
releases cannot read it) and not long after (a new install scaffolds from the catalogue).

**The `Release` workflow.** A green CI run on a pushed tag starts CD, which can publish on its own.
Disable it before pushing the tag, check that it is disabled, and re-enable it only after the CI run
on the tag has finished or been cancelled: a run still going would otherwise publish its own tree
over the manual one.

**After publishing.** `scripts/release-publish.sh install-check` installs from jumpkick.build into a
clean home and requires the new version. Then, on Linux, macOS, Windows and WSL, from clean homes:
`jk --version -v` (the tag's commit), `jk new <name> -l java && jk build && jk test` (workers fetched
from `repo/` and held to `jk-repo-<version>.sha256`), an update from the previous release with
`jk self update` and back with `jk self rollback`, and a reinstall killed mid-unpack that leaves the
previous `jk` working. `scripts/release-fleet.sh <version>` then runs `release-fleet-check.sh` (Linux,
WSL, macOS) or `release-fleet-check.ps1` (Windows PowerShell 5.1) on every machine of the fleet over
ssh: each stops its engines, deletes its `~/.jk`, installs from jumpkick.build, fast-forwards its JumpKick
checkout to the tag and times a cold and a warm `jk build`, printing one `RESULT` line the driver
appends to a Markdown table. Both refuse unless jumpkick.build serves the version they are given.
Finally bump `.jk/ci/ci-bootstrap-version` to the release, and with it raise this tree's
`jk-lock.toml` `jk-min` to the release (never above the pin: G105).

Every publishing step takes `JK_RELEASE_DRY_RUN=1`, which prints the `gsutil`, `firebase` and `gh`
commands instead of running them; `scripts/test-release-publish.sh` runs the merge and every step
that way against a throwaway key.

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
scripts/release-lane.sh /var/tmp/lane      # from a checkout of the tag: this host's platform tree
# inspect /var/tmp/lane/out/release-<version>-<os>-<arch>/ (unsigned)
```

To install that tree the way users will, serve it (and `out/repo/`) from a local web server with
`SHA256SUMS` and `latest/LATEST` signed by a throwaway key, run a copy of `install.sh` whose
`RELEASE_RSA_SPKI` is the throwaway key with `JK_RELEASES_URL` pointing at the server, and give that
home `[release] trusted-keys = "<throwaway SPKI>"` and a `[repositories.<id>]` table with
`mirror-of = ["jumpkick"]` at the server's `repo/`. `JK_TEMPLATES=<checkout of the catalogue
branch>` makes `jk new` scaffold from templates that are not published yet.

## Rotation

1. Generate a new RSA-3072 keypair in owner-only credential storage.
2. Replace the Java SPKI and installer SPKI/modulus/exponent together.
3. Run the public-key consistency and installer verification fixtures.
4. Replace the Actions secret only after the clients and public installers carrying the new key
   are ready to publish atomically.
