# Web UX: create new project (JK-1193)

## Status

**Implemented (JK-1193).** Engine `POST /api/projects` + `GET /api/templates`; dashboard
**New project** modal. Scaffold path is shared with CLI via `cc.jumpkick.scaffold.NewScaffolder`
(in `:core`). CLI `jk new` remains the full-featured entry (`-t` / `--template`, remote Giter8 git).

## UX

1. Dashboard empty state / “New project”
2. Fields: name, group, language, optional template short name, kind (when the template is a plugin with extra kinds)
3. **Layout (traditional/simple) + Executable** only when Template is “None” (blank scaffold).
   Layout here is only where the scaffolder writes files; there is no `layout` key in `jk.toml`.
   A selected Giter8 template owns its tree (`jk_layout`); the layout control is hidden and a
   read-only “Template layout: …” note is shown instead. See [giter8-templates.md](giter8-templates.md).
4. Parent directory picker (engine-local filesystem roots only)
5. Progress: scaffold → optional `jk lock` → open project

Template list is filtered by Language (`jk_languages` / catalog). Catalog is merged with local
template roots via `Giter8TemplateIndex`.

Modal defaults come from `GET /api/projects/defaults` (`{ group, parentDir }` — group from git
email like `jk new`, parent from build history / well-known roots). The endpoint derives from the
engine owner's git identity and home layout, so like `/api/fs` and `/api/log` it requires the
bearer token even on loopback; a tokenless session falls back to blank/`com.example` defaults.

## Backend (to implement)

| Piece | Proposal |
|-------|----------|
| Wire | `new-project` JSONL / HTTP `POST /api/projects` with body `{ name, group, lang, layout, template?, parentDir }` |
| Engine | Call shared scaffolder (`NewScaffolder` for blank; engine Giter8 apply for `-t`) |
| Auth | Same bearer as dashboard; only paths under configured roots |
| Response | `{ path, jid? }` + SSE progress optional |

Until the RPC exists, the UI may deep-link to CLI instructions:

```text
jk new my-app --lang java
jk new my-app --lang java --layout simple
jk new -t cli my-tool
```

## Acceptance mapping

| Criterion | State |
|-----------|--------|
| E2E create from web | **Done** — modal → `POST /api/projects` → open project + build |
| Template picker | Short names via `GET /api/templates`; local/classpath apply on engine |
| Errors | 400/409 with `error` message (exists, invalid name, parent outside home) |
| Shared scaffold path | **Done** — `cc.jumpkick.scaffold.*` used by CLI + engine |
| Plugin / catalog templates from web | Template picker (`spring-boot`, `cli`, …) + language |
| Remote Giter8 short names | Engine clones git refs and freshens the official catalog |
