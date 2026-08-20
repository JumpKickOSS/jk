# Packaging matrix (thin / fat / minified / Boot / Quarkus / Grails)

**Ticket:** JK-1032 ([kanartist](https://github.com/JumpKickOSS/kanartist) project `jk`); Quarkus JK-1160/1202

JumpKick has **six** intentional packaging paths. Pick one product story; do not enable R8 by
default.

| Artifact | How | Command | R8? |
|---|---|---|---|
| **Thin jar** | always | `jk build` | no |
| **Fat jar** `-all.jar` | `[application] assembly = true` | `jk assemble` / `jk build` | no |
| **Minified jar** `-min.jar` | `[application] minified = true` | `jk assemble` / `jk build` | yes (opt-in) |
| **Spring Boot jar** | spring-boot plugin | `jk build` | plugin-owned |
| **Quarkus fast-jar / uber-jar** | quarkus plugin | `jk build` | plugin-owned (augment) |
| **Grails jar** (Boot layout) | grails plugin | `jk build` | plugin-owned |

## Artifacts are additive

```
target/
  svc-0.1.0.jar        thin      always, no opt-out
  svc-0.1.0-all.jar    fat       assembly = true
  svc-0.1.0-min.jar    minified  minified = true, which also builds the fat jar
```

Each switch adds an artifact; none replaces another. Asking for a minified jar always produces
the fat jar beside it, so the two can be compared without a config change — which matters,
because a minified jar can be silently wrong for an application that resolves types by runtime
generic matching.

```toml
[application]
main     = "com.example.App"
assembly = true      # -all.jar
minified = true      # -min.jar (implies assembly)
```

A plugin that owns the module's artifact shape — Spring Boot, Quarkus, Grails — still owns it;
`BOOT-INF` is not a thin jar with extras. The additive rule governs jk's own packaging modes.

## Thin jar (always)

```bash
jk build    # target/<name>-<version>.jar (or layout default)
```

## Fat jar (`-all.jar`)

```toml
[application]
main = "com.example.App"
assembly = true
```

```bash
jk assemble   # errors with a one-line fix if assembly is off
jk build      # same packaging graph when assembly = true
# → target/<name>-<version>-all.jar
```

### One-off CLI override (`--fat` / `--minified`)

You can package without (or against) `jk.toml` for a single run:

```bash
jk assemble --fat                 # fat jar this run only
jk assemble --minified            # add -min.jar this run only
jk assemble --minified --write-config # -min.jar + surgically set minified = true in jk.toml
jk assemble --fat --write-config      # fat + assembly = true
```

| Flag | Effect |
|---|---|
| `--fat` | Override packaging to classic fat jar (`*-all.jar`) for **this invocation** |
| `--minified` | Also build the R8 `-min.jar` for **this invocation** |
| `--write-config` | With `--fat` or `--minified`, surgically edit `[application].assembly` / `.minified` in `jk.toml` (requires an existing `[application].main`; leaves `main` and other keys alone). Never rewrites the whole file. |

One-offs print a loud note that the mode is not persisted (unless you pass `--write-config`). CLI
overrides ride the client→engine session envelope and are included in packaging action-cache keys
(`packaging:fat` / `packaging:<packager>`), so fat and minified never cache-collide.

**Merge rules** (engine `AssemblyPackager`):

- Concatenate `META-INF/services/*`
- Concatenate `META-INF/spring.handlers`, `spring.schemas`, `spring.factories`, and Boot
  `AutoConfiguration.imports`
- Project classes win on path conflict; dependency jars earlier in the graph win among themselves

**Excludes:**

- `META-INF/*.SF` / `*.RSA` / `*.DSA` / `*.EC` / `SIG-*` (signatures)
- `module-info.class` (JPMS descriptors from deps break a single classpath jar)

Sample: [examples/assembly-app/](examples/assembly-app/).

## Minified jar (`-min.jar`, R8)

```toml
[application]
# main is optional (library minified jars keep module classes without an entry point)
main = "com.example.App"
minified = true   # R8 over classes + runtime closure → target/<name>-<version>-min.jar
```

Optional keep rules / R8 version live under `[minified]`:

```toml
[minified]
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

jk also composes the GraalVM metadata libraries publish under `META-INF/native-image`.
`native-image` finds that on the classpath unaided; R8 has no equivalent and would ignore it, so
the reflective types, members, proxies and resources it declares are translated into keep rules.
On a Micronaut application that is another ~220 entries, free — the data is already in the jars
and no application run is involved.

The effective rule set — jk's defaults, the derived rules, the composed library metadata, and
yours — is written next to the artifact as `<name>-keep.pro`. Read it when R8 kept something
unexpected, or when writing a rule for something it could not derive.

### Classes absent from the closure

R8 treats a class it cannot find as an error and produces nothing. On any realistic dependency
graph that stops the build immediately: Netty and Micronaut alone reference dozens of optional
integrations — brotli, zstd, epoll, io_uring, quic, bouncycastle, conscrypt, log4j bridges —
behind `Class.forName` probes.

jk resolved the closure from `jk-lock.toml`, so absence is intentional, and the missing-class
diagnostic is downgraded to a warning. The build reports the count and `-v` lists them. Set
`strict-warnings = true` to fail on them instead, for a closure that should be complete.

Build labels size before → after.

Try without editing the file first: `jk assemble --minified`. Persist with
`jk assemble --minified --write-config`.

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

Keep them with [minified] keep, or a keep-files rule file:
-keep class ch.qos.logback.classic.spi.LogbackServiceProvider { *; }
…
```

The audit compares the shrunk jar against the inputs, so a name the inputs never resolved — an
optional dependency nobody bundled — is not reported.

A clean audit means the by-name indexes are intact. It does not mean the application works — see
below for the two things it cannot see.

### What shrinking still cannot work out for you

Minification is an advanced opt-in. `assembly = true` is the reliable choice; reach for it when
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
[minified]
keep = ["-keep class com.example.GenericThing { *; }"]
```

By-name discovery (the Logback case) is what [`jk train`](dynamic-surface.md) can discover by
observing a real run. Generic-signature resolution is not — the tracing agent cannot record
`getTypeParameters()` (a documented recorder gap), so those classes need hand-written keeps.

Sample: [examples/minified-cli/](examples/minified-cli/).

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

**Giter8:** `jk new -t quarkus`
(short-name catalog). Keep `quarkus-junit5` on `[test-dependencies]` only.

Cold first-lock of the Quarkus platform still materializes a large jar set; subsequent
locks are cache-hit heavy. See [docs/perf/resolve-io.md](../perf/resolve-io.md).

## Grails

Use the `[grails]` plugin — Boot-launcher executable jar (same family as spring-boot packaging),
not `assembly`. See the user guide “Grails” section.

## Mental model

```text
thin      → package-jar
assembly  → package-assembly    (jk assemble / alias: assembly)
minified  → minified-jar packager (R8)
boot      → spring-boot packager
grails    → grails packager (Boot layout)
quarkus   → quarkus-fast-jar (augment; fast-jar default / uber-jar opt-in)
```
