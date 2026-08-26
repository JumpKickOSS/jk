# Format

JumpKick can **format your source code**. `jk format` rewrites Java, Kotlin, Groovy, and Scala
in place (or checks that they are already formatted). Import hygiene is on by default.

```bash
jk format                 # rewrite dirty files
jk format --check         # exit non-zero if anything would change (CI gate)
```

This page is the full reference. High-level: [Manual](manual.md) · [Getting started](getting-started.md).

## What it does

The first-party **formatter** plugin runs in a forked worker (not in the engine JVM).

**Java pipeline** (Spotless), in order:

1. **Import order** (optional, default on)
2. **Remove unused imports** (optional, default on)
3. **Style** — Palantir Java Format (default), Google Java Format, or AOSP

**Kotlin** uses **ktfmt** (Spotless). Default style is `kotlinlang`.

**Groovy** uses Spotless `removeSemicolons` (Groovy accepts both; this matches typical Groovy
source). Gradle Groovy DSL (`*.gradle`) is not formatted.

**Scala** uses **scalafmt** (Spotless), pinned by JumpKick, with Palantir-aligned defaults
(4-space indent, 120 columns, Scala 3 dialect).

**Optimize imports** (optional extra pass, before Spotless, default on): shorten
fully-qualified class names and add import statements. Preferred style is short names +
imports; keep an FQCN only when there is a real collision. See
[what it can shorten](#what-optimize-imports-can-shorten). Applies to every language `jk
format` walks.

Per-file stamp cache skips unchanged files.

## Language support

| Language | Formatter | Default style | Import hygiene |
|----------|-----------|---------------|----------------|
| **Java** | Spotless (Palantir / Google / AOSP) | `palantir` | Yes (order + unused + FQCN shorten) |
| **Kotlin** | Spotless ktfmt | `kotlinlang` | FQCN shorten; ktfmt style (Java import-order / unused flags do not apply) |
| **Groovy** | Spotless removeSemicolons | — | FQCN shorten |
| **Scala** | Spotless scalafmt | 4-space / 120-col | FQCN shorten |

Java unnamed classes are skipped silently by Palantir/Google formatters (they cannot format
them). Mixed-language modules format each file with the matching pipeline.

Line endings are **Unix (`LF`)** regardless of host OS.

## Styles

Java: `palantir` · `google` · `aosp`

Kotlin: `kotlinlang` · `google` · `meta`

Cross-language preset `--style standard` (or `[format] style = "standard"`) is
**palantir + kotlinlang** — both 4-space, 120-column, so mixed repos look consistent.

```bash
jk format --java-style google
jk format --kotlin-style meta
jk format --style standard          # Java + Kotlin styles
```

```toml
# jk.toml
[format]
style = "standard"          # optional alias → palantir + kotlinlang
# java = "google"           # per-language override
# kotlin = "meta"
```

Unknown style names fail with the allowed list.

### Precedence (styles)

Per language: **CLI `--java-style` / `--kotlin-style` → CLI `--style` alias →
`[format] java` / `kotlin` → `[format] style` alias → built-in default.**

## Import hygiene

| Toggle | What it does | Default | Languages |
|--------|----------------|---------|-----------|
| **optimize-imports** | Shorten FQCNs and add imports | on | Java, Kotlin, Groovy, Scala |
| **import-order** | Spotless `importOrder` | on | Java |
| **remove-unused-imports** | Spotless `removeUnusedImports` | on | Java |

```bash
jk format --no-optimize-imports
jk format --no-import-order
jk format --no-remove-unused-imports
```

```toml
[format]
optimize-imports = true
import-order = true
remove-unused-imports = true
```

Environment: `JK_FORMAT_OPTIMIZE_IMPORTS`, `JK_FORMAT_IMPORT_ORDER`,
`JK_FORMAT_REMOVE_UNUSED_IMPORTS` (booleans).

### What optimize-imports can shorten

Shortening `com.example.Widget.of()` to `Widget.of()` uses a **type index**, not a compiler:
project sources (package + type declarations in `.java` / `.kt` / `.groovy` / `.scala`) plus
public JDK classes from `jrt:/`. No build is required first.

| Source of types | Shortened? |
|-----------------|------------|
| The JDK (`java.*`, `javax.*`) | Yes |
| Your own modules' types | Yes — from source, even unbuilt |
| Dependency (jar) types | **No** — import those by hand |

A simple name that would collide with another type in the file (or an existing import)
stays fully qualified. Comments and string literals are never rewritten, including
`{@link com.example.Widget}`.

**Precedence:** CLI flag → env var → `[format]` → default `true`.

`--optimize-imports` / `--no-optimize-imports` (and the same pattern for the other two)
are exclusive pairs.

## Check mode (CI)

```bash
jk format --check
```

Exits non-zero if any file is unformatted. Does not write. Under `--check`, dirty files
are reported as findings (not as successful formats).

JumpKick formats **this** repository with `jk format`; `jk format --check` is the local
gate before a commit. There is no required CI format job unless you add one.

## Limitations

- **Gradle Groovy DSL is not formatted.** `*.gradle` / `*.gradle.kts` stay out of `jk format`.
- **Not a linter.** `jk format` rewrites style; it does not run Checkstyle, SpotBugs, or
  detekt. Java analysis: install Checkstyle as a tool — [Tools](tools.md) and the
  [checkstyle-recipe example](examples/checkstyle-recipe/). Kotlin analysis (detekt) is
  not a first-party plugin yet.
- Palantir/Google **skip unnamed/simple compilation units** they cannot format.
- **optimize-imports cannot shorten a name that is not in the type index** — dependency
  jars, and collisions; see [what it can shorten](#what-optimize-imports-can-shorten).
- Format is engine-hosted: you need a reachable engine (`jk` starts one) and a `jk.toml`
  in the working directory.

## Related

- [Quality / lint recipes](tools.md)
- [Config](config.md) (global CLI chrome; format flags are command-local)
- [Commands](commands.md)
