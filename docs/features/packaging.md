# Packaging matrix (thin / fat / shrink / Boot)

**Ticket:** [1032](../kanban/ticket-1032-assembly-packaging-depth.md)

JumpKick has **four** intentional packaging paths. Pick one product story; do not enable R8 by
default.

| Artifact | How | Command | R8? |
|---|---|---|---|
| **Thin jar** | default | `jk build` | no |
| **Assembly jar** | `[application] assembly = true` | `jk assembly` / `jk assemble` / `jk build` | no |
| **Shrunk jar** | `[shrink]` table (+ shrink plugin) | `jk build` | yes (opt-in) |
| **Spring Boot jar** | spring-boot plugin | `jk build` | plugin-owned |

## Thin jar (default)

```toml
[project]
# …
# no [application] assembly — package-jar only
```

```bash
jk build    # target/<name>-<version>.jar (or layout default)
```

## Assembly jar

```toml
[application]
main = "com.example.App"
assembly = true
```

```bash
jk assembly   # alias: jk assemble — errors with a one-line fix if assembly is off
jk build      # same packaging graph when assembly = true
```

**Merge rules** (engine `AssemblyPackager`):

- Concatenate `META-INF/services/*`
- Concatenate `META-INF/spring.handlers`, `spring.schemas`, `spring.factories`, and Boot
  `AutoConfiguration.imports`
- Project classes win on path conflict; dependency jars earlier in the graph win among themselves

**Excludes:**

- `META-INF/*.SF` / `*.RSA` / `*.DSA` / `*.EC` / `SIG-*` (signatures)
- `module-info.class` (JPMS descriptors from deps break a single classpath jar)

Sample: [examples/assembly-app/](examples/assembly-app/).

## Shrunk jar (R8)

```toml
[application]
main = "com.example.App"

[shrink]
# optional: obfuscate = false   # default
# optional: keep = ["-keep class com.example.** { *; }"]
# optional: keep-files = ["proguard-rules.pro"]
```

Enable the first-party shrink plugin (see plugin docs / workspace conventions). Build summary
labels size before → after. **Not** on by default.

Sample: [examples/shrunk-cli/](examples/shrunk-cli/).

## Spring Boot

Use the spring-boot first-party plugin — a **different** layout (`BOOT-INF/…`), not `assembly`.
Do not combine assembly with Boot packaging for the same product.

## Mental model

```text
thin      → package-jar
assembly  → package-assembly   (jk assembly / jk assemble)
shrunk    → shrunk-jar packager (R8)
boot      → spring-boot packager
```
