# Host warmup: worker AOT + calibration (engine self-heal)

JumpKick pre-trains compiler-worker AOT caches and host calibration **inside the resident
engine** — there is no user-facing `jk optimize` or `jk engine calibrate` command.

## When it runs

| Trigger | Behavior |
|---------|----------|
| **First engine start** (install, first build, `jk self update` / new engine jar) | If worker AOT or calibration is missing for this host, queue idle-boundary warmup |
| **Every ~12 h** (same cadence as store-feed refresh / cache GC) | Re-check; regenerate only what is missing |
| **JDK change** | Calibration is keyed to jk version + host JDK id; a new JDK re-probes |

Disable with user config:

```toml
[engine]
auto-warmup = false
```

Or env: `JK_AUTO_WARMUP=off`. Worker AOT also respects `JK_AOT_TRAIN=off` / `JK_WORKER_AOT=off`.

## What is trained

| Artifact | Notes |
|----------|--------|
| **java-compiler-*.aot** | ToolProvider javac worker (HotSpot 25+) |
| **kotlinc-*.aot** | Kotlin compiler plugin worker (latest shipping plugin classpath) |
| **host-metrics.toml `[calibration]`** | HardwareProbe multi-probe for cold ETA |

**Not** pre-trained: Groovy (and older language versions) — train-on-miss on first real use.
**Not** trained: test-runner AOT (suite classpath includes project classes; caches are not reusable).

Work runs on a **daemon idle thread** when `activePipelines == 0` so client builds are not blocked.

## Engine first start (related)

| Step | Behavior |
|------|----------|
| **`libs.global.toml`** | `StoreFeedRefresh` on start and ~12 h |
| **Engine AOT** | Sidecar train via `-Djk.aot.train.output` (flags match serving spawn, including `--enable-native-access=ALL-UNNAMED`) |

## Install

```text
jk engine start    # queues self-heal if needed; returns when engine is up
```

No separate optimize/calibrate steps. Warmup continues in the background after install returns.

## Synthetic history

Internal train/calibrate builds (if any) use triggers `optimize` / `calibrate` / `synthetic` and are
omitted from `jk history` / the web activity feed.
