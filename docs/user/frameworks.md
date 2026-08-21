# Frameworks

First-party plugins teach JumpKick extra `jk.toml` tables and shape `jk build` / `jk test`
/ `jk image` around them. You **use** them by declaring the table (and usually a template).
Authoring plugins: [contributor plugin guide](../contributors/plugins.md) (pre-1.0: first-party
and private/vendored jars — the SDK is not on Central yet).

User-facing plugin install: [Plugins](plugins.md). Templates: [Templates](templates.md).

## Spring Boot

```bash
jk new -t spring-boot/hello my-api
```

Pin the platform with `[spring-boot] version` (or `[platform-dependencies]`). Starters are
versionless under the BOM. `jk build` produces a Boot jar (plugin-owned, not assembly
packaging). DevTools is picked up by [`jk watch run` / `jk dev`](run.md).

## Quarkus

```bash
jk new -t quarkus/hello my-api
```

- `[quarkus] version = "latest"` (first `jk lock` pins the current stable BOM). A
  major-line floor (`"3"`) or exact pin (`=3.38.0`) also works.
- Starters / extensions are **versionless** under `[dependencies]` (`quarkus-rest`,
  `quarkus-rest-jackson`).
- Default package is **fast-jar** (`quarkus-run.jar` + `lib/` + `quarkus-app/`). Set
  `package = "uber-jar"` for a single runner. Packaging uses pure bootstrap (no permanent
  `mvn` CLI).
- **Native:** `[native] enabled = "always"` (or a bare `[native]` / `enabled = true` for
  `jk native` only) builds through **Quarkus’s own** native-image command. JumpKick
  supplies the GraalVM toolchain. Nothing jk composes is added on top; `[native] args`
  still applies.
- Use a plain `main` + `Quarkus.run` (as scaffolded). Avoid `@QuarkusMain` under jk’s
  `target/classes/main` layout — `@QuarkusTest` can report two mains with the same name.
- Keep `quarkus-junit5` / RestAssured on **`[test-dependencies]`** only so MAIN does not
  pull Maven embedder.
- **Multi-module:** workspace path deps are packaged into `lib/main` for the Quarkus app
  module. Prefer a small `@ApplicationScoped` holder in the app module over CDI producers
  whose return types live only in sibling jars (Jandex). Synthetic `pom.xml` is for
  tooling only — JumpKick owns resolve via `jk-lock.toml`.

Cold first lock of the Quarkus platform is large; warm CAS re-locks are fast.

## Grails

Grails 8 (Apache, Spring Boot 4) on the Groovy lane:

```bash
jk new -t grails/hello my-svc
```

```toml
groovy = "latest"

[grails]
version = "8.0.0-M4"          # 8.x milestone floor; "latest" would pick Grails 7 GA

[dependencies]                # versionless under the BOM
grails-core     = { group = "org.apache.grails", name = "grails-core" }
grails-web-boot = { group = "org.apache.grails", name = "grails-web-boot" }
```

The plugin contributes `grails-app/*` source/resource roots, compiles with `--parameters`,
and `jk build` produces a Boot-launcher executable jar.

## Micronaut

```bash
jk new -t micronaut/hello my-api
```

HTTP service scaffold (java / kotlin). Minified jars: by-name indexes and generic-reflection
limits — [Packaging](packaging.md#minified-jar-r8).

## Android

First-party Android plugin for app/library modules. This is **not** AGP parity. Use `jk`
for the JumpKick-supported Android path; keep `jk gradle` when you still need full AGP.

## Protobuf

`[protobuf]` table: generate Java (and friends) from `.proto` sources as part of the
compile graph. The plugin owns the generate stage; `jk.toml` stays data.

## Related

[Platforms](platforms.md) · [Packaging](packaging.md) · [Templates](templates.md) ·
[Native](native.md)
