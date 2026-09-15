# Authoring build plugins

A build plugin teaches jk a new `jk.toml` table — `[spring-boot]`, `[grails]`, `[quarkus]`,
`[android]`, `[protobuf]` — and shapes the standard commands around it. First-party examples
live under [`plugins/`](../../plugins/) (start with [`plugins/spring-boot`](../../plugins/spring-boot)
or [`plugins/quarkus`](../../plugins/quarkus)).

**Who this is for (pre-1.0):** **first-party** plugins in this monorepo, and **private/
vendored** plugin jars (path or Maven pin + required `sha256`). A public third-party
authoring path is **deferred until ~1.0** when the plugin SPI freezes — `jk-plugin-sdk` is
**not** published to Maven Central yet. Do not plan on consuming a released SDK coordinate
from outside this tree until that lands.

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

Compile against the in-tree **`plugin-sdk`** module (`shared/plugin-sdk`, artifact name
`jk-plugin-sdk` when published later). Keep the worker at a JDK floor compatible with user
projects (first-party workers target `--release 17` where they ride the user’s JVM).

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

[schema]
version = { type = "string", required = true, example = "4.1.0",
            hint = "platform BOM version — a release such as 4.1.0 (exact), ^4 for the newest 4.x, or latest" }
aot     = { type = "bool" }   # no default = tri-state
```

Schema types: `string`, `bool`, `int`, `string-list`. Missing `required` keys fail parse with
your `example` / `hint`.

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

**Interpolation (closed set):** `${config.<key>}`, `${kotlin.version}`,
`${project.group|name|version}`, `${host.os}`, `${host.os-arch}`.

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
| **Public third-party** SDK on Maven | **Not available** until ~1.0 SPI freeze |
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
2. Compile against in-tree `plugin-sdk` (or a future published `jk-plugin-sdk` after ~1.0);
   never require engine classes on the plugin classpath.
3. Pin: `sha256sum vendor/your-plugin.jar` → paste into `sha256`.
4. `jk lock` materializes the manifest under `target/plugin-manifests/<sha>.jk-plugin.toml`.
5. Code layer: trust once, then worker forks use the locked CAS jar (action keys include jar hash).

Private plugins **error** if they claim a table or id already owned by a built-in plugin.

## Further reading

- Blueprint plugins: `plugins/spring-boot/`, `plugins/quarkus/`, `plugins/grails/`
- SPI sources: `shared/plugin-sdk/`
- Packaging matrix: [user packaging](../user/packaging.md)
- Frameworks (Quarkus / Grails / platforms): [user frameworks](../user/frameworks.md)
- Architecture context: [architecture.md](architecture.md)
