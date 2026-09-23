# Releases

How JumpKick ships installable binaries. For day-to-day use see [user install](../user/install.md).

## Versioning

What a release may change in the formats projects depend on: [Compatibility](compatibility.md).

| Line | Meaning |
|------|---------|
| **`0.13.9`** | Current product version (no `-SNAPSHOT` on `main`) |
| Tag **`v0.13.9`** | Next public release cut from that line |
| Prior | **`0.13.8`** — previous tagged release; **`0.10.1`** first public |
| Later | Semver-ish: `0.13.9`, `0.14.0`, … |

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

### 0.13.9

- **Two checkouts of one project share a machine.** Worktrees share the action cache and the
  artifact store without evicting or corrupting each other: keys and task pointers no longer name
  the checkout, compiler argv enters a key as portable paths, and heavy-artifact generations are
  counted per checkout rather than by flips of a pointer every checkout shares. A build holds a
  slot on its checkout, so a second build — or a `jk clean` — is refused by build number instead
  of racing the tree, and `jk history` and the dashboard answer per checkout.
- **`jk outdated` is a report you can read.** One row per coordinate by default, a progress row
  while the engine reads repositories, only the rows an update would move (the rest are counted),
  a table that fits a 100-column terminal, and `target/jk-outdated-dependencies.md` written on
  every run — from the CLI and from MCP.
- **`jk clean` shows its work and refuses what it must not remove.** A live row while it deletes,
  trees taken off disk on a pool, a checkout a build holds left alone with the holder's build
  number, and a Windows junction never entered — a delete that follows one hollows its target.
- **A workspace member answers for its own coordinate.** A published POM edge onto a coordinate
  this workspace builds is served by the member, on the classpath and in the build order, with no
  row locked for it. Under a platform `FLOOR` a BOM pin lifts a Maven range rather than clamping
  it, a caret or range below the newest 48 releases resolves, and `jk import` writes Maven's
  `[1.2.3]` as the exact pin it is.
- **The cache check is faster, and answers for more.** The forecast walks a wave of independent
  modules at a time and reads each classpath entry once, and `jk install` reads the same dirty
  memo a build does. That memo now notices a JDK point release, a member's `jk-guards.toml`,
  build-logic script bytes and a root anchor's checkout scope, so "nothing to do" means it.
- **AOT is the user's to ask for.** The engine-side Leyden substrate is gone. `jk build
  --aot-cache`, `[image]` and `[train]` stay exactly as they were, and each worker keeps one
  startup cache keyed to the JDK jk itself runs on.
- **Windows.** An action-cache store no longer loses its record to a concurrent reader, and a
  lookup no longer fails the build when a store is replacing the name it is reading. A tool
  version, a URL cache name and a generated launcher's main class are each confined to what they
  are — one path segment, one path segment, one Java binary name.
- **A release is cut by publishing a GitHub Release.** The tag and the published release drive
  the build, signing and publish for linux-x86_64, macos-aarch64 and windows-x86_64; nothing
  reaches the bucket, the pointer or the installers from a developer machine.
- **The release signing key has been rotated, so this one cannot be self-updated into.** Every
  client verifies a release against the public key baked into it, and 0.13.8 and earlier carry
  the previous one: `jk self update` to 0.13.9 fails its signature check by design. Reinstall
  from [jumpkick.build](https://jumpkick.build) — the installers published with this release
  carry the new key — and `jk self update` works again from 0.13.9 onward.

### 0.13.8

- **Windows holds.** `jk install` finishes on Windows: atomic file replaces leave no gap for
  concurrent readers, the install's engine-handoff pass re-shelves without rebuilding, and an
  install after `jk clean` restores outputs instead of shelving a jar that is not on disk. A
  cancelled job no longer hangs the engine's connection thread in a socket read Windows never
  wakes, and a worker whose command line would pass the 32 K cap launches through an `@argfile`.
- **A missing jar is named.** A lock row that pins no checksum fails a compile classpath by name
  on every lock, and every compiler fork refuses a classpath naming a jar that is not on disk —
  the failure says which jar and what to run, not the first class the compiler could not find.
- **Language inference ignores resource trees** (`.kt` / `.groovy` templates under
  `src/main/resources`) and reads package directories named `resources` under a language root as
  sources.
- **A silent engine's life is judged by every child it runs**, not only JVMs: a native-image link
  or an import shell keeps a busy engine from being displaced.
- **Reports read everywhere.** `jk-results.md` pointers are forward-slash on every host; the
  coverage HTML pointer no longer carries the Windows separator.

### 0.13.7

- **A declared version is an exact pin.** `jackson3-databind = "3.2.2"` in `jk.toml` means that
  release, in every dependency scope, `[workspace.dependencies]`, plugin `version` keys, the
  `kotlin` / `groovy` / `scala` keys and `[native] metadata-repository`. Floating is spelled out:
  `^`, `~`, a range, `latest`. A major-line floor is `[spring-boot] version = "^4"`.
- **A dependency may be a Maven coordinate string.** `mylib = "com.acme:mylib:1.2.3"` pins;
  `web = "org.springframework.boot:spring-boot-starter-web"` is managed by the BOM; the third
  slot takes any selector. A classifier still needs the inline table.
- **`jk update` is the bump verb.** It rewrites the declared pins in `jk.toml` to the newest
  stable on the same Maven major, then relocks; `--major` crosses a line, `jk update <name>` or
  `--dep` limits it, and the diff to review is `jk.toml` plus the lock. `jk lock` keeps pins,
  `jk lock -F` moves only opt-in selectors, `jk outdated` shows Compatible equal to Current for a
  pin. MCP gains `jk_update`, a preview by default (`apply=true` writes and relocks).
- **Writers pin today's stable.** `jk add <name>` with no version, `jk add g:a`, `jk new` and
  every template write the current stable as a number — catalog one-liner, coordinate string or
  inline table, in that order — never `latest`. Offline with no version is a usage error.
- **The run report leads with the verdict.** `jk results` opens with the outcome, a non-zero
  exit and up to three lines of why: compiler errors, crashed test workers and failed steps all
  count, not a JUnit pass rate alone.
- **Windows.** PATH entries that are not paths are skipped instead of failing a tool lookup, a
  worker's stderr survives a JUL handler close, the engine log sink tracks its file on
  filesystems without file keys, a loopback registry no longer spawns Docker credential
  helpers, and the shellcheck lane skips on Windows.
- Smaller: `jk guard` runs inside a linked git worktree; a Kotlin version the importers cannot
  read becomes `latest` for the first lock.

### 0.13.6

- **Every JDK or GraalVM jk installs for you shows the `jk jdk install` experience.** The installer's
  engine warm-up, a build that pins a JDK not yet in the store, and a native build's GraalVM all
  render the same download bar, install phase and "installed at" line, then continue; plain mode
  prints the phases, `--output json` emits a `toolchain` plan ahead of the build's.
- **The Windows installer works on Windows PowerShell 5.1**: its architecture probe no longer dies
  on a runtime without `RuntimeInformation`, native programs' stderr (`java -version`) is read as
  text instead of ending the run, and when a release has no Windows native client yet it installs
  the JVM client on a JDK 25+ and says so.
- **Gradle is back as the bootstrap for hosts without a hosted native client** (macOS, Windows, Linux
  aarch64):
  `./gradlew dist installLocal` builds the first jk; jk remains the gate and the release builder.
- Smaller: every jk script writes under `target/`; CI bootstraps from the published 0.13.5.

### 0.13.5

- **Inlined constants recompile their users.** javac folds a `static final` constant used in a
  string concatenation into an `invokedynamic` recipe and drops every reference to its owner, so
  Zinc's class-file analysis never saw the edge and a class that inlined the constant kept the old
  value across rebuilds — a version bump left a test asserting the previous `JkVersion.VERSION`.
  The Java worker now recovers those edges from javac's attributed AST and files them in the
  analysis.
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
    LATEST                  # signed pointer: `version 0.13.6` + `issued <unix-seconds>`, LF each
    LATEST.sig              # base64 RSA/SHA-256 signature over the exact LATEST bytes
    VERSION                 # bare version — a redirect-compatible convenience nothing verifies
                            # (all three: Cache-Control: no-cache)
  0.13.6/
    jk-linux-x86_64-0.13.6.xz
    jk-linux-aarch64-0.13.6.xz
    jk-macos-x86_64-0.13.6.xz
    jk-macos-aarch64-0.13.6.xz
    jk-windows-x86_64-0.13.6.xz    # self-update (engine inflates; no system xz needed)
    jk-windows-x86_64-0.13.6.zip   # install.ps1 / jk.bat only
    jk-engine-0.13.6.jar
    jk-0.13.6.jar                  # the JVM client: every host with no native client (install.sh
                                   # falls back to it; install.ps1 on JK_CLIENT=jvm)
    jk-maven-spy-0.13.6.jar        # the Maven core extension `jk mvn` attaches; a client fetches
                                   # its own version's on the first `jk mvn` (native and JVM alike)
    SHA256SUMS              # coreutils: <hex>  <filename>
    SHA256SUMS.sig          # base64 RSA/SHA-256 signature over exact SHA256SUMS bytes
```

`install.sh` and the Unix `jk` wrapper fetch `jk-<os>-<arch>-<version>.xz`. `jk.bat` /
`install.ps1` fetch the Windows `.zip`. On a host with no native client, `install.sh` fetches
`jk-<version>.jar` and `jk-engine-<version>.jar` instead (so does `install.ps1` on
`JK_CLIENT=jvm`, and `jk self update` from a JVM install). No installer fetches
`jk-maven-spy-<version>.jar`: the client does, from the same version directory and against the
same signed sums, the first time `jk mvn` runs and finds none under `~/.jk/lib/` (a dist install
copies it there). All three jars are platform-neutral, built by every platform job, and
`scripts/flatten-release.sh` takes linux-x86_64's copy after checking the others are
byte-identical. That identity assumes one bootstrap: the embedded SBOM
(`META-INF/sbom/application.cdx.json`) names the jk that packaged the jar as its tool, and nothing
else in an assembly varies between builds of one commit, so every lane packaging with the pinned
`.jk/ci-bootstrap-version` writes the same bytes, while a dogfood tree whose install re-shelved
under the newly built engine writes a jar that differs by that one field. A release directory is
assembled from one build, never from a native binary of one and a jar of another. Every artifact
name carries the version: the
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

## CI release (run by publishing a GitHub Release)

Workflow: [`.github/workflows/release.yml`](../../.github/workflows/release.yml)

A release is cut by publishing one on GitHub, nothing else: push the tag, publish a release for
it, and the workflow does the rest. Nothing reaches the bucket, the pointer, the installers or
the release page from a developer machine.

```bash
git tag v0.13.9 && git push origin v0.13.9
gh release create v0.13.9 --title "jk 0.13.9" --notes "building"   # the body is replaced by CI
gh run watch                                                         # ~30 min across the matrix
```

The release exists empty while the matrix builds; the workflow attaches the tree as its last step,
after the bucket and the installers are live. A `workflow_dispatch` of the same workflow is the
dry run: it builds every platform, flattens and signs the tree and stops with it as a workflow
artifact, publishing nothing.

The workflow's token is read-only except where a job names the scope it writes, and every action
it uses is pinned to a commit SHA with the tag in a trailing comment — a floating tag in the job
that holds the signing key would be a signed release someone else cut. The
`workflow-pins-permissions` guard refuses a floating tag or a writing top-level `permissions:`
under `jk guard`, `scripts/check-workflows.sh` refuses the same in CI's workflow-lint job, and
`.github/dependabot.yml` moves the pins weekly.

1. Publish a GitHub Release for tag `v0.13.9`. The version must equal `JkVersion` without the
   `v`; `scripts/release-version.sh` refuses any other tag before anything is built.
2. Matrix builds native client + engine jar per OS/arch — with jk itself (`jk build`, the layout
   under `target/dist`). The jk that builds is the hosted release `.jk/ci-bootstrap-version` pins,
   installed as a user installs it: the native client on linux-x86_64 and windows-x86_64, the
   JVM client on macos-aarch64 ([below](#platforms-without-a-hosted-client)). The linux-x86_64
   lane also runs `jk install`, so the first-party plugins it stages for `repo/` are the commit's
   own.
3. `scripts/assemble-release-dir.sh` (with `DIST_DIR` naming the dist) produces per-platform dirs —
   the client archive(s), the engine jar, the JVM client jar and the Maven spy jar — +
   `SHA256SUMS`.
4. The publish job first writes the release notes (`scripts/release-notes.sh <version>`: the
   version's entry under [Highlights](#highlights), then the commits since the previous tag) and
   refuses a version with no entry before anything is downloaded.
5. It flattens the matrix's trees into one (`scripts/flatten-release.sh`; `JK_RELEASE_PLATFORMS`
   names the matrix rows, and a tree short of one, or a platform-neutral jar whose bytes differ
   between platforms, is refused), re-signs the combined `SHA256SUMS`, and takes the CycloneDX
   SBOM the linux-x86_64 build wrote of the engine (`jk publish --sbom --dry-run` in
   `server/engine`, which leaves `target/server/engine/sbom/jk-engine-<version>.cdx.json` at the
   workspace root — the document the engine jar embeds under `META-INF/sbom/`, derived from
   `jk-lock.toml`) as `out/sbom/jk-<version>.cdx.json` — beside the tree, so the signed
   `SHA256SUMS` the installers verify is untouched. A dispatch ends here.
6. **`gsutil rsync`** to GCS, then the pointer (`scripts/sign-latest-pointer.sh`): `LATEST.sig`
   first, then `LATEST`, then `VERSION`, all with no-cache headers. A client reading between the
   two copies gets a signature refusal and retries; it never gets an unverified version.
7. `firebase deploy --only hosting` puts `hosting/public` — the installers with this release's
   floor and public key — on jumpkick.build, after the pointer they will read.
8. `actions/attest-build-provenance` stores one build-provenance attestation per client, the
   engine jar and the SBOM (`gh attestation verify <file> --repo <owner>/<repo>` checks one).
9. Last, `scripts/publish-github-release.sh` attaches the tree, the SBOM and the notes to the
   release that started the run and marks it latest. The release page announces nothing before the
   installers can fetch it. A re-run replaces the assets, as the rsync replaced the tree.
10. Bump `.jk/ci-bootstrap-version` to the new release
    ([self-host](self-host.md#the-bootstrap-pin)). When the release shipped a first native client
    for a platform, that platform's row joins the matrix and `JK_RELEASE_PLATFORMS`.
11. By hand, never from the workflow: the plugin SDK to Maven Central
    ([below](#maven-central)).

### Platforms without a hosted client

jumpkick.build serves two native clients, **linux-x86_64** and **windows-x86_64**, beside the
engine jar and the JVM client. Linux aarch64 and macOS (both architectures) have no hosted
native client. The installers put the JVM client on such a host, which is how the macos-aarch64
release row bootstraps and then builds that platform's native client; linux-aarch64 and
macos-x86_64 have no runner in the matrix, so no release ships a client for them.

A contributor on one of those hosts builds the first jk with the Gradle bootstrap —
`./gradlew dist installLocal` then `./install.sh build/dist/jk`, which
`scripts/bootstrap-from-gradle.sh` runs in that order ([self-host](self-host.md#bootstrap)) — and
the checkout's own jk takes over from there. Gradle builds nothing CI judges; jk is the gate and
the release builder.

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
`releases/<version>/SHA256SUMS` lists the platform, its row joins `release.yml` and
`JK_RELEASE_PLATFORMS` (and, for macOS x86_64, the nightly `os-smoke` matrix) with the pin bump.

### Required secrets

| Secret | Role |
|--------|------|
| `JK_RELEASE_RSA_SIGNING_KEY` | Base64 PKCS#8 DER RSA-3072 private key (signing) |
| `JK_RELEASE_GCS_BUCKET` | GCS bucket name only (no `gs://`), e.g. `jumpkick` |
| `JK_RELEASE_GCS_SA_JSON` | Key of a service account holding `roles/storage.objectAdmin` on that bucket and `roles/firebasehosting.admin` on the Firebase project that serves jumpkick.build (`jk-release-uploader@jkbuild`) |
| `GITHUB_TOKEN` (automatic) | Read-only in every job but `publish`, which holds `contents: write` for the GitHub Release and `id-token`/`attestations: write` for the provenance attestations |

Replacing one: `gh secret set <NAME> --repo JumpKickOSS/jk < file`, as an account with admin on
the repository. Nothing prints the value.

### Manual upload (ops)

The workflow is the publisher; this is the same sequence by hand, for a release cut on a machine
when CI cannot run. Order matters: the version tree, then the pointer (signature before
pointer), then the website — a freshly deployed `install.sh` carries the new floor and refuses
the old pointer until step 2 is done.

Before any of it, the tree is built and installed in the order the CI lane keeps: the
**previous** release's client and engine — the ones the home names before the bump — run
`jk install --skip-tests` on the new tree. That pass shelves the new version's workers, installs
the new engine and client into the home, and re-shelves under the new engine (the fixed point);
only then can the new engine compile anything, because it looks for workers of its own version
and the repository does not serve them yet. (`install.sh target/dist/jk` shelves the dist's
`repos/jk-local/` too, so a dist install is never an engine without workers; the release is still
cut from the `jk install` pass, whose shelf the new engine packaged.) Assemble
`target/release/<version>/` from the `target/dist` that pass leaves, so the released bytes are the
installed ones; stage the first-party repository from the shelf afterwards (`JK_MAVEN_STAGE_ONLY=1
JK_MAVEN_STAGE_DIR=target/release/repo scripts/publish-maven-repo.sh`) and upload it beside the
version tree.

```bash
# 1. After assemble-release-dir.sh / flatten-release.sh (or the merged workflow artifact):
gsutil -m rsync -r -d target/release/0.13.6/ gs://$BUCKET/releases/0.13.6/

# 2. The signed pointer: LATEST.sig, then LATEST, then the VERSION convenience.
scripts/sign-latest-pointer.sh 0.13.6 target/release/latest /owner-only/path/release-key.pem
for object in LATEST.sig LATEST VERSION; do
  gsutil -h "Cache-Control:no-cache,max-age=0" cp "target/release/latest/$object" \
    "gs://$BUCKET/releases/latest/$object"
done

# 3. Verify through the public edge the installers use, with the baked-in public key:
curl -fsSL https://jumpkick.build/releases/latest/LATEST -o LATEST
curl -fsSL https://jumpkick.build/releases/latest/LATEST.sig | openssl base64 -d -A >LATEST.sig.bin
openssl dgst -sha256 -verify release-public.pem -signature LATEST.sig.bin LATEST   # "Verified OK"
cat LATEST                                                                        # version 0.13.6 / issued …

# 4. Deploy hosting/public (install.sh / install.ps1 with the matching floor).

# 5. The GitHub Release, from the same tree: the highlights entry must exist (step 4 of the CI
#    flow refuses without it); a release for the tag must exist as a draft, since publishing one
#    starts the workflow (`gh release create v0.13.6 --draft`); GH_TOKEN must be able to write
#    releases. Publish the draft last.
scripts/release-notes.sh 0.13.6 > RELEASE_NOTES.md
(cd server/engine && jk publish --sbom --dry-run)   # prints the path it wrote, under the root's target/
cp target/server/engine/sbom/jk-engine-0.13.6.cdx.json jk-0.13.6.cdx.json
scripts/publish-github-release.sh 0.13.6 RELEASE_NOTES.md target/release/0.13.6/* jk-0.13.6.cdx.json
```

`release-public.pem` is the SPKI in `ReleaseVerifier.BUILT_IN_KEY` wrapped in
`-----BEGIN PUBLIC KEY-----` / `-----END PUBLIC KEY-----` at 64 columns.

### Maven Central

The plugin SDK is a public coordinate: `cc.jumpkick:jk-plugin-sdk` and its one dependency
`cc.jumpkick:jk-host` go to Maven Central through the Central Portal, from the same tree the
release was built from, after `repo/` is uploaded (a plugin author resolving from `jumpkick.build`
must see the same bytes). `jk publish --central` bundles each module's jar, POM, sources and
javadoc jars — the `jk build` outputs under `target/shared/…/lib/` — signs every file with the
release GPG key and polls the Portal to its verdict ([user doc](../user/publish.md#maven-central)).

```bash
# once per machine: the Portal user token, bound to the Portal's origin
printf '%s' "$TOKEN_PASSWORD" | jk repo login central --url https://central.sonatype.com --username "$TOKEN_NAME"

# 6. Central, host first (the SDK's POM depends on it): user-managed, so the deployment waits for
#    a click in the Portal after a look at target/jk-results.md; --publishing-type automatic skips it.
export JK_GPG_PASSPHRASE=…
JK_PUBLISH_CENTRAL=1 JK_CENTRAL_GPG_KEY_FILE=/owner-only/path/release-gpg.asc scripts/publish-maven-repo.sh
#    or by hand, module by module:
(cd shared/host && jk publish --central --sign --key-file /owner-only/path/release-gpg.asc)
(cd shared/plugin-sdk && jk publish --central --sign --key-file /owner-only/path/release-gpg.asc)
```

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
jk build --skip-tests    # target/dist/jk + lib/jk-engine-<ver>.jar + lib/jk-<ver>.jar + lib/jk-maven-spy-<ver>.jar
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
