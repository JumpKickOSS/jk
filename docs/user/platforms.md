# Platform BOMs and starters

A **platform BOM** is a Maven BOM you pin in `[platform-dependencies]` (or a plugin table
such as `[spring-boot] version` / `[quarkus] version`). JumpKick treats it as Maven
`dependencyManagement`: it is a **pin source**, not a runtime jar.

A **starter** is a published Maven module (`spring-boot-starter-web`, a Quarkus extension)
whose POM expands a curated transitive set. Under a BOM, starters are usually **versionless**
in `[dependencies]`.

JumpKick does **not** reimplement vendor starters as a parallel catalog. Short names in the
[library catalog](dependencies.md#library-catalog) are `name → group:artifact` only.

## Enforced vs floor

By default platforms are **enforced**: GAs listed in the BOM map use the BOM pin on
transitive edges. Explicit Maven ranges on a POM edge remain open ranges.

| Policy | Config | BOM-map pin |
|--------|--------|-------------|
| **enforced** (default) | omit / `[resolve] platform = "enforced"` | exact |
| **floor** (opt-in) | `[resolve] platform = "floor"` or `jk update --platform=floor` | lower bound (may highest-wins lift) |

```toml
[platform-dependencies]
spring-boot-dependencies = "4.1.0"

# or, via the plugin:
[spring-boot]
version = "latest"    # first jk lock pins the current stable BOM
```

Use `latest`, an exact pin, or a caret/tilde floor on the BOM itself. First `jk lock`
records the concrete BOM version (and `pinned-by` on managed lock rows). Open ranges on
the BOM pin are rejected. The tools a plugin fetches for packaging (Boot's loader, Quarkus's
bootstrap) follow that locked version, not the selector you wrote.

```bash
jk tree -s platform          # BOM under the platform section, tagged (platform)
```

A version the platform BOM manages replaces the version a transitive POM declares, the
way Gradle's `platform()` does, while an exact version you declare yourself still beats
the BOM. Inside the BOM the precedence is Maven's: entries the BOM (or its parents)
declares win over the BOMs it imports, and among imports the first wins.

GAs the platform does **not** manage resolve to the highest version the POMs that name
them declare (Maven/Gradle parity). Opt into exact fills for unmanaged GAs with
`[resolve] unmapped = "strict"` (every unmanaged diamond is a hard error). Exact user
roots still override the BOM for that GA.

Without any platform BOM, the same highest-declared rule applies to every transitive
(see [Dependencies](dependencies.md)), with PubGrub prose on conflict.

## Export a BOM from your lock

Library / platform authors can freeze a lock scope as a publishable Maven BOM POM:

```bash
jk export bom
jk export bom --scope test
jk export bom --out dist/my-bom.pom --overwrite
```

Import that POM like any other platform BOM (`[platform-dependencies]`).

## Framework notes

- **Spring Boot** — `[spring-boot] version`; starters versionless under the BOM.
  [Frameworks](frameworks.md).
- **Quarkus** — `[quarkus] version = "latest"` (or a major-line floor / exact pin);
  extensions versionless. [Frameworks](frameworks.md#quarkus).
- **Grails 8** — `[grails] version`; Groovy lane. [Frameworks](frameworks.md#grails).

Cold first lock of a large platform is slow if the artifact store is empty; warm CAS
re-locks are fast.

## Related

[Dependencies](dependencies.md) · [Lockfile](lockfile.md) · [Frameworks](frameworks.md)
