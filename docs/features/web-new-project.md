# Web UX: create new project

**Implemented.** Engine `POST /api/projects` + `GET /api/templates`; dashboard **New project**
modal. Scaffold path is shared with CLI via `cc.jumpkick.scaffold.NewScaffolder` (in `:core`) and
engine Giter8 apply. CLI `jk new` remains the full-featured entry (`-t` / `--template`, remote
Giter8 git).

## UX

1. Dashboard empty state / “New project”
2. Fields: name, group, language, **framework** (typeahead filter), **template** (typeahead),
   layout when the selection is blank or dual-layout
3. **Layout (traditional/simple)** for the blank scaffolder and for templates whose
   `.jk-template.toml` lists both layouts. Hidden when `layouts` is only `custom` (Grails).
   Layout here is only where files land; there is no `layout` key in `jk.toml`.
4. Parent directory picker (engine-local filesystem roots only)
5. Progress: scaffold → optional `jk lock` → open project

`GET /api/templates` is a JSON array of `{ id, name, language, framework, description, layouts,
source, pluginId? }`. Plugin-bundled and catalog trees are the same list. Language and framework
are filters: **All frameworks** shows every template for the language; **none** shows only
unframed templates.

Modal defaults come from `GET /api/projects/defaults` (`{ group, parentDir }` — group from git
email like `jk new`, parent from build history / well-known roots). The endpoint derives from the
engine owner's git identity and home layout, so like `/api/fs` and `/api/log` it requires the
bearer token even on loopback; a tokenless session falls back to blank/`com.example` defaults.

## Backend

| Piece | Shape |
|-------|--------|
| Wire | `new-project` JSONL / HTTP `POST /api/projects` with body `{ name, group, lang, layout, template?, parentDir }` |
| Engine | Shared scaffolder (`NewScaffolder` for blank; engine Giter8 apply for `-t`) |
| Auth | Same bearer as dashboard; only paths under configured roots |
| Response | `{ path, jid? }` + SSE progress optional |

```text
jk new my-app --lang java
jk new my-app --lang java --layout simple
jk new -t hello my-app
jk new -t spring-boot/webmvc --layout simple my-api
```

## Acceptance mapping

| Criterion | State |
|-----------|--------|
| E2E create from web | Modal → `POST /api/projects` → open project + build |
| Template picker | Ids via `GET /api/templates`; language + framework filters |
| Errors | 400/409 with `error` message (exists, invalid name, parent outside home) |
| Shared scaffold path | `cc.jumpkick.scaffold.*` used by CLI + engine |
| Plugin / catalog templates | Same picker rows (`java/spring-boot/hello`, `java/none/cli`, …) |
| Remote Giter8 short names | Engine clones git refs and freshens the official catalog |
