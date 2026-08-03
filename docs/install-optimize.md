# Install optimize + language calibration (JK-1385)

## Engine first start (verified)

| Step | Behavior |
|------|----------|
| **`libs.global.toml`** | Resident engine runs `StoreFeedRefresh` on start and every ~12h: missing or stale `store/libs.global.toml` → conditional GET. Failures are quiet (offline keeps last copy). |
| **Engine AOT** | Client may set `-Djk.aot.train.output=…/engine-*.aot`. After election, `EngineMain` spawns a sidecar with `-XX:AOTCacheOutput` + `--aot-training`. Install calls `jk engine start` which triggers this path. |

Plugin workers (**kotlinc**, **java-compiler**) are **not** trained at engine start alone — that is `jk optimize`.

## `jk optimize`

1. Ensures engine is up.
2. Engine `optimize-request` → `WorkerAotBootstrap.trainCommonWorkers` (sync train of **java-compiler** + **kotlinc** under host HotSpot 25+).
3. Materializes `templates/optimize/{java,kotlin,groovy}-train` to a temp dir.
4. Builds each fixture with `JK_BUILD_TRIGGER=optimize` (synthetic journal; purged).
5. Java: touch all `.java` + rebuild with `JK_JAVA_FORCE_WORKER=1` so the ToolProvider worker + PluginAot path runs.
6. Runs `jk test` on fixtures (warms test-runner process).
7. Writes `[mean.by_language.<lang>]` (fixture walls + sane `compile_per_source_ms`) and seeds
   `[mean] compile-{java,kotlin,groovy}-per-source-ms` for cold ETA (relative language cost ×
   product baseline — not raw wall/sources, which would include resolve+test).
8. Deletes temp trees.

TTY: PipelineWedge stages → settle **Done optimizing JumpKick! Hi-yah!**

## Install order

```text
jk engine start
jk optimize
jk engine calibrate
```

## Synthetic history (JK-1390)

Builds with `trigger` ∈ {`optimize`,`calibrate`,`synthetic`} are omitted from `jk history` / web list and purged from `state/builds/projects/`.

## Graal

`PluginAot.eligible` rejects Graal hosts — worker train is skipped with notes; engine host should remain Temurin/HotSpot for training.
