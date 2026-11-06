# Templates (`jk new`)

```bash
jk new                       # on a terminal: the new-project form
jk new my-app                # the app template; without a terminal, with its defaults
jk new my-app --dir ~/src    # ~/src/my-app: --dir (-C) is the parent directory
jk new -t library my-lib
jk new -t cli my-app
jk new -t spring-boot/hello my-api
jk new -t quarkus/hello my-api
jk new -t grails/hello my-svc
jk new -t spring-boot/webmvc -L simple my-api
jk new -t app -p native=true my-app   # -p answers a template question; repeatable
jk new -t app --help         # the template's questions, with their values and defaults
jk init -t hello
```

Every project comes from a template. With no `-t` it is **`app`**, the blank application in the
chosen language (`library` is the blank library); the template search preselects it.

**On a terminal**, `jk new` asks what its flags left open. It walks jk's own step first (language,
template, name, group, directory), then the chosen template's: the layout when the template renders
more than one, then the template's [questions](#template-questions-jk-templatetoml). A flag answers
its field up front (`--lang`, `-t`, the name or `--name`, `--group`, `--dir`, `-L`, and
`-p key=value` for a template's question), and the field is shown answered rather than asked; an
`advanced` question takes its default unless `-p` sets it. The walk ends on the equivalent command,
which makes the same project without asking:

```text
Equivalent command  jk new my-app -t kotlin/none/app -L simple -p native=true
```

**Without a terminal** (a pipe, `CI`, `JK_NONINTERACTIVE`), the same form is settled from the
flags, and every field they leave open takes its default, so `jk new my-app` is `app` with its
defaults. A required field with no default (the name, when no argument gives it) is an error naming
the flag that answers it, one line per field:

```text
jk new: database needs a value: -p database=none|postgres|mysql
jk new: name must match [a-z][a-z0-9-]*; got 'MyApp' (--name <name>)
```

A name is lowercase letters, digits and `-`, starting with a letter: it is the directory, the
artifact and the image name. `--jdk 21` sets the template's `java = 21`; a module takes its
workspace's `java` and language unless a flag says otherwise.

The flags of the former blank-project scaffolder:

| Flag | Now |
|---|---|
| `-e`, `--executable` | `-t app` (the default) |
| `--no-executable` | `-t library` |
| `-A`, `--assembly` | refused: `-p assembly=true` |
| `-n`, `--native` | refused: `-p native=true` |
| `-d`, `--deps` | refused: `-p deps=guava,commons-lang,commons-io`; `jk add` for any other |
| `--jdk <major>` | the template's `java`; a vendor spec is refused (pin it in `jk.toml` after) |
| `-m`, `--kotlin-module` | refused: set `module` in the new `jk.toml` |
| `-P`, `--plugin` | refused: no template scaffolds a build plugin yet; start from [third-party-plugin](examples/third-party-plugin/) |

**`jk new -t <template> --help`** follows the command's own help with the template's questions, one
line each, generated from its form:

```text
Template java/none/app (-L for layout, -p key=value for the rest):
  layout: traditional|simple = traditional  # Layout
  deps: [guava,commons-lang,commons-io] = []  # Libraries
  assembly: true|false = false  # Runnable fat jar
  native: true|false = false  # Native binary
```

A line reads `key: values = default`: `a|b` is a choice, `[a,b]` a multi (answer with
comma-separated ids), `true|false` a bool and `text` a text, with its pattern between slashes.
`(required)` marks a question with no default, `when …` the condition that shows it and
`(advanced)` one the form does not ask.

A standalone template scaffold gets what a plain `jk new` gets: [jk's section of
**`AGENTS.md`**](agents.md#jks-section-of-agentsmd) (into the template's own `AGENTS.md` when it
shipped one), the files of each [agent in use](agents.md) and a first code graph, and jk's
`.gitignore` unless the template ships its own. Workspace modules get none of these — the root
owns them. A template scaffolded inside a project is listed in the root's `[workspace].modules`,
as a plain `jk new` module is, so the next `jk build` builds it.

Every library and framework version a scaffold writes is today's **current stable, as an exact
pin** (`[spring-boot] version = "4.1.0"`, not `"latest"`). The committed file says what you
build against; `jk update` moves it — [Lockfile](lockfile.md#jk-update).

`--lang` defaults to **java**. For a `framework/name` ref, a miss on that language walks
java → kotlin → groovy → scala until a hit (`jk new -t grails/hello` lands on groovy).
`jk new -l groovy` scaffolds the Groovy `app`. No catalogue template is in Scala yet, so the
form offers java, kotlin and groovy (and `--lang node` runs the Node generators below).

Layout (`traditional` vs `simple`) is **file placement**, not a `jk.toml` key —
[Layout](layout.md). `--layout simple` sets Giter8 `simple=yes` when the template’s
`layouts` include both.

The layout a template renders is the first of these that is set: `-L` / `--layout` (`auto`
leaves the choice to the rest of this list), `-p jk_layout=<layout>`, the template's own
`jk_layout` in `default.properties`, then `traditional`. So `jk new -t cli` renders the simple
tree its template names, and `jk new -t cli -L traditional` the Maven one.

The **engine** applies Giter8 (StringTemplate 4). The native CLI does not ship ST4.

## Resolution (`-t <ref>`)

Catalog layout: `<lang>/<framework>/<name>.g8`. `none` is the framework bucket for
templates that are not a framework (`cli`, `ktor-3`, …).

| Invocation | Resolves to |
|------------|-------------|
| `jk new -t hello` | `java/none/hello` |
| `jk new -t hello -l kotlin` | `kotlin/none/hello` |
| `jk new -t spring-boot/hello` | `java/spring-boot/hello` when java exists |
| `jk new -t grails/hello` | miss java/kotlin, hit `groovy/grails/hello` |
| `jk new -t webapp` | `java/spring-boot/webapp` (no `none/webapp`; java before kotlin) |
| `jk new -t webapp -l kotlin` | `kotlin/spring-boot/webapp` |
| `jk new -t java/spring-boot/hello` | exact id |

A bare `name` tries framework `none` first, then any framework. Without `-l` it takes the
first of java → kotlin → groovy that has the name; two frameworks in that one language
with the same name is ambiguous and the error lists both ids. `-t spring-boot` is a
**framework**, not a template: the engine lists templates under that framework.

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
| `cli` | none | java, kotlin | Simple executable | `library` (java), house rules (kotlin) |
| `cli-native` | none | java | Interactive Java CLI with JLine | house rules |
| `app` | none | java, kotlin | Blank application: a main and its test; asks for common libraries, a fat jar and a native binary | `library` |
| `library` | none | java, kotlin | Published library: sources and javadoc jars (Dokka's for Kotlin), `[build-info]`, unit test; the Java one a `@NullMarked` API; asks for common libraries | `library` |
| `ktor-3` | none | kotlin | Ktor + Koin + Exposed | house rules |
| `hello` | spring-boot | java, kotlin | Plugin hello app | `spring` |
| `webmvc` | spring-boot | java, kotlin | Clean-architecture WebMVC workspace | `spring`, `monorepo` |
| `webmvc-security-actuator-jpa-h2` | spring-boot | java, kotlin | WebMVC + JPA/H2 + Actuator | `spring` |
| `webapp` | spring-boot | java, kotlin | Boot API + Vite/React SPA; `web` is a node module jk builds and packages as a resource jar; `jk dev` runs Vite beside the app, inferred from the dependency | `spring`, `monorepo` |
| `mcp` | spring-boot | java | Boot MCP server | `spring` |
| `hello` | quarkus | java, kotlin | Plugin REST app | `quarkus` |
| `hello` | micronaut | java, kotlin | Plugin HTTP service | — |
| `hello` | grails | groovy | Grails 8 REST | — |
| `compose` | android | kotlin | Jetpack Compose app (debug APK, `jk run` deploy) | `android` |

`webapp --frontend <framework>` swaps the Vite/React `web` module for another front end, generated
as below; the default stays the offline Vite/React one.

## Template questions (`.jk-template.toml`)

Every template root holds a `.jk-template.toml`: its identity, which must match its
`<lang>/<framework>/<name>.g8` path, and the questions it asks beyond the ones every project shares.

```toml
language = "java"
framework = "spring-boot"
name = "webmvc"
description = "Clean-architecture WebMVC workspace"
layouts = ["traditional", "simple"]
internal = ["boot_version"]          # default.properties entries that are not questions

[[ask]]
key = "database"
label = "Database"
kind = "choice"
options = ["none", { id = "postgres", label = "PostgreSQL" }, "mysql"]
default = "postgres"

[[ask]]
key = "migrations"
label = "Migrations"
kind = "choice"
options = ["flyway", "liquibase"]
default = "flyway"
when = "database != 'none'"

[[ask]]
key = "native"
label = "Build a native binary"
kind = "bool"
default = false
advanced = true
```

| Attribute | Meaning |
|-----------|---------|
| `key` | the Giter8 property the answer sets |
| `label`, `help` | one short line each; `label` is required |
| `kind` | `text`, `bool`, `choice` or `multi` |
| `options` | for `choice` and `multi`: ids, or `{ id, label, help }` tables |
| `default` | a string, a boolean (`bool`) or a list of ids (`multi`); without one the ask is required |
| `required` | defaults to `true` exactly when there is no `default` |
| `pattern` | a regex a `text` answer must match |
| `when` | a condition on earlier asks (`==`, `!=`, `in [...]`, `&&`, `\|\|`, `!`); a hidden ask takes its default |
| `advanced` | not prompted; its default applies unless `-p` sets it |

`-p key=value` answers an ask: `true`/`false` (or `yes`/`no`) for a `bool`, comma-separated ids for
a `multi`. An answer that does not fit is refused with the values it takes
(`database must be one of none, postgres, mysql; got 'oracle' (-p database=none|postgres|mysql)`).

**Giter8 bindings.** StringTemplate conditionals test truthiness only, so the engine sets more than
the answer itself: for a `choice`, `<key>_<option>` is `yes` for the chosen option and `no` for the
rest (`database_postgres`); for a `multi`, the same for each selected option, with the answer
itself the selected ids joined by commas; a `bool` is `yes` or `no`. A character an option id may
hold but a property name may not (`-`, `.`) becomes `_`: option `commons-lang` binds
`deps_commons_lang`. Templates write `$if(database_postgres.truthy)$` and keep no logic of their
own.

**Catalogue lint.** The engine checks every template it loads, and refuses one that fails with a
message naming the property; a refused template is left out of the catalogue and is not applied:

- every property in `default.properties` is reserved, an `[[ask]]` key, or listed in `internal`;
- an ask is not a reserved key, and a value `default.properties` also gives it equals its default;
- every `internal` entry is set in `default.properties`, and is neither reserved nor asked;
- every `when` names earlier asks only, and every `default` is one of its `options`;
- no `<key>_<option>` binding names another property.

Reserved properties are jk's own: the keys of jk's step (`lang`, `template`, `name`, `group`,
`layout`) and what the apply sets (`organization`, `package`, `java`, `jk_languages`, `jk_layout`,
`jk_version`, `name_normalized`, `simple` and `verbatim`).

## Node front ends (`--lang node`)

```bash
jk new -l node -t vite-react web     # web/ with jk.toml (node = the newest LTS) and the starter
jk new -t webapp -f angular shop # the webapp template with an Angular web/
```

jk writes only `jk.toml` and `.gitignore`. The tree is the framework's own starter, made by its
generator under the Node.js jk provisions, so it is always the framework's newest. The generator
installs nothing: jk resolves `package-lock.json` after it (`npm install --package-lock-only`), and
the first `jk build` installs `node_modules` once, from jk's npm cache. Generators
come from the npm registry: there is no offline form. `--param` passes a flag through (`k=v` as
`--k=v`).

| `-t` | Generator | Output |
|------|-----------|--------|
| `vite-react`, `vite-vue`, `vite-preact`, `vite-solid`, `vite-svelte` | `create-vite` (TypeScript template) | static |
| `angular` | `@angular/cli new` | static |
| `astro` | `create-astro` | static |
| `next` | `create-next-app` | server |
| `nuxt` | `create-nuxt` | server |
| `sveltekit` | `sv create` | server |
| `react-router` | `create-react-router` | server |
| `tanstack-start` | `@tanstack/create-start` | server |

A static front end is packaged into what depends on it; a server one is runnable by `jk run` and
imageable by `jk image` ([Node.js](node.md)).

A template with a rule pack writes `jk-guards.toml` with `[guards] extends =
["cc.jumpkick.guards:<pack>:<jk version>"]`: the pack's house rules run inside `jk build` from the
first build, `jk lock` pins it, and `jk guard explain` lists its rules with their source. Add your
own rules below the `[guards]` table; exempt a site with an `allow` entry and a reason. Packs:
`spring`, `quarkus`, `android`, `library`, `monorepo` — [Guards](../contributors/code-as-art.md)
describes the vocabulary they are written in. A template without a framework pack ("house rules")
ships a small `jk-guards.toml` of its own — a file-size ratchet and one ban with a fixture under
`guard-fixtures/` — so the guard loop is there from the first build.

Every template also declares test tiers (`[test] exclude-tags` with one profile per tag), a
`[format]` style, and, for a runnable app on the JVM, an `[image]` table (`cli-native` has none: its
native binary ships as itself). Every Java template declares [`[nullness]`](build.md#null-checking-nullness)
and marks each package it generates `@NullMarked`, so NullAway checks the project from the first
build. The Spring Boot and `library`
templates carry a [`[build-info]`](packaging.md#build-info-gitproperties-and-boots-build-infoproperties)
table, so the jar records the commit it was built from (`git.properties`, and Boot's
`META-INF/build-info.properties` for `/actuator/info`). None ships an `AGENTS.md`: `jk new`
writes jk's current section into every project it scaffolds.

MCP `new` asks the same questions — [MCP](mcp.md#new). The web dashboard has a New project
modal — [Web](web.md).

## Related

[Getting started](getting-started.md) · [Frameworks](frameworks.md) · [Plugins](plugins.md)
