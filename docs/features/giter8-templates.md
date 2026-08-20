# Giter8 templates for `jk new` / `jk init`

One format. Catalog templates, plugin-bundled kinds, local paths, and git refs all apply through
the engine Giter8 renderer.

## CLI

```text
jk new -t <ref> [name]
jk new --template <ref> [name]
jk init -t <ref>
jk new -t spring-boot --lang kotlin --kind default my-api
jk new -t cli my-tool
jk new -t cli --lang kotlin my-tool
jk new -t ktor-3 my-svc
jk new --param key=value          # repeatable; non-interactive props
```

`-t` / `--template` is exclusive with `--plugin`. Blank `jk new` (no `-t`) is still the wizard /
flag scaffolder for a plain library or `--executable` app.

`--lang` defaults among directories that exist: **java**, else **kotlin**, else **groovy**.
`--kind` defaults to `default` and is only valid for plugin-bundled templates.

## Layout: `<lang>/<kind>`

Catalog (`jk-templates` and in-tree dogfood `templates/`):

```
java/cli.g8
kotlin/cli.g8
kotlin/ktor-3.g8
groovy/grails-8.g8
…
```

Plugin jars:

```
src/main/resources/templates/<lang>/<kind>/
  default.properties
  src/main/g8/…
```

Each kind directory is a Giter8 template root. The template owns the whole tree, including
`jk.toml`. There is no layout remapping at apply time.

## Resolution (`-t <name>` when `<name>` is a short id)

1. Installed plugin whose `id` or `table` equals `<name>` and whose jar contains `templates/` →
   `templates/<lang>/<kind>/`
2. Catalog / local / git short name → `<lang>/<name>.g8`
3. Local path, `owner/repo`, or git/HTTPS URI (unchanged)

Plugin wins on name collision (`-t quarkus` is the plugin hello-app when the quarkus plugin is
installed). Richer catalog kinds keep distinct names (`spring-boot-webmvc`, `cli`, `ktor-3`).

### Official templates repo

First-party catalog content lives in **[JumpKickOSS/jk-templates](https://github.com/JumpKickOSS/jk-templates)**
(overridable via config). The **engine** freshens the shallow clone on `jk new`/`jk init` for
short names. The native CLI does not apply templates or ship ST4.

### Third-party sources (`~/.config/jk/config.toml`)

```toml
[templates]
official = "https://github.com/JumpKickOSS/jk-templates"

[templates.sources]
acme = "https://github.com/acme/jk-g8"
corp = { url = "https://git.example/corp/jk-templates.git", rev = "main" }
```

```bash
jk new -t my-starter --template-source https://github.com/acme/jk-g8
```

## Apply language

Engine-hosted Giter8 (StringTemplate 4 + Giter8 extensions): `$key$`, `$name;format="Camel"$`,
path `$name__Camel$`, `$if(x.truthy)$` / `$else$` / `$endif$` in content and paths, `.` flatten,
`verbatim`, `$! comments !$`, property-to-property defaults, `maven(group, artifact[, stable])`.
Writes stay under the project directory; template symlinks are skipped. Output is not wrapped in
an extra `$name$` directory.

## Catalog kinds (first-party)

| Kind | Languages | Intent |
|------|-----------|--------|
| `cli` | java, kotlin | Simple executable (Mill SIMPLE layout) |
| `cli-native` | java | Interactive Java CLI with JLine |
| `spring-boot-webmvc` | java, kotlin | Boot WebMVC + JPA/H2 + Actuator |
| `spring-boot-mcp` | java | Boot MCP server |
| `quarkus` | java | Catalog REST app (plugin `-t quarkus` wins when installed) |
| `ktor-3` | kotlin | Ktor + Koin + Exposed |
| `micronaut` | java, kotlin | Micronaut HTTP service |
| `grails-8` | groovy | Grails 8 REST |

Plugin hello-apps: `-t spring-boot`, `-t quarkus`, `-t micronaut`, `-t grails`.
Plugin kinds (Spring Boot): `-t spring-boot --kind default` (hello app) and
`-t spring-boot --kind webmvc` (clean-architecture notes: Java/JPA or Kotlin/JOOQ).

## Non-goals

- sbt `g8Scaffold` into an existing repo
- Public template marketplace
