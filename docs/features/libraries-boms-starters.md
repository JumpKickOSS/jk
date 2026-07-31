# PRD: Libraries, BOMs, starters, and catalogs

**Status:** accepted (design frozen for implementation)  
**Audience:** implementers, importers, first-party plugins, kanartist tickets (`JK-NNNN`) 
**Related code:** `LibraryCatalog`, `LibraryRegistryClient`, `JkBuildParser` `[libraries]`,
`[platform-dependencies]`, `GradleVersionCatalog` / `GradleImporter`, Boot plugin / `jk new --spring`

---

## 1. Problem

jk adopters — especially Spring Boot and Quarkus — expect **starter-style** ergonomics:
one BOM pin, versionless capability modules, short names, and clean migration from Gradle
version catalogs. Internally we already have several mechanisms that look similar (library
catalog, platform BOMs, real Maven starters, features). Without a frozen vocabulary and
layering story, tickets reinvent catalogs, put versions in the wrong place, or try to
replace vendor starters with a jk-only graph.

This PRD freezes **what each mechanism is for**, what it must never do, and what “starter
support” means for 1.0-quality adoption.

---

## 2. Goals

1. **Clear product model** — four distinct concepts: catalog, platform BOM, starter, bundle
   (optional). Features stay on the dependency graph, not in the catalog.
2. **Platform-friendly** — Spring/Quarkus (and similar) adopt via **their** Maven BOMs +
   starter/extension artifacts + jk plugins/scaffold, not a parallel starter format.
3. **Layered short names** — project → host-local → admin-global → bundled, all **name → GA only**.
4. **Import alignment** — Gradle version catalogs feed coords/versions; short names normalize
   through the existing catalog stack when possible.
5. **Lockfile-first** — versions and reproducibility live in deps / platform pins / `jk-lock.toml`,
   never in the global name index.

## 3. Anti-goals

1. **Do not** put versions into the library catalog (any layer). Reject `group:artifact:version`
   in catalog files (already enforced).
2. **Do not** turn `libs.global.toml` / host local / `[libraries]` into a versioned mega-BOM or
   multi-GA “super starter” graph maintained by jk admins.
3. **Do not** reimplement Spring Boot starters or Quarkus extensions as jk-only meta-packages
   that lag upstream releases.
4. **Do not** evaluate Gradle/Groovy/Kotlin build scripts to “fully” understand catalogs
   (string-level import + on-disk `libs.versions.toml` remains the fidelity ceiling unless a
   later PRD says otherwise).
5. **Do not** collapse platform BOM pins into the **catalog** (catalog stays name→GA only);
   enforcement belongs to resolve, not catalog layers.
6. **Do not** grow unbounded public user docs for this — guide stays short; this PRD is the
   deep reference for tickets.

---

## 4. Vocabulary (normative)

| Term | Meaning | Versions? | Graph edges? |
|---|---|---|---|
| **Library catalog** | Layered map **short name → `group:artifact`** | No | No |
| **Platform / BOM** | `[platform-dependencies]` entry; enforced Maven-style dependencyManagement at resolve | Yes (on the BOM pin) | Manages versions of other GAs at resolve |
| **Starter** | A **published** Maven module (e.g. `spring-boot-starter-web`, Quarkus extension) whose POM expands a curated transitive set | Usually versionless under a BOM | Via real POM, not catalog |
| **Bundle** (optional future) | jk data expand: one name → N catalog short names / GAs at **parse** time | No in the bundle definition | Fan-out only; not a Maven artifact |
| **Feature** | Optional capability set on a **library** (`[features]` / consumer `features = […]`) | Via optional deps’ own pins / BOM | Yes, on that library’s graph |
| **Lockfile** | Canonical resolved versions + checksums | Yes | Resolved graph |

If user-facing docs say “starter,” they mean a **Maven starter artifact**, not a catalog bundle.

---

## 5. Library catalog layers

Precedence **high → low** (first hit wins on lookup):

| Layer | Source | Owner | Scope |
|---|---|---|---|
| **project** | `jk.toml` `[libraries]` | Repo | One project / workspace |
| **local** | `~/.jk/libs.toml` (layer name `"local"`) | Human / host | All projects on this machine |
| **global** | `~/.jk/cache/libs.global.toml` | jk admins via `jk library update` | Downloaded registry |
| **bundled** | classpath `libraries.toml` | Ship with the binary | Offline cold start |

### 5.1 Schema (all layers)

```toml
[libraries]
junit-jupiter = "org.junit.jupiter:junit-jupiter"   # name = "group:artifact" only
```

- **Required shape:** `name = "group:artifact"`.
- **Forbidden:** embedding a version in the value string.
- **Curation (bundled/global):** Java/Kotlin ecosystem; one canonical short name per artifact
  when possible; major **coordinate** forks get distinct names (e.g. `jackson2-*` /
  `jackson3-*`); no silent default major for those forks.

### 5.2 What goes in which layer

| Need | Layer |
|---|---|
| Public curated short names | **global** (+ **bundled** subset) |
| Host/org conventions without committing to every repo | **local** |
| Repo-specific renames / private GAs | **project** `[libraries]` |
| “What version do we use?” | **Not the catalog** — dep / BOM / lock / optional future `[versions]` |

### 5.3 Commands

- `jk library list|search|update` — inspect layers; refresh global from the registry URL.
- `jk add <short-name>@…` / shorthand deps in `jk.toml` — resolve names through the layered catalog.
- Optional later: `jk library import` from Gradle `libs.versions.toml` writes **GA aliases only**
  into **project** (default) or **local** (`--user`); never into global by default.

---

## 6. Platform BOMs

### 6.1 Behavior

```toml
[platform-dependencies]
boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "3.4.0" }

[dependencies]
web = { group = "org.springframework.boot", name = "spring-boot-starter-web" }  # versionless OK
```

Plugin tables inject their platform for you: `[spring-boot] version` imports
`spring-boot-dependencies`, `[grails] version` imports the Apache `grails-bom`
(which itself imports `spring-boot-dependencies`) — versionless `grails-*` /
starter entries resolve under either.

- BOM is **not** on the runtime classpath as a normal jar of “everything.”
- Managed GAs are **enforced** at resolve by default (BOM pin on the edge). While any platform
  BOM is active, bare EffectivePom-filled versions are exact as well. Opt-in
  `[resolve] platform = "floor"` (or `jk update --platform=floor`) treats managed pins as
  floors only (JK-1206).
- The BOM pin itself must be exact or caret/tilde-anchored (not floating `latest`).
- **Export:** `jk export bom` freezes lockfile versions for a scope into a publishable Maven
  BOM (JK-1207) — producer side of the platform story.

### 6.2 Catalog interaction

- Catalog may map short names for **BOM GAs** and **starter GAs**.
- Catalog does **not** store the Boot/Quarkus version or the managed set.
- Prefer shorthand after catalog lookup, e.g. `boot = "3.4.0"` once the short name resolves.

### 6.3 Import

- `platform(…)` / platform-scoped catalog accessors → `[platform-dependencies]`.
- Version-less catalog libraries under an imported BOM → **platform-managed** roots when
  possible, not silent drops (import fidelity requirement; see §9).

---

## 7. Starters (normative product stance)

### 7.1 Definition

A **starter** in jk is a **vendor-published Maven module** whose POM declares a curated
dependency set (Spring Boot starters, Quarkus extensions, similar “capability modules”).

jk’s job:

1. Resolve them as normal dependencies under a platform BOM when versionless.
2. Expose short names in the catalog where curated.
3. Scaffold (`jk new --spring`, future Quarkus) and plugins (packaging/dev) for the stack.
4. Import them cleanly from Gradle/Maven.

jk’s job is **not** to maintain a parallel list of “jk starters” that duplicate Boot/Quarkus
release trains.

### 7.2 Golden path (platforms)

```toml
[platform-dependencies]
# short name preferred once cataloged
spring-boot-dependencies = "3.4.0"

[dependencies]
spring-boot-starter-web = true          # or versionless table / shorthand per parser rules
# test scope:
# spring-boot-starter-test = "…"
```

Exact shorthand syntax for “versionless under platform” must match `JkBuildParser` (platform-managed
placeholder or omitted version with group/name). Implementations and tickets must not invent a
third form without updating this PRD.

### 7.3 Adoption risk (what actually blocks platforms)

| Risk | Mitigation |
|---|---|
| Painful BOM + starter authoring | Docs + short names + `jk new` templates |
| Import drops version-less catalog entries | ticket-1008 and follow-ups |
| No plugin/scaffold for the stack | first-party plugin + `new` |
| Catalog mistaken for a BOM | this PRD + guide wording |
| jk-only meta-starters lag upstream | **don’t build them** |

### 7.4 Optional convention (non-normative UX)

`jk add spring-boot-starter-web` without a Boot platform present may **warn** or offer to add
the matching BOM. Soft UX only; not a hard resolver rule unless a later ticket specifies.

---

## 8. Bundles (optional, secondary)

A **bundle** is a pure data expand: one name → N catalog short names (or GAs) at parse time.

- **Not** a Maven artifact; **not** called a “starter” in user docs.
- Useful for non-vendor sets (`testing`, `logging`) and Gradle bundle import.
- Definitions may live in project/local data; global bundles only if curation cost is justified.
- Expand produces ordinary deps; versions still from user, BOM, or future version refs.

**Pre-1.0:** bundles are optional. Prefer real starters + BOM for Boot/Quarkus.

---

## 9. Features vs starters

| | Features | Starters |
|---|---|---|
| Defined on | A library’s `[features]` (and consumer selection) | Vendor Maven module |
| Activates | That library’s `optional = true` deps | Transitives via the starter’s POM |
| Catalog role | None | Optional short name for the starter GA |
| Status | Local + path cross-package (JK-1006); Maven-coord later | Core resolve model |

Do not implement starters as feature tables in the library catalog.

---

## 10. Gradle version catalogs vs jk catalogs

| | Gradle `libs.versions.toml` | jk library catalog |
|---|---|---|
| Alias → GA | Yes | Yes |
| Alias → version | Yes | **No** |
| Bundles | Yes | Optional later |
| Plugins | Yes | Out of scope for library catalog |
| Layered host/global | Unusual | **Yes** |

### 10.1 Import mapping (normative intent)

When importing Gradle:

| From Gradle | Into jk |
|---|---|
| library alias → `g:a:v` | Dep line with version; **prefer** reverse-map to catalog short name when GA matches |
| library alias → `g:a` only | Platform-managed / versionless root if a BOM is in play; else report, don’t silent-drop |
| `version.ref` / rich version | Flatten to a single selector string on the dep or platform pin |
| bundle | Expand to N deps (or document limitation until bundles land) |
| plugin accessors | Warning / plugin import rules — not library catalog |

Import must **not** write versions into any catalog layer.

### 10.2 Optional `jk library import`

- Default write target: **project** `[libraries]` (GA only).
- Host-wide: **local** layer with an explicit flag.
- Never overwrite **global** from a random project catalog by default.

---

## 11. Optional future: version refs (orthogonal)

Project/workspace **`[versions]`** / `version.ref` may be added later for monorepo DRY
version *strings*. That is **not** a catalog layer and **not** a BOM:

- Does not manage other modules’ versions the way a Maven BOM does.
- Does not replace `[platform-dependencies]` for Boot/Quarkus.
- Must not be stored in global/local library catalog files.

---

## 12. Requirements summary

### Must (1.0-quality / current architecture)

| ID | Requirement |
|---|---|
| R1 | Catalog remains layered name→GA only (project / local / global / bundled). |
| R2 | Platform BOMs are enforced at resolve (managed GAs + bare EffectivePom fills); lockfile is law for builds. |
| R3 | Starters are normal Maven deps; golden path documented with BOM + versionless roots. |
| R4 | User/docs vocabulary distinguishes catalog, platform, starter, bundle, feature. |
| R5 | Import never silently drops version-less catalog libs when a BOM can own them (track in tickets). |
| R6 | Global catalog updates do not rewrite project version pins. |

### Should

| ID | Requirement |
|---|---|
| S1 | Reverse-map imported GAV to catalog short names when unique. |
| S2 | Catalog short names for major BOMs and common starters (Boot first). |
| S3 | Scaffold emits idiomatic platform + starters for first-party stacks. |
| S4 | `jk add` / diagnostics may hint missing BOM when adding a known starter GA. |

### Could (post-core)

| ID | Requirement |
|---|---|
| C1 | Versionless bundles (parse expand). |
| C2 | `jk library import` from Gradle catalog into project/local layers. |
| C3 | Project/workspace `[versions]` refs. |
| C4 | Cross-package features for Maven-coord libraries (beyond path). |

---

## 13. Ticket references

kanartist tickets (`JK-NNNN`) should link this PRD when touching:

- Library catalog layers, registry, `jk library *`
- `[libraries]`, shorthand deps, `jk add` short names
- `[platform-dependencies]`, enforced platform BOMs, platform-managed roots
- Gradle/Maven import of catalogs, version-less deps, bundles
- Spring/Quarkus scaffold and “starter” UX wording
- Optional bundles / `[versions]` designs

Examples: JK-1008 (import fidelity), platform/BOM work, catalog registry curation.

---

## 14. Decision log

| Date | Decision |
|---|---|
| 2026-07 | Catalog is versionless name→GA at all layers; project `[libraries]` is the top layer (already implemented). |
| 2026-07 | Starter support = BOM + vendor Maven starters + plugins/scaffold — **not** a jk-native versioned starter graph in the catalog. |
| 2026-07 | Bundles are optional and secondary; do not call them starters. |
| 2026-07 | Default platform policy **enforced**; opt-in **floor** (`[resolve] platform` / `jk update --platform=floor`) — JK-1206. |
| 2026-07 | **`jk export bom`** freezes lockfile versions for a scope into a Maven BOM POM — JK-1207. |
| 2026-07 | Host local catalog is the place for machine/org short names without a private registry. |

---

## 15. One-sentence summary

**The catalog is a phone book of coordinates; the BOM is version policy; starters are real Maven modules; the lockfile is truth — platforms adopt jk by making that path obvious, not by inventing a second graph.**
