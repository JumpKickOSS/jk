# Host warmup: worker AOT + calibration (engine self-heal)

JumpKick pre-trains compiler-worker AOT caches and host calibration **inside the resident
engine** — there is no user-facing `jk optimize` or `jk engine calibrate` command.

## When it runs

| Trigger | Behavior |
|---------|----------|
| **First engine start** | Queue idle warmup (feeds → templates → AOT → cal) |
| **Every minute** | Poll `config.toml` mtime (reload policy); check wall-clock 12 h stamp |
| **Every ~12 h wall-clock** (laptop-safe) | Feeds + `jk-templates` freshen + cache GC enqueue + AOT/cal if still missing |
| **JDK / jk version change** | Calibration re-probes when host JDK id or jk version no longer matches |

The 12 h interval is **wall-clock since last stamp**, checked each minute — a laptop that never stays up 12 hours continuously still refreshes after resume once 12 h have elapsed.

Disable with user config:

```toml
[engine]
auto-warmup = false
```

Or env: `JK_AUTO_WARMUP=off`. Worker AOT also respects `JK_AOT_TRAIN=off` / `JK_WORKER_AOT=off`.

## Warmup order (idle worker)

1. **`libs.global.toml`** — conditional GET / ETag (skip if fresh)
2. **`jdks.json`** — TTL + If-Modified-Since
3. **Official `jk-templates` shallow clone** — `git fetch --depth 1` / clone (includes future AOT fixtures)
4. **java-compiler AOT** — only if missing for this host
5. **Host calibration** — only if missing/stale for this jk version + JDK

Network errors are **fail-fast and quiet** (no retries). The next minute/12 h cycle or engine restart tries again.

## What is trained

| Artifact | Notes |
|----------|--------|
| **java-compiler-*.aot** | ToolProvider javac worker (HotSpot 25+) — pre-trained on idle |
| **engine-`<jk-version>`-*.aot** | Resident engine JAR (sidecar train; same GC / native-access as serve) |
| **host-metrics.toml `[calibration]`** | HardwareProbe multi-probe for cold ETA |

**Not** pre-trained: **Kotlin** — trains on miss at the first real Kotlin compile (classpath is
project/version-specific; a dedicated bootstrap key would not match production forks). List caches
with `jk engine aot`.
**Not** AOT-cached at all: **Groovy** workers (no cache integration yet) and test-runner AOT
(suite classpath includes project classes; caches are not reusable).

### `aot.toml` (human index)

Cache file names are content hashes (`tool` + JDK home/vendor/version + GC + classpath for workers;
engine jar identity + JDK for the engine). Open **`~/.local/state/jk/aot/aot.toml`** (or
`$JK_STATE_DIR/aot/aot.toml`) for a readable table of each file: tool, key, status (`ready` /
`pending` / `noaot` / `empty` — a zero-byte cache from an interrupted train), size, JDK, GC,
classpath, JVM flags, and timestamps. Written on train / use / sweep; safe to delete or hand-edit
(a corrupt file is regenerated on the next write).

List the same details from the CLI:

```bash
jk engine aot              # summary table + per-cache details
jk engine aot -O json      # machine-readable
```

### Training failure and retry semantics

Worker training failures back off, but never permanently:

- **Trainer fails** (nonzero exit): a sticky `.noaot` marker sibling is written and the key is
  skipped — no retry storm from retrying on every compile.
- **Trainer overruns** (120 s watchdog): **no** sticky marker — an overrun is usually transient
  (first Kotlin compile on a loaded machine). The training claim file is kept instead, which
  blocks retrains for ~10 minutes; the next compile after that retries.
- **Marker TTL**: a `.noaot` marker older than **7 days** is treated as absent at read time and
  removed, giving a once-failed key a fresh training attempt. (A JDK/Kotlin/GC bump mints a new
  key and retries immediately, marker or not.)

Work runs on a **daemon idle thread** when `activePipelines == 0` so client builds are not blocked.
Within a maintenance workset, **`System.gc()` is always last** — after prune, journal/metrics
retention, metrics harvest, feeds/templates, AOT train, and calibration — so the heap is not
shrunk mid-chore.

## Engine first start (related)

| Step | Behavior |
|------|----------|
| **Engine AOT** | Sidecar train via `-Djk.aot.train.output` (flags match serving spawn, including `--enable-native-access=ALL-UNNAMED`) |
## Install

```text
jk engine start    # queues self-heal if needed; returns when engine is up
```

No separate optimize/calibrate steps. Warmup continues in the background after install returns.

## Synthetic history

Internal train/calibrate builds (if any) use triggers `optimize` / `calibrate` / `synthetic` and are
omitted from `jk history` / the web activity feed.
