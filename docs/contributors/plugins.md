# Authoring build plugins

A build plugin teaches jk a new `jk.toml` table — `[spring-boot]`, `[grails]`, `[quarkus]`,
`[android]`, `[protobuf]` — and shapes the standard commands around it. First-party examples
live under [`plugins/`](../../plugins/) (start with [`plugins/spring-boot`](../../plugins/spring-boot)
or [`plugins/quarkus`](../../plugins/quarkus)).

**Who this is for:** **first-party** plugins in this monorepo, and plugins authored **outside**
it against the published SDK coordinate `cc.jumpkick:jk-plugin-sdk:<jk version>` — see
[Authoring against the published SDK](#authoring-against-the-published-sdk). A consumer pins any
plugin not shipped inside jk by content (path or Maven pin + required `sha256`); there is no
marketplace. Pre-1.0 the SPI is additive-only by intent, not yet by contract
([compatibility](compatibility.md)).

**The bar:** you declare *what*; jk owns *when* (ordering) and *whether it can be skipped*
(caching / action keys). You do not hand-manage the content-addressed store.

## Anatomy

One jar containing:

```
jk-plugin.toml                      # declarative layer (required)
templates/<lang>/<framework>/<name>.g8/  # optional Giter8 trees for jk new -t <framework>/<name>
<your classes>                      # optional code layer (forked process only)
```

- **Declarative layer** — jk parses `jk-plugin.toml` (data; safe even for untrusted plugins)
  and applies contributions itself. Zero plugin code in the engine JVM.
- **Code layer** — runs in a **forked worker** over a JSONL protocol. The engine never
  classloads your classes.

Compile against the **`plugin-sdk`** module: the workspace edge `jk-plugin-sdk.workspace = true`
inside this tree, the coordinate `cc.jumpkick:jk-plugin-sdk:<version>` outside it. Keep the worker
at a JDK floor compatible with user projects (first-party workers target `--release 17` where
they ride the user’s JVM).

### Authoring against the published SDK

Every jk release publishes `cc.jumpkick:jk-plugin-sdk` and `cc.jumpkick:jk-host` (the SDK's one
dependency) at the release's version — sources and javadoc jars, a POM with the metadata Central
requires, GPG-signed — to `https://jumpkick.build/repo/` and, through `jk publish --central`, to
Maven Central ([releases](releases.md#maven-central)). A plugin outside this tree:

```toml
[dependencies]
jk-plugin-sdk = "cc.jumpkick:jk-plugin-sdk:0.13.7"     # the release you compile against
```

```toml
[plugin]
id        = "hello"
table     = "hello"
version   = "0.1.0"
sdk       = "0.13.7"          # the jk-plugin-sdk release you compiled against — the pin above
jk-compat = ">=0.13"          # the floor: the oldest jk line that loads this plugin
```

Two versions describe the SDK. `sdk` is the exact release the code compiled against, the same
number as the `[dependencies]` pin: a consumer's `jk lock` pins the worker's SDK floor
(`jk-plugin-sdk`, `jk-host`) at it, as `plugin`-scoped rows resolved from the consumer's
repositories, so the worker forks on the SPI it was built for whatever jk is running. A manifest
without `sdk` is pinned at the running jk's version and the lock notes that. `jk-compat` is the
contract between the two lines: a plugin built against release `N`'s SDK declares `>=N` and every
later jk loads it, an older jk refuses it with an upgrade error before any code runs. Pick the
oldest SDK whose SPI you use and name that release.

The first-party plugins under `plugins/` keep the workspace edge on purpose: the self-host build
compiles them against the SDK in the tree, so a release never depends on a prior release having
been published. [`docs/user/examples/third-party-plugin`](../user/examples/third-party-plugin/) is
the complete out-of-tree shape — manifest, code layer, consumer pin — and
`ThirdPartyPluginExampleTest` builds it against a `jk publish`ed SDK end to end.

### Dependency boundary

`plugin-sdk` is the default and only unclassified first-party dependency. Compiler and test
workers may vendor `host` as their JSONL/host primitive implementation; this is packaging, not an
engine dependency. No module under `server/` may enter a plugin runtime.

The guarded exceptions each carry a current product invariant:

- `auditor → core`: the auditor reads the canonical lockfile and emits the shared audit report.
- `publisher → core`: publishing reads the canonical manifest, lockfile, and build layout.
- `publisher → client-io`: repository uploads use the client-safe HTTP/file/object-store
  transports. The transport surface lives there specifically so the worker does not depend on
  server I/O.
- `auditor → client-io`: OSV queries go through `Http`, the one client jk routes through the
  configured proxy.
- `android → client-io`: the SDK license feed is downloaded through `Http`, for the same reason.
- `image-builder → jk-api`: image configuration and repository credentials are public model
  types.
- `minified → dynamic-surface`: R8 keep rules and native reachability share one model.
- `grails → spring-boot`: a Grails package is a Boot jar, so the plugin composes the Boot
  packager.

Publisher may use `core` test fixtures, and image-builder may use `host` test fixtures. These
test-only edges do not enter worker publication or runtime classpaths.
The `plugin-sdk-boundary` rule in `jk-guards.toml` (a `layers` rule: `plugins` may depend on
`sdk` = plugin-sdk + host, `closed`) rejects every other project edge; each exception above is one `allow` edge with the same reason, and an allow whose
edge is gone is red (`stale-allow`). Keep this list and the rule's allow entries in step by hand
until a `generated` rule can hold them to each other.

## Manifest essentials

### Identity and schema

```toml
[plugin]
id        = "spring-boot"
table     = "spring-boot"     # jk.toml table you own
version   = "1.0.0"
jk-compat = ">=0.10"
# sdk = "0.13.7"              # out-of-tree plugins: the jk-plugin-sdk release compiled against

[schema]
version = { type = "string", required = true, example = "4.1.0",
            hint = "platform BOM version — a release such as 4.1.0 (exact), ^4 for the newest 4.x, or latest" }
aot     = { type = "bool" }   # no default = tri-state
```

Schema types: `string`, `coordinate` (a `group:artifact:version` the user writes, a classifier and
`!type` allowed after it — fewer than three segments fails parse naming the table and key), `bool`,
`int`, `string-list`, `string-map` (an inline table of strings: `options = { a = "1" }`). Missing
`required` keys fail parse with your `example` / `hint`.

A table of *things* rather than keys — `[generate.api]`, `[generate.grammar]` — declares
`[entries]`: every `[<table>.<name>]` sub-table validates against the named `[sub-schema]` and the
worker reads them as `config().entries()` (name → values), in declaration order.

```toml
[entries]
schema = "generator"

[sub-schema.generator]
tool   = { type = "string", required = true }
inputs = { type = "string-list", required = true }
```

### Contributions

```toml
[[contribute.platform-dependency]]
coordinate = "org.springframework.boot:spring-boot-dependencies:${config.version}"

[[contribute.compiler-args]]
javac  = ["-parameters"]
kotlin = ["-java-parameters"]
groovy = ["--parameters"]
ksp    = ["room.schemaLocation=…"]

[[contribute.source-roots]]
dir  = "grails-app/domain"    # module-relative; absolute or ..-escaping dirs fail at load
kind = "source"               # source | resource — joins roots()/compile/IDE/BSP/fingerprints

[[contribute.kotlin-plugin]]
id         = "org.jetbrains.kotlin.noarg"
coordinate = "org.jetbrains.kotlin:kotlin-noarg-compiler-plugin-embeddable:${kotlin.version}"
options    = ["preset=jpa"]
when       = { classpath-has = "jakarta.persistence:jakarta.persistence-api" }

[[contribute.step-dependency]]        # a tool your steps (and packagers) read — engine-fetched,
artifact   = "aapt2"                  # handed to the worker by name, in the key of each step
coordinate = "com.android.tools.build:aapt2:9.3.1-15703166:${host.os}"
for-step   = "android-res"            # that reads it: the named step(s)/packager, else all

[[contribute.command-dependency]]     # a tool ONLY your commands read (an adb) — same entry
artifact      = "adb"                 # shape and rules, but provisioned when the command runs
sdk-component = "platform-tools"      # and part of no action key: upgrading it invalidates
sdk-path      = "adb"                 # nothing
```

A tool both lanes need is declared once, as a `step-dependency` — commands receive both lanes,
so one artifact may not sit in both (parse error). `[[contribute.provided-classpath]]` resolves
against the step lane only: a command-only tool never joins a compile classpath.

**Scope (`for-step`).** Without it, a step tool is fetched by, handed to, and keyed into every
step and packager the plugin registers. `for-step = "<name>"` — or a list, `["android-dex",
"android-r8"]` — names the steps or packagers that read the tool (a `TaskSpec.named(…)` or
`PackagerSpec.replacingMainArtifact(…)` name): those fetch it and carry it in their action key;
the others never see it, so a debug build does not materialize the release packager's tool and
bumping one tool's version re-runs only the steps that fork it. A name the plugin does not
register under the current config (a step it adds only for release) simply matches nothing.
`[[contribute.provided-classpath]]` names its tool itself and is unaffected by scope; commands
receive the whole lane regardless. Step-lane only — a command tool has no step to scope to.

**Per-entry tools.** A `[[contribute.step-dependency]]` with `per-entry = true` is one
declaration expanded once per `[entries]` sub-table: `artifact`, `coordinate`, `with`,
`managed-by` and `for-step` may use `${entry.name}` (the sub-table's name) and `${entry.<key>}`
(its validated values). The generator plugin declares `artifact = "${entry.name}"`,
`coordinate = "${entry.tool}"`, `for-step = "generate-${entry.name}"`, so each entry's tool is
fetched by and keyed into that entry's step alone. A table may carry `[schema]` keys and
`[entries]` at once: protobuf's `[protobuf.<id>]` entries are protoc plugins, each fetched as
`protoc-gen-${entry.name}` beside the table's own protoc. A per-entry declaration whose `coordinate`
names an optional entry key is expanded only for the entries that set it.

**Interpolation (closed set):** `${config.<key>}`, `${entry.name}` / `${entry.<key>}` (per-entry
tools only), `${kotlin.version}`, `${project.group|name|version}`, `${host.os}`,
`${host.os-arch}`.

**Coordinate versions — bare is exact**, the same grammar as `jk.toml`. In a
`[[contribute.step-dependency]]`, `[[contribute.command-dependency]]` or
`[[contribute.packager-dependency]]`, `…:8.5.35` is a hard pin and costs no network: a tool
version in a manifest is *your* choice, and a literal usually exists because the tool has to
match some other line (android's r8 tracks the AGP tools line). Write `^` or `~` when you mean
float-within-line, resolving against the tool's own `maven-metadata.xml`. `latest` and open
ranges are rejected in a tool coordinate.

**The platform line's key is the locked version.** The user writes a *selector* into the key
your `[[contribute.platform-dependency]]` reads (`[spring-boot] version = "4.1.1"`, `"^4"`,
`"latest"`); in a tool coordinate that same `${config.<key>}` is the version the lock pinned
the platform to, never the selector — no selector is a fetchable version. So Boot's
`…:spring-boot-loader:${config.version}` is the locked Boot release exactly (no network), and
Quarkus's `…:^${config.version}` floats within the locked line. Before a lock exists the
selector's anchor stands in (`4.1.1` → `4.1.1`, `^4` → `4`).

`[[contribute.platform-dependency]]` is *not* a tool coordinate — it lands in the project's
`[platform-dependencies]` and follows the `jk.toml` [version grammar](../user/projects.md#version-strings):
a bare version is that BOM release, `^N` is the opt-in floor.

```toml
[[contribute.native-args]]
when = { native-declared = true }
args = ["--initialize-at-build-time=ch.qos.logback"]
```

`native-args` carries what `native-image` needs and reachability metadata cannot express —
chiefly class-initialization policy. Contributed args land before the project's `[native] args`,
so a user can override anything a plugin sets.

**Conditions (closed set, one per `when`):** `classpath-has`, `config`/`equals`,
`native-declared`, `kotlin-project`. Richer logic belongs in code.

### Packaging

```toml
[packaging]
packager       = "boot-jar"   # code packager id
exec-mode      = "jar"        # jar | classpath | binary
self-contained = true
classes-run    = true
main-scan      = true
layered-image  = true
```

Static data consulted by `jk run` / `install` / `image` without forking your code.

### Templates and import

Bundle Giter8 trees at `src/main/resources/templates/<lang>/<framework>/<name>.g8/` (see
[templates](../user/templates.md)). `jk new -t <framework>/<name>` applies
that tree (`jk new -t spring-boot/hello`).

```toml
[[import.gradle-plugin]]
id         = "org.springframework.boot"
version-to = "version"
```

### Code layer

```toml
[code]
protocol-prefix = "##FOO:"   # worker JSONL marker; must match Plugin.manifest()
```

The process entry is always the SDK's `PluginMain` — do not put `[application]` on a plugin
`jk.toml`. Presence of `jk-plugin.toml` (or a `Plugin` service registration) is the worker flag.

Workers speak the same JSONL style as compiler plugins: one shared `SpecWriter` builds every
spec, and the engine reads worker replies through a bounded line reader. Prefer the harness in
`plugin-sdk` (`BuildPlugin`, `TaskSpec`/`TaskContribution` task and packager SPIs) over
hand-rolled protocols. A plugin's `jk-compat = ">=x.y"` floor is enforced at manifest load —
too-old jk refuses the plugin with an upgrade error.

## Active plugins per module

`ActivePlugins.of(project, moduleDir)` is the selection rule: every installed manifest with a
`[code]` layer whose table the module declares is active, in registry order, and each forks its
own worker. Capability bounds the count of a kind — any number of step contributors; at most one
plugin whose `[packaging]` replaces the main artifact (`main-artifact` unset or `true`), a second
is refused by name; any number whose packaging writes beside it (`main-artifact = false`, the
minifier). `ActivePlugins.declared` runs the describe round for each and merges the replies into
one `Declarations`: every step with the plugin that owns it (`Declared.ownerOf` is what the plan
forks), the main-artifact packager's `packager`, every command with its owner. Step and command
names are one namespace across the module's plugins; a collision is a refusal, never a priority.
The planner reads the merged view everywhere — compile folds in every plugin's generated
sources, package-jar dispatches to the one packager, the native tail asks the packager plugin
for `native-image-sources` — and a packager that writes beside the main artifact keeps its own
declarations for its own step.

## Tasks and transforms

Plugins contribute **tasks** into the build plan (codegen before compile, class transforms
after compile, custom packagers) via `TaskSpec`/`TaskContribution`. Important SPI notes:

- **`transformsClasses`** — at most one classes transform per build (e.g. Hilt weaving); runs
  between compile and package and replaces the classes dir for downstream tasks.
- **`stage`** — every task carries a `BuildStage`, inferred from the window the engine schedules it
  in. `TaskSpec.stage("package")` narrows it for the UI fold, and is validated: it may not name a
  stage earlier than the window, may not go past `test` for a test-classpath contributor, and an
  unrecognized name is an error. A task may never `require` a task in a later stage — the plan is
  rejected before anything runs. Stages are a closed set (`resolve`, `generate`, `compile`,
  `test`, `package`, `native`, `image`, `other`).
- **Action keys include plugin worker jar hashes** — upgrading the plugin invalidates cache.
- Tasks declare inputs/outputs so incrementality and `jk explain` stay correct.
- **Repositories** — a step whose own resolver fetches outside the lock (a framework's build-time
  closure) declares `In.repositories()` and reads `TaskExec.repositories()`; a packager that
  fetches a tool or a base image declares the same input and reads `PackageIo.repositories()`.
  Either receives the module's `[repositories]` set over the built-in remotes, in resolve order,
  each as jk routes it — a `settings.xml` mirror standing in for the repository, Maven Central
  rewritten to its mirror while Central is refusing this host — with the credential the request
  carries. The body asks exactly these and never names Central itself. The action key carries the
  declared set, not the routing; a body that did not declare the input reads an empty list.
- **Diagnostics** — a body reports a located finding with `TaskExec.diagnostic(severity, file,
  line, col, message)`; the engine forwards each as the step's warning or error (the
  `file:line[:col]: message` header the journal parses), before a failing body's throw.
- The worker wire keeps its legacy spellings (`run-step`, `step:` input refs, `step-output`) —
  protocol literals, not API names.

## Testing

- Unit-test pure logic in the plugin module.
- Integration-test through a small fixture project with `jk lock` / `jk build` (see first-party
  engine tests for patterns).
- Never require the engine to load your classes on its classpath.

## Distribution (today)

| Path | Status |
|---|---|
| **First-party** plugins under `plugins/` | Ship with jk; `jk install` publishes them to `repos/jk-local` |
| **Private / vendored** jars (`[plugins]` + `sha256`) | Supported now — see below |
| **Third-party** plugins against the published SDK | `cc.jumpkick:jk-plugin-sdk:<version>` on `jumpkick.build/repo` and Maven Central from each release; pinned by content in the consumer |
| **Plugin marketplace / registry** | Intentionally deferred (product anti-goal pre-freeze) |

### Private plugins (path or Maven pin)

Enterprises can vendor a plugin jar without a registry. Declare it under `[plugins]` with a
**required** content pin (`sha256`). Unpinned plugins are refused at parse time.

```toml
# Path pin (air-gapped / monorepo vendor dir) — path relative to the project jk.toml
[plugins]
acme-rules = { path = "vendor/acme-rules-1.0.0.jar",
               sha256 = "…" }   # 64 hex chars; optional sha256: prefix

# Maven coordinate pin (fetch at lock; must match the pin)
[plugins]
acme-rules = { group = "com.acme", name = "acme-rules", version = "1.0.0",
               sha256 = "…" }
# or: coordinate = "com.acme:acme-rules:1.0.0", sha256 = "…"
```

**Trust model (fail closed)**

| Situation | Behavior |
|---|---|
| Missing `sha256` | Parse error |
| Path pin, file hash ≠ declared | `jk lock` error naming both digests |
| Coord pin, resolved jar ≠ declared | `jk lock` error naming both digests |
| Worker code not trusted | Fork refused (`jk trust plugin <group:name>`) — manifest contributions still apply |

**Packaging checklist** (mirror `plugins/spring-boot/`):

1. Jar root must contain `jk-plugin.toml` (`[plugin]` id/table/version + `[schema]` + optional
   `[[contribute.*]]` + optional `[code]` for a worker main).
2. Compile against `cc.jumpkick:jk-plugin-sdk:<version>` (in-tree: the workspace edge); never
   require engine classes on the plugin classpath.
3. Pin: `sha256sum vendor/your-plugin.jar` → paste into `sha256`.
4. `jk lock` materializes the manifest under `target/plugin-manifests/<sha>.jk-plugin.toml`.
5. Code layer: trust once, then worker forks use the locked CAS jar (action keys include jar hash).

Private plugins **error** if they claim a table or id already owned by a built-in plugin.

## Generators

Protobuf runs a native binary; every JVM generator a service reaches for — OpenAPI Generator,
jOOQ codegen, Avro, ANTLR, JAXB `xjc` — runs through **one worker, many tables**
([user doc](../user/generate.md)).

### One worker: `[generate]`

`plugins/generator` owns `[generate]` through `[entries]`: each `[generate.<name>]` is one
`GeneratorEntry`, one generate-stage task named `generate-<name>` whose output is contributed to
the compiler's source set or the resources — the lane the protobuf plugin uses.

```toml
[generate.api]
tool     = "org.openapitools:openapi-generator-cli:7.11.0"   # pinned like any step-dependency
main     = "org.openapitools.codegen.OpenAPIGenerator"       # optional; default: the jar's Main-Class
inputs   = ["api/openapi.yaml"]                              # module-relative globs; the cache key
args     = ["generate", "-i", "${in}", "-g", "spring", "-o", "${out}", "--package-name", "com.acme.api"]
contributes = "sources"                                      # sources | resources
```

- **Tool classpath**: a `per-entry` `[[contribute.step-dependency]]` with `transitive = true`
  — the coordinate is the pin (bare = exact), its runtime closure is materialized into the CAS,
  and the closure's hash is in the action key. The body receives the jar or the closure directory
  by the entry's name and reads `Main-Class` from the jar named after the coordinate's artifact.
- **Action key** = the inputs' glob bases (`In.projectFiles`) + the entry's config (`In.config()`,
  which carries `args`, `main`, `tool`) + tool hashes + JDK + worker jar. Unchanged inputs restore
  `${out}`; the step shows in `jk explain` and the results Deliverables table like any other.
- **Isolation**: `java -cp <tool> <main> <args>` forks on the build JDK with `${out}` as its
  working directory. `${in}` expands to the first input, `${inputs}` to all, `${module.dir}` to
  the module root.
- **Diagnostics**: the tool's output is captured; lines matching `path:line[:col]: message` go
  through `TaskExec.diagnostic` (an error when the tool failed or said so, else a warning), the
  rest is the failure message's tail.
- **Test sources**: `contributes = "test-sources"` declares `TaskSpec.contributesTestSources`; the
  step still runs in the generate stage, compile-test requires it and folds the output into the
  selected suites' sources (`PlannerTest.TestSources.collect`), and compile-main never sees it.
- **Unpack**: `unpack = "g:a:v"` is a second per-entry `[[contribute.step-dependency]]`
  (`${entry.name}-unpack`, the jar alone) the body extracts under the step's scratch before the
  fork; `${unpacked}` names the directory. An entry without the key declares no such tool: a
  per-entry declaration whose `coordinate` names an optional entry key the entry leaves unset is
  skipped for that entry (`Interpolation.entryProvides`).
- **A preset's own main**: `GeneratorEntry.classpath` puts jars ahead of the tool's closure on the
  forked classpath, so a preset can run a `main` it ships over a library that has none
  (`[localizer]`).
- **Beside a framework**: a module's active code plugins are a list (`ActivePlugins.of`), so a
  generator table sits beside `[spring-boot]` or `[quarkus]` in one module; see
  [Active plugins per module](#active-plugins-per-module).

### Many tables: presets over the worker

A preset is a first-party plugin owning its own table whose code layer builds one
`GeneratorEntry` and registers `entry.task()` — the same body, no second worker protocol. Its
manifest carries the schema and the tool's `[[contribute.step-dependency]]` scoped to
`for-step = "generate-<name>"`. A preset whose `main` is its own code and needs no tool
(`[taglib]`) declares no step-dependency: the entry's `toolArtifact` is null and its classpath is
the worker's jar alone. `jk explain` shows the step the preset expands to.

| Table | Tool | Default inputs | Status |
|---|---|---|---|
| `[openapi]` | openapi-generator-cli | `api/*.yaml` | shipped (`plugins/openapi`): `generator`, `package`, `version`, `options` |
| `[localizer]` | localizer-maven-plugin's jar + `LocalizerMain` from the worker's own jar | `src/main/resources/**/Messages.properties` | shipped (`plugins/localizer`): `mask`, `resources`, `encoding`, `access-modifier-annotations`, `strict-types`, `key-pattern`, `version` |
| `[jooq]` | jooq-codegen's closure (+ jooq-meta-extensions and the `driver`) + `JooqMain` from the worker's own jar | `src/main/resources/db/migration/**/*.sql` via `DDLDatabase` | shipped (`plugins/jooq`): `sql`, `package`, `schema`, `name-case`, `includes`, `excludes`, `records`, `pojos`, `daos`, `fluent-setters`, `properties`, `jdbc-url` / `jdbc-user` / `jdbc-password` (opt-in live schema, the scripts still the key), `driver`, `version` |
| `[avro]` | avro-compiler's closure (+ avro-idl) + `AvroMain` from the worker's own jar | `src/main/avro/**/*.{avsc,avpr,avdl}` | shipped (`plugins/avro`): `src`, `string-type`, `field-visibility`, `setters`, `optional-getters`, `decimal-logical-type`, `encoding`, `version` |
| `[antlr]` | antlr4's tool jar + `AntlrMain` from the worker's own jar | `src/main/antlr4/**/*.g4` | shipped (`plugins/antlr`): `src`, `lib`, `package`, `listener`, `visitor`, `encoding`, `arguments`, `options`, `version` |
| `[taglib]` | none — `TaglibMain` from the worker's own jar | `src/main/resources/**/*.jelly` | shipped (`plugins/taglib`): `resources`, `encoding` |
| `[jaxb]` | jaxb-xjc's closure, `com.sun.tools.xjc.Driver` over the schema directory | `src/main/xsd/**/*.xsd` (+ `bindings`) | shipped (`plugins/jaxb`): `src`, `package`, `bindings`, `encoding`, `extension`, `arguments`, `version` |

A tool with no preset works through `[generate]`.

### Why not the alternatives

- **Build-logic scripts** have four anchors, no declared inputs and a whole-module cache key; a
  generator that re-runs on every source edit is the wrong shape for the inner loop.
- **A worker per tool** multiplies the plugin count by the tool count for no isolation gain; the
  worker's contract (classpath, `${out}`, key) is identical for every generator.
- **A Gradle-style task DSL** would put code in the manifest; `jk.toml` stays data.

## Further reading

- Blueprint plugins: `plugins/spring-boot/`, `plugins/quarkus/`, `plugins/grails/`
- SPI sources: `shared/plugin-sdk/`
- Packaging matrix: [user packaging](../user/packaging.md)
- Frameworks (Quarkus / Grails / platforms): [user frameworks](../user/frameworks.md)
- Architecture context: [architecture.md](architecture.md)
