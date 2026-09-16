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
spring-boot-dependencies = "4.1.0"     # exactly that BOM release

# or, via the plugin:
[spring-boot]
version = "4.1.0"     # the same pin; "^4" floats within 4.x, "latest" takes the newest stable

[dependencies]
web  = "org.springframework.boot:spring-boot-starter-web"   # versionless: the BOM manages it
jdbc = "org.springframework.boot:spring-boot-starter-jdbc"
```

The BOM version follows the [version grammar](projects.md#version-strings): a bare version is
that release, `^4` / `~4.1` are opt-in floors, `latest` is opt-in. Open ranges on the BOM pin
are rejected. `jk lock` records the concrete BOM version (and `pinned-by` on managed lock
rows); `jk update` bumps the pin like any other. The tools a plugin fetches for packaging
(Boot's loader, Quarkus's bootstrap) follow that locked version, not the selector you wrote.

A managed dependency is a versionless GAV string — `group:artifact` with no third slot — or an
inline table without `version`. Writing a version on it is a user root, and a user root beats
the BOM for that coordinate.

```bash
jk tree -s platform          # BOM under the platform section, tagged (platform)
```

A version the platform BOM manages replaces the version a transitive POM declares, the
way Gradle's `platform()` does, while an exact version you declare yourself still beats
the BOM. Inside the BOM the precedence is Maven's: entries the BOM (or its parents)
declares win over the BOMs it imports, and among imports the first wins.

## Two BOMs that manage one module

`[platform-dependencies]` is an ordered table. When two of its BOMs manage the same module at
different versions, what happens is decided by `[resolve] pins`:

| Policy | Two BOMs disagree on a module |
|--------|-------------------------------|
| `pins = "exact"` (default, hand-written manifests) | `jk lock` refuses: `platform BOM conflict on g:a: X constrains to 2.10.1, but Y constrains to 2.13.2` — pick one BOM, pin the module yourself, or opt into the rule below |
| `pins = "nearest"` (what `jk import` writes) | Maven's rule: the first-declared BOM that manages the module wins, the later BOM's say is dropped, the lock row's `pinned-by` names the winner, and `jk lock` prints one line per such module naming the winner and every BOM it overrode |

The rule is Maven's for `<dependencyManagement>` imports — the first `import` that manages a
coordinate wins, in declaration order — and `jk import` writes the BOMs in the order the POM
declares them, so an imported project resolves to the versions Maven built with. Your own exact
pin on the module beats every BOM under both policies. In a workspace the table is the root's
entries followed by each member's in `[workspace] modules` order, each in its own declaration
order, so a BOM the root declares wins over one a member declares. A member's BOM constrains its
own graph and the graphs of members that depend on it, not an unrelated member's — a member the
workspace's BOM-lifted version cannot serve gets its own rows
([Workspaces](workspaces.md#members-that-disagree)).

`nearest` adopts exactly two of Maven's rules: a direct pin is the version, over any transitive's
floor, and the first-declared BOM wins over a later one. It does not adopt Maven's mediation between
transitives by depth and declaration order: an unmanaged module that two POMs ask for at different
versions resolves to the highest declared version under both policies, and a workspace member's pin
is the version for the whole lock. On the Maven top-20 corpus in jk-examples, the 14 repositories
that lock were compared module by module against Maven's own resolution: 170 of 341 modules differ
on at least one version, 705 (module, coordinate) pairs in all. 278 of those pairs are inline
`<dependencyManagement>` entries Maven applies to transitives and jk applies to declared
dependencies only, 172 are a Boot BOM one member's `[spring-boot]` table brings that governs every
member's rows, and 111 pairs over 35 coordinates are depth mediation proper, where Maven's nearer
declaration is older than the highest one jk picks.

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
- **Quarkus** — `[quarkus] version = "3.38.0"` (that release; `^3` for the newest 3.x);
  extensions versionless. [Frameworks](frameworks.md#quarkus).
- **Grails 8** — `[grails] version`; Groovy lane. [Frameworks](frameworks.md#grails).

Cold first lock of a large platform is slow if the artifact store is empty; warm CAS
re-locks are fast.

## Related

[Dependencies](dependencies.md) · [Lockfile](lockfile.md) · [Frameworks](frameworks.md)
