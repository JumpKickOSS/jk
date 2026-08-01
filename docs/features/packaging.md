# Packaging matrix (thin / fat / shrink / Boot / Quarkus / Grails)

**Ticket:** JK-1032 ([kanartist](https://github.com/jkbuild/kanartist) project `jk`); Quarkus JK-1160/1202

JumpKick has **six** intentional packaging paths. Pick one product story; do not enable R8 by
default.

| Artifact | How | Command | R8? |
|---|---|---|---|
| **Thin jar** | default | `jk build` | no |
| **Assembly jar** | `[application] assembly = true` | `jk assembly` / `jk assemble` / `jk build` | no |
| **Shrunk jar** | `[application] assembly = "shrink"` | `jk assembly` / `jk build` | yes (opt-in) |
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
jk assembly   # alias: jk assemble — errors with a one-line fix if assembly is off
jk build      # same packaging graph when assembly = true
# → target/<name>-<version>-all.jar
```

### One-off CLI override (`--fat` / `--shrink`)

You can package without (or against) `jk.toml` for a single run:

```bash
jk assembly --fat                 # fat jar this run only
jk assembly --shrink              # R8 this run only
jk assembly --shrink --write-config   # R8 + surgically set assembly = "shrink" in jk.toml
jk assembly --fat --write-config      # fat + assembly = true
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

Optional keep rules / R8 version still live under `[shrink]` when you need them:

```toml
[shrink]
# keep = ["-keep class com.example.** { *; }"]
# keep-files = ["proguard-rules.pro"]
# obfuscate = false   # default
```

A bare `[shrink]` table (without `assembly = "shrink"`) still enables the packager for
backward compatibility. Prefer `assembly = "shrink"`. Build labels size before → after.

Try without editing the file first: `jk assembly --shrink`. Persist with
`jk assembly --shrink --write-config`.

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
assembly  → package-assembly   (jk assembly / jk assemble)
shrunk    → shrunk-jar packager (R8)
boot      → spring-boot packager
grails    → grails packager (Boot layout)
quarkus   → quarkus-fast-jar (augment; fast-jar default / uber-jar opt-in)
```
