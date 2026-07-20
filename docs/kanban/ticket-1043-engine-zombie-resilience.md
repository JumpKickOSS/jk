# ticket-1043 — Engine zombie / half-dead process resilience

**Priority:** P1-infra (product reliability)  
**Status:** done  
**Kind:** go-do  
**Source:** Full-suite hang investigation (client blocked on `runSync` / `runSingleBuild` while engine accepted the socket); long-lived laptop daemon (sleep/wake)  
**Depends on:** none  
**Branch:** `ticket-1043-engine-zombie-resilience`  
**Estimate:** M  
**Order:** **1 of 3** infra batch (before 1022 / 1042)

## Problem

A **live socket is not enough** to know the engine is healthy. Observed failure mode:

1. Client connects successfully (UDS/TCP).  
2. Client sends a request and blocks on protocol `readLine`.  
3. Engine never finishes (or never emits another line) — client hangs until idle timeout (**default 60 minutes**).  

Contributing factors:

- **`forceStop` / stop** can return before the process fully exits → next client races a dying generation.  
- **Best-effort paths** (e.g. IDE pre-sync) had **no overall deadline** (partially mitigated: IDE 30s box).  
- **Protocol idle** is configurable (`JK_STREAM_IDLE_MS`) but production default is very long.  
- Laptops: sleep/wake, half-open sockets, stale endpoint files.

Users leave the resident engine up for days. A **zombie** that accepts connections but never serves must not wedge the next command for minutes.

## Liveness model (product contract)

| Layer | Authority | Bound |
|---|---|---|
| **Probe** (`ping` / `hello` / `status`) | One request/reply via `exchange` | **2s** socket watchdog (`SOCKET_TIMEOUT_MILLIS`) |
| **Stream** (build/test/sync) | Line stream via `BoundedLineReader` | **`JK_STREAM_IDLE_MS`** (default 60m between lines; tests use 45s) |
| **Ensure** | Handshake must succeed | On connect-but-silent: **displace once** (kill + respawn) |
| **Stop** | Process death, not only `bye` | Wait for PID exit (then hardKill) |

Document this in [architecture.md](../architecture.md) under process model / lifecycle.

## MVP scope (this ticket)

Ship the **client-side** recovery path that already almost exists; do **not** build a full engine-side job watchdog yet.

1. **`forceStop` / stop helpers wait for process death**  
   After a successful or failed force-stop that knew a pid, wait (bounded, ~1.5s) then `hardKill`. Same idea as `EngineTestSupport.stopEngineOnly` — promote to product API.

2. **`doEnsure` treats silent peer as dead**  
   If the endpoint/socket exists but `handshake` fails with timeout / closed-without-reply (not “nothing listening”), read pid from status/pidfile if available, **hardKill**, clear stale endpoint if needed, then `startWithSelfHeal` once.

3. **Stream idle failure is fail-closed and clear**  
   When `BoundedLineReader` closes for idle: message names the timeout + env knob + suggests `jk engine stop --force`. No automatic mid-build restart of a multi-minute compile (that would be surprising); next `ensureRunning` recovers via (2).

4. **Docs** — architecture liveness table; env vars already exist.

5. **Tests**  
   - Silent peer: socket accepts, never replies → handshake empty within ~2s; ensure recovers (or kill path exercised).  
   - Stop waits for death (unit or integration against in-process server / mock process if needed).

### Already landed (do not regress)

- IDE `hostedBestEffortSync` 30s ceiling + forceStop on timeout  
- CLI test `JK_STREAM_IDLE_MS=45000`  
- `EngineTestSupport.stopEngineOnly` PID wait  
- `BoundedLineReader` idle close  

## Out of scope (follow-ups)

- Engine-side **per-request wall deadline** / job watchdog that emits ERROR mid-build  
- Periodic heartbeat frames on long silent compiles  
- Separate supervisor process  
- Changing default stream idle globally to a short value (huge monorepos need long idle)

## Acceptance

- [x] Architecture (or engine) docs describe probe vs stream vs ensure/stop liveness  
- [x] `forceStop` path waits for PID death (bounded) before returning when pid known  
- [x] Silent peer: next `ensureRunning` / `jk engine status` path recovers within ~15s without manual `kill -9`  
- [x] Idle stream timeout surfaces a clear error (env name + force-stop hint)  
- [x] Unit/integration coverage for silent peer and stop-waits-for-death  
- [x] `./gradlew :cli:test` green for changed areas; reinstall + smoke at batch merge  

## Non-goals

- Removing the resident engine model  
- RBE / remote engine  
- Full supervisor outside the engine  

## Refs

- `EngineClient.ensureRunning` / `doEnsure` / `forceStop` / `hardKill` / `exchange` / `protocolReader`  
- `EngineServer` accept loop  
- `IdeSupport.hostedBestEffortSync`  
- ticket-1021 (test stop cadence)  
