# Explain and tasks

`jk explain` forecasts **what will run** before it does: cache hit/miss per module and
stage, plus ETA. Java compile steps are forecast by the same Zinc worker a build uses
(one JVM for the explain job): source stamps, classpath jars that actually invalidate
sources, and deleted types — not a whole-module guess whenever the classpath identity
changes.

Prefer this over Gradle build scans for day-to-day “why did this rebuild?” questions.

```bash
jk explain                   # phase rollup + rebuild-surface table + ETA
jk explain --verbose         # expand every task under each module (a compile step names its -Xplugin:s)
jk explain --redo            # forecast full rebuild (same as jk build --redo)
```

When the lock is missing or stale, `jk explain` refreshes it first (same as `jk build`) so
the plan and ETA match the live countdown. Automatic refreshes keep pins — pinned versions
stay put.

The ETA seed matches bare `jk build` bit-for-bit (same `-w` auto, `-j`, flags).

## Module graph (no engine)

```bash
jk explain --graph dot > modules.dot
dot -Tsvg modules.dot -o modules.svg
jk explain --graph mermaid > build.mmd
jk explain --graph mermaid --modules 'libs/*' --graph-out filtered.mmd
```

Interactive DAG: engine dashboard → Project → Dependencies ([Web](web.md)).

## Tasks

First-party task catalog (Mill resolve-lite):

```bash
jk tasks                         # list tasks
jk show package-jar              # primary jar path for this module
jk inspect compile-java          # phase + path + on-disk status
jk tasks show package-jar --modules 'libs/*'
```

Stages are a closed set, in pipeline order: `resolve`, `generate`, `compile`, `test`,
`package`, `train`, `native`, `image`, `publish`, `other`.

## Timeline

`target/jk-profile.json` — [Build](build.md#timeline).
