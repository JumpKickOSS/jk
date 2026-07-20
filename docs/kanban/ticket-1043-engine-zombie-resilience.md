# ticket-1043 — Engine zombie / half-dead process resilience

**Priority:** P2-infra (product reliability; promote when P2 product tickets allow)  
**Status:** backlog  
**Kind:** go-do  
**Source:** Full-suite hang investigation (client blocked on `runSync` / `runSingleBuild` while engine accepted the socket); long-lived laptop daemon (sleep/wake)  
**Depends on:** none  
**Branch:** `ticket-1043-engine-zombie-resilience`  
**Estimate:** M  

## Problem

A **live socket is not enough** to know the engine is healthy. Observed failure mode:

1. Client connects successfully (UDS/TCP).  
2. Client sends a request and blocks on protocol `readLine`.  
3. Engine never finishes (or never emits another line) — client hangs until idle timeout (**default 60 minutes**).  

Contributing factors from investigation:

- **`forceStop` / afterEach stop** could return before the process fully exited → next client may race a dying generation.  
- **Best-effort paths** (e.g. IDE pre-sync) had **no overall deadline** (partially mitigated for IDE with a 30s box).  
- **Protocol idle** is configurable (`JK_STREAM_IDLE_MS` / minutes) but production default is very long.  
- Laptops: sleep/wake, suspended engine threads, half-open sockets, stale endpoint files.

Users leave the resident engine up for days. We must not allow a **zombie** that still accepts connections but never serves.

## Goals

1. **Detect** unhealthy engines quickly (liveness beyond “socket exists”).  
2. **Recover** automatically: displace/restart without user `kill -9`.  
3. **Fail closed** on the client with a clear error within seconds–tens of seconds, not minutes.  
4. Survive **sleep/wake** and long idle without wedging the next command.

## Design sketch (refine in implementation)

### Engine side

| Mechanism | Purpose |
|---|---|
| **Heartbeat / idle protocol** | Optional periodic `noop` or progress so idle timeout is meaningful; or server-side job watchdog |
| **Request deadline** | Per-request max wall time for sync/build (configurable); abort + ERROR frame |
| **Displacement on stall** | If generation holds lock but stops accepting/responding, next spawn takes over (already partly true for version skew) |
| **Wake hygiene** | On first post-wake accept, validate JVM/thread state; refuse or self-restart if clocks/FDs look wrong |

### Client side

| Mechanism | Purpose |
|---|---|
| **Health probe before heavy work** | `ping` + short status; if hang, hardKill + ensureRunning |
| **Shorter stream idle default for interactive** | Keep long idle for huge builds; use tighter default for IDE/best-effort and document `JK_STREAM_IDLE_MS` |
| **ensureRunning must not trust a wedged peer** | If exchange fails or times out, kill and respawn once |

### Partial mitigations already landed (do not regress)

- IDE `hostedBestEffortSync` 30s ceiling + forceStop on timeout  
- CLI test `JK_STREAM_IDLE_MS=45000`  
- `EngineTestSupport.stopEngineOnly` waits for PID death  
- `BoundedLineReader` idle close of channel  

Ticket work = **product-grade** version of the same ideas for normal `jk build` / long-lived daemons.

## Acceptance

- [ ] Documented liveness model (status + idle + kill/respawn) in architecture or engine docs  
- [ ] Client: after simulated wedged engine (accepts connect, never replies), next `jk engine status` / `jk build` recovers within a bounded time (e.g. ≤15s probe + one restart) without manual kill  
- [ ] Engine: no forever-stuck request without an ERROR or connection close (watchdog or deadline)  
- [ ] Sleep/wake note tested or documented (macOS laptop path)  
- [ ] Unit/integration tests for “silent peer” and “stop waits for death”  
- [ ] Defaults chosen so huge monorepo builds still work (tunable env)  

## Non-goals

- Removing the resident engine model  
- RBE / remote engine  
- Full supervisor process outside the engine  

## Refs

- `EngineClient.ensureRunning` / `forceStop` / `hardKill` / `protocolReader`  
- `EngineServer` accept loop + async pipeline handlers  
- `IdeSupport.hostedBestEffortSync`  
- ticket-1021 (test stop cadence)  
