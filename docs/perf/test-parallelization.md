# Test parallelization: Mill vs jk (JK-1086 Phase A)

**Status:** Phase A investigation complete · **Default-on deferred**  
**Date:** 2026-07-20 · **Follow-up:** JK-1087 (within-module class-level / process-pool productization)

## Product stance (stable)

| Default | Why |
|---------|-----|
| **`-j` / jobs** = effective cores | Module graph parallel is Mill-shaped and already shipped (JK-1082 / 1084). |
| **Cross-module tests serial** | Hermeticity: shared ports, temp dirs, statics, DB. Opt-in: `--parallel-tests`. |
| **`-w` / workers = 1** | One test JVM per module unless the user asks for more. |
| **Do not default-on `--parallel-tests`** | Until isolation contract + measured monorepo win (Phase B/C). Prefer green over flaky speed. |

CI may opt in via flags/env without changing laptop defaults.

## Mill model (what we are matching)

From [Mill JVM test parallelism](https://mill-build.org/blog/11-jvm-test-parallelism.html):

1. **Task graph** parallel by default (modules/tasks) — jk: **jobs**.
2. **Within a suite**: class-level work units, process pool, isolation heuristics (ports, temp, reuse policy).
3. Aggressive defaults only after isolation is trustworthy.

jk’s gap is **(2)** product quality + **(3)** policy — not inventing a second jobs knob.

## jk today (code map)

| Layer | Behavior | Code |
|-------|----------|------|
| Cross-module test gate | Serial unless `Session.parallelTests` | `BuildPipelines` test gate; CLI `--parallel-tests` |
| Module concurrency | `-j` / `Jobs` / cgroup cores | `Jobs`, `AvailableCpus`, scheduler width |
| Per-module workers | `-w` default 1; `>1` = discovery + pull-queue JVMs | `JUnitLauncher` (`runSingle` / `runParallel`) |
| Class distribution | Concurrent deque of FQCNs (pull); min(workers, classCount) | `JUnitLauncher.runParallel` |
| RAM | Peak JVMs ≈ `modules×workers` if parallel-tests else `max(modules, workers)`; `HeapPlan` veto | `HeapPlan.requestedJvms` |
| Engine heap | Thin coordinator (~256 MiB); workers own cost | JK-1075 |

**Already present but under-used:** multi-worker pull-queue for **one** module when `-w N`. Missing Mill-class polish:

- Balanced sharding / duration-aware scheduling  
- Long-lived process **pool** (reuse JVM across classes vs fork-per-batch)  
- Isolation sandbox (unique temp, ports, optional HOME)  
- Heuristics for “safe to parallel” vs force serial  
- Default raising of `-w` or class-parallel without user flag  

## RAM cost model

Let:

- `J` = effective jobs (modules building/testing at once)  
- `W` = `-w` workers per module  
- `P` = 1 if `--parallel-tests`, else 0  

Approximate peak **test** worker JVMs:

```
peak ≈ P ? (J × W) : max(J, W)     # then HeapPlan clamps by free RAM
```

Examples (before RAM clamp):

| Scenario | Peak test JVMs |
|----------|----------------|
| Default laptop (`J=cores`, `W=1`, no `P`) | ~1 test JVM at a time for tests (modules still parallel on compile); test steps gated serial |
| `jk test -j4 -w2` no parallel-tests | max(4, 2) = 4 compile-side; tests still serial per gate |
| `jk test -j4 -w2 --parallel-tests` | 8 test JVMs requested |
| CI monorepo `-j0 -w4 --parallel-tests` | cores × 4 (often RAM-clamped hard) |

**Implication:** defaulting parallel-tests without isolation multiplies flake risk *and* RSS. Raising default `-w` alone multiplies **within** a module only — safer for productization after 1087.

## Flake / isolation classes (known)

| Class | Symptom if parallel | Mitigation direction |
|-------|---------------------|----------------------|
| Fixed ports (HTTP, gRPC, DB) | BindException / cross-talk | Unique port allocation per worker; or serial tag |
| Shared temp / `java.io.tmpdir` | File races | Per-worker temp root |
| Mutable statics / singletons | Order-dependent fails | Document as non-hermetic; force serial |
| System properties / env | Cross-worker pollution | Fork boundaries already help; pool reuse must reset |
| File locks on project dirs | Timeouts | Per-worker work dirs for generated outputs |
| Kotlin worker “closed” (historical) | Flaky engine tests | JK-1053 style process lifecycle |

Go criteria for **default** parallel (Phase C): monorepo CI green at opt-in settings for N days; no increase in flake rate; wall-time win ≥ ~20% median on a multi-module suite; RSS acceptable under HeapPlan.

No-go: any of the above fail → keep opt-in; ship isolation first (Phase B).

## Decision: within-module vs cross-module

| Track | Ticket | Priority |
|-------|--------|----------|
| **Within-module** class shard / process pool (Mill-class) | **JK-1087** | Next go-do; does not require defaulting `--parallel-tests` |
| **Cross-module** default-on | Phase C of this epic | **Deferred** until isolation + measure |

Phase A answer: **yes, split** — JK-1087 owns the Mill within-suite investigation/spike; this epic owns policy for cross-module defaults and the isolation contract checklist.

## Phase B checklist (isolation — ship next)

- [x] Per-worker `java.io.tmpdir` (+ `TMPDIR`) when `W > 1` (`JUnitLauncher.driveWorker`)  
- [ ] Document sandbox contract for suite authors (ports, temp, statics) in guide  
- [ ] Optional port-range helper / documented convention for fixed-port tests  
- [ ] Failure lines always include module (+ class when sharded)  
- [ ] Optional “serial” tag / config for known bad suites  
- [ ] Auto default `-w = min(jobs, classCount)` (or capped) once isolation is trusted — **Mill parity**

## Phase C checklist (cross-module defaults)

- [ ] Microbench / monorepo: serial vs `-wN` vs `--parallel-tests` (wall + RSS + timeline)  
- [ ] CI profile: `-j0 -wN --parallel-tests` with measured flake budget  
- [ ] Only then consider default-on `--parallel-tests` for laptop  
- [ ] Opt-out path remains first-class (`-w1`, no parallel-tests)

## Explicit defer (Phase B/C)

**Defer default-on cross-module test parallel** until Phase B isolation + Phase C numbers clear the go criteria. **Raise default `-w` only after per-worker temp (and flake data) land.** Current opt-in flags stay.

### Story board (implementation order)

| # | Story | Outcome |
|---|--------|---------|
| B1 | Per-worker temp isolation (`W>1`) | Done (engine `JUnitLauncher`) |
| B2 | Guide: how to run Mill-like `jk test -j0 -wN --parallel-tests` | Docs |
| B3 | Auto `-w` policy (min(jobs, classes), HeapPlan clamp) | Default closer to Mill |
| B4 | Serial tag / suite opt-out | Hermetic suites stay green |
| C1 | Monorepo measure + CI profile | Data-driven default-on decision |
| C2 | Default `--parallel-tests` (or CI-only) | Cross-module Mill/Gradle-parallel parity |

## Refs

- CLI: `BuildCommand` / `TestCommand` `--parallel-tests`, `-w`  
- Engine: `JUnitLauncher`, `BuildPipelines` test gate, `HeapPlan.requestedJvms`  
- Jobs: `Jobs`, `AvailableCpus` (JK-1082 / 1084)  
- Mill: https://mill-build.org/blog/11-jvm-test-parallelism.html  
