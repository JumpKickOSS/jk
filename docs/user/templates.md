# Templates (`jk new`)

```bash
jk new my-app
jk new -t cli my-app
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

Every library and framework version a scaffold writes is today's **current stable, as an exact
pin** (`[spring-boot] version = "4.1.0"`, not `"latest"`). The committed file says what you
build against; `jk update` moves it — [Lockfile](lockfile.md#jk-update).

`--lang` defaults to **java**. For a `framework/name` ref, a miss on that language walks
java → kotlin → groovy → scala until a hit (`jk new -t grails/hello` lands on groovy).
`jk new --lang scala` scaffolds a Scala 3 app (mixed Java+Scala compiles in one Zinc
session).

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
# ~/.jk/config.toml
[templates]
official = "https://github.com/JumpKickOSS/jk-templates"

[templates.sources]
acme = "https://github.com/acme/jk-g8"
corp = { url = "https://git.example/corp/jk-templates.git", rev = "main" }
```

Configured sources are cloned into the artifact store (`<store>/templates`,
next to `libs.global.toml`) alongside the official catalog and refreshed the
same way; their short names resolve in `jk new -t` and appear in the picker.
Override the store root with `JK_STORE_DIR`. For a one-off template that isn't
in a configured catalog, pass its git/HTTPS URL (or a local path) directly to
`-t`.

Third-party monorepos must use `<lang>/<framework>/<name>.g8`.

## First-party templates

| Name | Framework | Languages | Intent | Rule pack |
|------|-----------|-----------|--------|-----------|
| `cli` | none | java, kotlin | Simple executable | house rules |
| `cli-native` | none | java | Interactive Java CLI with JLine | house rules |
| `library` | none | java, kotlin | Published library: sources and javadoc jars (Dokka's for Kotlin), `[build-info]`, unit test; the Java one a `@NullMarked` API | `library` |
| `ktor-3` | none | kotlin | Ktor + Koin + Exposed | house rules |
| `hello` | spring-boot | java, kotlin | Plugin hello app | `spring` |
| `webmvc` | spring-boot | java, kotlin | Clean-architecture WebMVC workspace | `spring`, `monorepo` |
| `webmvc-security-actuator-jpa-h2` | spring-boot | java, kotlin | WebMVC + JPA/H2 + Actuator | `spring` |
| `webapp` | spring-boot | java, kotlin | Boot API + Vite/React SPA in a resource-only `web` module; `jk dev` runs Vite as a sidecar | `spring`, `monorepo` |
| `mcp` | spring-boot | java | Boot MCP server | `spring` |
| `hello` | quarkus | java, kotlin | Plugin REST app | `quarkus` |
| `hello` | micronaut | java, kotlin | Plugin HTTP service | — |
| `hello` | grails | groovy | Grails 8 REST | — |
| `compose` | android | kotlin | Jetpack Compose app (debug APK, `jk run` deploy) | `android` |

A template with a rule pack writes `jk-guards.toml` with `[guards] extends =
["cc.jumpkick.guards:<pack>:<jk version>"]`: the pack's house rules run inside `jk build` from the
first build, `jk lock` pins it, and `jk guard explain` lists its rules with their source. Add your
own rules below the `[guards]` table; exempt a site with an `allow` entry and a reason. Packs:
`spring`, `quarkus`, `android`, `library`, `monorepo` — [Guards](../contributors/code-as-art.md)
describes the vocabulary they are written in. A template without a framework pack ("house rules")
ships a small `jk-guards.toml` of its own — a file-size ratchet and one ban with a fixture under
`guard-fixtures/` — so the guard loop is there from the first build.

Every template also declares test tiers (`[test] exclude-tags` with one profile per tag), a
`[format]` style, and, for a runnable app, an `[image]` table. The Spring Boot and `library`
templates carry a [`[build-info]`](packaging.md#build-info-gitproperties-and-boots-build-infoproperties)
table, so the jar records the commit it was built from (`git.properties`, and Boot's
`META-INF/build-info.properties` for `/actuator/info`). None ships an `AGENTS.md`: `jk new`
writes the current one into every project it scaffolds.

MCP `jk_new`: `action=templates` lists `{id,name,language,framework,…}`; `preview=true`
returns the file set without writing. The web dashboard has a New project modal —
[Web](web.md).

## Related

[Getting started](getting-started.md) · [Frameworks](frameworks.md) · [Plugins](plugins.md)
