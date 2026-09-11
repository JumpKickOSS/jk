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

## Slow files and the per-file timeout

A code formatter chooses line breaks by searching the expression tree, and that search grows
steeply with **nesting depth** rather than with file size. A few hundred lines with ten levels of
nested lambdas can hold a thread far longer than a 60 KB class does.

`jk format` bounds every file:

| Threshold | Default | What happens |
|-----------|---------|--------------|
| **Named** | 500 ms | The file is printed as `slow`, with its elapsed time, *while it is still being formatted*. Repeats on a doubling interval, so a long stall is named a handful of times rather than hundreds. |
| **Timed out** | 3 s | jk gives up on that file. It is reported as an `error` naming the path, the rest of the run keeps going, and `jk format` exits non-zero. |

Three seconds is a wide margin, not a tight one: across a 3,000-file Java and Kotlin tree the
slowest single file is around 120 ms, and the 60 KB classes are nearer 110 ms. A file that has been
running for three seconds is not a big file — it is a search that has stopped tracking the size of
the source. The margin is deliberately generous for hosts that are nothing like that one.

A timed-out file is **not** formatted and **not** recorded as clean, so `jk format` keeps
reporting it and keeps exiting non-zero until you deal with it. That holds even when the
formatter finishes late: a file's bytes are only written once the run has confirmed the file did
not time out, so nothing that gave up on a file can leave a half-run's output in your tree. When
the source explains the stall, the error says so:

```
  error  src/test/java/example/ProviderTest.java: timed out after 3.0s (limit 3000 ms);
         deepest expression nesting here is 11 parentheses at line 214, 6 nested lambdas —
         a line-break search grows steeply with nesting, so splitting that expression into
         named locals or helper methods is usually the fix
```

For those files the timeout is paid **once**: jk remembers them by content, so later runs report
them straight away instead of spending the limit again.

```
  error  src/test/java/example/ProviderTest.java: timed out at a 3000 ms limit on an earlier
         run and has not changed since, so it was not retried; deepest expression nesting …
```

Only files whose nesting accounts for the stall are remembered. A file of ordinary shape that
somehow blew the limit is far more likely a busy host — or a genuinely enormous source — than
something that cannot be formatted, so it is reported and then **retried in full** next time; a
wrong memo would refuse a good file on every later run. Those say so, and name the knob:

```
  error  build/generated/demo/Huge.java: timed out after 3.0s (limit 3000 ms); nothing about
         this file's shape explains that, so it was not remembered — raise
         jk.format.file-timeout-ms if the file is simply very large, or this host slow
```

For scale: a 4.7 MB, 200,000-line flat Java source takes about 9.5 s to format. Machine-generated
sources that big are the main reason to raise the limit.

Editing a remembered file — any change at all — or raising the limit makes the next run attempt it
for real. So does a change to the formatting configuration, which re-keys every cache jk keeps.

A thread that a timed-out file keeps busy is replaced, so the rest of the run keeps its
parallelism — up to one replacement per thread the run started with. Past that, the files not yet
started are reported as errors straight away rather than waited on:

```
  error  src/main/java/example/Later.java: not formatted: every formatter thread the run could
         spare was left wedged by a file that timed out
```

Deal with the timed-out files (or raise the limit) and run again; nothing about those files is
remembered, since they were never attempted.

Both thresholds are formatter-worker JVM properties, in milliseconds; `0` turns either off. Raise
the timeout if a genuinely slow host starts reporting files you know are fine — the error names
the path, so you can tell that case from a real one. Note that `[jvm] args` reaches every worker jk
forks, not only the formatter.

```toml
# jk.toml
[jvm]
args = ["-Djk.format.file-timeout-ms=15000", "-Djk.format.file-warn-ms=2000"]
```

```bash
JK_JVM_ARGS=-Djk.format.file-timeout-ms=0 jk format   # no bound at all
```

## Limitations

- **Gradle Groovy DSL is not formatted.** `*.gradle` / `*.gradle.kts` stay out of `jk format`.
- **A file the formatter cannot finish is dropped, not waited on**, and stays reported until you
  change it — see [the per-file timeout](#slow-files-and-the-per-file-timeout).
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
