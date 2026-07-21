# ticket-1014 — IDE clients over engine protocol

**Priority:** P2 (v1.0 target area)  
**Status:** done  
**Branch:** `ticket-1014-ide-engine-client`  
**Refs:** [architecture.md](../architecture.md) (IDE integration sequence),
`cc.jumpkick.cli.ide.IdeEngineClient`, `IdeEngineClientTest`

## Shipped (phase 1)

1. **`IdeEngineClient`** facade: `open` → `connect` → `projectInfo` → `sync` → `ideModel` / `build`
2. **Progress callbacks** (`ProgressListener` / `BuildListener`) — no CLI stdout parsing
3. Reuses frozen wire verbs via `EngineClient`; in-process seam under `jk.test.noEngine`
4. Automated tests in `:cli-engine` proving callbacks fire
5. Architecture docs: integration sequence; file generation remains export path

## Acceptance

- [x] Documented sequence in architecture.md
- [x] Facade API + automated test with in-process engine
- [x] Progress/status via callbacks
- [x] No new wire message types
- [x] `./gradlew :cli:compileJava :cli-engine:test` green for touched modules

## Follow-ups

- ticket-1017 marketplace IDE plugins
- Full workspace build over wire may need a non-null `JkBuild` on some paths — exercise with a
  live engine in a later ticket if needed
