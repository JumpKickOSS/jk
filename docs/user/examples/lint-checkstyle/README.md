# Lint with Checkstyle

Checkstyle runs as a **cached build step**: `[lint] checkstyle` names the rule set, the step runs
after compile over `src/main/java`, and every finding is a diagnostic — file, line, message and
the rule id — in `target/jk-results.md`, on the terminal, and in the MCP `diagnostics` an
agent reads. A clean module is a cache hit on the next build; a source or rule-set edit re-runs
the step and nothing else does.

```text
lint-checkstyle/
  jk.toml                                # [lint] checkstyle = "checkstyle.xml"
  jk-lock.toml                           # committed; pins the project's deps and the lint worker
  checkstyle.xml                         # the rule set — only rules jk format cannot apply
  src/main/java/demo/Sample.java         # the audited code
  src/main/java/demo/package-info.java   # @NullMarked, hence the jspecify provided-dep
  src/test/java/demo/SampleTest.java     # asserts the classifier the README prints
```

```toml
[lint]
checkstyle = "checkstyle.xml"
```

Checkstyle itself is not in `[dependencies]`: the lint plugin fetches
`com.puppycrawl.tools:checkstyle` at its pinned release (`checkstyle-version`, `14.1.0` unless
you say otherwise) into the content-addressed store, hashes it into the step's key, and forks it
on the build JDK. The rule set is a project file, so it is part of the key too.

## Build

```console
$ jk build
jk: + Build > Build successful. Built target/lint-checkstyle-0.0.1.jar
```

`checkstyle.xml` turns on five rules, each of which `Sample.java` visibly satisfies:

| Rule | What it costs the code |
|------|------------------------|
| `MagicNumber` | `200`, `400`, `429`, `500` are named constants, each with a Javadoc saying what it means |
| `MissingJavadocMethod` | every public method has a Javadoc comment |
| `NeedBraces` | every `if` has a block, even one-liners |
| `FinalClass` | `Sample` is `final` |
| `HideUtilityClassConstructor` | `Sample` has a `private Sample() {}` |

A second `jk build` reports the `lint-checkstyle` step as cached — `jk explain` shows why: the
sources, the rule set, the classes and the table are unchanged.

## A finding

Write `classify` the quick way:

```java
public static Outcome classify(int status) {
    if (status < 400) return Outcome.OK;
    if (status == 429 || status >= 500) return Outcome.RETRY;
    return Outcome.FAIL;
}
```

javac compiles it and the tests still pass; the lint step does not:

```text
✘ lint-checkstyle
  src/main/java/demo/Sample.java:41:9: 'if' construct must use '{}'s. [NeedBraces]
  src/main/java/demo/Sample.java:41:22: '400' is a magic number. [MagicNumber]
  …
  checkstyle: 6 findings at or above `fail-on = "error"` (6 in all)
```

The rule set says `severity = "error"`, so each finding is an error and the step fails the
build. `fail-on = "warning"` would fail on warnings too; `fail-on = "never"` reports and passes.
Put the constants and braces back and the step is green again — and cached.

## Why lint when `jk format` exists?

Different jobs. [`jk format`](../../format.md) rewrites **layout** and deletes unused imports;
it has no opinion about naming a constant, writing a Javadoc, or bracing a branch. So
`checkstyle.xml` deliberately turns on **no** rule the formatter already handles — `UnusedImports`
is absent for exactly that reason.

## Related

- [Lint](../../lint.md) — the `[lint]` table: Checkstyle, PMD, SpotBugs, detekt
- [Format](../../format.md)
