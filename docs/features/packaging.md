# Packaging matrix (thin / fat / shrink / Boot)

**Ticket:** [1032](../kanban/ticket-1032-assembly-packaging-depth.md)

JumpKick has **four** intentional packaging paths. Pick one product story; do not enable R8 by
default.

| Artifact | How | Command | R8? |
|---|---|---|---|
| **Thin jar** | default | `jk build` | no |
| **Fat / assembly jar** | `[application] shadow-jar = true` | `jk build` or `jk assembly` | no |
| **Shrunk jar** | `[shrink]` table (+ shrink plugin) | `jk build` | yes (opt-in) |
| **Spring Boot jar** | spring-boot plugin | `jk build` | plugin-owned |

## Thin jar (default)

```toml
[project]
# …
# no [application] shadow-jar — package-jar only
```

```bash
jk build    # target/<name>-<version>.jar (or layout default)
```

## Fat / assembly jar

```toml
[application]
main = "com.example.App"
shadow-jar = true
```

```bash
jk assembly   # errors with a one-line fix if shadow-jar is off
jk build      # same packaging graph when shadow-jar = true
```

**Merge rules** (engine `ShadowPackager`):

- Concatenate `META-INF/services/*`
- Concatenate `META-INF/spring.handlers`, `spring.schemas`, `spring.factories`, and Boot
  `AutoConfiguration.imports`
- Project classes win on path conflict; dependency jars earlier in the graph win among themselves

**Excludes:**

- `META-INF/*.SF` / `*.RSA` / `*.DSA` / `*.EC` / `SIG-*` (signatures)
- `module-info.class` (JPMS descriptors from deps break a single classpath jar)

Sample: [examples/fat-jar-app/](examples/fat-jar-app/).

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

Use the spring-boot first-party plugin — a **different** layout (`BOOT-INF/…`), not `shadow-jar`.
Do not combine shadow-jar with Boot packaging for the same product.

## Mental model

```text
thin  → package-jar
fat   → package-shadow   (jk assembly)
shrunk → shrunk-jar packager (R8)
boot  → spring-boot packager
```
