# Web UX: create new project (JK-1193)

## Status

**Implemented (JK-1193).** Engine `POST /api/projects` + `GET /api/templates`; dashboard
**New project** modal. Scaffold path is shared with CLI via `cc.jumpkick.scaffold.NewScaffolder`
(in `:core`). CLI `jk new` remains the full-featured entry (framework flags, remote Giter8 git).

## UX

1. Dashboard empty state / “New project”
2. Fields: name, group, language, optional template short name
3. **Layout (simple/traditional) + Executable** only when Template is “None” (blank scaffold).
   A selected Giter8 template owns its tree (`jk_layout`); the layout control is hidden and a
   read-only “Template layout: …” note is shown instead. See [giter8-templates.md](giter8-templates.md).
4. Parent directory picker (engine-local filesystem roots only)
5. Progress: scaffold → optional `jk lock` → open project

Template list is filtered by Language (`jk_languages` / catalog). Catalog is merged with local
template roots via `Giter8TemplateIndex`.

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
| E2E create from web | **Done** — modal → `POST /api/projects` → open project + build |
| Template picker | Short names via `GET /api/templates`; local/classpath apply on engine |
| Errors | 400/409 with `error` message (exists, invalid name, parent outside home) |
| Shared scaffold path | **Done** — `cc.jumpkick.scaffold.*` used by CLI + engine |
| Framework scaffolds from web | Deferred — use CLI `jk new --spring` (clear error if attempted) |
| Remote Giter8 short names | Engine uses local/classpath; full git resolve remains CLI (`Giter8Git`) |
