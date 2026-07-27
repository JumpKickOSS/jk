# Giter8 templates for `jk new` / `jk init` (JK-1181)

## Product split (JK-1195)

| Path | Use for |
|------|---------|
| Interactive wizard + flags (`--spring`, `--quarkus`, …) | **Simple** single-module (and plugin-scaffold) projects |
| **Giter8** (`jk new --template …`) | **Complex** multi-module workspaces and community starters |

Giter8 does **not** replace the wizard for “hello app” cases. Over time, first-party framework
flags may become thin wrappers that apply a pinned G8 template, but plugin-declared
`[scaffold]` remains the built-in path for Spring/Grails/Quarkus until those templates exist.

## CLI surface

```text
jk new --template <ref> [name]
jk init --template <ref>        # same generator; init semantics for cwd
jk new --template <ref> --param key=value   # non-interactive props (repeatable)
```

**`<ref>` resolution order**

1. **Local path** — directory or `…/template.g8` containing `src/main/g8/` (or Giter8 root layout).
2. **Short name** — catalog entry (`java-cli`, `kotlin-cli`, …) → fixed git URI under `jkbuild/*-g8`.
3. **GitHub shorthand** — `owner/repo` or `owner/repo.g8` (Giter8 convention).
4. **Full git/HTTPS URI** — including optional `#branch` / `@tag`.

Invalid refs fail before any files are written.

## Props

- Read `default.properties` from the template.
- Map known keys: `name`, `organization`/`group`, `package`, `jdk`/`java_version` → JumpKick
  conventions (`group`, `name`, `jdk`, `java`).
- Interactive prompts only when stdin is a TTY and a prop is missing (Mill-like).
- `--param` / env overrides win over defaults.

## Hosting model (decision)

| Constraint | Choice |
|------------|--------|
| Native Graal CLI must stay thin | **No** Giter8 library on the client classpath |
| Templates need JVM apply | **Engine-hosted** generator (`generate` protocol, like plugin scaffold) |
| Isolation | Apply in a **forked worker** (`jk-giter8` or reusable template worker), not the engine heap |

Flow:

1. Client resolves `<ref>` → local cache path (git clone/fetch into `~/.jk/cache/templates/…`).
2. Client sends `generate` with `kind=giter8`, template root, props, dest dir.
3. Worker runs Giter8 (or a compatible pure-Java subset: `default.properties` + `$name$` replace).
4. Client receives file list or writes paths already produced under dest (same pattern as
   `ScaffoldOps`).

**MVP implementable subset (JK-1182):** local path + git HTTPS clone + string props only (no
conditional `src/main/g8` includes unless already supported by the chosen Giter8 version).

## Catalog (JK-1183)

Ship short names in-repo or as data:

| Name | Intent |
|------|--------|
| `java-cli` | Simple Java 25 executable (Mill SIMPLE layout) |
| `kotlin-cli` | Simple Kotlin executable |

Complex multi-module examples stay in `jk-examples` and optional `workspace-*` G8 templates later.

## Coexistence with plugin scaffolds

- `jk new --spring` / `--grails` / `--quarkus` continue to use baked `[scaffold]` manifests.
- `--template` is mutually exclusive with framework flags and `--plugin`.
- Documentation points “simple app” → wizard/flags; “workspace from community” → `--template`.

## Non-goals (this design)

- Full Giter8 feature matrix on day one (SBT plugin interop, nested includes).
- Public template marketplace UI.
- Replacing `jk new --plugin` authoring scaffold.
