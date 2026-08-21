# Giter8 templates for `jk new` / `jk init`

One format. Catalog templates, plugin-bundled trees, local paths, and git refs all apply through
the engine Giter8 renderer. Language and framework are **filters**. The leaf is a named template.

## CLI

```text
jk new -t <ref> [name]
jk new --template <ref> [name]
jk init -t <ref>
jk new -t hello my-app
jk new -t hello --lang kotlin my-app
jk new -t spring-boot/hello my-api
jk new -t grails/hello my-svc
jk new -t spring-boot/webmvc --layout simple my-api
jk new --param key=value          # repeatable; non-interactive props
```

`-t` / `--template` is exclusive with `--plugin`. Blank `jk new` (no `-t`) is still the wizard /
flag scaffolder for a plain library or `--executable` app.

`--lang` defaults to **java**. For a `framework/name` ref, a miss on that language walks
java → kotlin → groovy until a hit (`jk new -t grails/hello` lands on groovy).

## Layout: `<lang>/<framework>/<name>.g8`

Catalog (`jk-templates` and in-tree dogfood `templates/`):

```
java/none/cli.g8
java/spring-boot/mcp.g8
kotlin/none/ktor-3.g8
…
```

Plugin jars:

```
src/main/resources/templates/<lang>/<framework>/<name>.g8/
  .jk-template.toml
  default.properties
  src/main/g8/…
```

`none` is the framework bucket for templates that are not a framework (cli, ktor-3, …).
Each tree is a Giter8 template root. The template owns the whole tree, including `jk.toml`.

### `.jk-template.toml`

```toml
language = "java"
framework = "spring-boot"   # or "none"
name = "hello"
description = "Minimal Spring Boot application"
layouts = ["traditional", "simple"]   # omit → both; Grails: ["custom"]
```

Path is source of truth; the file must match `<lang>/<framework>/<name>`.

## Resolution (`-t <ref>`)

| Invocation | Resolves to |
|---|---|
| `jk new -t hello` | `java/none/hello` |
| `jk new -t hello --lang kotlin` | `kotlin/none/hello` |
| `jk new -t spring-boot/hello` | `java/spring-boot/hello` (java exists) |
| `jk new -t grails/hello` | miss `java/grails/hello`, miss `kotlin/grails/hello`, hit `groovy/grails/hello` |
| `jk new -t java/spring-boot/hello` | exact id |

Bare `name` is always framework `none`. `-t spring-boot` is a **framework**, not a template:
the engine lists templates under that framework (`hello`, `webmvc`, …).

Local path, `owner/repo`, or git/HTTPS URI are unchanged.

Plugin and catalog trees share one index. Plugin overlays catalog on the same
`(language, framework, name)`.

### Official templates repo

First-party catalog content lives in **[JumpKickOSS/jk-templates](https://github.com/JumpKickOSS/jk-templates)**
(overridable via config). The **engine** freshens the shallow clone on `jk new`/`jk init` for
short names. The native CLI does not apply templates or ship ST4. A stale clone of the old
`<lang>/<name>.g8` layout is deleted and re-cloned.

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

Third-party monorepos must use `<lang>/<framework>/<name>.g8`.

## Apply language

Engine-hosted Giter8 (StringTemplate 4 + Giter8 extensions): `$key$`, `$name;format="Camel"$`,
path `$name__Camel$`, `$if(x.truthy)$` / `$else$` / `$endif$` in content and paths, `.` flatten,
`verbatim`, `$! comments !$`, property-to-property defaults, `maven(group, artifact[, stable])`.
Writes stay under the project directory; template symlinks are skipped. Output is not wrapped in
an extra `$name$` directory.

`--layout simple` (and the Web Layout control) set Giter8 `simple=yes` when the template’s
`layouts` include both traditional and simple. Custom trees (Grails `grails-app/`) stay custom.

## First-party templates

| Name | Framework | Languages | Intent |
|------|-----------|-----------|--------|
| `cli` | none | java, kotlin | Simple executable |
| `cli-native` | none | java | Interactive Java CLI with JLine |
| `ktor-3` | none | kotlin | Ktor + Koin + Exposed |
| `hello` | spring-boot | java, kotlin | Plugin hello app |
| `webmvc` | spring-boot | java, kotlin | Clean-architecture WebMVC workspace |
| `webmvc-security-actuator-jpa-h2` | spring-boot | java, kotlin | Catalog monolith WebMVC + JPA/H2 + Actuator |
| `mcp` | spring-boot | java | Boot MCP server |
| `hello` | quarkus | java, kotlin | Plugin REST app |
| `hello` | micronaut | java, kotlin | Plugin HTTP service |
| `hello` | grails | groovy | Plugin Grails 8 REST |

## Non-goals

- sbt `g8Scaffold` into an existing repo
- Public template marketplace
