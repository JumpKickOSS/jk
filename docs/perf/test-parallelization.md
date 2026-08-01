# Test parallelization: Mill vs jk (JK-1086 Phase A)

**Status:** Phase A investigation complete · **Default-on deferred**  
**Date:** 2026-07-20 · **Follow-up:** JK-1087 (within-module class-level / process-pool productization)

## Product stance (stable)

| Default | Why |
|---------|-----|
| **`-j` / jobs** = effective cores | Module graph parallel is Mill-shaped and already shipped (JK-1082 / 1084). |
| **Cross-module tests parallel** | C2: default on after C1 measure (~40% wall on `shared/*`) + module `[test] workers=1` opt-out. Use `--serial-tests` to serialize. |
| **`-w` / workers = 0 (auto)** | `min(jobs, classCount)` + `HeapPlan` clamp (Mill `testSubprocessCount`). Explicit `-w1` = serial. |
| **Opt-out first-class** | `--serial-tests` / `--no-parallel-tests`; per-module `[test] workers=1` / `parallel=false`. |

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
| Per-module workers | `-w` default **0 (auto)**; explicit `≥1`; pull-queue when W>1 | `TestWorkers` + `JUnitLauncher` |
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
- [x] Auto default `-w = min(jobs, classCount)` + `HeapPlan` clamp (`TestWorkers`, default `-w0`)  
- [x] Module serial opt-out: `[test] workers=1` / `parallel=false` (or `[build] test-workers`)  
- [x] Document sandbox contract for suite authors (ports, temp, statics) in guide  
- [ ] Optional port-range helper / documented convention for fixed-port tests  
- [x] Failure lines always include module (+ class when sharded / worker when W>1)

## Phase C checklist (cross-module defaults)

- [x] Microbench / monorepo: serial vs `-wN` vs `--parallel-tests` (wall + RSS + timeline) — see **C1 measure** below  
- [x] CI profile: pure-jk self-host uses `-j0 -w0` (+ optional explicit `--parallel-tests`) + `JK_AOT_TRAIN=off`  
- [x] Default-on cross-module parallel tests (C2) with `--serial-tests` opt-out  
- [x] Opt-out path remains first-class (`-w1`, `--serial-tests`, `[test] workers=1`)

## Explicit defer (Phase B/C)

**Defer default-on cross-module test parallel** until Phase B isolation + Phase C numbers clear the go criteria. **Raise default `-w` only after per-worker temp (and flake data) land.** Current opt-in flags stay.

### Story board (implementation order)

| # | Story | Outcome |
|---|--------|---------|
| B1 | Per-worker temp isolation (`W>1`) | Done |
| B2 | Guide: Mill-like recipes | Done (`docs/guide.md` Parallelism) |
| B3 | Auto `-w` = min(jobs, classes) + heap clamp | Done (`TestWorkers`, default `-w0`) |
| B4 | Module serial opt-out (`[test] workers=1`) | Done |
| C1 | Monorepo measure + CI profile | Done (measure + CI opt-in) |
| C2 | Default cross-module parallel tests | Done (default on; `--serial-tests` opt-out) |

## C1 measure (2026-07-22)

**Method:** `scripts/test-parallel-measure.sh` with `MODULES='shared/*'`, `EXTRA_ARGS='--no-progress --redo'`,
`JK_AOT_TRAIN=off`. Host: Darwin arm64, 12 cores. Thin client + engine jar after B4/C1 wiring
(`jk test --parallel-tests` now parses; multi-module selection runs concurrent `runTest` with the
engine test gate lifted).

| config | wall s | notes |
|--------|--------|-------|
| `-j0 -w1` | **21** | serial within-module |
| `-j0 -w0` (auto, serial modules) | **25** | auto workers; TEST_GATE still serial across modules |
| `-j0 -w0 --parallel-tests` | **15** | **~40% wall win** vs serial-modules auto |

GO wall criterion (≥20%): **met** on this suite → **C2 shipped**: cross-module parallel is the
product default; hermetic modules pin `[test] workers = 1` (e.g. `clients/cli`); full serial gate
via `--serial-tests`.

Re-run:

```bash
MODULES='shared/*' EXTRA_ARGS='--no-progress --redo' ./scripts/test-parallel-measure.sh
# fuller monorepo (matches CI filter):
MODULES='shared/*,server/io,server/resolver,server/toolchain,server/engine,clients/cli,plugins/*' \
  EXTRA_ARGS='--no-progress --redo' ./scripts/test-parallel-measure.sh
```

## Refs

- CLI: `BuildCommand` / `TestCommand` `--parallel-tests`, `-w`  
- Engine: `JUnitLauncher`, `BuildPipelines` test gate, `HeapPlan.requestedJvms`  
- Jobs: `Jobs`, `AvailableCpus` (JK-1082 / 1084)  
- Mill: https://mill-build.org/blog/11-jvm-test-parallelism.html  
- **JUnit vs `-w`:** [junit-parallel-vs-jk-workers.md](junit-parallel-vs-jk-workers.md) (JK-1092)
