# Exclusive same-fingerprint builds + durable in-flight history

Product: concurrent builds on one resident engine are allowed when jobs differ; identical jobs
are rejected; running builds survive a dashboard refresh.

## Fingerprint (exclusivity key)

Computed by `BuildJobFingerprint` for **build-history kinds** only (`BuildHistoryKinds`:
`build`, `test`, `compile`, `native`, `image`).

**JK-1291:** for those kinds, the key is **project directory + kind only** (canonical
`toRealPath()` when resolvable). Flags such as `--rebuild`, `-m`, `skipTests`, or variant do
**not** open a second concurrent slot — they share the same `target/` tree and would race.

| Input | Notes |
|-------|--------|
| Canonical project dir | `toRealPath()` when the tree exists; else absolute normalized path — **worktrees differ** |
| Kind | Separate slots per kind (e.g. `build` vs `test` on the same dir may run concurrently) |

SHA-256 of a canonical multiline form. Non-build kinds (`lock`, `update`, `format`, `sync`,
tooling, …) do **not** take a slot and are **not** written to durable build history — even though
they may show up on the live activity feed while running.

## Client exit while building

| Client event | Engine |
|--------------|--------|
| Ctrl-C / SIGINT | cancel path (`BUILD_CANCEL` / cooperative halt) |
| SIGPIPE (`jk build \| head`) | socket EOF → same cancel path as disconnect |
| Client kill -9 | OS closes socket → EOF → cancel |

The exclusive hold stays until the engine finishes cancel / workers die (grace then force). A
second identical-dir `jk build` during that window receives **already-running**.

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
`~/.local/state/jk/builds/projects/<key>/run-number.txt` (JK-1377) **only for build-history kinds**.
Finish harvest trains metrics but does **not** mint a second number. The journal and SSE
`request-start` carry the same `#N`.

## Durable in-flight

At admit, when history is enabled, `BuildJournal.begin` writes under
`projects/<key>/runs/<buildNumber>/` with `running: true`. `record.json` carries a UTC timestamp
as `id` (when the run started) and the numeric `buildNumber`. CLI `details.jsonl` binds to that
run dir from `job-start` (`buildNumber` + `detailsPath`) — no separate history id on the wire.

On finish, `complete` rewrites the **same** run directory with the finished record
(`running: false`) and `metrics.toml`, then requests `MetricsHarvest`.

`/api/history` therefore lists in-flight runs; the web client `seedFromHistory` materializes
running cards so a refresh/new tab still shows them. SSE reconciles by `buildNumber` + `dir`.

On engine start, `abandonStaleRunning` marks leftover `running: true` rows cancelled (process
died mid-run).

## Schema

Additive only (`running` on `BuildRecord`, optional `buildNumber` on `request-start`). Schema
version stays **2** (pre-1.0 freeze).
