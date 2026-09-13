# Packaging

JumpKick writes **additive** artifacts. The thin jar is always produced. Extra flags add jars
beside it; they do not replace it. Framework plugins that own the product shape (Spring Boot,
Quarkus, Grails) still own it — a Boot jar is not “thin plus extras.”

R8 minification is **opt-in**, never the default.

| Artifact | Config | Command |
|----------|--------|---------|
| Thin jar `target/<name>-<version>.jar` | always | `jk build` |
| Fat jar `*-all.jar` | `[application] assembly = true` | `jk assemble` / `jk build` |
| Minified jar `*-min.jar` | `[application] minified = true` | `jk assemble` / `jk build` (also builds the fat jar) |
| Spring Boot jar | spring-boot plugin | `jk build` |
| Quarkus fast-jar / uber-jar | `[quarkus]` | `jk build` |
| Grails jar (Boot layout) | grails plugin | `jk build` |

```toml
[application]
main     = "com.example.App"   # required for run / fat / typical apps
assembly = true
# minified = true
# config   = "config/install.toml"  # optional template → ~/.jk/config/<bin>/config.toml
```

On `jk install`, fat/minified apps land under `~/.jk/lib/<bin>/` and write
`~/.jk/config/<bin>/config.toml`. If `config` is set, that module-relative template is
rendered with `${version}`, `${jar}`, `${name}`, and any `jk-config.*` system properties.
Without `config`, those same `jk-config.*` properties (plus `jar` / `version` / `name`) are
written directly.

Samples: [assembly-app](examples/assembly-app/), [minified-cli](examples/minified-cli/).
Frameworks: [Frameworks](frameworks.md). Native / OCI: [Native](native.md), [Images](images.md).

## One-off CLI override

```bash
jk assemble --fat
jk assemble --minified
jk assemble --minified --write-config    # also set minified = true in jk.toml
jk assemble --fat --write-config
```

`--write-config` surgically edits `[application].assembly` / `.minified` (requires an
existing `[application].main`). One-offs print a loud note that the mode is not persisted.
Fat and minified never share an action-cache key.

`jk assemble` without flags errors with a one-line fix if assembly is off.

## Fat jar merge rules

- Concatenate `META-INF/services/*`
- Concatenate `META-INF/spring.handlers`, `spring.schemas`, `spring.factories`, and Boot
  `AutoConfiguration.imports`
- Project classes win on path conflict; dependency jars earlier in the graph win among themselves

**Dropped:** signature files (`META-INF/*.SF` / `*.RSA` / `*.DSA` / `*.EC` / `SIG-*`) and
dependency `module-info.class` (JPMS descriptors break a single classpath jar).

## Fat jar size against Shadow and Shade

`assembly = true` replaces Gradle Shadow and Maven Shade, so its output is measured against both.
The bench (`JarSizeBenchTest`, tier `bench`; fixtures in [`bench/jar-size/`](../../bench/jar-size/README.md))
packages four fixture apps with the installed `jk`, with Shadow and with Shade over the same pinned
dependencies and attributes every byte of difference. Banked sizes live in
[`jar-size-baseline.toml`](../../jar-size-baseline.toml); a jk jar more than 0.5 % above its line,
or more than 1 % above Shadow's, fails the bench.

<!-- jar-size-table:start -->
Measured 2026-09-11 with jk 0.13.2, Gradle 9.7.0 + Shadow 9.6.1, Maven 3.9.16 + Shade 3.6.2,
Spring Boot 4.1.1, Kotlin 2.4.20, Micronaut platform 5.1.5, on Temurin JDK 25.0.4.1. Every jar is
DEFLATE-only except the Boot layout, whose nested jars are STORED by design.

| Fixture | Tool | jar bytes | entries (files + dirs) | classes | compressed payload | extra-field bytes | only in jk / only in tool | jk − tool |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| plain-cli | jk assembly | 6,932,980 | 4,216 (4,053 + 163) | 4,011 | 6,064,104 | 8 | | |
| | Gradle Shadow | 6,931,707 | 4,214 (4,052 + 162) | 4,011 | 6,063,119 | 0 | 2 / 0 | +1,273 (+0.018 %) |
| | Maven Shade | 6,933,436 | 4,220 (4,055 + 165) | 4,012 | 6,063,856 | 8 | 2 / 6 | −456 (−0.007 %) |
| kotlin-cli | jk assembly | 7,737,235 | 4,663 (4,496 + 167) | 4,449 | 6,714,805 | 8 | | |
| | Gradle Shadow | 7,734,515 | 4,657 (4,491 + 166) | 4,446 | 6,713,077 | 0 | 7 / 1 | +2,720 (+0.035 %) |
| | Maven Shade | 7,725,279 | 4,642 (4,471 + 171) | 4,422 | 6,706,953 | 8 | 31 / 10 | +11,956 (+0.155 %) |
| micronaut-http | jk assembly | 14,470,688 | 9,169 (8,589 + 580) | 8,133 | 12,540,570 | 8 | | |
| | Gradle Shadow | 14,469,639 | 9,169 (8,590 + 579) | 8,135 | 12,539,407 | 0 | 2 / 2 | +1,049 (+0.007 %) |
| | Maven Shade | 14,472,299 | 9,175 (8,594 + 581) | 8,137 | 12,541,009 | 8 | 2 / 8 | −1,611 (−0.011 %) |
| spring-boot-web | jk assembly | 19,542,089 | 11,768 (11,016 + 752) | 10,746 | 16,981,283 | 8 | | |
| | Gradle Shadow | 19,540,024 | 11,766 (11,015 + 751) | 10,746 | 16,979,506 | 0 | 2 / 0 | +2,065 (+0.011 %) |
| | Maven Shade | 19,546,121 | 11,771 (11,018 + 753) | 10,747 | 16,984,707 | 8 | 2 / 5 | −4,032 (−0.021 %) |
| spring-boot-web (Boot layout) | jk Boot jar | 19,935,456 | 169 (146 + 23) | 101 | 19,902,130 | 8 | | |
| | Gradle `bootJar` | 19,902,721 | 163 (140 + 23) | 101 | 19,870,495 | 0 | 10 / 4 | +32,735 (+0.164 %) |
| | Maven `repackage` | 19,904,803 | 168 (142 + 26) | 101 | 19,871,429 | 368 | 10 / 9 | +30,653 (+0.154 %) |

jk's flat jar is within 0.04 % of Shadow's on every fixture and smaller than Shade's on three of four;
the Kotlin exception is Maven's nearest-wins keeping `org.jetbrains:annotations` at 13.0.
<!-- jar-size-table:end -->

**Where the remaining bytes are.** On every fixture the compressed bytes of byte-identical entries
are equal across all three tools, so there is no compression gap left. What differs:

- **The SBOM** (`META-INF/sbom/application.cdx.json`, 0.6–2.9 KB plus its directory entry) is jk-only
  content, and is the whole of jk's lead over Shadow once the rest nets out.
- **The manifest** carries two extra `Sbom-*` attributes (about 40 bytes).
- **Merged `META-INF` files** (`services/*`, Spring's `spring.factories`, `spring.handlers`,
  `spring.schemas`, `AutoConfiguration.imports`) differ by a few bytes of separator and order.
- **Project classes** are smaller under jk: javac's default debug attributes (`-g:source,lines`)
  against the `-g` Gradle and Maven pass; a compiler setting, not packaging.
- **First-wins picks** (`META-INF/LICENSE`, `NOTICE`, `io.netty.versions.properties`) come from a
  different jar depending on each tool's traversal order, a few hundred bytes either way.
- **Resolution divergence** shows up where the tools disagree about a transitive version:
  under the Micronaut platform jk keeps `jackson-annotations 2.21` from the declaring POM where the
  BOM manages `2.22`, and on the Kotlin fixture jk resolves `org.jetbrains:annotations 26.1.0` where
  Gradle takes 23.0.0 and Maven 13.0. Neither is a packaging cost; both are reported as their own
  line so they cannot hide inside "overhead".
- **The Boot jar** nests what `bootJar` nests: a dependency whose manifest `Spring-Boot-Jar-Type` is
  `dependencies-starter`, `annotation-processor` or `development-tool` (the `spring-boot-starter-*`
  POM-with-a-manifest jars, the configuration processor, devtools) is left out of `BOOT-INF/lib` and
  both index files, because Boot's own plugins leave it out and it has no classes to load. The
  exploded loader keeps its `META-INF/services/java.nio.file.spi.FileSystemProvider` registration,
  which the `nested:` filesystem behind `-Djarmode=tools extract` needs. What remains above `bootJar`
  is the 3 KB SBOM relocated under `BOOT-INF/classes`.
- **Headers.** Every tool writes the same local header, central record and 16-byte data descriptor
  per entry. jk's only extra field is the 8-byte JAR-magic marker on the first entry, which Shade
  also writes and Shadow does not. Entries are all DEFLATE; there is no STORED waste.

**Deflate level.** jk, Shadow and Shade all deflate at zlib's default level 6 (`Deflater.DEFAULT_COMPRESSION`
in jk's `DeterministicZip`, in Ant's `ZipOutputStream` that Shadow writes through, and in Shade's
`JarOutputStream`). Re-deflating the fixture jars at level 9 saves 0.18–0.24 % of the jar (16 KB of 6.9 MB,
34 KB of 14.5 MB, 44 KB of 19.5 MB) for 17–58 % more deflate CPU on the packaging step of every
build. jk stays at level 6: byte parity with both tools on identical entries is worth more than a
quarter of a percent, and anyone who needs a smaller jar has `minified = true`.

**Metadata and licences.** Shadow and Shade carry every dependency's `META-INF/maven/**`
(`pom.xml` and `pom.properties`) into the fat jar; jk drops it. It describes how one library was
built, names a coordinate the assembly is not, is read by nothing at runtime, and it is up to half
a percent of the jar (24–63 entries, 41–81 KB across the fixtures). `META-INF/LICENSE*`,
`META-INF/NOTICE*` and `META-INF/licenses/**` stay: they are a redistribution obligation for most
of the bundled libraries. The bench prints the size of both groups per fixture.

## Minified jar (R8)

```toml
[application]
minified = true

[minified]
# keep = ["-keep class com.example.** { *; }"]
# keep-files = ["proguard-rules.pro"]
# obfuscate = false         # default
# strict-warnings = false   # default
```

JumpKick derives keep rules from by-name indexes (`META-INF/services/*` and
`META-INF/<vendor>/<interface>/<impl>` markers) and composes GraalVM metadata published
under `META-INF/native-image`. The effective rule set is written next to the artifact as
`<name>-keep.pro`.

Missing optional classes (Netty/Micronaut `Class.forName` probes) are **warnings** by
default (`strict-warnings = true` to fail). After shrink, an **audit** fails the build if
R8 removed a class still named by a service file or index in the jar. A second audit
**warns** (never fails, and outside `strict-warnings`) when classes lost or raw-ified
their generic `Signature` between the inputs and the `-min.jar` — with a count, examples,
and, past a small share, the honest advice that the app is not a minify candidate
(`assembly = true`). See [what training cannot fix](dynamic-surface.md#what-training-cannot-fix).

**Limitations (why this stays opt-in):**

- Instantiation by name that appears in **no** index (Logback `logback.xml` appenders).
- Types whose **generic signature** is read at runtime. In R8 `--classfile` full mode, a
  class keeps its generic signature only if the class is explicitly kept. Apps that resolve
  types by runtime generic matching **cannot be shrunk** at any keep setting.
- A minified jar can be silently wrong. The fat jar is built beside it so you can compare.

Training (`jk train`) is **not** part of `jk build`. See [Dynamic surface](dynamic-surface.md).

## `jk install` (project)

Writes the thin jar and POM to the local repo (`repos/jk-local`), then prefers a native
binary in `~/.jk/bin` if one exists, else a minified/fat jar under `~/.jk/lib/<name>/`
plus a `java -jar` script, else a thin `java -cp` script over the repo jars.

Plugin workers (`jk-plugin.toml`) are those same repo jars — the engine rebuilds their
runtime classpath from the installed POM. Outside a project, pass a coordinate (`jkx` mode).

## Related

[Build](build.md) · [Dynamic surface](dynamic-surface.md) · [Publish](publish.md)
