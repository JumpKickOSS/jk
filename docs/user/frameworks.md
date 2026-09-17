# Frameworks

First-party plugins teach JumpKick extra `jk.toml` tables and shape `jk build` / `jk test`
/ `jk image` around them. You **use** them by declaring the table (and usually a template).
Authoring plugins: [contributor plugin guide](../contributors/plugins.md) — against the published
`cc.jumpkick:jk-plugin-sdk` coordinate, pinned by content in the consumer.

User-facing plugin install: [Plugins](plugins.md). Templates: [Templates](templates.md).

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
(`web = "org.springframework.boot:spring-boot-starter-web"`). `jk build` produces a Boot jar (plugin-owned, not assembly
packaging). DevTools is picked up by [`jk watch run` / `jk dev`](run.md).

`[spring-boot]` keys beside `version`:

| key | default | what |
|---|---|---|
| `aot` | on when `[native]` is declared, else off | run Spring's AOT processor (`SpringApplicationAotProcessor`) before packaging; the generated classes and hints go into the Boot jar with `spring.aot.enabled=true` |
| `aot-jvm-args` | `[]` | flags for the processor's JVM — the `--add-opens` a library needs on JDK 17+, a heap size |
| `aot-args` | `[]` | arguments handed to the application while it starts under processing |
| `include-tools` | `true` | nest `spring-boot-jarmode-tools` so `java -Djarmode=tools -jar app.jar` works |

`META-INF/build-info.properties` (what `BuildProperties` and the `/actuator/info` endpoint read)
and `git.properties` (`GitProperties`) come from the core [`[build-info]` table](packaging.md#build-info-gitproperties-and-boots-build-infoproperties),
which writes both into the classes tree the Boot jar nests.

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
  resolve.
- Starters / extensions are **versionless** under `[dependencies]` (`quarkus-rest`,
  `quarkus-rest-jackson`).
- Default package is **fast-jar** (`quarkus-run.jar` + `lib/` + `quarkus-app/`). Set
  `package = "uber-jar"` for a single runner. Packaging uses pure bootstrap (no permanent
  `mvn` CLI).
- **Native:** `[native] enabled = "always"` (or a bare `[native]` / `enabled = true` for
  `jk native` only) builds through **Quarkus’s own** native-image command. JumpKick
  supplies the GraalVM toolchain. Nothing jk composes is added on top; `[native] args`
  still applies.
- Use a plain `main` + `Quarkus.run` (as scaffolded).
- Keep `quarkus-junit5` / RestAssured on **`[test-dependencies]`** only so MAIN does not
  pull Maven embedder.
- **Tests:** `@QuarkusTest` boots from an application model jk writes before the module's tests
  run (the `quarkus-test-model` step): the locked test closure resolved through Quarkus's own
  bootstrap, with `target/classes/main` as the application's one root. The forked test JVM gets
  its path as `-Dquarkus-internal-test.serialized-app-model.path=…`, the seam Quarkus's Gradle
  plugin uses, so the bootstrap indexes the application archive once and augments once per test
  profile; no `pom.xml` is read or written. The same step gives every test JVM of the module
  `-XX:MaxMetaspaceSize=1g`, since the bootstrap keeps one augmented application per profile
  resident; `[test] jvm-args` overrides it. The `[quarkus]` table is what turns this on — `jk
  import` writes it from `quarkus-maven-plugin` at the platform version.
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
version = "8.0.0-M4"          # exactly this 8.x milestone; "latest" would pick Grails 7 GA

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

## Protobuf

`[protobuf]` table: generate Java (and friends) from `.proto` sources as part of the
compile graph. The plugin owns the generate stage; `jk.toml` stays data.

```toml
[protobuf]
version = "4.33.1"        # the protoc release; pins com.google.protobuf:protoc in jk-lock.toml
src     = "proto"         # module-relative proto root, protoc's include root
lite    = false           # lite-runtime codegen (pairs with protobuf-javalite)
kotlin  = false           # also emit the Kotlin DSL (--kotlin_out; pairs with protobuf-kotlin)

[protobuf.grpc-java]      # one protoc plugin per [protobuf.<id>]; <id> is protoc's name for it
plugin  = "io.grpc:protoc-gen-grpc-java:1.81.0"
options = []              # the plugin's parameter, e.g. ["@generated=omit"]
```

protoc runs once over every `.proto` under `src`, writing `--java_out` (and `--kotlin_out`) and
each entry's `--<id>_out` into one generated directory that joins the module's sources, so a
`service` compiles against its gRPC stubs with no source root to declare. protoc and every
plugin executable are fetched from the repositories for the host's OS and architecture and pinned
in `jk-lock.toml`; an entry's `plugin` is a `group:artifact:version` and fewer segments fail the
parse naming the entry. A `.proto` a dependency jar carries — `google/protobuf/*.proto` in
protobuf-java, `google/rpc/status.proto` in proto-google-common-protos, a `provided` contract
library's own — is importable, as it is under Maven: the jars of the runtime closure and of the
compile classpath are both searched. The step re-runs when a proto, the table, a tool or either
classpath changes.
`jk import` writes the table and its entries from a POM's `protobuf-maven-plugin`
([Migration](migration.md#which-maven-plugins-import-and-how-well)).

## Related

[Platforms](platforms.md) · [Packaging](packaging.md) · [Templates](templates.md) ·
[Native](native.md)
