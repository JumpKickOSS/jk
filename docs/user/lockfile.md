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
| `jk sync` | Materialize cache; `--offline-prepare` for offline CI; `--sources` fetches every library's `-sources.jar` for the IDE |
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
| `"g:a"` / `"managed"` (versionless) | untouched — the BOM decides | untouched |

The tool tables move under the same rule and flags: an exact `[dokka] version`, `[protobuf] version`
(protoc), a `[protobuf.<id>] plugin`, and a `[generate.<name>] tool` or `unpack` coordinate are
rewritten to the newest stable on their major; `jk update dokka`, `jk update grpc-java` or the tool's
`group:artifact` selects one, and the rewrite line names the key (`grpc-java  1.70.0 → 1.81.0
(protobuf.grpc-java.plugin)`). A floating selector in a tool table keeps its text as a dependency's
does. Any plugin's tool pins move the same way: a step dependency whose coordinate takes its
version from the plugin's table, or from an entry's key, is a pin on the module the coordinate
names — its group and artifact filled from the table the way the step fills them.

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

## Rows without a file

A row that pins no `checksum` has no file of its own: a BOM (`type = "pom"`), an aggregator or a
`packaging=pom` module with no jar beside it, a relocation stub whose POM points at another
coordinate, a Kotlin multiplatform root whose `-jvm` row holds the bytes. Such a row names the file
it stands for as its `path` — `aggregator-1.0.pom`, `widget-1.0.0.module` — and its `source` is the
repository that served that file, never a repository that served nothing:

```toml
[[artifact]]
name    = "com.foo:aggregator:jar:"
version = "1.0"
source  = "central+https://repo.maven.apache.org/maven2/"
path    = "aggregator-1.0.pom"
scopes  = ["main"]
```

A row without a file puts nothing on a classpath and is skipped without a word. A row that pins no
checksum *and* names no such file pins a jar nobody fetched, and a compile classpath fails on it by
name (`dependency org.picketbox:picketbox:5.0.3.Final has no file …`) rather than compiling without
it — re-run `jk lock`. That rule holds for a lock whose `generated-by-build-time` says its writer
marked such rows; a lock from an earlier writer carries its BOMs and aggregators unmarked, is read
by that writer's rule — every checksum-less row is file-less — and builds as before. The first
build or `jk sync` that finds such a lock fresh rewrites it in place — each bare row gains the
`path` of the POM it stands for, the manifest digest and every other row stay — and says so on one
line, so an existing lock converges without a resolve and without a diff to read beyond the marks;
a `jk lock` marks the rows as it writes. When a compile fails on `package X does not exist` and the module's lock
carries a jar-typed row standing for a POM alone, the error names that row under `locked without a
file:` with the repository that served its POM: the jar was not there when the lock was written, so
add the repository that publishes it to `[repositories]` and re-run `jk lock`.

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
[`[managed-dependencies]`](dependencies.md#managed-versions) entry. A workspace's plain rows are
solved under the BOMs and entries every member holds
([Workspaces](workspaces.md#members-that-disagree)); where a BOM or entry only some members hold
manages a row's module at the version the workspace's row took anyway, the row carries it — the
first member's in `[workspace] modules` order whose graph reaches the row — so the lock says who
pinned the version for every member that reads it, and `jk why` says it again.

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

A test dependency whose POM floors a module *above* the version main floated onto is the same dead
row from another cause: the test graph honours the edge and the lock keeps that row (its closure is
what the dependency asked for), but the test classpath still carries main's version, so `jk lock`
notes the module, both versions and the test dependency holding the floor. Declare the module in a
main scope at the version the tests need, or pick a test dependency that accepts main's.

`nearest` covers direct pins and BOM order and nothing else: a module only transitive POMs name
resolves highest-declared under both policies, so a lock row can sit above the version Maven's
nearer declaration gives the same module. Measured on the Maven top-20 corpus in jk-examples, over
the 16 repositories that lock and their 493 modules, 225 modules differ from Maven on some version;
depth mediation accounts for 367 of the 713 differing (module, coordinate) pairs, two BOMs managing
one module for 137, another member's pin for 70, and a parent that already differs for 102. The
policy table is in [Platforms](platforms.md#two-boms-that-manage-one-module).

## Rows a member owns

A workspace row without a `members` key is the workspace's version: every member's classpath
reads it. A row with `members` is one member's answer where the workspace's could not be its
answer — see [Workspaces](workspaces.md#members-that-disagree) — and replaces the plain row of the
same coordinate for the members it lists, by their `[[module]]` path:

```toml
[[artifact]]
name     = "jakarta.jms:jakarta.jms-api:jar:"
version  = "2.0.3"
scopes   = ["main"]

[[artifact]]
name     = "jakarta.jms:jakarta.jms-api:jar:"
version  = "3.1.0"
pinned-by = "org.springframework.boot:spring-boot-dependencies:3.5.12"
scopes   = ["main"]
members  = ["zipkin-server"]
```

`zipkin-server`, whose `[spring-boot]` table brings the BOM, compiles, tests and packages against
3.1.0; every other member reads the 2.0.3 `activemq-client` declares. A member's row can also be
the workspace's version with fewer `deps`: the edges a BOM or `[managed-dependencies]` entry only
that member holds excluded, listed under its `excluded-by`, so the member's classpath walk never
reaches them where every other member's does. A member listed on no row of a coordinate reads the plain one; a coordinate only a member's
own graph reaches has only its `members` row. A member's row is written with the member's own
scopes, so a version the workspace holds only as a test row and a member wants on its main
classpath is a `members` row with `scopes = ["main"]`, and it replaces the plain rows of its own
classpath family alone — main, runtime and test are one family, since the test classpath carries
the main rows — while a plain row of the same coordinate on the annotation processor path, a graph
of its own — the Guava an Error Prone processor path reads beside a main classpath a member pins to
another Guava — is still the member's to read. `jk lock` keeps the versions such a row holds like
any other's, and says once per member which coordinates it reads its own rows for, with its version
and the workspace's. `jk why` shows both versions with their members; every tool that reads one
module's rows — the build, `jk run`, packaging and its SBOM, `jk native` training, a plugin's
platform pins, `jk export bom` from the member's directory, the IntelliJ libraries `jk ide` writes
for the module — reads that module's rows, and `jk tree` shows each member's node the version it
reads, the row tagged `(for <members>)`, expanding a shared subtree again where the member's rows
differ from the workspace's. `jk export bom` from the root freezes the workspace's rows.

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

## How the lock is read

The lock is TOML, and any TOML reader can read it. jk reads it through the row grammar its
writer emits — bare keys, quoted strings, integers, booleans, string arrays, `[table]` and
`[[row]]` headers — in one pass, so a workspace lock of a thousand modules and a megabyte of rows
is parsed in a few milliseconds and a few megabytes of heap, and a `jk lock` that produces one
finishes at the default heap. Anything a hand edit adds that the writer never emits (a comment, a
literal `'string'`, an inline table) is read through a full TOML parser instead, with its
diagnostics; the meaning is the same either way. The `project-id` is scanned from the head of the
file, so history and dashboard routes for a project resolve without reading its rows.

## Who wrote the lock

`generated-by` names the jk version that wrote the lock. Beside it, `generated-by-build` and
`generated-by-build-time` name the build — the twelve-hex identity of the jk code archive that
wrote it and the commit time its packaging stamped into that archive's manifest (`Build-Time`) —
so two builds of one version can be told apart and ordered by the source they were built from,
and a reinstall of the same jar moves neither; a jk running from a classes directory writes
neither. The stamps are provenance, not pins: they choose no artifact and never make a lock stale.

`Build-Time` belongs to the archive's content, not to the checkout that happened to package it.
The assembly is keyed by what goes into it — the classes, the bundled jars, the main class, the
manifest table, the relocations — and the commit time is not part of that key, so a later commit
that leaves the engine's inputs untouched restores the same archive from the action cache, stamp
and all. Two commits with identical code are one build, with one identity and one time, and that
time names the commit whose source last changed the bytes; a fresher stamp on identical bytes
would give one build identity two times.

A jk **older than the writer never relocks**. When the manifests move and the build, `jk sync`
or `jk lock` would rewrite a lock a newer jk wrote — a newer version, or a later build of the same
version — the command fails naming both:

```
jk-lock.toml was written by jk 0.13.7 (build 1a2b3c4d5e6f of 2026-09-17T15:57:16Z) and this
engine is jk 0.13.7 (build 4ee07400a592 of 2026-09-16T13:12:00Z), an older build of the same
version — a relock with it would restate the lock in the older jk's format …
```

Install the jk that wrote the lock (`jk self update`, or `jk install --skip-tests` from its
checkout) and run `jk engine stop` so the next command starts it; `jk lock --force` rewrites the
lock with the running jk anyway. A newer jk always may relock, and a lock the same build wrote is
never in question.

## Related

[Dependencies](dependencies.md) · [Platforms](platforms.md) · [Repositories](repositories.md)
