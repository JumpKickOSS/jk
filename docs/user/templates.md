# Templates (`jk new`)

```bash
jk new my-app
jk new -t hello my-app
jk new -t spring-boot/hello my-api
jk new -t quarkus/hello my-api
jk new -t grails/hello my-svc
jk new -t spring-boot/webmvc --layout simple my-api
jk new --param key=value          # repeatable; non-interactive props
jk init -t hello
```

`-t` / `--template` is exclusive with `--plugin`. Blank `jk new` (no `-t`) is the wizard /
flag scaffolder for a plain library or `--executable` app.

Standalone scaffolds write **`AGENTS.md`** (unless the template already shipped one)
pointing coding agents at `jk manual`. Workspace modules skip it — the root owns the file.

`--lang` defaults to **java**. For a `framework/name` ref, a miss on that language walks
java → kotlin → groovy until a hit (`jk new -t grails/hello` lands on groovy).

Layout (`traditional` vs `simple`) is **file placement**, not a `jk.toml` key —
[Layout](layout.md). `--layout simple` sets Giter8 `simple=yes` when the template’s
`layouts` include both.

The **engine** applies Giter8 (StringTemplate 4). The native CLI does not ship ST4.

## Resolution (`-t <ref>`)

Catalog layout: `<lang>/<framework>/<name>.g8`. `none` is the framework bucket for
templates that are not a framework (`cli`, `ktor-3`, …).

| Invocation | Resolves to |
|------------|-------------|
| `jk new -t hello` | `java/none/hello` |
| `jk new -t hello --lang kotlin` | `kotlin/none/hello` |
| `jk new -t spring-boot/hello` | `java/spring-boot/hello` when java exists |
| `jk new -t grails/hello` | miss java/kotlin, hit `groovy/grails/hello` |
| `jk new -t java/spring-boot/hello` | exact id |

Bare `name` is always framework `none`. `-t spring-boot` is a **framework**, not a
template: the engine lists templates under that framework.

Local path, `owner/repo`, or git/HTTPS URI also work.

Official catalog: [JumpKickOSS/jk-templates](https://github.com/JumpKickOSS/jk-templates)
(overridable). Plugin jars may overlay the same `(language, framework, name)`.

```toml
# ~/.config/jk/config.toml
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

## First-party templates

| Name | Framework | Languages | Intent |
|------|-----------|-----------|--------|
| `cli` | none | java, kotlin | Simple executable |
| `cli-native` | none | java | Interactive Java CLI with JLine |
| `ktor-3` | none | kotlin | Ktor + Koin + Exposed |
| `hello` | spring-boot | java, kotlin | Plugin hello app |
| `webmvc` | spring-boot | java, kotlin | Clean-architecture WebMVC workspace |
| `webmvc-security-actuator-jpa-h2` | spring-boot | java, kotlin | WebMVC + JPA/H2 + Actuator |
| `mcp` | spring-boot | java | Boot MCP server |
| `hello` | quarkus | java, kotlin | Plugin REST app |
| `hello` | micronaut | java, kotlin | Plugin HTTP service |
| `hello` | grails | groovy | Grails 8 REST |

MCP `jk_new`: `action=templates` lists `{id,name,language,framework,…}`; `preview=true`
returns the file set without writing. The web dashboard has a New project modal —
[Web](web.md).

## Related

[Getting started](getting-started.md) · [Frameworks](frameworks.md) · [Plugins](plugins.md)
