# Lockfile

`jk-lock.toml` is **canonical**. Commit it. Day-to-day you should not think about it: any
command that needs a current lock (build, explain, status, tree, sync, export, ide, …)
auto-refreshes when the lock is missing or out of sync with manifests.

`jk build` **does not re-resolve**. That is the product.

```bash
jk lock          # resolve → write jk-lock.toml, keeping every version it already holds
jk lock -F       # same, but move opt-in selectors (^ ~ ranges latest) to their newest match
jk sync          # materialize cache / --offline-prepare
jk outdated      # read-only: Current / Compatible / Latest
jk update        # bump declared pins in jk.toml to the newest stable on the same major, relock
jk update --major  # allow a major-line jump
jk tree / jk why
```

There is **one** lockfile: workspace root or standalone project root. Members redirect to
the root lock. Never write per-module lockfiles.

## Commands

| Command | Role |
|---------|------|
| `jk lock` | Resolve and write the lock, keeping every version it already holds; only what a changed constraint rules out moves. Metadata warm within 24h TTL (local first) |
| `jk lock -F` | The same resolve, but every opt-in selector (`^`, `~`, range, `latest`) takes the newest version it allows; exact pins do not move. Revalidates metadata past the TTL |
| `jk sync` | Materialize cache; `--offline-prepare` for offline CI |
| `jk outdated` | Current / Compatible / Latest table (exit 0 always on success) |
| `jk update` | Rewrite declared pins in `jk.toml` to the newest stable on the same major, then relock — [below](#jk-update) |
| `jk build` | Uses the lock; does not re-resolve |
| `jk tree` / `jk why` | Inspect the graph offline |

Metadata indexes live under the store (`metadata/`, 24h TTL + ETag). Back-to-back `jk lock`
hits disk only; use `jk update` or `-F` when you need Central’s current version lists today.

**`jk lock` keeps pins.** Like `uv lock` and `poetry lock`, the everyday verb re-resolves without
taking upstream drift: existing pins seed the solver, and only coordinates a new or changed
constraint rules out move. That is what you want for landing a manifest edit that changes no
dependency — the lockfile diff is the manifest stamp and nothing else.

A bare version in `jk.toml` is exact ([version strings](projects.md#version-strings)), so for
most projects there is nothing for the lock to float: the declared number is the locked number.
`jk lock -F` moves only the **opt-in** selectors (`^`, `~`, ranges, `latest`) to the newest version
each one allows; transitives may follow within what the directs require. Automatic refreshes — a
stale lock on `jk build`, or any command that needs a current lock — always keep pins, `-F` or not.

## `jk update`

`jk update` is the bump verb. It rewrites the **declared** versions in `jk.toml`, then relocks:

```bash
jk update                      # every declared Maven coordinate → newest stable on its major
jk update jackson2-databind    # only the named handle(s); also --dep <name>
jk update --major              # allow a major-line jump (2.22.2 → 3.x)
jk update --git [name]         # advance git dependencies to their current ref instead
jk update --platform=floor     # relock with BOM pins as lower bounds — Platforms
```

| Declared | `jk update` | `jk update --major` |
|----------|-------------|---------------------|
| `"2.18.0"` (exact) | writes `"2.18.2"`, the newest stable **2.x** | may write `"3.0.0"` |
| `"^2.18"` / `"~2.18"` / range / `"latest"` | the lock takes the newest match inside that selector; the text stays | same, unless the selector itself excludes the new major |
| `"g:a"` (versionless) | untouched — the BOM decides | untouched |

The Maven major is the first numeric segment (`2.18.2` → 2.x, `33.4.8-jre` → 33.x). Pre-releases
are never "stable", so an RC is taken only by an opt-in selector that admits it. The rewritten
manifest keeps its spelling — a catalog one-liner stays a one-liner, a GAV string stays a GAV
string — and the reviewable diff is `jk.toml` plus `jk-lock.toml`. `jk update` also revalidates
metadata and refreshes the recorded toolchain ([below](#toolchain-pins)). MCP has the same verb
with a preview: [`jk_update`](mcp.md#tools).

## `jk outdated`

```bash
jk outdated
jk outdated --exclude-up-to-date
jk outdated --output json
jk outdated --offline
```

| Column | Meaning |
|--------|---------|
| **Current** | Version pinned in `jk-lock.toml` (empty if unlocked) |
| **Compatible** | Newest version the declared selector still admits — for an exact pin, the same as Current |
| **Latest** | Newest stable in the repo; what `jk update` (same major) or `jk update --major` would write |
| **Tip** | With `--show-tip`: prerelease / git frontier ahead of Latest |

Exit code is always `0` on a successful report. There is no `--fail-if-outdated` — lockfile
changes stay intentional. For CI “fail if drift”, parse `--output json`.

`--offline` uses only local cache / repo mirrors. Unreachable remotes look empty on
Compatible/Latest — the CLI prints a note so that is not mistaken for “everything is current.”

JSON is an **array** of row objects (`module`, `dependency`, `display`, `scope`, `current`,
`compatible`, `latest`, `tip`). `module` is empty for a single-project root. `display` is
the catalog short name when known.

## Toolchain pins

`jk lock` records the JDK (and GraalVM, when one was in play) that resolved the graph, on two
independent axes:

```toml
[jdk]
suggested-vendor  = "temurin"     # what built it; a later build may differ
suggested-version = "25.0.4.1"    # floor on the MAJOR: 25.0.1 and 26.x clear it, 21 does not

[graal]                           # omitted when no Graal was used
required-vendor   = "graalvm-ce"  # no choice — install it or the build fails
required-version  = "25.0.4"      # exact: 25.0.3, 25.1.0 and 26.0.2 all fail
```

`suggested-*` is a record of what created the lock. It binds nothing but the major: a build on a
newer JDK is fine, an older one is not — and because it is a record, `jk lock` leaves it as it
found it. Re-locking on a machine with a different vendor does not rewrite what built the lock;
only `jk update`, whose job is moving forward, refreshes it. An exception: a previous suggestion
that names a vendor jk cannot install is dropped and rewritten from the toolchain that resolved.
Copying `nosuchvendor-99` forward would make the next `jk build` try to install a catalog-missing
spec after the agent already removed the pin.

`required-*` is a pin the project asked for, and only an `=` in `jk.toml` writes one:

| jk.toml | lock |
| --- | --- |
| `jdk = "temurin-25"` | `suggested-vendor`, `suggested-version` |
| `jdk = "=temurin-25.0.4"` | `required-vendor`, `required-version` |
| `jdk = "=temurin-25"` | `required-vendor` + `suggested-version` (no patch to be exact about) |
| `jdk-vendor = "=corretto"`, `jdk-version = 25` | `required-vendor` + `suggested-version` |
| `[native] graal = "=25.2.4-graalce"` | `required-vendor`, `required-version` (SDKMAN order) |

The fields mix freely, and each axis is written on one side only — a required vendor makes the
suggested one meaningless. Vendors are lower-cased short ids.

Among installs that satisfy the pin, the hook prefers exact vendor+version, then the same vendor,
then the newest. A `required-*` nothing satisfies means install, never settle: the shell hook
exports nothing rather than hand over a JDK the build itself will refuse.

A `suggested-*` nothing installed satisfies is different — it is only a floor, so jk falls
through to whatever else is available (`JAVA_HOME`, `GRAALVM_HOME`, `PATH`) as long as that
still clears the major. Nothing below the floor is accepted from any of them, and if nothing
anywhere clears it, jk installs.

Locks written before this shape (a bare `vendor` / `version` pair under `[jdk]`) are rejected
rather than guessed at — re-run `jk lock`. The lockfile's `version` stays `1` until JumpKick 1.0;
the shape changes in place, never by a new number.

## Pre-release pins

A lock that records an RC/M/beta is kept by `jk lock` when it still satisfies the declared
selector. A platform BOM pin (including a pre-release line) is enforced on
managed GAs while the platform is active. `latest` prefers the newest
**stable** over a newer pre-release; `snapshot` takes pre-releases too. Deliberate upgrades off
a pre-release belong on `jk update`.

## Lock-time trust

`jk lock` is the trust boundary. For each POM/artifact download JumpKick:

1. Streams bytes, computes SHA-256 locally, and stores a Maven-layout `*.jar` under the
   store's `repos/<origin>/` (written through to the Maven local repo too when `[m2] integration`
   is on and the slot is empty or already equal).
2. Fetches the repository’s published sidecar (`.sha256`, else `.sha1`, else `.md5` as the last
   resort) and **fails closed** on mismatch. An artifact only an `.md5` vouches for (Central holds
   POMs published that way, `org.jetbrains.kotlin:kotlin-bom:1.9.20` among them) is accepted, and
   the lock output carries a note naming the artifact and the weaker digest; from then on the lock
   pins its bytes by SHA-256 like every other row.
3. Refuses to pin when no sidecar exists, unless the repository table says
   `allow-unverified = true`; the lock summary then counts those rows as `unverified (allowed)`.
4. Refuses a plaintext `http://` repository when the manifest is read, unless its table says
   `allow-insecure = true`; the summary then names it as `insecure (allowed)`. Neither key is
   accepted on `central`. See [Repositories](repositories.md#transport-and-checksum-trust).

After the lock exists, `jk sync` / builds enforce the **pinned hashes only** — they do not
re-check upstream sidecars. A digest mismatch against the lock is a cache miss / refetch,
not a silent accept. `jk repo refresh <coord>` re-fetches a coordinate on purpose.

GPG/Sigstore for *your* publishes: [Publish](publish.md). First-write-wins and
`--offline` never networking are the store policy.

## Rows a dependency's repository serves

A row's `source` is the repository that served its artifact, as `<name>+<url>`. That is normally
one of the project's `[repositories]` (or a built-in), but a dependency POM may declare a
repository of its own for its subtree, and a row it served records it —
`source = "jitpack.io+https://jitpack.io"` — so a later `jk build` fetches from the same origin
without the project declaring it. The lock output names each such repository once, with the POM
that introduced it; the trust rule is a declared repository's (https, published checksums), with
no opt-out. See [Dependencies](dependencies.md#repositories-a-dependencys-pom-declares).

## Rows that follow the host

A dependency POM may spell a classifier with a property Maven values from the machine —
OpenJFX's `${javafx.platform}`, os-maven-plugin's `${os.detected.classifier}`. The row `jk lock`
writes for such an edge is the artifact of the host that ran it (`org.openjfx:javafx-graphics:jar:linux`),
and the lock output carries one note per edge naming the module, the classifier it took and the
expression the POM wrote. Relocking on another platform rewrites those rows for that platform;
see [Dependencies](dependencies.md#classifiers-that-follow-the-host).

## What else the lock pins

Not everything a build depends on is a classpath entry. Alongside `[[artifact]]` the lock
carries the resolved Kotlin/Scala compiler versions, `[[plugin]]` and `[[sdk]]` rows, and:

```toml
[native]
metadata-repository = "1.1.4"
checksum = "sha256:…"
```

— the GraalVM reachability-metadata repository release, resolved from `[native]
metadata-repository` in `jk.toml`. It is not an `[[artifact]]` row: it is on no classpath
and in no scope. It *is* an input to `native-image`, so it is pinned like one. See
[Native images](native.md#the-graalvm-metadata-repository).

A `[[plugin]]` row normally pins the plugin jar by `checksum`: a jar that is fetched later and
disagrees with it is refused. A plugin the workspace builds itself — a module whose
coordinate is the plugin's, such as jk's own tree building `cc.jumpkick:jk-guards-junit` for
its guard suites — is pinned by `path` to that module instead, with no digest. Its identity is
its source (the module's manifest is already inside `manifests-sha256`), and it is verified by
being built from the workspace, so the row is the same whichever jar happens to be installed or
staged and `jk lock` on a clean checkout rewrites nothing. Third-party plugins and rule packs
keep their digest.

A first-party plugin that ships inside jk (`cc.jumpkick:jk-minified`, `jk-spring-boot`,
`jk-micronaut`, …) is the running jk's own copy, and its row **follows the running jk**. The row
records which jk the project built with — `coordinate` and `version` alone while that version is
a pre-release (a `0.x` or any qualifier/snapshot, republished with every rebuild), plus the
`checksum` of the shipped jar at a stable release — and it never chooses the bytes: the plugin
that runs is the one inside the jk that is building. A build under a newer jk rewrites those rows
in place and says so once (`jk-lock.toml: jk-micronaut 0.13.2 → 0.13.7 (first-party plugins follow
the running jk)`); no library row moves, so a jk upgrade is never a relock. A first-party plugin a
`[plugins]` table declares explicitly is pinned by that declaration like any vendored jar.

A first-party rule pack (`cc.jumpkick.guards:spring`, `:quarkus`, `:library`, … named by
`[guards] extends`) is pinned by `coordinate` and `version` alone while that version is a
pre-release, and carries its digest at a stable release. Third-party plugins and packs carry their
digest at every version.

## What an edge records

Every `[[artifact]]` row lists the edges its POM contributes to the graph. An edge names the
package the solve picked and, after `<-`, the selector the parent declared for it:

```toml
deps = [
  "org.jetbrains:annotations:jar:@13.0 <- 13.0",
  "org.slf4j:slf4j-api:jar:@2.0.17 <- [2.0,3.0)",
]
```

Two versions on one line is the point: when a transitive lands somewhere surprising, the lock
itself says which declaration produced it, without re-reading any POM. `jk why <coord>` walks
these edges and prints each step with `(declared <selector> by <parent>)` beside the resolved
version — the parent is `jk.toml` for a declared root and the previous step otherwise. An edge
the lock does not carry a selector for is written without the `<-` part. A POM edge written as
Maven's `LATEST` or `RELEASE` records that word (`<- LATEST`) beside the number the solve pinned.

A row whose version a pin source decided carries `pinned-by`: the BOM as `group:artifact:version`
for a `[platform-dependencies]` entry, or `jk.toml:<handle>` for a
[`[managed-dependencies]`](dependencies.md#managed-versions) entry.

Under `[resolve] pins = "nearest"` (what `jk import` writes for a Maven POM) the picked version can
sit below the declared one: `jakarta.inject-api:jar:@2.0.1 <- 2.0.1.MR` says the project pinned
`2.0.1` and the parent's floor of `2.0.1.MR` gave way to it, as a transitive's version gives way to
a direct dependency's under Maven. `jk lock` prints one note per pinned module, naming the pin,
how many dependencies it overrode and what each asked for. A floor written as an open range
(`[2.0.18,)`) gives way the same way and is recorded the same way (`<- [2.0.18,)`). A workspace
resolves under its root's `[resolve]` table, and a pin any member declares is the version for the
whole lock, whichever member brought in the transitive that asked for more. Under the default
`pins = "exact"` that shape is a conflict the lock refuses instead; see
[Dependencies](dependencies.md#coordinates).

A test-scope exact pin on a module the main graph resolves at another version gives way to main's:
the test classpath is the main classpath plus the test rows, so main's version is the one there
whatever the pin asks, and a test row at the pin's version would be content nothing reads. The lock
writes one row at main's version for both scopes, and `jk lock` says so once per such pin, naming
the pin and main's version. Move the pin to a main scope to make it the version everywhere, or drop
it. A test dependency's own edge onto such a module still resolves against main's version the same
way ([Dependencies](dependencies.md#coordinates)).

`nearest` covers direct pins and BOM order and nothing else: a module only transitive POMs name
resolves highest-declared under both policies, so a lock row can sit above the version Maven's
nearer declaration gives the same module. Measured on the Maven top-20 corpus in jk-examples, over
the 14 repositories that lock and their 341 modules, 170 modules differ from Maven on some
version, mostly through inline `<dependencyManagement>` entries jk applies to declared dependencies
only; depth mediation alone accounts for 111 of the 705 differing (module, coordinate) pairs. The
policy table is in [Platforms](platforms.md#two-boms-that-manage-one-module).

## Rows a member owns

A workspace row without a `members` key is the workspace's version: every member's classpath
reads it. A row with `members` is one member's answer where the workspace's could not be its
answer — see [Workspaces](workspaces.md#members-that-disagree) — and replaces the plain row of the
same coordinate for the members it lists, by their `[[module]]` path:

```toml
[[artifact]]
name     = "jakarta.jms:jakarta.jms-api:jar:"
version  = "3.1.0"
pinned-by = "org.springframework.boot:spring-boot-dependencies:3.5.12"
scopes   = ["main"]

[[artifact]]
name     = "jakarta.jms:jakarta.jms-api:jar:"
version  = "2.0.3"
scopes   = ["main"]
members  = ["zipkin-collector/activemq"]
```

`zipkin-collector/activemq` compiles, tests and packages against 2.0.3; every other member reads
3.1.0. A member listed on no row of a coordinate reads the plain one; a coordinate only a member's
own graph reaches has only its `members` row. A member's row is written with the member's own
scopes, so a version the workspace holds only as a test row and a member wants on its main
classpath is a `members` row with `scopes = ["main"]`. `jk lock` keeps the versions such a row holds like
any other's, and says once per member which coordinates it reads its own rows for, with its version
and the workspace's. `jk why` shows both versions with their members; every tool that reads one
module's rows — the build, `jk run`, packaging and its SBOM, `jk native` training, a plugin's
platform pins, `jk export bom` from the member's directory, the IntelliJ libraries `jk ide` writes
for the module — reads that module's rows, and `jk tree` shows each member's node the version it
reads. `jk export bom` from the root freezes the workspace's rows.

## What an exclusion records

A row whose POM edge an exclusion pruned lists the edge under `excluded-by`: the child's
`group:artifact` and, after `<-`, who excluded it — `jk.toml:<handle>` for an `exclude` entry in the
manifest, `group:artifact@version` for an `<exclusions>` block in a dependency POM.

```toml
[[artifact]]
name = "io.apicurio:apicurio-registry-schema-util-json:jar:"
version = "2.6.13.Final"
excluded-by = [
  "io.apicurio:apicurio-common-app-components-logging <- jk.toml:schema-json",
]
```

The line is about the edge, not the coordinate: the child may still sit in the lock through another
path, and `jk why <child>` shows both — every path that brings it and every edge that dropped it.
A pattern several paths declared lists each origin, comma-separated. A row with nothing pruned has
no `excluded-by` key. See [Dependencies](dependencies.md#exclusions).

## Related

[Dependencies](dependencies.md) · [Platforms](platforms.md) · [Repositories](repositories.md)
