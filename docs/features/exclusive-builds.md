# Exclusive same-fingerprint builds + durable in-flight history

Product: concurrent builds on one resident engine are allowed when jobs differ; identical jobs
are rejected; running builds survive a dashboard refresh.

## Fingerprint (exclusivity key)

Computed by `BuildJobFingerprint` for kinds `build` and `test` only:

| Input | Notes |
|-------|--------|
| Canonical project dir | `toRealPath()` when the tree exists; else absolute normalized path — **worktrees differ** |
| Kind | `build` vs `test` |
| rebuild/force | changes the job |
| offline, modules, variant, assemblyOverride | session/wire flags that change work |
| skipTests / testOnly | HTTP shape |

SHA-256 of a canonical multiline form. Non-exclusive kinds (`lock`, `sync`, …) do not take a slot.

## Admission

`InFlightBuilds` holds `fingerprint → Hold(requestId, buildNumber, …)` in the engine process.

- Second admission with the same fingerprint → **reject** before work starts.
- CLI: JSONL `error` with `code: already-running` and message `Build #N is already running`
  (CommandWedge via existing fail path).
- HTTP `POST /api/build`: **409** with the same message.
- Different fingerprint or different real path → concurrent OK.

MVP is **engine-local** (not multi-engine / multi-host).

## Build numbers

`BuildNumberAllocator` assigns a monotonic per-project number at **request-start** from
`~/.jk/state/builds/projects/<key>/run-number.txt` (JK-1377). Finish harvest trains metrics but
does **not** mint a second number. The journal and SSE `request-start` carry the same `#N`.

## Durable in-flight

At admit, when history is enabled, `BuildJournal.begin` writes `record.json` under
`projects/<key>/runs/<id>/` with `running: true` and the start-time `buildNumber`. CLI
`details.jsonl` binds to the same run dir from `job-start` (`historyId` / `detailsPath`).

On finish, `complete` rewrites the **same** entry id with the finished record (`running: false`)
and `metrics.toml`, then requests `MetricsHarvest`.

`/api/history` therefore lists in-flight runs; the web client `seedFromHistory` materializes
running cards so a refresh/new tab still shows them. SSE reconciles by `buildNumber` + `dir`.

On engine start, `abandonStaleRunning` marks leftover `running: true` rows cancelled (process
died mid-run).

## Schema

Additive only (`running` on `BuildRecord`, optional `buildNumber` on `request-start`). Schema
version stays **2** (pre-1.0 freeze).
