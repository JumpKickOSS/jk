# Progress contract — open-loop ETA and effort-weight bar

Status: **normative** for TUI / wire progress. Implementations live under
`shared/wire` (`WorkSchedule`, `WorkspaceProgressTracker`, `RemainingWork`) and
`server/engine` (`EffortWeights`, `TestEffort`, `BuildService.seedEta`).

## Goals

1. **One aggregate** for the whole request (single module, selection subset, or monorepo).
2. **Open-loop seed `R0`** — wall-ms estimate at plan start (`jk explain` ≡ `jk build`). Users plan from the start; mid-run residual must not redefine the countdown.
3. **Effort-weight progress bar** — denominator is Σ plan step weights (measured walls preferred). Cache/skip → TOKEN. Bar **never goes backwards**.
4. **Real-work only** — bookkeeping steps do not inflate dirty set or ETA.
5. **Hierarchical learning** — test method → test class → task → stage → module → workspace.
6. **Dual clock** — countdown from open-loop `R0 − elapsed`; dim `+elapsed` count-up from command start.
7. **details.jsonl** carries fine events; the header bar/clock stay run-wide.

## Model

```
R0            = seed wall ms (jk explain ≡ jk build seed) — frozen for countdown
countdown     = max(0, R0 − elapsed)     # open-loop; never rewritten mid-run
residual R(t) = private remaining wall from schedule of unfinished work (RemainingWork)
bar (clock)   = min(99%, elapsed / (elapsed + R(t)))   # adaptive; falls back to elapsed/R0
weight slices = fallback when R0 unknown
```

**Countdown** stays pure open-loop so the seed quality KPI stays honest.

**Bar** uses residual as a *private* estimate: when work finishes faster than R0, R(t)
shrinks and the bar speeds up; when residual is larger, it slows. Formula
`elapsed / (elapsed + residual)` hits ~100% as residual → 0 without rewriting the countdown.
Cap **99%** until settle; peak hold never goes backwards.

| Situation | Seed (R0) | Bar slice |
|-----------|-----------|-----------|
| Dirty real work | priced measured walls | full effort weight |
| Cache/skip in plan | TOKEN in composition | TOKEN / reweight shrink |
| Omitted from plan | absent | absent |

### Packaging cascade (hard rule)

| If dirty… | Then dirty… |
|-----------|-------------|
| **jar** | **native** (when the module builds one) |
| **jar** or **native** | **OCI** (when the module builds an image) |

Impossible: jar dirty + native clean. Impossible: jar/native dirty + OCI clean.
Implemented in `EffortWeights.jarWillChange` / `nativeWillChange` / `ociWillChange` and
`TaskForecaster` native forecast.

Dirty-module prepare/run sets **over-reserve tails** so native/assembly/OCI reserve full
learned walls at plan-start even when an old binary still looks mtime-fresh vs the pre-build
jar. Runtime may **shrink** on cache hit (`RESTORE`); never reweight *up* mid-run.

### HARD INVARIANT: `jk explain` ≡ `jk build` seed `R0`

| Rule | Detail |
|------|--------|
| **One function** | Both call `BuildService.estimateEtaMillis` only for `R0`. |
| **One forecast** | Costs from `TaskForecaster` / `ExplainPlan` only. |
| **Material dirty only** | A module is dirty only if a *material* step (compile/test/package/native/…) is not CACHED — not parse-build / resolve-deps / write-stamp bookkeeping. Resource drift is material: the forecaster emits `copy-resources` (main/extra) or `copy-test-resources` (test scope) only when trees actually drifted, and either schedules the module. Compile-consumer cascade seeds from **compile/package** only (not `copy-resources` alone): consumers hash the packaged jar; package is forecast against a post-copy projection when resources drifted. A dirty `order-after`-only prereq adds an unpriced `order-check` task: the dependent schedules (real action keys re-check out-of-band outputs) but contributes nothing to ETA. |
| **Price material steps only** | ETA costs skip bookkeeping steps even when the plan still runs them. Cascade-forced compile/package/**native** (`dependency changed` / `main changed` / `compile changed` without local *compile* content) are recheck tokens, not suite/native walls. Resource-only modules (copy/package resources) price package+copy only — never unlock compile/test suite walls. `run-tests` stays full when the module has local compile content, no compile steps (test-dep only), or a heavy packaging tail forecast (cli-shaped); pure cascade modules discount tests. |
| **Same concurrency** | `etaConcurrency(...)` matches workspace scheduler clamp. |
| **Open-loop clock** | Client freezes `R0` when execute starts; residual ETA events are not applied mid-run. |
| **Seed quality KPI** | `|R0 − execute_wall| / execute_wall` on success (`jk: eta-seed quality …` when serious or `JK_ETA_SEED_LOG=1`). |
| **Fully-cached fast path** | Empty dirty → `R0 = 0`, skip forecast walk. |

### Schedule admission (ETA ≡ live)

`WorkSchedule` admits **first ready in topo/list order**, full prereq completion, at most
`concurrency` in flight — same policy as bounded `WorkspaceScheduler` (not longest-first).

Serial (`-j1` / concurrency ≤ 1): sum of module weights.  
When `parallelTests == false`: `max(scheduled, Σ testWeight)` as serial test floor.

## Engine owns aggregate math

| Component | Responsibility |
|-----------|----------------|
| `EffortWeights` | Plan-time task weights; TOKEN for skip; packaging cascade; dirty over-reserve |
| `TestEffort` | `run-tests` pricing (own suite wall before method product) |
| `WorkSchedule` / `RemainingWork` | Wall schedule + residual annotation `R(t)` |
| `WorkspaceProgressTracker` | Preflight band + calibrated effort-weight bar; peak hold (never backwards) |
| `BuildService` | `R0` seed; dirty prepare uses live `estimatedTotalWeight` (not stale shape-memo) |
| Wire `workspace-progress` / `eta` | Snapshots: `progress`, `numerator`, `denominator`, `phase`, `remainingMs`, `R0` |

## Task pricing ladder

1. Module measured task wall  
2. Host task wall  
3. Residual per-unit rates × count  
4. Host continuous learned rates / calibration × host scale  
5. Tight static floors  

### Test task (`run-tests`)

1. **This module’s** whole `run-tests` task wall (preferred)  
2. Class walls when complete selection has walls  
3. Methods × hierarchical method-ms + suite-startup (cold module with known count)  
4. Host suite wall only when method count is unknown  
5. Cold baseline  

### Metrics hygiene

- `AggregatedMetrics.loadAll` / harvest use **one project home per checkout path** (prefer
  `source=lock`, higher run count).  
- Merge-by-higher-count when keys still collide.  
- Session workspace loads project-scoped metrics for live ETA.  
- **Heavy-step floors:** native-image &lt; 5s and write-image &lt; 3s walls are dropped at journal
  write and harvest (cache-restore noise).  
- Cancelled / failed builds do not train.

## Progress bar

Shared strategies (`cc.jumpkick.runtime.progress` in `:wire`) — used by **engine
workspace-progress**, **CLI header**, and **web dashboard**:

| Mode | Strategy | When |
|------|----------|------|
| **clock** (default via AUTO) | `ClockProgressStrategy` — `elapsed/(elapsed+R(t))` when residual known, else `elapsed/R0` | R0 &gt; 0 (AUTO) or force clock |
| **weighted** | `WeightedProgressStrategy` — Σ effort weights | R0 absent (AUTO) or force weighted |

```bash
JK_PROGRESS_MODE=clock      # force open-loop (engine + CLI)
JK_PROGRESS_MODE=weighted   # force weight slices
# unset / auto              # clock when R0 > 0, else weighted
```

Web override (browser cannot read process env): `localStorage.jkProgressMode = 'clock'|'weighted'|'auto'`.

- Engine emits `progress` from the strategy on every `workspace-progress` event.  
- Web also recomputes clock client-side from `R0` + `r0At` so the bar stays smooth between events.  
- **Never go backwards** — one `SharedPeak` fraction floor per clock/weighted pair (JK-1815), so
  neither the AUTO weighted→clock takeover nor a denominator growth (calibrate/reweight) repaints
  lower; the web clamps per card (`card.peakPct`). Settle → 100%.

## Wire (schema 1, additive fields)

| Event | Fields |
|-------|--------|
| `eta` | `millis` (= remaining), `remainingMs`, `R0` |
| `workspace-progress` | `progress`, `numerator`, `denominator`, `phase`, `modulesComplete`, `modulesTotal`, `remainingMs`, `R0` |

## Learning layout

```
~/.local/state/jk/builds/
  host-metrics.toml
  projects/<id>/
    identity.toml
    project-metrics.toml
    runs/<build-number>/
      record.json
      details.jsonl
      metrics.toml
```

## Out of scope

- Method-level live TUI rows  
- Perfect millisecond ETA  
- Critical-path / remaining-work bar (Σ weights is the product bar)  
- Third-party metrics backends  
- Allowing the bar to go backwards  
