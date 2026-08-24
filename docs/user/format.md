# Format

JumpKick can **format your source code**. `jk format` rewrites Java and Kotlin in place
(or checks that they are already formatted). Import hygiene is on by default.

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

**OpenRewrite** (optional extra pass, before Spotless):

- **Optimize imports** — shorten fully-qualified class names and add import statements
  (default on). Preferred style is short names + imports; keep an FQCN only when there is
  a real collision. See [what it can shorten](#what-optimize-imports-can-shorten).
- Custom recipes via `--rewrite-config` / `JK_FORMAT_REWRITE_CONFIG` (OpenRewrite YAML).

Per-file stamp cache skips unchanged files.

## Language support

| Language | Formatter | Default style | Import hygiene |
|----------|-----------|---------------|----------------|
| **Java** | Spotless (Palantir / Google / AOSP) | `palantir` | Yes (order + unused + FQCN shorten) |
| **Kotlin** | Spotless ktfmt | `kotlinlang` | Style only (ktfmt); Java import flags do not apply |
| **Groovy** | **Not formatted** | — | `jk format` does not rewrite `.groovy` |

Java unnamed classes are skipped silently by Palantir/Google formatters (they cannot format
them). Mixed Java+Kotlin modules format each file with the matching pipeline.

Line endings are **Unix (`LF`)** regardless of host OS.

## Styles

Java: `palantir` · `google` · `aosp`

Kotlin: `kotlinlang` · `google` · `meta`

Cross-language preset `--style standard` (or `[format] style = "standard"`) is
**palantir + kotlinlang** — both 4-space, 120-column, so mixed repos look consistent.

```bash
jk format --java-style google
jk format --kotlin-style meta
jk format --style standard          # both languages
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

## Import hygiene (Java)

| Toggle | What it does | Default |
|--------|----------------|---------|
| **optimize-imports** | OpenRewrite: shorten FQCNs, add imports | on |
| **import-order** | Spotless `importOrder` | on |
| **remove-unused-imports** | Spotless `removeUnusedImports` | on |

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

Shortening `com.example.Widget.of()` to `Widget.of()` means resolving `com.example.Widget`
to a type, so the pass is only as good as the classpath it resolves against. `jk format`
gives it two things:

| Source of types | Shortened? |
|-----------------|------------|
| The JDK (`java.*`, `javax.*`) | Yes — always, javac supplies these |
| Your own modules' types | Yes, **once that module has been built** |
| Dependency (jar) types | **No** — see below |

Your modules' types come from each module's class output (`target/<module>/classes/{main,test}`),
taken from `jk-lock.toml`'s module list. A module you have never compiled contributes no
types, so its callers keep their fully-qualified names until you build it — run `jk build`
before the first `jk format` of a fresh checkout. A project with no lockfile gets no
classpath at all and only JDK names shorten; `jk format` never resolves or downloads
anything itself.

**Dependency jars are deliberately left off.** OpenRewrite parses each file with its own
compiler front end, so every classpath entry is re-opened for every file. On jk's own tree
(2,011 sources) the module outputs add 18s to a 108s run and shorten 3,293 names; adding
the 150 dependency jars shortens 47 more and adds six minutes. A dependency's type written
out in full stays that way — import it yourself.

Optimize-imports also never rewrites Javadoc: `{@link com.example.Widget}` is left alone.

**Precedence:** CLI flag → env var → `[format]` → default `true`.

`--optimize-imports` / `--no-optimize-imports` (and the same pattern for the other two)
are exclusive pairs.

Supplying `--rewrite-config` also enables optimize-imports when neither the flag nor the
env var said otherwise, so the OpenRewrite plan actually runs.

```bash
jk format --rewrite-config rewrite.yml
# or: JK_FORMAT_REWRITE_CONFIG=/path/to/rewrite.yml
```

## Check mode (CI)

```bash
jk format --check
```

Exits non-zero if any file is unformatted. Does not write. Under `--check`, dirty files
are reported as findings (not as successful formats).

JumpKick formats **this** repository with `jk format`; `jk format --check` is the local
gate before a commit. There is no required CI format job unless you add one.

## Limitations

- **Groovy is not formatted.** Use an editor formatter or a `jk tool run` recipe if you
  need Groovy style checks.
- **Not a linter.** `jk format` rewrites style; it does not run Checkstyle, SpotBugs, or
  detekt. Java analysis: install Checkstyle as a tool — [Tools](tools.md) and the
  [checkstyle-recipe example](examples/checkstyle-recipe/). Kotlin analysis (detekt) is
  not a first-party plugin yet.
- Palantir/Google **skip unnamed/simple compilation units** they cannot format.
- **optimize-imports cannot shorten a name it cannot resolve** — unbuilt modules and
  dependency jars; see [what it can shorten](#what-optimize-imports-can-shorten).
- Format is engine-hosted: you need a reachable engine (`jk` starts one) and a `jk.toml`
  in the working directory.

## Related

- [Quality / lint recipes](tools.md)
- [Config](config.md) (global CLI chrome; format flags are command-local)
- [Commands](commands.md)
