# Publish and supply chain

```bash
jk publish --repo-url https://repo.example.com/releases/
jk publish --sign --key-file ~/.gnupg/release.asc
jk publish --sigstore
jk publish --slsa
jk publish --sbom
jk publish --central --sign --key-file ~/.gnupg/release.asc   # Maven Central, via the Portal
jk verify                 # rebuild in a scratch dir and diff artifact hashes
jk audit                  # OSV
jk deny                   # apply [deny.sources]
```

Real credentialed uploads are **CLI-only**. MCP `publish` / `run kind=publish` is
always a **dry-run** so tokens never enter the engine — [MCP](mcp.md).

Export a lock scope as a Maven BOM: `jk export bom` — [Platforms](platforms.md).

A library's release set — jar, POM, `-sources.jar`, `-javadoc.jar` — is what `jk build` already
wrote, and `jk publish` uploads the two classifier jars whenever they are on disk; Maven Central
refuses a release missing either. A Kotlin library's javadoc jar is Dokka's output, so the bundle
carries real API docs rather than an empty jar.
See [Packaging: library artefacts](packaging.md#library-artefacts-sources-and-javadoc-jars).

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

## Maven Central

`jk publish --central` publishes through the Sonatype Central Portal: one signed bundle — jar,
POM, sources jar, javadoc jar, a detached GPG `.asc` on each, `.md5` and `.sha1` beside each — is
uploaded to the Portal's API, and jk polls the deployment until the Portal rules.

```bash
# once: the Portal user token (central.sonatype.com → account → Generate User Token)
printf '%s' "$TOKEN_PASSWORD" | jk repo login central --url https://central.sonatype.com --username "$TOKEN_NAME"

jk build                                                   # the four artefacts
jk publish --central --sign --key-file ~/.gnupg/release.asc   # JK_GPG_PASSPHRASE or --key-passphrase
#   Published com.example:widget:1.0.0 to the Central Portal (16 files)
#     deployment 28570f16-… · VALIDATED — release it from the Portal, or publish with --publishing-type automatic
```

| Flag | Meaning |
|------|---------|
| `--central` | the Portal instead of a repository URL; requires `--sign --key-file` and the `[publish]` table |
| `--publishing-type user-managed` | default: the Portal validates and parks the deployment for a click in its UI (`VALIDATED`) |
| `--publishing-type automatic` | the Portal releases a valid deployment to Central on its own (`PUBLISHED`) |
| `--repo-url <url>` | another Portal (a mirror, a stub) instead of `https://central.sonatype.com/` |
| `--dry-run` | write the bundle to `target/publish/central-bundle.zip`, list its entries, upload nothing |

Before anything is signed, jk refuses with the exact fix when the sources or javadoc jar is not
on disk, when `[publish]` lacks a key Central requires, or when `--sign` is absent. The poll walks
`PENDING → VALIDATING → VALIDATED` (`→ PUBLISHING → PUBLISHED` with `automatic`); a `FAILED`
deployment fails the run and prints every validation error the Portal listed.

The credential is the `central` entry: `jk repo login central --url https://central.sonatype.com`
with the token's name as `--username` and its password on stdin (jk sends the Portal the base64
`name:password` it documents), or a token already encoded, on stdin without `--username`. From
CI, `JK_REPO_CENTRAL_USERNAME` + `JK_REPO_CENTRAL_PASSWORD` (or `JK_REPO_CENTRAL_TOKEN`) with
`JK_REPO_CENTRAL_HOST=central.sonatype.com` beside them — the same name-to-origin binding every
stored credential needs ([Repositories: credentials](repositories.md#credentials)). `--user` /
`--password` on the command line are the token's name and password too.

`target/jk-results.md` gets a **Publish** block for every publish run — the target, the file
count, and for Central the deployment id, the state the poll ended in and every validation error
— so a failed release is diagnosed from the results file, not from scrollback.

## SBOM

`jk publish --sbom` uploads a CycloneDX 1.6 and an SPDX 2.3 document beside the artifact and
writes the same two files under the module's build output — `target/sbom/<name>-<version>.cdx.json`
and `target/sbom/<name>-<version>.spdx.json`; a workspace member's build output sits under the
root's `target/<module>/` — printing their paths. With `--dry-run` nothing is uploaded and no
`--repo-url` is needed, so a release script takes the bill of materials from disk:

```bash
jk publish --sbom --dry-run
#   wrote target/sbom/widget-1.0.0.cdx.json
#   wrote target/sbom/widget-1.0.0.spdx.json
```

The CycloneDX document is deterministic (no serial number, no timestamp) and is written by the
same code that embeds `META-INF/sbom/application.cdx.json` in every application jar
([Packaging](packaging.md)), so the sidecar of a module is the document its jar carries: the
production runtime rows of `jk-lock.toml`, one per module, each with its SHA-256.

## Deny policy

```toml
[deny.sources]
deny = ["jcenter.bintray.com"]   # enforced at lock / jk deny (host match)
```

Host matching is exact or a DNS-label suffix (`evil.com` matches `repo.evil.com`, not
`notevil.com`).

License and yanked policies are **not** enforced yet. Those keys are **rejected at parse**
until enforcement ships — silent no-ops are not allowed.

## Audit

`jk audit` queries OSV for every package in `jk-lock.toml` and reports each advisory against
the locked version: severity, summary, and — when OSV names one — the nearest fixed version
above the locked one. Lock rows pin a source repo (namespace binding planned). Combine with
exclusive repository groups — [Repositories](repositories.md).

```bash
jk audit                       # report everything, gate at LOW
jk audit --severity HIGH       # gate at HIGH and above
jk audit --output json         # one JSON line per finding
```

### Exit status

The exit status is the verdict. `jk audit` exits **0** when no finding at or above
`--severity` (default `LOW`) is outstanding, and **1** when one is. An advisory whose severity
OSV does not label counts at every threshold — the audit fails closed rather than hide what it
cannot classify. A finding covered by an unexpired `[audit] ignore` entry is reported but never
counted. `--offline` is refused before anything is queried: an audit has no cached answer, and a
clean report produced without asking OSV would be a claim about safety nobody made.

A CI gate is `jk audit --severity HIGH` and a non-zero exit.
jk's own repository runs exactly that in its self-host job, after the lock-drift check: the
`--output json` lines are kept as an artifact and each finding becomes one annotation
(`scripts/ci-audit-annotate.sh`, one `::error` per blocking advisory), and the nightly run reports
at `LOW` without gating ([CI](ci.md)).

### JSON

With `--output json` every finding is one line in the [machine envelope](machine-output.md)
(`schema` 1, `ts`, `type`), after the run's own plan events:

```json
{"schema":1,"ts":1721664000123,"type":"audit-finding","id":"GHSA-xxxx-xxxx-xxxx","package":"com.fasterxml.jackson.core:jackson-databind","version":"2.9.8","severity":"HIGH","summary":"…","fixedIn":"2.9.10.4","ignored":false}
```

| Field | Meaning |
|-------|---------|
| `id` | The advisory id as OSV reports it (`GHSA-…`, `CVE-…`) |
| `package` / `version` | The locked package (`group:artifact`) and version the advisory applies to |
| `severity` | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, or `UNKNOWN` when OSV gave no label |
| `summary` | OSV's one-line summary |
| `fixedIn` | The nearest fixed version above the locked one; absent when OSV names none |
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

Every entry needs its `reason` — an ignore nobody can review is refused at parse. `until` is
optional and an ISO date (`YYYY-MM-DD`): the entry ignores through that day and expires after
it. The text report says `ignored (reason)` for a covered finding and `ignore expired (reason,
until …)` for a lapsed one; the JSON carries the same state. Unknown keys on either level are
refused, so a misspelt `untill` cannot silently turn a dated ignore into a permanent one.

## Verify

`jk verify` rebuilds in a scratch directory and compares artifact hashes. Use it when you
need a rebuild-from-lock check, not as a substitute for the lockfile itself.

## Related

[Packaging](packaging.md) · [Repositories](repositories.md) · [Lockfile](lockfile.md) ·
[Security](security.md)
