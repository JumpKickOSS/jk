# Build

```bash
jk compile              # type-check
jk build                # compile + package + default (unit) tests
jk build --guard         # guards + unit + integration green; the same flag on assemble / image / native / install
jk build --guard --skip-tests   # package graph + guard scripts, no JUnit
jk build --scripts-only        # guard scripts, no JUnit (same as test --scripts-only)
jk build --skip-tests
jk build --redo         # ignore action cache (full rebuild)
jk clean                # delete target/; unchanged inputs restore from cache
jk clean --force        # also invalidate this project's action-cache entries
```

`jk build` uses [`jk-lock.toml`](lockfile.md) and does **not** re-resolve. Packaging
shapes: [Packaging](packaging.md). Tests that run as part of build are the **default
(unit) suite** unless you pass `--suite` / `--all` — [Test](test.md).

In a [workspace](workspaces.md), `jk compile` type-checks every module, or just the
`-m` cone. Compile-only stops at classes; a module that another module in the same run
compiles against is packaged instead, because the edge between them is its jar.

When something fails, run `jk results` — [Troubleshooting](troubleshooting.md).

## What gets skipped

JumpKick hashes inputs (sources, classpath, options, tool versions) into an **action
cache**. Matching keys **restore** outputs instead of recomputing. `jk explain` forecasts
hits and misses before you run — [Explain](explain.md).

`jk clean` deletes project `target/` outputs. The next build restores jars/classes/binaries
from the action cache when inputs are unchanged (discovery + I/O only). Input fingerprints
also live under `~/.jk/cache/projects/…` so clean does not force a full rebuild forecast.

## javac plugins

A javac **plugin** (Error Prone, NullAway, Checker Framework, Manifold) is a jar on the
processor path that javac invokes by name with `-Xplugin:<Name> <options…>`. Declare the jar in
`[processor-dependencies]` and name the plugin in `[javac]`:

```toml
java = 25                      # the release; [javac] is the compiler table

[processor-dependencies]
error_prone_core = { group = "com.google.errorprone", name = "error_prone_core", version = "latest" }
nullaway         = { group = "com.uber.nullaway", name = "nullaway", version = "latest" }

[javac]
plugins = { ErrorProne = { options = ["-Xep:NullAway:ERROR", "-XepOpt:NullAway:AnnotatedPackages=com.example"] } }
args    = ["-XDcompilePolicy=simple", "--should-stop=ifError=FLOW"]
```

| Key | Meaning |
|-----|---------|
| `plugins.<Name>` | The plugin's registered javac name, case-sensitive (`ErrorProne`, not `errorprone`); passed as `-Xplugin:<Name>` |
| `plugins.<Name>.options` | Handed to the plugin after its name |
| `args` | Verbatim javac arguments, appended after every plugin |

The table is named `javac`, not `java`: `java = 25` is the release, and a TOML key cannot be
both a value and a table.

`compile-main` and `compile-test` run the same plugins, and every plugin and option is part
of the compile action key — bumping a severity recompiles. `jk explain --verbose` names the
plugins a compile step invokes. Unknown keys under `[javac]` fail the parse. Lint
(`[build] lint`), plugin-contributed and profile `javac` args come first in the argv, then the
plugins, then `args`.

**Error Prone's companions.** Error Prone documents two javac flags it needs beside the plugin,
and jk does not add them silently: `-XDcompilePolicy=simple` (the default by-todo policy is not
supported) and `--should-stop=ifError=FLOW` (so its checks still run after an ordinary compile
error). Put both in `args`, as above. NullAway also needs `-XepOpt:NullAway:AnnotatedPackages=…`
or it refuses to run. The worker JVM already opens `jdk.compiler`'s internals, which Error Prone
requires on JDK 16+; nothing to configure.

A planted dereference then fails the build with NullAway's diagnostic in `target/jk-results.md`:

```
Widget.java:7: error: [NullAway] dereferenced expression 'label' is @Nullable
```

Kotlin compiler plugins are `[[kotlin-plugins]]`, a separate table.

## Parallelism (`-j`)

Module graph concurrency:

| Value | Meaning |
|-------|---------|
| omit / `0` | All **effective** cores (default) |
| `1` | Serial (`-j1`) |
| `N` | Cap at N concurrent modules |

**Effective cores:** cgroup CPU quota when readable (Docker/k8s), else
`Runtime.availableProcessors()`. So `jobs = 0` on a 2-CPU container uses 2, not the
host’s 64.

| Layer | Setting |
|-------|---------|
| CLI | `-j` / `--jobs` (wins) |
| Env | `JK_JOBS` |
| Machine TOML | `~/.jk/config.toml` → `[engine] jobs = N` |

There is no `--parallel` / `--no-parallel`; use `-j` / `-j1`. Test workers (`-w`) are
separate — [Test](test.md#within-module-workers--w).

Free RAM may still reduce live worker JVMs.

## Module filters

```bash
jk build -m api,worker
jk build --affected-since=origin/main
```

[Workspaces](workspaces.md#select-modules).

## Exclusive builds

A second `jk build` (same checkout + kind) while one is already running is **rejected**:
**Build #N already running**. `jk jobs` lists it; `jk cancel` stops it. Worktrees are
different slots. HTTP/MCP get the same rule (409).

## JVM startup cache (app AOT / CDS)

```bash
jk build --aot-cache       # target/aot-cache/ — trained cache + run.sh
./target/aot-cache/run.sh
```

Trains a JEP 514 AOT cache (JDK 25+; AppCDS below that) from one run of the application.
The cache is valid only for:

- the **exact JVM build** that trained it (`run.sh` execs that JVM)
- the **absolute classpath paths** (do not move `target/aot-cache/` around)
- the **jars themselves** (a changing build drops the cache and says so)

A mismatch is not a hard error at process start — the JVM silently starts cold — but
`jk build --aot-cache` starts the app once with the cache and **fails** if the JVM
refuses it, so a cache that exists is a cache that loads.

JVM options: `JK_JAVA_OPTS` (`"$@"` reaches the application). Flags that change the
collector or heap shape can cost the cache.

**Not available for `jk run`:** a CDS dump rejects directory classpath entries, and
`jk run` launches from `target/classes/`.

Container images: [Images](images.md#aot-cache-in-the-image).

## Timeline

Every `jk build` / `jk test` writes Chrome Trace Event JSON at `target/jk-profile.json`.
Open it in Perfetto or `chrome://tracing`. Archive it in CI.

```bash
jk build --no-timeline
JK_CHROME_PROFILE=off jk build
JK_CHROME_PROFILE=/tmp/trace.json jk build
```

## Related

[Explain](explain.md) · [Cache](cache.md) · [Engine](engine.md) · [CI](ci.md)
