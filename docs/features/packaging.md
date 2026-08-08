# Packaging matrix (thin / fat / shrink / Boot / Quarkus / Grails)

**Ticket:** JK-1032 ([kanartist](https://github.com/jkbuild/kanartist) project `jk`); Quarkus JK-1160/1202

JumpKick has **six** intentional packaging paths. Pick one product story; do not enable R8 by
default.

| Artifact | How | Command | R8? |
|---|---|---|---|
| **Thin jar** | default | `jk build` | no |
| **Assembly jar** | `[application] assembly = true` | `jk assemble` / `jk build` | no |
| **Shrunk jar** | `[application] assembly = "shrink"` | `jk assemble` / `jk build` | yes (opt-in) |
| **Spring Boot jar** | spring-boot plugin | `jk build` | plugin-owned |
| **Quarkus fast-jar / uber-jar** | quarkus plugin | `jk build` | plugin-owned (augment) |
| **Grails jar** (Boot layout) | grails plugin | `jk build` | plugin-owned |

## Thin jar (default)

```toml
[project]
# …
# no [application] assembly — package-jar only
```

```bash
jk build    # target/<name>-<version>.jar (or layout default)
```

## Assembly / fat jar (`*-all.jar`)

```toml
[application]
# main is optional — library fat jars need no entry point
main = "com.example.App"
assembly = true
```

```bash
jk assemble   # errors with a one-line fix if assembly is off
jk build      # same packaging graph when assembly = true
# → target/<name>-<version>-all.jar
```

### One-off CLI override (`--fat` / `--shrink`)

You can package without (or against) `jk.toml` for a single run:

```bash
jk assemble --fat                 # fat jar this run only
jk assemble --shrink              # R8 this run only
jk assemble --shrink --write-config   # R8 + surgically set assembly = "shrink" in jk.toml
jk assemble --fat --write-config      # fat + assembly = true
```

| Flag | Effect |
|---|---|
| `--fat` | Override packaging to classic fat jar (`*-all.jar`) for **this invocation** |
| `--shrink` | Override packaging to R8 shrink packager for **this invocation** |
| `--write-config` | With `--fat` or `--shrink`, surgically edit `[application].assembly` in `jk.toml` (creates the table if missing; leaves `main` and other keys alone). Never rewrites the whole file. |

One-offs print a loud note that the mode is not persisted (unless you pass `--write-config`). CLI
overrides ride the client→engine session envelope and are included in packaging action-cache keys
(`packaging:fat` / `packaging:<packager>`), so fat and shrink never cache-collide.

**Merge rules** (engine `AssemblyPackager`):

- Concatenate `META-INF/services/*`
- Concatenate `META-INF/spring.handlers`, `spring.schemas`, `spring.factories`, and Boot
  `AutoConfiguration.imports`
- Project classes win on path conflict; dependency jars earlier in the graph win among themselves

**Excludes:**

- `META-INF/*.SF` / `*.RSA` / `*.DSA` / `*.EC` / `SIG-*` (signatures)
- `module-info.class` (JPMS descriptors from deps break a single classpath jar)

Sample: [examples/assembly-app/](examples/assembly-app/).

## Shrunk jar (R8)

```toml
[application]
# main is optional (library shrink jars keep module classes without an entry point)
main = "com.example.App"
assembly = "shrink"   # R8 over classes + runtime closure → small fat jar (*-all.jar path)
```

Optional keep rules / R8 version live under `[shrink]`:

```toml
[shrink]
# keep = ["-keep class com.example.** { *; }"]
# keep-files = ["proguard-rules.pro"]
# obfuscate = false         # default
# strict-warnings = false   # default
```

### Derived keep rules

R8 removes what nothing references, and a class named only as text is referenced by nothing. jk
reads the two conventions that carry such names — `META-INF/services/<interface>` line lists, and
`META-INF/<vendor>/<interface>/<impl>` markers whose leaf path segment *is* the class name — and
keeps every class they name.

This is exact, so it beats any pattern you could write by hand: on a Micronaut app it is ~320
classes, and roughly a fifth of them match no naming convention at all (framework internals like
`InterceptorRegistryBean`, and `LogbackServiceProvider`, whose loss silences the logging that
would report the damage).

The effective rule set — jk's defaults, the derived rules, and yours — is written next to the
artifact as `<name>-keep.pro`. Read it when R8 kept something unexpected, or when writing a rule
for something it could not derive.

### Classes absent from the closure

R8 treats a class it cannot find as an error and produces nothing. On any realistic dependency
graph that stops the build immediately: Netty and Micronaut alone reference dozens of optional
integrations — brotli, zstd, epoll, io_uring, quic, bouncycastle, conscrypt, log4j bridges —
behind `Class.forName` probes.

jk resolved the closure from `jk-lock.toml`, so absence is intentional, and the missing-class
diagnostic is downgraded to a warning. The build reports the count and `-v` lists them. Set
`strict-warnings = true` to fail on them instead, for a closure that should be complete.

A bare `[shrink]` table (without `assembly = "shrink"`) also enables the packager. Prefer
`assembly = "shrink"`. Build labels size before → after.

Try without editing the file first: `jk assemble --shrink`. Persist with
`jk assemble --shrink --write-config`.

### By-name index audit

Shrinking is reachability analysis, and a class reached only *by name* is invisible to it. Two
conventions carry those names as text rather than bytecode:

| convention | shape |
|---|---|
| service files | `META-INF/services/<interface>` — one implementation FQCN per line |
| marker indexes | `META-INF/<vendor>/<interface>/<impl>` — the leaf path segment *is* the class name |

R8 removes what nothing references, and neither shape references anything. Worse, the removal is
quiet: `ServiceLoader` and framework equivalents skip an implementation they cannot load, so the
application starts with pieces missing instead of failing. Losing an SLF4J provider that way
silences the logging that would have reported it.

jk audits the shrunk jar against both index shapes and **fails the build** naming every class R8
removed, with the keep rule that retains it:

```
R8 removed 3 classes that are named by a service file or index in this jar,
so nothing can load them at runtime:
  ch.qos.logback.classic.spi.LogbackServiceProvider
  com.example.$HelloController$Definition
  io.micronaut.aop.internal.InterceptorRegistryBean

Keep them with [shrink] keep, or a keep-files rule file:
-keep class ch.qos.logback.classic.spi.LogbackServiceProvider { *; }
…
```

The audit compares the shrunk jar against the inputs, so a name the inputs never resolved — an
optional dependency nobody bundled — is not reported.

A clean audit means the by-name indexes are intact. It does not mean the application works — see
below for the two things it cannot see.

### What shrinking still cannot work out for you

Shrink is an advanced opt-in. `assembly = true` is the reliable choice; reach for `"shrink"` when
size matters enough to own the rules, and expect to iterate.

**Instantiation by name that appears in no index.** Logback reads `logback.xml` and constructs
appenders reflectively, so `ch.qos.logback.core.ConsoleAppender` is referenced by nothing R8 or
jk can read. Losing it costs you the error messages for everything else.

**Types whose generic signature is read at runtime.** In `--classfile` mode R8 runs in full mode,
where a class's generic signature survives only if the class is explicitly kept —
`-keepattributes Signature` is not enough. A framework calling `Class.getTypeParameters()` on a
type that was merely retained gets zero parameters:

```
IllegalArgumentException: Type parameter length does not match. Required: 0, Specified: 1
```

Keep the class itself to fix it:

```toml
[shrink]
keep = ["-keep class com.example.GenericThing { *; }"]
```

Both of these are what [`jk train`](dynamic-surface.md) exists to discover by observing a real
run, rather than by guessing.

Sample: [examples/shrunk-cli/](examples/shrunk-cli/).

## Spring Boot

Use the spring-boot first-party plugin — a **different** layout (`BOOT-INF/…`), not `assembly`.
Do not combine assembly with Boot packaging for the same product.

## Quarkus

Use the `[quarkus]` first-party plugin (JK-1160/1202). Packaging is **augmented** (pure
`QuarkusBootstrap` — no permanent `mvn` CLI), not `assembly` / thin jar:

| `package` | Output | Notes |
|-----------|--------|--------|
| **`fast-jar`** (default) | `quarkus-run.jar` + sibling `lib/` (and `quarkus-app/`) | Prefer for production layering |
| **`uber-jar`** | single runner jar | Opt-in via `[quarkus] package = "uber-jar"` |

```toml
[quarkus]
version = "3.38.0"
# package = "uber-jar"   # optional; default is fast-jar
```

`jk run` executes the packaged runner. Prefer a **plain** `Application.main` calling
`Quarkus.run` over `@QuarkusMain` so `@QuarkusTest` does not double-index under jk’s
`target/classes/main` layout.

**Workspace / path deps:** sibling module jars are installed into the augment model so they
appear under `lib/main` (multi-module dogfood: `jk-examples` `java/quarkus-petshop`).

**Scaffold / Giter8:** `jk new --quarkus` (plugin scaffold) or `jk new --template quarkus`
(short-name catalog). Keep `quarkus-junit5` on `[test-dependencies]` only.

Cold first-lock of the Quarkus platform still materializes a large jar set; subsequent
locks are cache-hit heavy. See [docs/perf/resolve-io.md](../perf/resolve-io.md).

## Grails

Use the `[grails]` plugin — Boot-launcher executable jar (same family as spring-boot packaging),
not `assembly`. See the user guide “Grails” section.

## Mental model

```text
thin      → package-jar
assembly  → package-assembly   (jk assemble / alias: assembly)
shrunk    → shrunk-jar packager (R8)
boot      → spring-boot packager
grails    → grails packager (Boot layout)
quarkus   → quarkus-fast-jar (augment; fast-jar default / uber-jar opt-in)
```
