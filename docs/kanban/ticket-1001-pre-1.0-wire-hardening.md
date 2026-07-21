# ticket-1001 — Pre-1.0 wire protocol + CLI freeze

**Priority:** P0 (1.0 contract)  
**Status:** done  
**Branch:** `ticket-1001-wire-hardening`  
**Refs:** [architecture.md](../architecture.md) (wire freeze vocabulary),
`shared/wire/.../EngineProtocol.java`, `EngineProtocolTest`, `CommandDispatchTest`

## Problem

Client↔engine JSONL and a few CLI edges still had dual field spellings and regression risk
around auth, flags, and materialization. Freezing the vocabulary before 1.0 avoids a permanent
compat tax.

## What landed

### Already solid (locked with regression tests)

| Item | Evidence |
|---|---|
| `--version` not colliding with subcommands | `CommandDispatch.withGlobals` fail-loud + `CommandDispatchTest.no_registered_command_option_collides_with_a_global`; `self update` / `wrapper` use positional `version`; `add` uses `--ver` |
| Engine→engine TCP auth envelope | `EngineServer.openClient` + `EngineProtocol.auth`; `EngineServerTest` / `EngineTcpTransportTest` |
| Delegation child stderr drain | Engine spawn `redirectErrorStream(true)` + log file (no pipe backpressure) |
| `install.sh` CAS materialize | `jk self materialize` for local dists (CAS then versions/) |
| Repeatable `--variant` / `--with` | `.repeat()` on opts; `VariantSelectionTest` + `ArgParserTest.repeatableOption` |
| Atomic repo materialize | `RepoArtifactStore.materialize` `.part` + `AtomicWrites.moveInto`; `RepoArtifactStoreTest` |
| P1 freeze vocabulary | `pipeline-finish.kind`, unified `error`, `dir`, `withSession`, bounded line reader, `proto`/`purpose` — covered in `EngineProtocolTest` |

### Fixes in this ticket

- Wire: `provision-request` project path field renamed **`projectDir` → `dir`** (last dual spelling)
- Docs: [architecture.md](../architecture.md) **Wire freeze vocabulary** table
- Restore missing `shared/plugin-sdk/.../plugin/build/*` (cutover omission; unblocked compile)
- Repair comment-pass syntax breaks (`GlobalConfig` unclosed Javadoc, `MinimalToml` illegal `\u`, stray `*/`)

## Acceptance

- [x] P0 correctness items closed with automated tests (install.sh CAS path is the client materialize seam)
- [x] Hosted-verb wire path: project directory field is always `dir`
- [x] Focused `:engine` / `:client-io` / `:cli-engine` tests green for touched areas
- [x] Short “1.0 freeze vocabulary” note in architecture.md

## Out of scope (unchanged)

- Public multi-version engine protocol
- IDE clients (ticket-1014)
