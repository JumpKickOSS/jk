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
the BOM pin are rejected.

```bash
jk tree -s platform          # BOM under the platform section, tagged (platform)
```

GAs the platform does **not** manage keep **highest-wins** by default (Maven/Gradle
parity). Opt into exact fills for unmanaged GAs with `[resolve] unmapped = "strict"`
(every unmanaged diamond is a hard error). Exact user roots still override the BOM for
that GA.

Without any platform BOM, bare transitives still use highest-version-wins floors (not
Maven nearest-wins), with PubGrub prose on conflict.

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
