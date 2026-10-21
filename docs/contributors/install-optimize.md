# Host warmup: calibration on the idle engine

JumpKick measures the host **inside the resident engine** and keeps the result in
`state/builds/host-metrics.toml`. There is no user-facing command for it; the engine does it when
nothing else is running.

## When it runs

| Trigger | Behavior |
|---------|----------|
| **First engine start** | Queue idle warmup (feeds → templates → calibration) |
| **Every minute** | Poll `config.toml` mtime (reload policy); check the wall-clock 12 h stamp |
| **Every ~12 h wall-clock** (laptop-safe) | Feeds + `jk-templates` freshen + cache GC enqueue + calibration if still missing or stale |
| **JDK / jk version change** | Calibration re-probes when the host JDK id or the jk version no longer matches |

The 12 h interval is **wall-clock since the last stamp**, checked each minute — a laptop that
never stays up 12 hours continuously still refreshes after resume once 12 h have elapsed.

Disable with user config:

```toml
[engine]
auto-warmup = false
```

Or env: `JK_AUTO_WARMUP=off`.

## Warmup order (idle worker)

1. **`libs.global.toml`** — `<store>/libs.global.toml` (`JkDirs.libraryRegistry()`); conditional GET / ETag (skip if fresh)
2. **`jdks.json`** — TTL + If-Modified-Since
3. **Official `jk-templates` shallow clone** — `<store>/templates` (`JkDirs.templates()`); `git fetch --depth 1` / clone
4. **Host calibration** — only if missing or stale for this jk version + JDK

Steps 1–3 belong to the maintenance cycle (`EngineMaintenance`) and run whether or not
`auto-warmup` is on; step 4 is `HostWarmup`, behind the switch. Network errors are **fail-fast and
quiet** (no retries). The next minute/12 h cycle or engine restart tries again.

## What calibration writes

| Artifact | Notes |
|----------|--------|
| **host-metrics.toml `[calibration]`** | `HardwareProbe` multi-probe (JVM fork, javac, disk, hash, JUnit fork and run, resolve) for the cold ETA |
| **worker jars in the artifact store** | The probe forks the java-compiler and test-runner workers, which `PluginJar.locate()` fetches into `<store>` |

Every root the pass writes under is outside `JK_CACHE_DIR`, so `jk cache nuke` is not undone by the
next cycle. After a store wipe the running engine stands down (`StoreWriteGate.wipedSinceStart`)
and the next engine rebuilds what it needs.

Work runs on a **daemon idle thread** when `activeBuildPlans == 0` so client builds are not
blocked. Within a maintenance workset, **`System.gc()` is always last** — after prune,
journal/metrics retention, metrics harvest, feeds/templates and calibration — so the heap is not
shrunk mid-chore.

## Worker startup caches

The java-compiler, kotlinc, formatter and graph workers (`java … PluginMain`) map a JEP 514 cache under
`~/.jk/state/aot/<tool>-<jk-version>-<jdk tag>-<key>.aot` (`WorkerAotCache`). The rule is one file
per tool, recorded only for the JDK jk itself runs on: a worker forked on a project's own JDK
runs without a cache, a landing cache deletes the tool's other caches, and the engine deletes
every cache for another jk version or another JDK when it starts. The key hashes the worker GC
flag, the worker classpath, the module's own JVM flags and, for javac, what the trainer compiled.
Nothing pre-trains: the first run that misses forks one background trainer (for javac a bundled
four-file module, `JavacTrainingSources`, compiled at `--release 21` or the module's, whichever is
higher; a synthetic `Hello.kt` for kotlinc; formatter sources) and the next run maps the result. A recording that fails is not retried until the next engine
start. `JK_WORKER_AOT=off` turns mapping and recording off; suite JVMs and CI set it.

## The engine's startup cache

The engine maps a JEP 514 cache of its own, `<state>/engine-aot/engine-<jk-version>-<key>.aot`
(`EngineAotCache`), keyed by the engine jar's path, size and mtime and the JDK's `release` file. A
resident engine cannot record it for itself: the JVM assembles a cache when the recording run
exits, and a resident engine exits when it is replaced. So an idle engine with no cache for its
jar (`EngineAotTrainer`, once per process, not under `CI`) runs the jk CLI against a scratch state
and cache with `JK_ENGINE_AOT_RECORD` set: the spawner starts that trainer engine recording, the
CLI builds the javac training module, edits and rebuilds it, rebuilds it as a no-op and asks for
status, and `jk engine stop` makes the trainer assemble the cache, which lands atomically beside
the others and replaces them. About 10 s once; on petclinic a no-op on a fresh engine is then
~0.8 s instead of 1.17 s, for ~9 MiB more idle PSS. `JK_ENGINE_AOT=off` turns both halves off.

## Install

```text
jk engine start    # queues self-heal if needed; returns when engine is up
```

No separate calibrate step. Warmup continues in the background after install returns.

## Synthetic history

Internal calibration builds use the triggers `calibrate` / `synthetic` and are omitted from
`jk history` / the web activity feed.
