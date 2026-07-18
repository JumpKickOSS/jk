# ticket-1009 — `jk explain` / why-rebuilt hero UX

**Priority:** P1 (product differentiation)  
**Status:** ready  
**Branch:** `ticket-1009-explain-ux`  
**Refs:** [guide.md](../guide.md), `clients/cli/.../command/ExplainCommand.java`,
engine explain / forecast (`EngineProtocol` explain/forecast requests),
`ActionKey.snapshotInputs`, `JavaIncrementalCompile.predict`

## Problem

`jk explain` already forecasts cache hit/miss and ETA, and `why-rebuilt` aliases to it. Offline
“what will run / why rebuild?” is still not the **default mental model** in help/docs, and
per-step **input key diffs** (which file/token flipped) may be shallow vs the PRD showcase.

## Scope

1. **Discoverability:** help text + guide diagnostics section: rebuild questions → `jk explain`
   (not Gradle build scans). Confirm `why-rebuilt` remains a documented alias.
2. **Depth:** when a compile step is dirty, surface **why** using existing input snapshots /
   predict path (at least: source path changed, release/options, classpath entry). Prefer reusing
   engine forecast data over a new protocol.
3. **Fixture test:** one-source edit → explain/forecast marks only that module’s compile dirty
   (or clearly lists the dirty step); unchanged modules stay cache-hit / skipped.
4. **No new product doc file** — edit guide + command description only.

## Acceptance

- [ ] Automated fixture (cli-engine or engine test) for single-source dirty forecast
- [ ] `jk explain --help` / guide mention explain as the rebuild diagnostic
- [ ] `why-rebuilt` still works as alias
- [ ] Does not require network

## Out of scope

- Full build-scan SaaS
- Historical “compare to last CI run on another machine” without shared cache (1012)
