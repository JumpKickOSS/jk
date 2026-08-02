# Web UX: create new project (JK-1193)

## Status

**Design frozen; engine RPC not yet wired.** CLI scaffolding (`jk new` / Giter8 host JK-1182+)
is the source of truth. Web should call the same backend — no second scaffolder.

## UX

1. Dashboard empty state / “New project”
2. Fields: name, group, language, layout (simple/traditional), optional template short name or git URI
3. Parent directory picker (engine-local filesystem roots only)
4. Progress: scaffold → optional `jk lock` → open project

## Backend (to implement)

| Piece | Proposal |
|-------|----------|
| Wire | `new-project` JSONL / HTTP `POST /api/projects` with body `{ name, group, lang, layout, template?, parentDir }` |
| Engine | Call shared scaffolder (extract from CLI `NewScaffolder` / `Giter8LocalApply` into engine-callable API) |
| Auth | Same bearer as dashboard; only paths under configured roots |
| Response | `{ path, jid? }` + SSE progress optional |

Until the RPC exists, the UI may deep-link to CLI instructions:

```text
jk new my-app --lang java --layout simple
jk new --template java-cli my-tool
```

## Acceptance mapping

| Criterion | State |
|-----------|--------|
| E2E create from web | Blocked on engine RPC |
| Template picker | Catalog short names from `Giter8Catalog` + config sources (JK-1380) |
| Errors | dir exists, invalid name, template fetch failed |
| Shared scaffold path | Required — extract when implementing RPC |
