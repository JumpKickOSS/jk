# Publish and supply chain

```bash
jk publish                       # every lane: libraries, the release, then images; signed
jk publish --dry-run             # the same plan; nothing is uploaded
jk publish --repository central  # this run's libraries go to Maven Central, via the Portal
jk publish --only release        # one lane: library, release or image
jk publish --stage               # this platform's release files, for a later final run
jk verify                 # rebuild in a scratch dir and diff artifact hashes
jk audit                  # OSV
jk deny                   # apply [deny.sources]
```

Central uploads are **CLI-only**. MCP `publish` / `run kind=publish` is a **dry-run** unless
`upload=true`, which never reaches Central — [MCP](mcp.md).

Export a lock scope as a Maven BOM: `jk export bom` — [Platforms](platforms.md).

`jk publish` builds the project first, with the same steps and tests as `jk build` (`-S` skips
the tests), then uploads what that build produced; a refused publish (a SNAPSHOT version, a
branch-tracked git dependency) fails before anything compiles. Maven repositories receive the
thin main jar, POM, sources and javadoc jars, plus checksums and any requested signatures, SBOM
and provenance sidecars. Maven Central requires both classifier jars.

Assembly and minified jars, native binaries, jlink images, lib-dir trees, WARs and framework
runnable jars belong in the release lane ([Releasing deliverables](#releasing-deliverables)). A
module with an assembly can publish its ordinary main jar. A framework packager that replaces the main artifact is refused before compilation.
`jk install --destination m2` uses the same main jar rule and installs the sources and javadoc
jars produced by the build, with Maven checksums.

The publish build writes the javadoc jar of every library that does not say `javadoc =
false`, even though a plain `jk build` leaves it out. A Kotlin library's javadoc jar is Dokka's output, so the bundle
carries real API docs rather than an empty jar.
See [Packaging: library artefacts](packaging.md#library-artefacts-sources-and-javadoc-jars).
What the publish renders itself (the POM, SLSA provenance, a sources jar the build did not write)
goes to `target/publish/` first: every file streams from disk, so a large jar never has to fit in
memory.

`jk publish` uploads only a release ([Releasing](release.md)). In a git checkout that is a clean
checkout whose HEAD carries the version's tag (`[release] tag`, `v1.4.7` by default); on any other
commit, or with uncommitted changes, it refuses before building and says which. In a tree with no
git (a source archive) a publish is a release by itself, and every artifact it uploads carries the
plain version.

A `-SNAPSHOT` version publishes only with `--allow-snapshot`, in Maven's unique form: each run uploads
`name-1.0.1-20261025.201500-3.jar` and rewrites the version's `maven-metadata.xml` to name the newest
build, so a snapshot repository that refuses redeploys takes every run and consumers resolve the latest.

## POM metadata: `[publish]`

The POM carries the coordinate, the `description` and the dependencies from `jk.toml`. A release
also names its home page, licenses, people and source repository — Maven Central refuses a POM
without every one of them — and those live in a `[publish]` table. Every key is optional for a
private repository; a workspace root's table applies to every member that declares none, and a
member's own table wins wholesale.

```toml
[publish]
name = "Widget"                      # POM <name>; default: the artifact id
url = "https://example.com/widget"
licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
developers = [{ id = "ada", name = "Ada Lovelace", email = "ada@example.com" }]
scm = { url = "https://github.com/example/widget",
        connection = "scm:git:https://github.com/example/widget.git",
        developer-connection = "scm:git:ssh://git@github.com/example/widget.git" }
```

### Naming the target

The same table says where a release goes and how it is signed, so a bare `jk publish` is the
whole command on a laptop and in CI:

```toml
[repositories.releases]
url = "https://nexus.example.com/repository/maven-releases/"

[publish]
repository = "releases"             # a [repositories.<name>] entry, or "central"
key-file = "~/.gnupg/release.asc"   # the PGP key; the passphrase is JK_GPG_PASSPHRASE, never here
# sign = false  sbom = false  provenance = false  hold = true      # opt-outs
```

Everything a release should carry is on by default. Flags only skip, preview or choose per run:
`-S` skips the tests, `-U` previews, `-s` allows a `-SNAPSHOT`, and `--repository <name|url>`
replaces `[publish] repository` for one run (`central` names the Portal). Credentials come from
`jk repo login` or `JK_REPO_<ID>_*`, the passphrase from `JK_GPG_PASSPHRASE`; a `password`,
`token` or `passphrase` key in `[publish]` is refused, naming where the secret goes. A repository's
object-store `region` and `endpoint` live on its `[repositories.<name>]` table.

A repository whose uploads go somewhere other than where it is read, such as a bucket behind a CDN
or a hosted repository behind a group, names that place with `publish-url`. `jk publish`,
`--dry-run` and `jk release` then write to it and check it: what is already published, the
`maven-metadata.xml` merge and whether the version is taken. See
[Repositories § Publishing to another URL](repositories.md#publishing-to-another-url).

**Signing** is on. A `key-file` means PGP: a detached `.asc` beside every file. When CI offers an
identity token (GitHub Actions `id-token: write`, or `SIGSTORE_ID_TOKEN` on GitLab), the release is
also signed keylessly with Sigstore, never through a browser prompt. Central with no PGP key is
refused before anything builds, naming `[publish] key-file`. Any other repository with neither a
key nor an identity gets an unsigned release and one line in the results saying so. `sign = false`
turns signing off. The SBOM and the SLSA provenance are uploaded beside the artifact unless
`sbom = false` or `provenance = false` says otherwise.

The key file may hold the whole secret key or only its subkeys (`gpg --export-secret-subkeys`, the
primary kept offline). The newest key in it that is flagged for signing, carries its secret and is
neither revoked nor expired signs; when none does, the refusal names each key and why it was passed
over.

### Publishing from a tag

`[release] tag` is the template of the git tag a release carries, `{version}` standing for the
released version (default `v{version}`). `jk publish` stops before anything compiles unless HEAD
carries that tag on a clean tree, so a CI job on the tag is just:

```yaml
# .github/workflows/publish.yml
on:
  push:
    tags: ["v*"]
jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      id-token: write            # keyless Sigstore signing
    steps:
      - uses: actions/checkout@v4
      - run: jk publish
```

### Publishing again

A published file never changes. Publishing a version again uploads only what the repository does
not hold yet, so a run that stopped half way finishes on the next try (`16 files, 4 already
there`); a file the repository holds with other bytes stops the run and says to raise the version.

### Publishing to object storage

For `s3://` and `gs://` targets (a repository's `url`, or its `publish-url`), each artifact and its sidecars are created only if the key
is absent. Concurrent publishers retry updates to Maven metadata against the latest version
list or snapshot build. Artifacts of 64 MiB or more use multipart uploads on S3. Google
Cloud Storage uses a single streamed upload with a generation precondition.

### Keeping a member out of a release

A workspace publishes every member with a jar. A member that must never ship (a test harness, a
benchmark, an example, the tool that builds the site) says so in its own `jk.toml`:

```toml
[publish]
skip = true
```

It still builds and tests with the workspace; `jk publish`, `jk install` (and so `jk release`) leave
it out, and the run says which members it left out (`[publish] skip keeps 3 modules out of jk
publish: corpus-checks, jmh, site`). `jk explain` names them too. `skip` belongs to the member that
writes it: a workspace root's `[publish]` table passes its other keys to members that have none,
never `skip`.

A shipped member's POM names the siblings in its `[dependencies]`, `[export-dependencies]` and
`[runtime-dependencies]`; a consumer resolving it would find none of the skipped ones. So a publish
or install that would ship such a member stops before it builds, naming both: `core depends on
testkit, which [publish] skip keeps out of jk publish, so nothing that uses core could resolve
it; remove skip from testing/testkit/jk.toml, or make testkit a [test-dependencies] entry of
core`. A skipped member in a shipped member's test tables is fine: test scope is not
transitive.

### Yanking a version

`jk yank` takes a published version out of the version list at the `[publish]` repository (its
`publish-url` when it has one), for every module `jk publish` ships:

```bash
jk yank 1.4.7                           # every published module of this project or workspace
jk yank com.example:widget:1.4.7        # one coordinate
jk yank 1.4.7 --dry-run                 # print the version list before and after; change nothing
jk yank 1.4.7 --delete                  # also delete the files under widget/1.4.7/
jk yank 1.4.7 -R staging                # a declared repository or a URL instead of [publish]
```

Yanking edits each artifact's `maven-metadata.xml`: the version leaves `<versions>`, and
`<latest>` and `<release>` are worked out again from what is left (`<release>` is never a
snapshot). Version ranges, `latest`, `jk add` and `jk update` stop choosing it. Its files stay, so
every lockfile that pins it still builds. Yanking the last version leaves an empty list with no
`<latest>` or `<release>`, and the run says so. Yanking a version that is not listed changes
nothing (`com.example:widget:1.4.7 is not listed; nothing to yank`).

The metadata is written the way `jk publish` writes it: on `s3://` and `gs://` a write that
another publisher beat is read again and retried, so a version published during the yank stays
listed. `file://` and web repositories are read and then written.

`--delete` also removes every file under the version's directory and warns that lockfiles pinning
it no longer build. Object stores list the directory and delete each object; `file://` deletes the
directory. A web repository is asked to delete each file its directory page links to (or the
directory itself when it has no such page); each file it refuses is named with its status and the
run exits 1.

`jk yank` needs the same credential `jk publish` sends that repository. Maven Central is refused:
it never delists or deletes a published version. `-O jsonl` prints one `yank` row per coordinate
([machine output](machine-output.md#command-rows)).

## Releasing deliverables

`jk publish` ships to up to three **lanes**, each with its own target in `jk.toml`:

| Lane | Target | What it ships |
|---|---|---|
| `library` | `[publish] repository` | thin jars, POMs, sources and javadoc jars to a Maven repository or Central |
| `release` | `[publish] release` | the files a person downloads, with `SHA256SUMS`, signatures and an index |
| `image` | `[image] registry` | each application module's OCI image, with its SBOM and provenance ([Images](images.md#pushing-an-image)) |

A plain `jk publish` runs every lane that has a target, in that order: library, release, image.
`--only release` (or `--only library,image`) runs only the lanes it names; naming a lane with no
target is refused, naming the key to set. `jk image` builds the image and loads it locally; only
`jk publish` pushes it.

```toml
[publish]
repository = "central"                        # the library lane
release    = "github"                         # the release lane: github, gitlab, forgejo, or a bucket

[release]
platforms = ["linux-x86_64", "macos-aarch64", "windows-x86_64"]
```

`release` takes `github`, `gitlab`, `forgejo` or an `s3://` or `gs://` prefix:

- `github` sends the files to a GitHub Release of the repository the git remote `origin` names,
  on github.com or a GitHub Enterprise host. See [GitHub Releases](#github-releases).
- `gitlab` sends them to a GitLab release of the project `origin` names, on gitlab.com or a
  self-managed host. See [GitLab releases](#gitlab-releases).
- `forgejo` sends them to a Forgejo or Gitea release of the repository `origin` names, on
  codeberg.org or any self-hosted instance. See [Forgejo, Gitea and Codeberg
  releases](#forgejo-gitea-and-codeberg-releases).
- A bucket's endpoint and region come from a `[repositories]` table whose `url` or `publish-url`
  covers the bucket, else from the AWS environment (`AWS_ENDPOINT_URL` for R2 or MinIO,
  `AWS_REGION`). The credential comes from the AWS chain (`AWS_ACCESS_KEY_ID` and
  `AWS_SECRET_ACCESS_KEY`, or `~/.aws/credentials`).

The workspace root's `[publish] release` and `[release]` govern the whole workspace.

**What the release carries.** From every module that is not `[publish] skip`:

| File | From | Platform |
|---|---|---|
| `<name>-<version>.jar`, the framework's runnable jar | `[spring-boot]`, `[grails]`, `[quarkus] package = "uber-jar"` | neutral |
| `<name>-<version>-all.jar` | `[application] assembly = true` | neutral |
| `<name>-<version>-min.jar` | `[application] minified = true` | neutral |
| `<name>-<version>.zip` | `[application] lib-dir` | neutral |
| `<name>-<version>.war` | `[war]` | neutral |
| `<name>-<version>-<os>-<arch>.tar.gz` (`.zip` on Windows) | `[jlink]` | that platform |
| `<executable>-<version>-<os>-<arch>` (`.exe` on Windows) | `[native]`, unless `enabled = false` | that platform |
| `<name>-<version>.cdx.json` | the module's SBOM, unless `sbom = false` | neutral |

Then the final run adds `SHA256SUMS` (`sha256sum` format, the one `jk`'s own installer verifies),
`SHA256SUMS.asc` when `key-file` is set, `SHA256SUMS.sigstore.json` when CI offers an identity
token, and `index.json`, which lists every file with its module, size, SHA-256, URL (a bucket's
and GitLab's; GitHub and Forgejo name their own) and, for a platform file, its `os` and `arch`. With signing on and
neither a key nor an identity, the release goes out unsigned and the run says so on one line, also
in the results: `release v1.4.7 is unsigned: set [publish] key-file, or sign = false`. Quarkus's
default fast-jar runs from its `quarkus-app/` directory, which the release does not carry;
`package = "uber-jar"` ships one jar.

**Native executables.** A release-lane run (`jk publish` or `jk publish --stage`, not `--only
library` or `--only image`) builds the native image of every `[native]` module on a platform the
release lists, after the tests and before the upload, so `[native]` alone is enough: `jk build`
and `jk install` stay JVM-only, and `enabled = "always"` is needed only to build the image on every
build. The executable is named after `[native] name` (the module's name by default), and each
platform's comes from a run on that platform: GraalVM builds for the host it runs on. `enabled =
false` ships none, and neither does a `--shared` library. See [Native images](native.md).

**Platforms and the final run.** `[release] platforms` lists the platforms whose files a release
must carry, named `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`,
`windows-x86_64` or `windows-aarch64`; with none listed, the host's own is the one. A release for
one platform needs nothing else: one `jk publish` builds, uploads and publishes it. For several:

- `jk publish --stage` runs on each platform. It builds and uploads only that platform's files,
  never touches the library or image lane, and publishes nothing. It is refused on a platform the
  list does not name, and with `--only library` or `--only image`.
- `jk publish`, once, is the final run. Before anything builds it checks that every other listed
  platform's files are staged, and refuses naming the first that is not: `release v1.4.7 lacks
  macos-aarch64; run jk publish --stage on that platform first`. Then it uploads the neutral files
  (and this host's own, if not staged yet), runs the library lane (a Central upload waits for
  `VALIDATED`), writes `SHA256SUMS`, its signatures and `index.json`, publishes the release, and
  only then presses Publish on the Central Portal (unless `hold = true`). The image lane goes last:
  each image is pushed once the release is out. If anything fails before the release is published,
  nothing on Central, in `latest.json`, on the forge or in the registry is public.

Every upload resumes: a file already there with the same bytes is left alone, so a final run that
stopped half way finishes on the next try, and a file there with other bytes stops the run and says
to raise the version.

**Bucket layout.** A release is `{prefix}{version}/`: every file above, each written only if the key
is absent. The release is published when `{prefix}latest.json`
(`{"version":"1.4.7","index":"1.4.7/index.json"}`) names it; the final run writes it by
compare-and-swap and never moves it back to an older version. A stage run's files sit under the
same `{version}/` and stay unannounced until then.

### GitHub Releases

With `release = "github"` the release is the GitHub Release of the version's tag (`v1.4.7` by
default, `[release] tag` otherwise) in the repository `origin` names. A run refuses with one line
when `origin` is missing or on another forge.

- The first upload creates the release as a **draft** named after the tag, at the tagged commit.
  Stage runs and the final run upload to that draft, which nobody else sees.
- An asset already in the draft with the same name, size and SHA-256 is left alone; one with other
  bytes stops the run. An empty asset a broken upload left behind is replaced.
- The final run uploads the neutral files, `SHA256SUMS`, its signatures and `index.json`, then
  publishes the draft.
- A release that is already published takes no new file. A final run that finds every file there
  with the same bytes changes nothing; one that would add a file is refused before anything
  builds: `v1.4.7 is already published without app-1.4.7-all.jar; raise the version`.

**The token.** JumpKick takes the first of `JK_GITHUB_TOKEN`, `GH_TOKEN`, `GITHUB_TOKEN`, `gh auth
token` and the token `jk auth login github` stored. It needs the `repo` scope, or `contents: write`
for the `GITHUB_TOKEN` of a GitHub Actions job; a token without it is refused with `GitHub refused
the release; the token needs repo scope or contents: write`. In a project with `release =
"github"`, `jk auth login github` asks for `repo` as well as `read:packages`. `GITHUB_API_URL`,
which Actions sets, names the REST API when it is set; otherwise it is `https://api.github.com`, or
`https://<host>/api/v3` for an Enterprise host. A rate limit stops the run with the time to try
again; JumpKick does not retry against it.

**A matrix in CI.** JumpKick does not write CI workflows; it holds the order with its commands. Stage
on each platform, then publish once. For a GitHub Release, each job's `GITHUB_TOKEN` needs
`contents: write`:

```yaml
# .github/workflows/release.yml
on:
  push:
    tags: ["v*"]
permissions:
  contents: write                # the draft release and its assets
jobs:
  stage:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - run: jk publish --stage
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  publish:
    needs: stage
    runs-on: ubuntu-latest
    permissions:
      contents: write
      id-token: write            # keyless Sigstore signing
    steps:
      - uses: actions/checkout@v4
      - run: jk publish
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          JK_REPO_CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
          JK_REPO_CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}
          JK_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

For a bucket the jobs carry the bucket's credential instead:

```yaml
# .github/workflows/release.yml
on:
  push:
    tags: ["v*"]
jobs:
  stage:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - run: jk publish --stage
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
  publish:
    needs: stage
    runs-on: ubuntu-latest
    permissions:
      id-token: write            # keyless Sigstore signing
    steps:
      - uses: actions/checkout@v4
      - run: jk publish
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          JK_REPO_CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
          JK_REPO_CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}
          JK_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

The final job's own platform (`linux-x86_64` here) is staged by the matrix too; the final run finds
its files already there and leaves them. Each stage job builds its platform's native executables,
so a `[native]` module ships one per runner with nothing more in the workflow.

### GitLab releases

With `release = "gitlab"` the release is the GitLab release of the version's tag in the project
`origin` names: gitlab.com, or any other host as a self-managed GitLab, with subgroups in the path
(`git@gitlab.example.com:acme/tools/app.git`). A run refuses with one line when `origin` is
missing or on another forge's public host.

GitLab has no draft release, and a release's assets are links. So:

- Every upload goes to the project's generic package `<name>/<version>` (`app/1.4.7`), which no
  release points at yet. A file already in the package with the same name, size and SHA-256 is left
  alone; one with other bytes stops the run.
- The final run uploads the neutral files, `SHA256SUMS`, its signatures and `index.json`, then
  creates the release with a link to every file of the package. `index.json` gives each file's
  package URL.
- A release that already exists takes no new file. A final run that finds every file there and
  linked changes nothing; one that would add a file is refused before anything builds.

The package shows in the project's package registry as soon as the first stage run uploads to it.

**The token.** JumpKick takes the first of `JK_GITLAB_TOKEN`, `GITLAB_TOKEN`, `glab auth token`
and the token `jk auth login gitlab` stored, then, in a GitLab CI job, the job's `CI_JOB_TOKEN`.
A personal or project access token needs the `api` scope and the Developer role; the job token
needs no setup, since the generic packages and releases APIs take it. A token without the scope is
refused with `GitLab refused the release; the token needs api scope and Developer`. In a project
with `release = "gitlab"`, `jk auth login gitlab` asks for `api` instead of `read_api`.
`CI_API_V4_URL`, which GitLab CI sets, names the REST API when it is set; otherwise it is
`https://<host>/api/v4`.

```yaml
# .gitlab-ci.yml
stages: [stage, publish]

stage:
  stage: stage
  rules:
    - if: $CI_COMMIT_TAG
  parallel:
    matrix:
      - RUNNER: [saas-linux-small-amd64, saas-macos-medium-m1]
  tags: [$RUNNER]
  script:
    - jk publish --stage

publish:
  stage: publish
  rules:
    - if: $CI_COMMIT_TAG
  tags: [saas-linux-small-amd64]
  id_tokens:
    SIGSTORE_ID_TOKEN:
      aud: sigstore              # keyless Sigstore signing
  script:
    - jk publish
```

### Forgejo, Gitea and Codeberg releases

With `release = "forgejo"` the release is the Forgejo or Gitea release of the version's tag in the
repository `origin` names: codeberg.org, or any other host as a self-hosted Forgejo or Gitea,
under a sub-path too (`https://git.example.com/forge/acme/app.git`). A run refuses with one line
when `origin` is missing or on another forge's public host.

The release works as on GitHub: the first upload creates a **draft** named after the tag, stage
runs and the final run upload attachments to it, an attachment with the same name, size and SHA-256
is left alone and one with other bytes stops the run, and the final run publishes the draft. A
release that is already published takes no new file.

**The token.** JumpKick takes the first of `JK_GITEA_TOKEN`, `GITEA_TOKEN`, `FORGEJO_TOKEN` and
the token `jk auth login forgejo --host <host>` stored for the host of `origin`. It needs the
`write:repository` scope; a token without it is refused with `Forgejo refused the release; the
token needs write:repository`. In a project with `release = "forgejo"`, `jk auth login forgejo`
asks for `write:repository` as well as `read:package`.

Forgejo Actions runs the same matrix as GitHub Actions. Give the jobs a token with
`write:repository` from a repository secret:

```yaml
# .forgejo/workflows/release.yml
on:
  push:
    tags: ["v*"]
jobs:
  stage:
    strategy:
      matrix:
        runner: [docker, macos-arm64]
    runs-on: ${{ matrix.runner }}
    steps:
      - uses: actions/checkout@v4
      - run: jk publish --stage
        env:
          FORGEJO_TOKEN: ${{ secrets.RELEASE_TOKEN }}
  publish:
    needs: stage
    runs-on: docker
    steps:
      - uses: actions/checkout@v4
      - run: jk publish
        env:
          FORGEJO_TOKEN: ${{ secrets.RELEASE_TOKEN }}
          JK_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

The runner labels are the ones your runners registered with.

**Previewing.** `jk publish --dry-run` prints each lane's plan: for the release, every file with its
size, SHA-256, destination and role (`staging` for a platform's own file, `final` for what only the
final run writes), then what the run would do last (`would publish the draft release v1.4.7`,
`would create the release v1.4.7` on GitLab, `would point latest.json at 1.4.7`); for the image
lane, each image reference it would push (`Would push image ghcr.io/acme/app:1.4.7`). Nothing is
uploaded, and a dry run reads no token. The build still runs, native image included, since the
sizes and digests are those of the files it writes. `target/jk-results.md` gives the Publish block
one row per lane and destination: an image's row names its reference and, once pushed, its digest.

## Releasing

`jk.toml` declares the **next release**, never a `-SNAPSHOT`. `jk release` cuts it:

```bash
jk release                  # build + test, install 1.4.7, tag v1.4.7, start 1.4.8, push
jk release --minor          # …then start 1.5.0 (--major: 2.0.0; --patch is the default)
jk release --no-push        # keep the tag and the commit local
jk release --dry-run        # check everything, change nothing
```

The segment flag picks what moves **after** the release. In order, `jk release`:

1. refuses unless the git tree is clean, the declared version is `MAJOR.MINOR.PATCH`, the tag
   (`[release] tag`, default `v{version}`) does not exist, and the `[publish]` repository does not
   already hold the version (an unreachable repository is no objection); outside git only the last
   two apply;
2. builds, tests and installs the version once, exactly as `jk install` does (`-S` skips the tests;
   `--destination m2` names the Maven local repository);
3. tags the commit (annotated), then rewrites the version in `jk.toml` (and in every workspace
   member that declares the same literal), relocks, and commits it as `Start <next>`;
4. pushes the branch and the tag (`--no-push` prints the push command instead);
5. publishes, when this machine is the one that publishes (below).

Outside git, steps 3 to 5 shrink to rewriting the version in `jk.toml` and relocking (and, in local
publish mode, the upload before it): there is nothing to tag, commit or push.

**Who publishes** is inferred, and `--publish ci|local|none` overrides it for one run:

| The project has | Mode | `jk release` ends by |
|-----------------|------|----------------------|
| no destination: no `[publish] repository` or `release`, no `[image] registry` (in `jk.toml` or `~/.jk/config.toml`) | `none` | pushing the tag |
| a destination, and a CI file that runs `jk publish` | `ci` | printing `pushed v1.4.7; CI publishes it` |
| a destination, and no such CI file | `local` | running `jk publish` on the pushed tag |

CI files are the usual ones: `.github/workflows/`, `.gitlab-ci.yml`, `.forgejo/workflows/`,
`.gitea/workflows/`, `.woodpecker*`, `.circleci/config.yml`, `.buildkite/`, `Jenkinsfile`,
`azure-pipelines.yml`, `bitbucket-pipelines.yml` and `.drone.yml`; a pipeline that only runs tests
does not count. When the inference is wrong for a project (a pipeline defined in a UI, say), say so
once in `jk.toml`:

```toml
[release]
publish = "ci"       # or "local" (or "none")
```

In `local` mode `jk release` checks, before it tags, that a credential and the signing key resolve
for every lane: the repository's credential, and for a release bucket an AWS credential. When they do not, nothing has changed: `no credential for central on this machine;
if CI publishes this project, run jk release --publish ci`. The upload runs from a checkout of the
tag, so a failed upload after the push leaves a pushed tag and says to run `jk publish` on it.
`--publish ci` with `--no-push` is refused: CI cannot see a tag that was not pushed.

A git tag is signed as git itself is configured (`tag.gpgSign`, `user.signingkey`); `jk release` has
no flag for it. When a later step fails, the message says what was done and the command that
finishes the rest. A workspace is released from the root; members that inherit its version follow
it.

`jk release --undo` takes back a release cut on this machine and not yet pushed or published: it
drops the `Start <next>` commit when it is HEAD, uninstalls the released version (from `~/.m2` only
what JumpKick put there) and deletes the tag, so `jk install` installs `<version>-SNAPSHOT` again.
It refuses when a git remote has the tag, a remote branch has the `Start <next>` commit, or the
`[publish]` repository holds the version.

[Releasing](release.md) has the CI recipes. Locally, `jk install` is the loop; `jk release` is the
end of it.

## Maven Central

`jk publish --repository central` (or `[publish] repository = "central"`) publishes through the
Sonatype Central Portal: one signed bundle — thin jar, POM, sources jar, javadoc jar, a detached
GPG `.asc` on each, `.md5` and `.sha1` beside each — is uploaded to the Portal's API as a
user-managed deployment. JumpKick polls it to `VALIDATED`, publishes the deployment itself and
polls it to `PUBLISHED`. Every file counts against Central's publishing limits, so the bundle
carries only those sidecars: no `.sha256` or `.sha512`, and no signature or checksum on a sidecar.
When CI offers an identity token, each file also gets a Sigstore `<file>.sigstore.json` bundle, the
name the Portal checks; PGP stays required.

```bash
# once: the Portal user token (central.sonatype.com → account → Generate User Token)
printf '%s' "$TOKEN_PASSWORD" | jk repo login central -U https://central.sonatype.com -s "$TOKEN_NAME"

jk publish                # [publish] repository = "central", key-file = "…"; JK_GPG_PASSPHRASE
#   Published com.example:widget:1.0.0 to the Central Portal (16 files)
#     deployment 28570f16-… · PUBLISHED
```

`[publish] hold = true` stops at `VALIDATED` and prints `release it from the Portal`, leaving the
last click to a person. `--dry-run` writes the bundle to `target/publish/central-bundle.zip`, lists
its entries and uploads nothing. `JK_CENTRAL_PORTAL_URL` points the `central` repository at another
Portal (a mirror, a stub).

Before anything is signed, JumpKick refuses with the exact fix when the sources or javadoc jar is not
on disk, when `[publish]` lacks the `key-file` Central requires, or says `sign = false`. The poll
walks `PENDING → VALIDATING → VALIDATED → PUBLISHING → PUBLISHED`; a `FAILED` deployment fails the
run and prints every validation error the Portal listed.

The credential is the `central` entry: `jk repo login central -U https://central.sonatype.com`
with the token's name as `--username` and its password on stdin (JumpKick sends the Portal the base64
`name:password` it documents), or a token already encoded, on stdin without `--username`. From
CI, `JK_REPO_CENTRAL_USERNAME` + `JK_REPO_CENTRAL_PASSWORD` (or `JK_REPO_CENTRAL_TOKEN`) with
`JK_REPO_CENTRAL_HOST=central.sonatype.com` beside them — the same name-to-origin binding every
stored credential needs ([Repositories: credentials](repositories.md#credentials)).

Sonatype limits each organization's monthly publishing by file count, release size and release
count ([limits](https://central.sonatype.org/publish/maven-central-publishing-limits/)). Every
Central publish, `--dry-run` included, prints the bundle's size on one line (`Central bundle: 16
files, 1.2 MB`) and repeats it in the results file. When one release alone passes the published
90th percentile, 1,167 files or 78 MB a month, it also warns and points to the
[Usage Center](https://central.sonatype.com/publishing/usage), the only place the month's totals
are shown. The warning does not fail the publish.

`target/jk-results.md` gets a **Publish** block for every publish run — the target, the file
count, and for Central the deployment id, the state the poll ended in and every validation error
— so a failed release is diagnosed from the results file, not from scrollback.

## SBOM

`jk publish` uploads a CycloneDX 1.6 and an SPDX 2.3 document beside the artifact (`[publish] sbom =
false` turns them off) and writes the same two files under the module's `target/` —
`target/sbom/<name>-<version>.cdx.json` and `target/sbom/<name>-<version>.spdx.json` — printing
their paths. With `--dry-run` nothing is uploaded and no repository is needed, so a release script
takes the bill of materials from disk:

```bash
jk publish -U
#   wrote target/sbom/widget-1.0.0.cdx.json
#   wrote target/sbom/widget-1.0.0.spdx.json
```

The CycloneDX document is deterministic (no serial number, no timestamp) and is written by the
same code that embeds `META-INF/sbom/application.cdx.json` in every application jar
([Packaging](packaging.md)), so the sidecar of a module is the document its jar carries: the
module's production runtime closure — the rows of `jk-lock.toml` its own dependencies and those of
the workspace siblings it depends on reach, not every member's — each with its SHA-256 and named
by its package URL, which carries a classifier and a type other than `jar` as qualifiers
(`pkg:maven/org.lwjgl/lwjgl@3.3.6?classifier=natives-linux`), so every jar is its own component;
the group, name and version are percent-encoded as the purl spec asks (`1.0.0+build.5` reads
`1.0.0%2Bbuild.5`). SPDX names such a variant Maven's way, `org.lwjgl:lwjgl:jar:natives-linux`
beside the main jar's `org.lwjgl:lwjgl`. Both
documents carry the dependency graph between them: CycloneDX as `dependencies`, SPDX as a
`DESCRIBES` relationship from the document to the module and `DEPENDS_ON` relationships from the
module and each package to what it depends on.

The release also carries an SLSA v1 provenance statement (`.intoto.json`; `[publish] provenance =
false` turns it off) whose `resolvedDependencies` are the same closure: each component's purl as `uri` and its locked
SHA-256 as `digest.sha256`.

With a PGP `key-file`, the statement goes up inside a [DSSE](https://github.com/secure-systems-lab/dsse)
envelope signed by the same key (`.intoto.jsonl`, one JSON line) instead of bare: the base64
statement as `payload`, `payloadType` `application/vnd.in-toto+json`, and one signature whose
`sig` is a binary OpenPGP signature and whose `keyid` is the signing key's fingerprint. The
signature covers DSSE's pre-authentication encoding of the payload, so `gpg` verifies it like
this:

```bash
f=widget-1.0.0.intoto.jsonl
type=application/vnd.in-toto+json
jq -r .payload "$f" | base64 -d > statement.json
jq -r '.signatures[0].sig' "$f" | base64 -d > statement.sig
{ printf 'DSSEv1 %d %s %d ' ${#type} "$type" "$(wc -c < statement.json)"; cat statement.json; } > pae.bin
gpg --verify statement.sig pae.bin
```

The envelope's signature is new each time it is made, so a re-run after a partial publish keeps
the envelope the repository already holds over the same statement, and refuses one over another.

Without a key, in CI that offers an identity token, the envelope is signed keylessly: its one signature carries no `keyid`,
and the `.intoto.jsonl.sigstore.json` beside it is the Sigstore bundle of that signature (the
certificate of the short-lived key and its transparency-log entry) rather than a bundle over the
envelope file. Only that bundle verifies the envelope, so a re-run that finds a keyless envelope
with no bundle beside it uploads a fresh envelope and its bundle in its place. Verify it against
the same `pae.bin`, naming the identity you expect:

```bash
cosign verify-blob --bundle widget-1.0.0.intoto.jsonl.sigstore.json --new-bundle-format \
  --certificate-identity <identity> --certificate-oidc-issuer <issuer> pae.bin
```

A dry run does not reach Sigstore, so without a key it writes the bare `.intoto.json`.
With a key and an identity both, the envelope is the GPG-signed one, with a `.sigstore.json` bundle
over the file like every other file.

## Deny policy

```toml
[deny.sources]
deny = ["jcenter.bintray.com"]   # checked by jk deny (host match)
```

Host matching is exact or a DNS-label suffix (`evil.com` matches `repo.evil.com`, not
`notevil.com`).

```toml
[deny.licenses]
deny  = ["GPL-*", "AGPL-*"]          # a row whose every licence matches is refused
allow = ["Apache-2.0", "MIT", "BSD-*"] # or: a row needs one licence that matches
```

Licences are the SPDX ids the lock records from each artifact's POM
([Lockfile](lockfile.md#what-a-row-says-about-its-licence)), matched by glob without case. A
dual-licensed artifact passes `deny` when one of its licences is not denied; under `allow`, a POM
that declares no licence fails. A row that records none (a path or git dependency) is not judged.
`jk deny` checks the lock against the policy; run it in CI as a gate.

The yanked policy is **not** enforced yet: `yanked` other than `"allow"` is **rejected at parse**
until enforcement ships — silent no-ops are not allowed.

## Audit

`jk audit` queries OSV for every package in `jk-lock.toml` and reports each advisory against
the locked version: severity, summary, and — when OSV names one — the nearest fixed version
above the locked one, taken from OSV's version ranges (a git commit range names no version). The
classifier jars of one version (a native jar per platform) are one package to OSV and give one
finding per advisory; two locked versions of a package give one each. Lock rows pin a source repo (namespace binding planned). Combine with
exclusive repository groups — [Repositories](repositories.md).

```bash
jk audit                       # report everything, gate at LOW
jk audit -s HIGH       # gate at HIGH and above
jk audit -O jsonl              # one JSON line per finding
jk audit --vex audit.vex.json  # also write an OpenVEX document
```

### Exit status

The exit status is the verdict. `jk audit` exits **0** when no finding at or above
`--severity` (default `LOW`) is outstanding, and **1** when one is. An advisory OSV gives no
severity label is rated by the highest score of its CVSS v3 and v4 vectors (9.0 and up
`CRITICAL`, 7.0 `HIGH`, 4.0 `MEDIUM`, below that `LOW`). One with neither counts at every
threshold — the audit fails closed rather than hide what it cannot classify. A finding covered by
an unexpired `[audit] ignore` entry is reported but never counted. `--offline` is refused before
anything is queried: an audit has no cached answer, and a clean report produced without asking OSV
would be a claim about safety nobody made.

Each advisory's details are kept in the cache root's `osv-vulns/` and fetched again only when OSV
reports the advisory changed, so a repeat audit asks OSV one batch query; an entry no audit has
read for 30 days goes.

The run's verdict says the same: with a blocking finding the audit fails, one error per finding
(severity, package and version, advisory, summary, the fix when OSV names one), so an agent's
verdict and a CI log read what the exit status means. A CI gate is `jk audit -s HIGH` and a
non-zero exit.
JumpKick's own repository runs exactly that in its self-host job, after the lock-drift check: the
`-O jsonl` lines are kept as an artifact and each finding becomes one annotation
(`scripts/ci-audit-annotate.sh`, one `::error` per blocking advisory), and the nightly run reports
at `LOW` without gating ([CI](ci.md)).

### JSON

With `-O jsonl` every finding is one line in the [machine envelope](machine-output.md)
(`schema` 1, `ts`, `type`), after the run's own plan events:

```json
{"schema":1,"ts":1721664000123,"type":"audit-finding","id":"GHSA-xxxx-xxxx-xxxx","package":"com.fasterxml.jackson.core:jackson-databind","version":"2.9.8","severity":"HIGH","summary":"…","fixedIn":"2.9.10.4","ignored":false}
```

| Field | Meaning |
|-------|---------|
| `id` | The advisory id as OSV reports it (`GHSA-…`, `CVE-…`) |
| `package` / `version` | The locked package (`group:artifact`) and version the advisory applies to |
| `severity` | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, or `UNKNOWN` when OSV gave neither a label nor a CVSS v3 or v4 vector |
| `summary` | OSV's one-line summary |
| `fixedIn` | The nearest fixed version above the locked one; absent when OSV names none |
| `aliases` | The advisory's other ids (the `CVE-…` of a `GHSA-…`); absent when it has none |
| `ignored` | `true` when an unexpired `[audit] ignore` entry covers the finding |
| `reason`, `until` | The covering entry's reason and last day, when an entry names the advisory |
| `ignoreExpired` | `true` when that entry has lapsed — the finding counts again |

Fields are additive; the exit status is still the verdict.

### Accepting a finding

An advisory you have reviewed and accept goes in the manifest beside `jk-lock.toml` — the
workspace root's, or the standalone project's:

```toml
[audit]
ignore = [
  { id = "GHSA-xxxx-xxxx-xxxx", reason = "test-only dependency; the gadget is not on the runtime path" },
  { id = "CVE-2025-0001", reason = "waiting on the upstream release", until = "2026-12-31" },
]
```

An entry's `id` may name the advisory or any of its aliases: OSV reports a Maven advisory under
its `GHSA-…` id, and an entry for the `CVE-…` it aliases covers it too. Every entry needs its
`reason` — an ignore nobody can review is refused at parse. `until` is optional and an ISO date (`YYYY-MM-DD`): the entry ignores through that day, on the local
calendar where `jk audit` runs, and expires after it.
The text report says `ignored (reason)` for a covered finding and `ignore expired (reason, until
…)` for a lapsed one; the JSON carries the same state. Unknown keys on either level are
refused, so a misspelt `untill` cannot silently turn a dated ignore into a permanent one.

### OpenVEX

`jk audit --vex <file>` writes the findings as an [OpenVEX](https://openvex.dev) document as well
as the normal report, whatever the verdict, so a scanner that reads VEX can drop what the project
does not ship. The audited project is the product, named by its purl, and each vulnerable jar is a
subcomponent. Only statements that cannot be wrong are made:

| Status | When |
|---|---|
| `not_affected`, justification `component_not_present` | Every locked row of the jar is test, processor, dev or plugin scope: it is in no runtime classpath and no packaged artifact |
| `under_investigation` | Anything else: a `main`, `runtime` or `export` jar ships, and a `provided` one is supplied by the platform the product runs on |

A shipped jar is never called `not_affected`: whether its vulnerable code runs is not something a
call graph proves, since reflection, `ServiceLoader` and auto-configuration reach code no call
edge names. The lock's scopes are the workspace's, so a jar one member ships is `under_investigation`
for every member. An `[audit] ignore` entry does not change a statement.

## Verify

`jk verify` rebuilds in a scratch directory and compares artifact hashes. Use it when you
need a rebuild-from-lock check, not as a substitute for the lockfile itself. A module that
packages no jar (a node module nothing depends on) has nothing to compare and is passed over;
one whose jar the rebuild makes but your last `jk build` did not fails with "run `jk build`
first". The scratch directory is removed when verify ends, on Ctrl-C too.

## Related

[Packaging](packaging.md) · [Repositories](repositories.md) · [Lockfile](lockfile.md) ·
[Security](security.md)
