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

**Compile avoidance.** A compile step is keyed on what the compiler can see of its classpath —
each dependency's ABI (signatures, supertypes, inlined constants, annotations; for Kotlin also
inline function bodies and `const val`s), never its method bodies. An implementation-only change
in a dependency recompiles and re-packages that dependency and re-runs the tests that load it,
but leaves every consumer's compile cached: no compiler forks. A change to a public signature, an
inlined constant or an inline function recompiles the consumers, and `jk explain --verbose` names
the dependency whose API moved. Annotation processors are keyed on their full content, so a
processor jar change always recompiles the modules that run it.

When a Java consumer does recompile, its Zinc session is handed the analyses of the workspace
siblings on its classpath, so a changed sibling invalidates only the consumer classes that
referenced the changed producer class — not every class that touched the sibling's jar. A
sibling whose analysis is missing or does not match its classes is treated like any other jar.

The first build after `jk install` of a new engine runs every plugin step, guard lane,
build-logic run and packaging step once more: their keys carry the identity of the engine that
produced them, so nothing an older engine produced is restored under the new one. Compile steps
keep their keys and stay cached.

## Annotation processors

Where javac looks for annotation processors follows the module's declarations, the way javac
and Maven behave:

| Declaration | Processors that run |
|-------------|---------------------|
| No `[processor-dependencies]` | Every compile-classpath entry that registers one in `META-INF/services/javax.annotation.processing.Processor` — Lombok or MapStruct declared as a plain or `provided` dependency runs |
| `[processor-dependencies]` present | That path alone; a processor that is only on the compile classpath does not run |

The discovered entries become the step's processor path, so a discovered processor and a
declared one are the same thing downstream: the worker loads it, records what it generates, and
the compile action key hashes its full content. `compile-main`, `compile-test`, fixtures and the
guard suite all apply the rule. Declare `[processor-dependencies]` when the manifest should say
what runs, when the processor needs dependencies of its own that do not belong on the compile
classpath, or when a classpath jar registers a processor you want silent.

## javac plugins

A javac **plugin** (Error Prone, NullAway, Checker Framework, Manifold) is a jar on the
processor path that javac invokes by name with `-Xplugin:<Name> <options…>`. Declare the jar in
`[processor-dependencies]` and name the plugin in `[javac]`:

```toml
java = 25                      # the release; [javac] is the compiler table

[processor-dependencies]
error_prone_core = "com.google.errorprone:error_prone_core:latest"   # opt-in float; a number pins
nullaway         = "com.uber.nullaway:nullaway:latest"

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
of the compile action key — bumping a severity recompiles. A `[javac.test]` table with the
same two keys replaces `[javac]` for `compile-test` alone; an empty one (`plugins = {}`) turns
the plugins off for a suite that hands null to parsers on purpose while production stays checked. `jk explain --verbose` names the
plugins a compile step invokes. Unknown keys under `[javac]` fail the parse. Lint
(`[build] lint`), plugin-contributed and profile `javac` args come first in the argv, then the
plugins, then `args`.

`[javac.test]` has one key `[javac]` has not: `release`. A module's tests are compiled at the
module's `java` level; `release = 21` under `[javac.test]` compiles the test sources alone with
`--release 21`, so a `java = 17` library can be tested with JDK 21 test code while its main
classes stay at class-file level 61 and its published artifact is unchanged. The value may not be
below `java` — the key raises the suite's level, never lowers it — and the suite then needs a JDK
of at least that release to run on.

```toml
java = 17                      # the library's level; main classes are 61.0

[javac.test]
release = 21                   # the suite may use a JDK 21 API; test classes are 65.0
```

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

## Debug info

Every javac compile carries full debug information (`-g`: source file, line numbers and
local variable names), the same as Gradle and Maven, so a debugger attached through
`--debug-jvm` shows locals, not `slot_1`. javac alone would default to `-g:source,lines`.
`[build] debug` picks the level — `"full"` (the default), `"lines"` (`-g:source,lines`:
stack traces still resolve, locals are unnamed) or `"none"` (`-g:none`: the smallest class
files, no line numbers in stack traces). It is a compile input: changing it recompiles the
module. Full debug info makes class files roughly 15% larger and costs nothing at run time.

```toml
[build]
debug = "lines"                # drop LocalVariableTable; keep line numbers
```

## Worker environment (`[env]`)

Every worker jk forks for a module — the compiler, each test JVM, a plugin step — starts from
an allow-list, not from a shell. A token exported in the shell that started the engine does
not reach a compiler worker or your test code by accident. Workers inherit only:

- `PATH`, `HOME`, `JAVA_HOME`, `TMPDIR` / `TMP` / `TEMP`, `LANG` / `LANGUAGE` / `LC_*`, `TERM`
- the proxy variables `http_proxy` / `https_proxy` / `no_proxy` in either case, the values of the
  shell running `jk` over the engine's own ([Config § Network](config.md#network)) — a worker
  that downloads goes through the proxy this command's terminal names, and so does a test JVM,
  a credential in the proxy URL included
- on Windows also `USERPROFILE`, `SystemRoot`, `SystemDrive`, `windir`, `PATHEXT`, `COMSPEC`,
  `NUMBER_OF_PROCESSORS`
- the `JK_*` settings a worker reads: the product roots (`JK_HOME`, `JK_STATE_DIR`,
  `JK_STORE_DIR`, `JK_CACHE_DIR`, `JK_JDKS_DIR`, `JK_M2_LOCAL`), terminal and output
  switches (`JK_COLOR`, `JK_NO_ANSI`, `JK_FORCE_ANSI`, `JK_NO_OSC`, `JK_NO_PROGRESS`,
  `JK_PROGRESS_MODE`, `JK_QUIET`, `JK_VERBOSE`, `JK_NERD_FONT`, `JK_NONINTERACTIVE`) and worker
  tuning (`JK_COMPILE_PHASES`, `JK_FILE_OPS`, `JK_WORKER_AOT`, `JK_AOT_TRAIN`,
  `JK_ANDROID_FEED_URL`)

Everything else — `SSH_AUTH_SOCK`, `DOCKER_HOST`, `GITHUB_TOKEN`, a repository's
`JK_REPO_*_TOKEN` — is dropped unless the module asks for it:

```toml
[env]
vars = ["DOCKER_HOST", { TZ = "UTC" }]   # forward a name, or set a value
```

`vars` has the `[test] env` shape: a bare name forwards the caller's value when it is set, a
table sets values outright (`${VAR}`, `${module}` and `${target}` expand; an unset `${VAR}` is
an error). `vars` reaches every worker of the module; `[test] env` layers on top for test JVMs
only and, unlike `vars`, is part of the run-tests key — [Test](test.md).

```toml
[env]
inherit = true   # the legacy build scripts read a dozen CI variables; listing them is on the backlog
```

`inherit = true` hands the module's workers the engine's whole environment, secrets included.
Write the reason beside it. `[env]` is per module and is not inherited from a workspace root.
The engine's own environment is the shell that started it, not the one running `jk` — apart
from the proxy variables above, a variable set for one command reaches a worker only through
`vars` or `[test] env`.

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
