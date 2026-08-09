# Giter8 templates for `jk new` / `jk init` (JK-1181+)

## Product split (JK-1195)

| Path | Use for |
|------|---------|
| Interactive wizard + flags (`--spring`, `--quarkus`, `--grails`, …) | **Simple** single-module (and plugin-scaffold) projects |
| **Giter8** (`jk new --template …`) | Local / catalog templates; later community multi-module starters |

Giter8 does **not** replace the wizard for “hello app” cases. Plugin-declared `[scaffold]`
remains the built-in path for Spring / Grails / Quarkus flags. The **`quarkus`** short name
is an alternate G8 shape of the same single-module app (not a multi-module workspace).

### Official templates repo

First-party Giter8 content lives in **[jkbuild/jk-templates](https://github.com/jkbuild/jk-templates)**
(overridable via config). Layout: monorepo with nested `*.g8` directories (or `templates/*.g8`).

In-tree `templates/*.g8` is **dev dogfood** (walk-up from cwd, not bundled). Production templates
come from the shallow-cloned `jk-templates` cache: `install.sh` clones it preemptively, and the
**engine** re-freshens it (fetch + hard reset, or a clean re-clone if that fails) on every `jk
new`/`jk init` that resolves a built-in short name — the CLI never touches the network for this
itself, it only reads the cache the engine just refreshed.

### Third-party sources (`~/.config/jk/config.toml`)

```toml
[templates]
# optional; default is https://github.com/jkbuild/jk-templates
official = "https://github.com/jkbuild/jk-templates"

[templates.sources]
acme = "https://github.com/acme/jk-g8"
corp = { url = "https://git.example/corp/jk-templates.git", rev = "main" }
```

Short names are looked up in each source (nested `name.g8` / `templates/name.g8`) after local
paths and before the official monorepo. One-shot CLI sources:

```bash
jk new --template my-starter --template-source https://github.com/acme/jk-g8
```

Complex multi-module dogfood also lives in **[jk-examples](https://github.com/jkbuild/jk-examples)** (e.g. `spring-boot/petshop`, `kotlin/ktor-petshop`).

## CLI surface

```text
jk new --template <ref> [name]
jk init --template <ref>        # same generator; init semantics for cwd
jk new --template <ref> --param key=value   # non-interactive props (repeatable)
```

`--template` is mutually exclusive with `--spring` / `--grails` / `--quarkus` / `--plugin`.

## `<ref>` resolution (shipped)

| Order | Form | Status |
|------:|------|--------|
| 1 | **Local path** — directory or `…/template.g8` with `src/main/g8/` (or G8 root layout) | **Shipped** (JK-1182) |
| 2 | **Short name** — `$JK_TEMPLATES`, `~/.jk/templates`, walk-up dogfood, **config sources**, **official [jk-templates](https://github.com/jkbuild/jk-templates)** (engine-refreshed shallow clone) | **Shipped** (JK-1380; requires `git` for remote) |
| 3 | **GitHub shorthand** — `owner/repo` or `owner/repo.g8` | **Shipped** (JK-1203; requires `git`) |
| 4 | **Full git/HTTPS URI** — optional `#branch` or `@tag` | **Shipped** (JK-1203; requires `git`) |

Invalid refs fail before any files are written.

### Short-name resolution (local catalog)

When `<ref>` is a known short name (not a path):

1. `$JK_TEMPLATES/<name>.g8`
2. `~/.jk/templates/<name>.g8`
3. Walk up from cwd for `templates/<name>.g8` (monorepo dogfood)
4. Official `jk-templates` shallow clone cache — the engine freshens this on-demand before the
   CLI reads it (see [Official templates repo](#official-templates-repo))

| Name | Intent |
|------|--------|
| `java-cli` | Simple Java 25 executable (Mill SIMPLE layout) |
| `kotlin-cli` | Simple Kotlin executable (Mill SIMPLE layout) |
| `quarkus` | Quarkus 3.x REST app (`[quarkus]` plugin, plain `Application` main, `@QuarkusTest`) |

```bash
jk new --template quarkus my-api
jk new --template java-cli my-tool
jk new --template kotlin-cli my-kt
jk new --template /path/to/local.g8 other
jk new --template owner/cool-g8 my-app          # GitHub shorthand (JK-1203)
jk new --template https://github.com/org/t.g8.git#main
```

## Props

- Read `default.properties` from the template root.
- Map known keys: `name`, `organization`/`group`, `package`, `jdk`/`java_version`,
  `quarkus_version` → JumpKick conventions (`group`, `name`, `jdk`, `java`, `[quarkus]`).
- Interactive prompts only when stdin is a TTY and a prop is missing (Mill-like).
- `--param` / env overrides win over defaults.

### JumpKick metadata props (picker / catalog)

| Key | Values | Purpose |
|-----|--------|---------|
| `jk_languages` | `java`, `kotlin`, `groovy` (comma-separated) | Which Language the template is built for (web + catalog filter) |
| `jk_layout` | `simple` \| `traditional` \| `custom` | Layout the template **ships** (not a user override at apply time) |

Official short names also carry the same fields in the built-in catalog
(`Giter8ShortNames` / `GET /api/templates`). On-disk props win when present
(`Giter8TemplateIndex`).

### Layout vs Giter8 templates

**Simple / traditional is a blank-scaffolder choice only** (`jk new` without `--template`, or
web “None — blank project”). A Giter8 apply copies a fixed tree — JumpKick’s apply path has no
conditionals, so we do **not** reshape a template to match the user’s layout radio.

| Approach | Verdict |
|----------|---------|
| Dynamic layout inside one `.g8` | Needs full Giter8 conditionals (not shipped); dual trees would be two templates |
| Rewrite dirs after apply | Fragile for framework trees (Spring `src/main/…`, Grails `grails-app/`) |
| **Hide layout when a template is selected** | **Shipped policy** — template owns its tree; blank projects keep the control |

`jk_layout=custom` marks framework-specific trees (e.g. Grails) that are neither Mill simple nor
Maven traditional.

## Implementation (shipped vs design)

| Constraint | Shipped today | Longer-term design |
|------------|---------------|--------------------|
| Native Graal CLI stays thin | **Pure-Java** `$key$` apply on the client (`Giter8LocalApply`) — no full Giter8 library | Optional engine-hosted worker for full Giter8 |
| Template apply | Local + short name (official monorepo + `[templates.sources]` + `--template-source`) + git URI | Full Giter8 conditionals/includes |
| Conditionals / includes | Not supported | If/when full Giter8 worker lands |

Monorepo sources: `templates/<name>.g8/` (dogfood). There is no bundled classpath copy — every
non-local, non-dogfood short name resolves through the engine-refreshed `jk-templates` cache.

## Coexistence with plugin scaffolds

- `jk new --spring` / `--grails` / `--quarkus` use baked `[scaffold]` manifests on the plugin.
- `jk new --template quarkus` produces a comparable single-module tree via G8.
- Prefer wizard/flags for “simple app”; prefer `--template` when you already have a local `.g8`
  or a catalog short name.

## Non-goals (near term)

- Full Giter8 feature matrix (SBT plugin interop, nested includes).
- Public template marketplace UI.
- Replacing `jk new --plugin` authoring scaffold.
- Git remote short names (tracked as JK-1203).
