# Giter8 templates for `jk new` / `jk init` (JK-1181+)

## Product split (JK-1195)

| Path | Use for |
|------|---------|
| Interactive wizard + flags (`--spring`, `--quarkus`, `--grails`, …) | **Simple** single-module (and plugin-scaffold) projects |
| **Giter8** (`jk new --template …`) | Local / catalog templates; later community multi-module starters |

Giter8 does **not** replace the wizard for “hello app” cases. Plugin-declared `[scaffold]`
remains the built-in path for Spring / Grails / Quarkus flags. The **`quarkus`** short name
is an alternate G8 shape of the same single-module app (not a multi-module workspace).

Complex multi-module dogfood lives in **jk-examples** (e.g. `java/quarkus-petshop`), not in
first-party G8 yet.

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
| 2 | **Short name** — catalog entry (`java-cli`, `kotlin-cli`, `quarkus`, …) | **Shipped** (JK-1183) |
| 3 | **GitHub shorthand** — `owner/repo` or `owner/repo.g8` | **Shipped** (JK-1203; requires `git`) |
| 4 | **Full git/HTTPS URI** — optional `#branch` or `@tag` | **Shipped** (JK-1203; requires `git`) |

Invalid refs fail before any files are written.

### Short-name resolution (local catalog)

When `<ref>` is a known short name (not a path):

1. `$JK_TEMPLATES/<name>.g8`
2. `~/.jk/templates/<name>.g8`
3. Walk up from cwd for `templates/<name>.g8` (monorepo dogfood)
4. Classpath bundle shipped in the CLI (`giter8/<name>/…`)

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

## Implementation (shipped vs design)

| Constraint | Shipped today | Longer-term design |
|------------|---------------|--------------------|
| Native Graal CLI stays thin | **Pure-Java** `$key$` apply on the client (`Giter8LocalApply`) — no full Giter8 library | Optional engine-hosted worker for full Giter8 |
| Template apply | Local path + short name + classpath + **git clone** into `~/.jk/cache/templates/` | Full Giter8 conditionals/includes |
| Conditionals / includes | Not supported | If/when full Giter8 worker lands |

Monorepo sources: `templates/<name>.g8/` (dogfood) and
`clients/cli/src/main/resources/giter8/<name>/` (install bundle).

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
