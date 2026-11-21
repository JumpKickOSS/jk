# Publish and supply chain

```bash
jk publish -l https://repo.example.com/releases/
jk publish -I -f ~/.gnupg/release.asc
jk publish -g
jk publish -L
jk publish -W
jk publish -c -I -f ~/.gnupg/release.asc   # Maven Central, via the Portal
jk verify                 # rebuild in a scratch dir and diff artifact hashes
jk audit                  # OSV
jk deny                   # apply [deny.sources]
```

Real credentialed uploads are **CLI-only**. MCP `publish` / `run kind=publish` is
always a **dry-run** so tokens never enter the engine — [MCP](mcp.md).

Export a lock scope as a Maven BOM: `jk export bom` — [Platforms](platforms.md).

`jk publish` builds the project first, with the same steps and tests as `jk build` (`-S` skips
the tests), then uploads what that build produced; a refused publish (a SNAPSHOT version, a
branch-tracked git dependency) fails before anything compiles. A library's release set is jar,
POM, `-sources.jar` and `-javadoc.jar`; Maven Central refuses a release missing either classifier
jar. The publish build writes the javadoc jar of every library that does not say `javadoc =
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

The same table says where a release goes, so a bare `jk publish` is the whole command on a
laptop and in CI:

```toml
[repositories.releases]
url = "https://nexus.example.com/repository/maven-releases/"

[publish]
repository = "releases"             # a [repositories.<name>] entry, or "central"
sign = true
key-file = "~/.gnupg/release.asc"   # the passphrase is JK_GPG_PASSPHRASE, never here
```

`--repo-url`, `--central`, `--sign` and `--key-file` override the table. Credentials and
passphrases are secrets and do not belong in `jk.toml`: a `password`, `token` or `passphrase` key
in `[publish]` is refused, naming where the secret goes (`jk repo login`, `JK_REPO_<ID>_*`,
`JK_GPG_PASSPHRASE`).

The key file may hold the whole secret key or only its subkeys (`gpg --export-secret-subkeys`, the
primary kept offline). The newest key in it that is flagged for signing, carries its secret and is
neither revoked nor expired signs; when none does, the refusal names each key and why it was passed
over.

### Publishing from a tag

`[release] tag` is the template of the git tag a release carries, `{version}` standing for the
released version (default `v{version}`). `jk publish --tag <name>` compares the name with the
template rendered for the project's version and stops before anything compiles when they differ:

```yaml
# .github/workflows/publish.yml
on:
  push:
    tags: ["v*"]
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: jk publish --tag "$GITHUB_REF_NAME"
```

The flag is explicit: JumpKick does not read CI variables to guess a tag.

### Publishing again

A published file never changes. Publishing a version again uploads only what the repository does
not hold yet, so a run that stopped half way finishes on the next try (`16 files, 4 already
there`); a file the repository holds with other bytes stops the run and says to raise the version.

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

## Releasing

`jk.toml` declares the **next release**, never a `-SNAPSHOT`. `jk release` cuts it:

```bash
jk release                  # build + test, install 1.4.7, tag v1.4.7, then start 1.4.8
jk release --minor          # …then start 1.5.0 (--major: 2.0.0; --patch is the default)
jk release --publish --push # also upload the release before tagging, and push the tag and branch
jk release --dry-run        # check everything, change nothing
```

The segment flag picks what moves **after** the release. In order, `jk release`:

1. refuses unless the git tree is clean, the declared version is `MAJOR.MINOR.PATCH`, the tag
   (`[release] tag`, default `v{version}`) does not exist, and the `[publish]` repository does not
   already hold the version (an unreachable repository is no objection); outside git only the last
   two apply;
2. builds, tests and installs the version once, where `jk install` would (`--destination m2` names
   the Maven local repository);
3. with `--publish`, runs `jk publish` against the `[publish]` target — a failed upload leaves no
   tag and no bump, and a re-run finishes what is missing;
4. tags the commit (annotated), then rewrites the version in `jk.toml` (and in every workspace
   member that declares the same literal), relocks, and commits it as `Start <next>`;
5. with `--push`, pushes the branch and the tag; otherwise it prints the push command.

Outside git, steps 4 and 5 shrink to rewriting the version in `jk.toml` and relocking: there is
nothing to tag, commit or push, and `--push` is refused.

When a later step fails, the message says what was done and the command that finishes the rest.
A workspace is released from the root; members that inherit its version follow it.

`jk release --undo` takes back a release cut on this machine and not yet pushed or published: it
drops the `Start <next>` commit when it is HEAD, uninstalls the released version (from `~/.m2` only
what JumpKick put there) and deletes the tag, so `jk install` installs `<version>-SNAPSHOT` again.
It refuses when a git remote has the tag, a remote branch has the `Start <next>` commit, or the
`[publish]` repository holds the version.

[Releasing](release.md) has the CI recipes. CI usually does the upload: a developer runs `jk release --push`, and the tag triggers a job that
runs `jk publish --tag "$GITHUB_REF_NAME"` ([above](#publishing-from-a-tag)). Locally, `jk install`
is the loop; `jk release` is the end of it.

## Maven Central

`jk publish -c` publishes through the Sonatype Central Portal: one signed bundle — jar,
POM, sources jar, javadoc jar, a detached GPG `.asc` on each, `.md5` and `.sha1` beside each — is
uploaded to the Portal's API, and JumpKick polls the deployment until the Portal rules.

```bash
# once: the Portal user token (central.sonatype.com → account → Generate User Token)
printf '%s' "$TOKEN_PASSWORD" | jk repo login central -U https://central.sonatype.com -s "$TOKEN_NAME"

jk publish -c -I -f ~/.gnupg/release.asc   # builds the four artefacts; JK_GPG_PASSPHRASE or -p
#   Published com.example:widget:1.0.0 to the Central Portal (16 files)
#     deployment 28570f16-… · VALIDATED; release it from the Portal, or use --publishing-type automatic
```

| Flag | Meaning |
|------|---------|
| `--central` | the Portal instead of a repository URL; requires `--sign --key-file` and the `[publish]` table |
| `--publishing-type user-managed` | default: the Portal validates and parks the deployment for a click in its UI (`VALIDATED`) |
| `--publishing-type automatic` | the Portal releases a valid deployment to Central on its own (`PUBLISHED`) |
| `--repo-url <url>` | another Portal (a mirror, a stub) instead of `https://central.sonatype.com/` |
| `--dry-run` | write the bundle to `target/publish/central-bundle.zip`, list its entries, upload nothing |

Before anything is signed, JumpKick refuses with the exact fix when the sources or javadoc jar is not
on disk, when `[publish]` lacks a key Central requires, or when `--sign` is absent. The poll walks
`PENDING → VALIDATING → VALIDATED` (`→ PUBLISHING → PUBLISHED` with `automatic`); a `FAILED`
deployment fails the run and prints every validation error the Portal listed.

The credential is the `central` entry: `jk repo login central -U https://central.sonatype.com`
with the token's name as `--username` and its password on stdin (JumpKick sends the Portal the base64
`name:password` it documents), or a token already encoded, on stdin without `--username`. From
CI, `JK_REPO_CENTRAL_USERNAME` + `JK_REPO_CENTRAL_PASSWORD` (or `JK_REPO_CENTRAL_TOKEN`) with
`JK_REPO_CENTRAL_HOST=central.sonatype.com` beside them — the same name-to-origin binding every
stored credential needs ([Repositories: credentials](repositories.md#credentials)). `--user` /
`--password` on the command line are the token's name and password too.

`target/jk-results.md` gets a **Publish** block for every publish run — the target, the file
count, and for Central the deployment id, the state the poll ended in and every validation error
— so a failed release is diagnosed from the results file, not from scrollback.

## SBOM

`jk publish -S` uploads a CycloneDX 1.6 and an SPDX 2.3 document beside the artifact and
writes the same two files under the module's `target/` — `target/sbom/<name>-<version>.cdx.json`
and `target/sbom/<name>-<version>.spdx.json` — printing their paths. With `--dry-run` nothing is uploaded and no
`--repo-url` is needed, so a release script takes the bill of materials from disk:

```bash
jk publish -S -U
#   wrote target/sbom/widget-1.0.0.cdx.json
#   wrote target/sbom/widget-1.0.0.spdx.json
```

The CycloneDX document is deterministic (no serial number, no timestamp) and is written by the
same code that embeds `META-INF/sbom/application.cdx.json` in every application jar
([Packaging](packaging.md)), so the sidecar of a module is the document its jar carries: the
module's production runtime closure — the rows of `jk-lock.toml` its own dependencies and those of
the workspace siblings it depends on reach, not every member's — each with its SHA-256. Both
documents carry the dependency graph between them: CycloneDX as `dependencies`, SPDX as a
`DESCRIBES` relationship from the document to the module and `DEPENDS_ON` relationships from the
module and each package to what it depends on.

`--slsa` uploads an SLSA v1 provenance statement (`.intoto.json`) whose
`resolvedDependencies` are the same closure: each component's purl as `uri` and its locked
SHA-256 as `digest.sha256`.

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

## Verify

`jk verify` rebuilds in a scratch directory and compares artifact hashes. Use it when you
need a rebuild-from-lock check, not as a substitute for the lockfile itself. A module that
packages no jar (a node module nothing depends on) has nothing to compare and is passed over;
one whose jar the rebuild makes but your last `jk build` did not fails with "run `jk build`
first". The scratch directory is removed when verify ends, on Ctrl-C too.

## Related

[Packaging](packaging.md) · [Repositories](repositories.md) · [Lockfile](lockfile.md) ·
[Security](security.md)
