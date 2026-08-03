# Install optimize + language calibration (JK-1385)

## Engine first start (verified)

| Step | Behavior |
|------|----------|
| **`libs.global.toml`** | Resident engine runs `StoreFeedRefresh` on start and every ~12h: missing or stale `store/libs.global.toml` → conditional GET. Failures are quiet (offline keeps last copy). |
| **Engine AOT** | Client may set `-Djk.aot.train.output=…/engine-*.aot`. After election, `EngineMain` spawns a sidecar with `-XX:AOTCacheOutput` + `--aot-training` (flags must match the serving spawn, including `--enable-native-access=ALL-UNNAMED`). Install calls `jk engine start` which triggers this path. |

Plugin workers (**kotlinc**, **java-compiler**) are **not** trained at engine start alone — that is `jk optimize`.

## `jk optimize`

1. Ensures engine is up; materializes fixtures into `JkDirs.cache()/templates/optimize/` (classpath or monorepo).
2. Engine `optimize-request` **schedules** worker AOT train on the **idle-boundary worker** and ACKs immediately (does not block the CLI on multi-second training). Training runs when `activePipelines == 0`, same chore path as cache prune / journal hygiene.
3. Materializes `templates/optimize/{java,kotlin,groovy}-train` to a temp dir.
4. Builds each fixture with `JK_BUILD_TRIGGER=optimize` (synthetic journal; purged).
5. Java: touch all `.java` + rebuild with `JK_JAVA_FORCE_WORKER=1` so the ToolProvider worker + PluginAot path runs.
6. Runs `jk test` on fixtures (warms test-runner **process**; no dedicated test-runner AOT trainer).
7. Writes `[mean.by_language.<lang>]` and seeds `[mean] compile-*-per-source-ms` for cold ETA.
8. Deletes temp trees.

**Pinned languages (pre-train):** Kotlin **2.4.10**, Groovy **5.0.8**, Java via current java-compiler worker. Other language versions train **on-demand** (PluginAot train-on-miss) the first time a real project uses them.

**Wall time:** First optimize often spends most of its wall on fixture resolve/fetch (network + CAS). A second warm run is much shorter; worker AOT itself is idle-scheduled and does not pad the CLI ACK. Prefer slight over-estimate on cold ETA over under-estimate.

TTY: PipelineWedge stages → settle **Done optimizing JumpKick! Hi-yah!**

## Install order

```text
jk engine start
jk optimize              # schedules idle AOT + runs language fixtures
jk engine calibrate
```

## Synthetic history (JK-1390)

Builds with `trigger` ∈ {`optimize`,`calibrate`,`synthetic`} are omitted from `jk history` / web list and purged from `state/builds/projects/`.

## Graal

`PluginAot.eligible` rejects Graal hosts — worker train is skipped with notes; engine host should remain Temurin/HotSpot for training.

## Fixture resolution (JK-1400)

1. `~/.cache/jk/templates/optimize/<name>` (or `JK_CACHE_DIR`)
2. Data root templates
3. Monorepo walk from CWD
4. Classpath resources shipped in the CLI jar / native image (`templates/optimize/…`)
