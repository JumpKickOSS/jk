# Build

```bash
jk compile              # type-check
jk build                # compile + package (thin jar always; more if configured)
jk build --skip-tests
jk build --redo         # ignore action cache (full rebuild)
jk clean                # delete target/; unchanged inputs restore from cache
jk clean --force        # also invalidate this project's action-cache entries
```

`jk build` uses [`jk-lock.toml`](lockfile.md) and does **not** re-resolve. Packaging
shapes: [Packaging](packaging.md). Tests that run as part of build: [Test](test.md).

When something fails, read `target/jk-results.md` — [Troubleshooting](troubleshooting.md).

## What gets skipped

JumpKick hashes inputs (sources, classpath, options, tool versions) into an **action
cache**. Matching keys **restore** outputs instead of recomputing. `jk explain` forecasts
hits and misses before you run — [Explain](explain.md).

`jk clean` deletes project `target/` outputs. The next build restores jars/classes/binaries
from the action cache when inputs are unchanged (discovery + I/O only). Input fingerprints
also live under `~/.cache/jk/projects/…` so clean does not force a full rebuild forecast.

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
| Env | `JK_JOBS` (or `JK_ENGINE_JOBS`) |
| Machine TOML | `~/.config/jk/config.toml` → `[engine] jobs = N` |

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
