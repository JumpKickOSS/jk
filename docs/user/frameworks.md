# Frameworks

First-party plugins teach JumpKick extra `jk.toml` tables and shape `jk build` / `jk test`
/ `jk image` around them. You **use** them by declaring the table (and usually a template).
Authoring plugins: [contributor plugin guide](../contributors/plugins.md) — against the published
`cc.jumpkick:jk-plugin-sdk` coordinate, pinned by content in the consumer.

User-facing plugin install: [Plugins](plugins.md). Templates: [Templates](templates.md). A
JavaScript front end beside any of them (Vite, Next, Angular, …) is a node module, not a plugin:
[Node.js](node.md).

Each framework template ships a **rule pack** (`cc.jumpkick.guards:spring`, `:quarkus`,
`:android`; libraries `:library`, workspaces `:monorepo`) through `[guards] extends` in
`jk-guards.toml`: one logger, Jakarta not javax, test libraries in the test scope, `java.time`,
a file-size ratchet, and the framework's own rules. `jk guard explain` lists them; a violation
names its `Instead:`.

## Spring Boot

```bash
jk new -t spring-boot/hello my-api
```

Pin the platform with `[spring-boot] version = "4.1.0"` (or `[platform-dependencies]`); `"^4"`
is the opt-in floor for the newest 4.x. Starters are versionless under the BOM
(`spring-boot-starter-web = "managed"`, or `web = "org.springframework.boot:spring-boot-starter-web"`). `jk build` produces a Boot jar (plugin-owned, not assembly
packaging). DevTools is picked up by [`jk watch run` / `jk dev`](run.md).

`[spring-boot]` keys beside `version`:

| key | default | what |
|---|---|---|
| `aot` | on when `[native]` is declared, else off | run Spring's AOT processor (`SpringApplicationAotProcessor`) before packaging; the generated classes and hints go into the Boot jar with `spring.aot.enabled=true` |
| `aot-jvm-args` | `[]` | flags for the processor's JVM — the `--add-opens` a library needs on JDK 17+, a heap size |
| `aot-args` | `[]` | arguments handed to the application while it starts under processing |
| `include-tools` | `true` | nest `spring-boot-jarmode-tools` so `java -Djarmode=tools -jar app.jar` works |
| `platform` | `true` | import `spring-boot-dependencies` at `version` as the module's platform; `false` leaves the BOM out and `version` names only the Boot loader, as a Maven build that runs the Boot plugin without importing Boot's BOM (`jk import` writes it for one) |

`META-INF/build-info.properties` (what `BuildProperties` and the `/actuator/info` endpoint read)
and `git.properties` (`GitProperties`) come from the core [`[build-info]` table](packaging.md#build-info-gitproperties-and-boots-build-infoproperties),
which writes both into the classes tree the Boot jar nests. The loader and jarmode tools the Boot
jar nests are pinned in `jk-lock.toml` at the locked Boot version, by digest, as is each Kotlin
compiler plugin `[spring-boot]` adds to a Kotlin module ([Lockfile](lockfile.md#plugin-tools)).

The processor starts the application context in AOT mode and then generates code from it. When
the step fails, its message says which of the two failed and names the root cause first: the
application's own startup failing (a bean that cannot be created, a library that needs a JVM flag
— it fails the same way at run time) points at `aot-jvm-args` / `aot-args`; the processor crashing
on a context that did start points at `aot = false`, which packages the Boot jar without AOT (a
`[native]` image still needs the processor's output).

## Quarkus

```bash
jk new -t quarkus/hello my-api
```

- `[quarkus] version = "3.38.0"` pins that BOM release (`jk new` writes the current stable).
  `"^3"` is the opt-in floor for the newest 3.x; `"latest"` takes the newest stable at each
  resolve. Quarkus 2.0 and later only: `jk import` writes no `[quarkus]` table for a 1.x project
  and says so.
- Starters / extensions are **versionless** under `[dependencies]` (`quarkus-rest`,
  `quarkus-rest-jackson`).
- Default package is **fast-jar** (`quarkus-run.jar` + `lib/` + `quarkus-app/`). Set
  `package = "uber-jar"` for a single runner. Packaging uses pure bootstrap (no permanent
  `mvn` CLI). The application's own coordinate and its workspace siblings live in the
  augment's private repository, jar and POM together, so no repository is ever asked for
  them: Central refusing the request cannot fail the package step. When the augment does fail,
  the step's failure text opens with the fork's own `jk-quarkus-augment:` lines — the remote
  repositories it was routed to and the locked closure it resolved — above the fork's last 60
  lines, so a routing or closure problem is readable from `target/jk-results.md`.
- **Native:** `[native] enabled = "always"` (or a bare `[native]` / `enabled = true` for
  `jk native` only) builds through **Quarkus’s own** native-image command. JumpKick
  supplies the GraalVM toolchain. Nothing jk composes is added on top; `[native] args`
  still applies.
- Use a plain `main` + `Quarkus.run` (as scaffolded).
- Keep `quarkus-junit5` / RestAssured on **`[test-dependencies]`** only so MAIN does not
  pull Maven embedder.
- **Tests:** `@QuarkusTest` boots from an application model jk writes before the module's tests
  run (the `quarkus-test-model` step): the locked test closure resolved through Quarkus's own
  bootstrap, with `target/classes` as the application's one root. The forked test JVM gets
  its path as `-Dquarkus-internal-test.serialized-app-model.path=…`, the seam Quarkus's Gradle
  plugin uses, so the bootstrap indexes the application archive once and augments once per test
  profile; no `pom.xml` is read or written. The bootstrap keeps one augmented application per
  profile resident in the suite JVM, and the module's suite is one JVM (Surefire's single
  fork): Quarkus writes and removes its test-class index under the test classes and binds the
  application's ports, so parallel suite JVMs of one module would race — `[test] workers = N`
  overrides that pin ([Testing](test.md#per-module-serial-opt-out)). The discovery JVM that lists
  the classes first runs no Quarkus launcher listener and boots nothing ([Testing](test.md#the-test-jvms-flags-test-jvm-args-test-system-properties)). A test
  JVM's metaspace is the JVM's own — jk caps it only where `[test] jvm-args` says so, as Surefire
  does. The
  `[quarkus]` table is what turns this on — `jk import` writes it from `quarkus-maven-plugin` at
  the platform version.
- **Quarkus's own reactor:** `[quarkus] version` implies `io.quarkus.platform:quarkus-bom` at that
  version, a BOM repositories publish for releases only. Importing a reactor that builds
  `quarkus-bom` itself writes no `[quarkus]` table on a member whose plugin runs at the reactor's
  own version (`999-SNAPSHOT`); one report row names those members, which build as plain jars —
  `jk mvn package` keeps the augment. A `[quarkus]` table at a version no repository has fails the
  lock with an error naming the table and the BOM it implies, never as a bare missing coordinate.
- **Multi-module:** workspace path deps are packaged into `lib/main` for the Quarkus app
  module. Prefer a small `@ApplicationScoped` holder in the app module over CDI producers
  whose return types live only in sibling jars (Jandex). JumpKick owns resolve via
  `jk-lock.toml`; a `pom.xml` beside `jk.toml` is for IDE tooling only.

Cold first lock of the Quarkus platform is large; warm CAS re-locks are fast.

## Grails

**Contrib battery** (best-effort; see [Plugins](plugins.md#batteries-and-their-tiers)).

Grails 8 (Apache, Spring Boot 4) on the Groovy lane:

```bash
jk new -t grails/hello my-svc
```

```toml
groovy = "5.0.0"              # exact; jk new writes the current stable

[grails]
version = "8.0.0"             # exact grails-bom release; "^8" takes the newest 8.x

[dependencies]                # versionless under the BOM
grails-core     = "org.apache.grails:grails-core"
grails-web-boot = "org.apache.grails:grails-web-boot"
```

The plugin contributes `grails-app/*` source/resource roots, compiles with `--parameters`,
and `jk build` produces a Boot-launcher executable jar.

## Micronaut

```bash
jk new -t micronaut/hello my-api
```

HTTP service scaffold (java / kotlin). Minified jars: by-name indexes and generic-reflection
limits — [Packaging](packaging.md#minified-jar-r8). Native images: declare `[native]` and run
`jk native`; the plugin supplies Micronaut’s native-image arguments and switches AOT to the
native runtime — [Native](native.md).

Micronaut Test Resources (the Gradle/Maven plugin that starts Testcontainers-backed databases
and brokers for tests and dev mode) is not part of the jk plugin before 1.0. Dev mode itself is
covered by `jk watch run` (`jk dev`), which is framework-neutral; a test that needs a real
service declares it the ordinary way — Testcontainers in the test, or a `[test] env` pointing at
what CI provides. The gap versus `mn:run` / `./gradlew run` with test-resources is exactly that
one convenience: no service is started for you.

## Android

**Contrib battery** (best-effort; see [Plugins](plugins.md#batteries-and-their-tiers)).

First-party Android plugin for app/library modules. This is **not** AGP parity. Use `jk`
for the JumpKick-supported Android path; keep `jk gradle` when you still need full AGP.
Start from the `android/compose` template (`jk new my-app -t android/compose`) — a minimal
Compose app with a JVM unit test and the `jk run` deploy path; see
[Templates](templates.md).

JVM unit tests run under Robolectric when it is on the test classpath: `android-test-config`
writes its `test_config.properties`, naming a unit-test manifest and a unit-test resource package.
That manifest is the module's own merged as an application with every AAR on the test runtime
closure, test-scope ones included, and the resource package links the same closure's resources and
the module's against it, since Robolectric reads the manifest from the package. So
`androidx.compose.ui:ui-test-manifest` as a `[test-dependencies]` entry is enough for a
`ComposeTestRule` to launch its `ComponentActivity`. The same step gives the test JVM the JDK
flags Robolectric needs on JDK 17 and later (`--add-exports java.base/jdk.internal.access`,
`--add-opens java.base/java.io`), so `[test] jvm-args` stays yours. Robolectric runs JUnit 4
tests: declare `junit:junit` and `org.junit.vintage:junit-vintage-engine` beside it.

## IntelliJ Platform plugins

`[intellij]` builds a plugin for IntelliJ IDEA and the IDEs on its platform: the module compiles
against an IntelliJ distribution, its tests run inside a headless IDE, and its artifact is the
zip an IDE installs from disk (**Settings → Plugins → ⚙ → Install Plugin from Disk…**).

```toml
name    = "my-plugin"
version = "1.0.0"

[intellij]
version = "262.10968.63"   # the platform build: IntelliJ IDEA's About dialog shows IC-262.10968.63
plugins = ["java"]         # bundled plugins the code uses, by directory under the IDE's plugins/
libraries = ["platform:ide-impl", "java:java-impl"]   # platform modules to compile against, as Maven artifacts
# requires = ["platform-todo-plugin"]   # other bundled plugins the named ones need at run time
# product     = "ideaIC"   # ideaIC (Community, the default) or ideaIU (Ultimate)
# since-build = "262"      # defaults to the branch of version
# until-build = "263.*"    # no upper bound unless set

[repositories.jetbrains]
url    = "https://www.jetbrains.com/intellij-repository/releases"
groups = ["com.jetbrains.intellij.*"]
```

- **Compiling.** The code compiles against the platform modules `libraries` names — each is
  `<group suffix>:<artifact>` under `com.jetbrains.intellij`, at `version` — with their transitive
  closure, from JetBrains' repository (and `org.jetbrains.intellij.deps.fastutil` from the
  `intellij-dependencies` repository, which a module declares restricted to that group). The
  closure is about a tenth the size of the distribution and compile-only: `jk build` fetches it
  and never unpacks the distribution.
- **The distribution.** Only a run of the tests needs it.
  `com.jetbrains.intellij.idea:<product>:<version>` is fetched from JetBrains' repository and unpacked once into scratch space (`state/tmp/unpacked/`, which jk
  deletes a week after its last use) — and only in part: `lib/`, `modules/`, and the bundled
  plugins `plugins` and `requires` name, about a tenth of the plugin tree. A plugin the IDE needs
  that is in neither fails the test setup naming the directory to add to `requires`. Its `lib/`
  jars, and those of each bundled plugin `plugins` names, are the test JVM's classpath, never
  compiled against and never packaged. `jk ide` lists the compile jars as provided libraries.
- **Tests.** The platform test framework (`com.jetbrains.intellij.platform:test-framework` at the
  same build, with JUnit 4 for its `TestCase` bases) joins the test classpath. The test JVM is
  started with the distribution's own launcher flags from its `product-info.json`, a sandbox
  under `target/intellij-sandbox/<version>/`, the module's `plugin.xml` read from the classpath,
  and the jars of every bundled plugin the named ones require. All of a module's tests run in one
  JVM, as one IDE. The platform's test bases (`BasePlatformTestCase`, `HeavyPlatformTestCase`) are
  JUnit 3 `TestCase` classes: declare `junit = "junit:junit:4.13.2"` in `[test-dependencies]` and jk
  adds the JUnit Vintage engine, which runs them as written. Without it the run fails, naming the
  missing engine, rather than reporting no tests. The test JVM is the module's JDK, so pick a platform build that runs on it:
  2026.2 requires 25, and older platforms' debuggers do not start on 25.
- **The zip.** `target/<name>-<version>.zip` holds `<name>/lib/`: the module's classes as
  `<name>-<version>.jar` and its runtime dependencies. The packaged `META-INF/plugin.xml` gets
  `<version>` from the module and `<idea-version since-build … until-build …>` from the table;
  the source file is left as written.

The step that sets up the test JVM shows in `jk explain` as `intellij-test-env`; the packager is
`intellij-plugin`. Running a sandbox IDE, the plugin verifier and Marketplace publishing are not
part of the battery.

## Hibernate bytecode enhancement

**Contrib battery** (best-effort; see [Plugins](plugins.md#batteries-and-their-tiers)).

An entity model that relies on build-time enhancement — a `FetchType.LAZY` basic attribute that
must not load with its entity, inline dirty tracking, bidirectional association management —
behaves differently without it. `[hibernate] enhance = true` runs the enhancer of the project's
own Hibernate (whatever `hibernate-core` the module's runtime classpath resolves) over the
compiled classes after compile; the enhanced classes are the module's classes from then on, so
`jk test`, the jar, `jk run` and `jk dev` all use them, and a `jk dev` recompile re-enhances
before the app restarts.

```toml
[hibernate]
enhance                = true
lazy-initialization    = true    # default true
dirty-tracking         = true    # default true
association-management = false   # default false
extended-enhancement   = false   # default false
```

The switches are the `enable*` settings of `hibernate-enhance-maven-plugin` and the
`org.hibernate.orm` Gradle plugin, with their defaults. The step is cached on the compiled
classes, the runtime classpath and the switches: an unchanged module is a cache hit, and an
edited entity re-enhances that module. `jk import` writes the table from
`hibernate-enhance-maven-plugin`, from `hibernate-maven-plugin`'s `enhance` goal, and from the
Gradle plugin's `hibernate { enhancement { … } }` block.

## JavaFX

**Contrib battery** (best-effort; see [Plugins](plugins.md#batteries-and-their-tiers)).

JavaFX runs as named modules: its jars must be on the module path with `--add-modules`, which
`javafx-maven-plugin` and the `org.openjfx.javafxplugin` Gradle plugin arrange. `[javafx]` does
the same for the test JVM and `jk run`. The OpenJFX artifacts are ordinary dependencies, and the
lock pins this host's `javafx-graphics` classifier ([host classifiers](dependencies.md)).

```toml
[application]
main = "com.acme.Launcher"

[javafx]
modules = ["javafx.controls", "javafx.fxml"]   # the default; passed as --add-modules

[dependencies]
javafx-controls = { group = "org.openjfx", version = "27" }
javafx-fxml     = { group = "org.openjfx", version = "27" }
```

Every runtime jar that declares a `javafx.*` module joins the module path; the modules those
require resolve from it. Compilation needs nothing extra. A fat jar runs with `java -jar` only
through a main class that does not extend `javafx.application.Application` (a small launcher that
calls `Application.launch`), as with the Maven and Gradle plugins. A suite that opens windows
needs a display; on a headless CI host run it under `xvfb-run`.

`jk import` writes the table from `javafx-maven-plugin` (its `mainClass`, `module/class` included,
is `[application] main`) and from the Gradle plugin's `javafx { version; modules }` block, writing
the OpenJFX dependency the Gradle plugin adds for each module. A jlink image and jpackage installers
are not covered.

## Protobuf

`[protobuf]` table: generate Java (and friends) from `.proto` sources as part of the
compile graph. The plugin owns the generate stage; `jk.toml` stays data.

```toml
[protobuf]
version = "4.33.1"        # the protoc release; pins com.google.protobuf:protoc in jk-lock.toml
src     = "proto"         # module-relative proto root(s), protoc's include roots; a list declares several
exclude = []              # root-relative globs protoc never sees, e.g. ["Legacy.proto", "**/*_legacy.proto"]
lite    = false           # lite-runtime codegen (pairs with protobuf-javalite)
kotlin  = false           # also emit the Kotlin DSL (--kotlin_out; pairs with protobuf-kotlin)
replace = {}              # regular expression = replacement, over every generated file after protoc

[protobuf.grpc-java]      # one protoc plugin per [protobuf.<id>]; <id> is protoc's name for it
plugin  = "io.grpc:protoc-gen-grpc-java:1.81.0"
options = []              # the plugin's parameter, e.g. ["@generated=omit"]
```

protoc runs once over every `.proto` under `src` — one root, or each of a list (`src = ["proto",
"src/main/proto"]`) — that no `exclude` glob names (a glob matches the proto's path relative to its
root; one starting `**/` matches at the root too), writing `--java_out` (and `--kotlin_out`) and
each entry's `--<id>_out` into one generated directory that joins the module's sources, so a
`service` compiles against its gRPC stubs with no source root to declare. An entry's `plugin` is a
`group:artifact:version` and fewer segments fail the parse naming the entry.

protoc and every plugin executable are pinned in `jk-lock.toml` as a
[`[[tool]]` row](lockfile.md#plugin-tools): `jk lock` records the sha256 of the binary for each
platform the repository publishes (`linux-x86_64`, `linux-aarch_64`, `osx-x86_64`, `osx-aarch_64`,
`windows-x86_64`), and the build fetches `com.google.protobuf:protoc:<version>:<os-arch>!exe` (and
each plugin at the same classifier) for the host and refuses bytes other than the pinned ones. A
lock written on a Mac verifies the Linux CI download the same way. `jk update` moves `version` and
each `plugin` to the newest stable on their major and relocks the rows
([`jk update`](lockfile.md#jk-update)). protoc's include path is the module's `src` roots, then the `src` of every
workspace sibling the module's main compile reads (a `[dependencies]`, `[export-dependencies]` or
`[provided-dependencies]` sibling, and the siblings those reach) that has a `[protobuf]` table — a
proto imports a sibling's by bare name, as it does under Maven where the sibling's jar carries its
protos — then the protos
the runtime-closure jars carry, then those of the compile-only jars: `google/protobuf/*.proto` in
protobuf-java, `google/rpc/status.proto` in proto-google-common-protos, a `provided` contract
library's own are importable, as they are under Maven. The step re-runs when a proto of the module
or of one of those siblings, the table, a tool or either classpath changes.

`replace` rewrites what protoc wrote before it compiles: each key is a regular expression, each
value its replacement (`$1` names a group, as Java's `Matcher.replaceAll` reads it), applied in
declared order to every generated `.java` and `.kt` file. This is how a module compiles against a
shaded protobuf runtime — Hadoop's generated sources import `org.apache.hadoop.thirdparty.protobuf`
where protoc wrote `com.google.protobuf`:

```toml
[protobuf]
version = "3.25.5"
src     = "src/main/proto"
replace = { "([^\\.])com.google.protobuf" = "$1org.apache.hadoop.thirdparty.protobuf" }
```

`jk import` writes the table and its entries from a POM's `protobuf-maven-plugin`, and `replace`
from a `maven-replacer-plugin` execution over the plugin's output
([Migration](migration.md#which-maven-plugins-import-and-how-well)).

## Related

[Platforms](platforms.md) · [Packaging](packaging.md) · [Templates](templates.md) ·
[Native](native.md)
