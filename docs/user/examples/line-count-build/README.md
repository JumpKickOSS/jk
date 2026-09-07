# Line-count build logic

Project build logic: the `.jk/` convention directory next to `jk.toml`, holding **stem
scripts** that run at named anchors of the build. Reference doc:
[build-logic.md](../../build-logic.md).

`.jk/after-resources.groovy` runs after resources are copied. It counts lines of Java under
`src/`, writes the total to `line-count.txt` in its `outDir`, and jk merges that directory
into the module's resources — so `demo.App` reads `/line-count.txt` back off the classpath
at runtime. No script, no resource: the sample only prints a number if its own build logic
ran.

```text
line-count-build/
  jk.toml
  jk-lock.toml               # committed; every version pinned
  .jk/
    after-resources.groovy   # the only stem script here
  src/main/java/demo/App.java
  src/test/java/demo/AppTest.java
```

The script writes only to `outDir`
(`target/generated/sources/jk-logic-out-after-resources/main/`), which jk creates empty before
each run — it is the only place a stem script may write, and the only thing the action cache
captures.

## Run it

```bash
cd docs/user/examples/line-count-build
jk lock     # already committed; re-run only after editing jk.toml
jk build
jk run
jk test
```

```text
$ jk build
jk: + Build > Build successful. Built target/line-count-build-0.0.1.jar - took 1.9s

$ jk run
jk: > Run > Executing `java -jar target/line-count-build-0.0.1.jar`

Line Count: 74

$ jk test
+ Test Successful: Passed 1 test - took 10ms
```

`74` is this sample's own Java, `src/main` and `src/test` together, so the number moves when
you edit either. Nothing asserts a literal count — see below.

## What the test proves

`AppTest` counts the same tree again, in Java, and compares that with what `App` reads off
the classpath. So it fails when the script did not run (no resource at all), when the
resource is stale or wrong (numbers differ), and when the walk finds nothing to count (guard
before the comparison). It cannot pass by agreeing with itself, and it needs no update when
the sources grow.

## Caching

Stem scripts are action-cached on the module's sources, `jk.toml` and `jk-lock.toml`, so an
unchanged tree replays the previous output instead of re-running the script:

```text
$ jk build
jk: + Build > Build successful, project up to date - took 15ms
```

Test sources are part of that key, which is why editing `AppTest` re-runs the count rather
than leaving a stale number behind. `jk build -F` forces a re-run.

## Failure modes

**Build logic disabled.** Add `logic = "off"` under a `[build]` table in `jk.toml` and the
script stops running — the resource is gone, and the sample says so instead of printing a
wrong number:

```text
$ jk run
Exception in thread "main" java.lang.IllegalStateException: /line-count.txt is not on the classpath: build logic under .jk/ did not run
	at demo.App.lineCount(App.java:20)
	at demo.App.main(App.java:13)
```

`jk test` fails on the same message, which is the point of the test.

**A leftover `.jk-build/`.** The compiled-build-logic scheme that directory belonged to is
retired and has no compatibility path, so its mere existence fails the build — even with a
valid `.jk/` beside it:

```text
$ mkdir .jk-build && jk build
[Build Logic Before Compile] Failure in demo:line-count-build
 | project build logic lives in jk/ or .jk/ -- rename .jk-build/ (no compatibility path)
 +--

jk: ! Build > Failed to build demo:line-count-build - took 225ms
```

(jk repeats that diagnostic once per reporting scope; the message is the same each time.)

## Variations

- `jk/` instead of `.jk/` is the same feature, visible in a default listing. If both exist,
  `jk/` wins and the trees are not merged.
- `[build] logic = "tools/codegen"` points the convention elsewhere; `logic = "off"` disables
  it. Omit the table to use `jk/` / `.jk/`, as this sample does.
- Other anchors (`before-compile`, `after-compile`, `before-package`), `.kts` scripts, and
  the workspace-root stems (`after-build`, `guard`) work the same way; this sample stays at
  one anchor on purpose. See [build-logic.md](../../build-logic.md).
