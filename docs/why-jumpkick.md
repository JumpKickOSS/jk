# Why JumpKick

**Audience:** Anyone evaluating JVM build tools — Maven users looking for an upgrade,
Gradle users tired of build-as-code, and people who wonder why the default still feels
like 2005 or 2015.

**Thesis:** The market already voted. It wants a **finite, declarative build** more than
it wants a **programmable build**. Maven still leads because of that preference. Gradle
is a strong #2 with real power, but it is not the ergonomic successor most teams were
waiting for. JumpKick is that successor: **Cargo/uv-shaped UX on Maven Central**, without
XML and without turning your build into a second software project.

---

## What the market actually uses

Public surveys of Java developers (not Android-only, not “new GitHub toys”) still put
**Maven first** and **Gradle second** — for years, not as a blip.

| Source | Maven | Gradle | Notes |
|--------|------:|-------:|-------|
| [JetBrains *State of Java 2025*](https://lp.jetbrains.com/the-state-of-java-2025/) | **~67%** | clear #2 on the same multi-year chart | Headline: Maven is still “the most widely used build system.” Spring ~65% among frameworks. |
| [JetBrains Dev Ecosystem 2022 — Java](https://www.jetbrains.com/lp/devecosystem-2022/java/) | **~73%** | **~50%** | Multi-select (“which do you use”), so overlap is expected. |
| JetBrains series ~2019–2025 | Maven stays on top | Gradle plateaus as #2 | No knockout. Stable duopoly, not a Gradle takeover. |

**How to read the numbers**

- Questions are usually **multi-select**. Many people use both (Maven at work, Gradle for
  Android or a side module). Shares do not sum to 100%.
- JetBrains samples skew tool-aware, but they are the best **longitudinal** public series
  for *Java* developers.
- **Android is Gradle’s fortress** (AGP lives there). That does not mean Gradle owns
  server-side Java. Backend-heavy Java surveys still look Maven-led — and JetBrains’
  2025 Java cut is mostly backend/API work.

**Segment reality (not a single “winner”)**

| Segment | Typical default | Why |
|---------|-----------------|-----|
| Spring / enterprise services | **Maven** | Parent POMs, BOMs, CI templates, reviewable config |
| Android / much of Kotlin mobile | **Gradle** | Platform tooling assumes it |
| Greenfield multi-module “we need custom everything” | Often **Gradle** (or Bazel-class) | Escape hatch is the product |
| Regulated / long-lived backends | Often **Maven** | Predictable shape over infinite flexibility |

**Takeaway:** Gradle did not fail commercially — it is an obvious #2 with monopoly power
in Android. But **it did not displace Maven as the Java default**, because a large slice of
the market never wanted “a better programming language for builds.” They wanted **a better
build tool**.

---

## The hole in the market

### Maven: right shape, wrong surface

Maven’s enduring advantage is philosophical, not aesthetic:

- A **standard lifecycle** and a **finite vocabulary** of plugins and phases  
- The build is **data** (`pom.xml`), not an open-ended program  
- New contributors can read the project without learning a DSL  
- Code review of dependency and plugin changes stays tractable  

That is why “old habits” is only half the story. Preference for **predictability** is
rational. Maven still leads because it models a **build with a known shape**.

The tax is real in 2026:

- **Verbose XML** for everyday declarations  
- Weak day-to-day **CLI ergonomics** next to Cargo, npm, Go, uv  
- Lockfiles and modern resolve diagnostics are bolt-ons, not the product center  
- Incremental “skip what you can prove is done” is not the default mental model  

Nobody is nostalgic for editing 200-line POMs by hand. They are nostalgic for **not
debugging the build**.

### Gradle: more power, worse default experience

Gradle fixed real Maven pain (flexibility, multi-project graphs, incremental performance
*when the build is expertly written*). It also made a product bet:

> Here is a box of Legos. Good luck.

That bet is great for teams that **need** a programmable graph. It is a poor default for
teams that need a **standardized build**:

- The build **is** software — Groovy or Kotlin DSL, plugins, configuration cache caveats,
  version catalogs, convention plugins, settings scripts  
- There is **no finite shape** the way Maven’s lifecycle is finite; every shop reinvents
  house style  
- Onboarding cost is high: you learn a build system *and* a programming model  
- Maven users often find it **foreign**, not “Maven but nicer”  
- The best thing about Gradle (you can do anything) is the worst thing (you *will*
  eventually do anything, and maintain it forever)

**Gradle is not the solution the market was asking for as Maven’s successor.** It is a
different product that won Android and a share of power users. Headcount and survey
lead still sit with the **declarative** camp. That is the gap.

### What people actually want next

Something that is:

1. **More approachable than Maven** — no XML tax; modern config and CLI  
2. **More constrained than Gradle** — a finite build model, not an open language  
3. **Faster and more reproducible by default** — lockfile as law, cache what you can prove  
4. **Still on Maven Central** — same coordinates, BOMs, ecosystem; no parallel universe  

That is JumpKick.

---

## JumpKick’s answer

JumpKick takes Maven’s winning idea (a **declarative, conventional build**) and Gradle’s
valid performance ambitions (skip work, graph awareness, modern tooling), then rejects
both tools’ worst ergonomics.

| Principle | What it means |
|-----------|----------------|
| **Data, not a program** | `jk.toml` is TOML — readable, editable, reviewable. Not a scripting language. Not XML. |
| **Finite shape** | Convention-over-configuration; plugins extend a known model instead of inventing a new graph every repo. |
| **Lockfile is law** | `jk-lock.toml` at the workspace root; `jk build` does not re-resolve when the lock is valid. |
| **Correct resolve you can read** | PubGrub; highest-wins without a platform BOM; enforced platforms when BOMs are present; `jk why` / conflict prose. |
| **Cargo / uv ergonomics** | Native CLI, `jk add` / `remove` / `update` / `tree` / `outdated`, sub-second cold start ambitions. |
| **Maven Central native** | Same GAV coordinates and repository gravity; adoption path via import / `jk mvn` / `jk gradle` when you are not ready to rewrite. |
| **Power without polluting the manifest** | Advanced behavior lives in plugins and intentional escape hatches **outside** TOML — never “the build file became an app.” |

```toml
# jk.toml — the whole default build story
group   = "com.example"
name    = "my-app"
version = "0.1.0"
java    = 25

[dependencies]
jackson3-databind = "latest"

[platform-dependencies]
spring-boot-dependencies = "4.1.0"
```

That is the upgrade path people meant when they said “there has to be something better
than a POM” — **without** answering “so write Kotlin to compile Java.”

---

## Positioning in one page

| | Maven | Gradle | JumpKick |
|---|---|---|---|
| Build is… | Data (XML) | Code (Groovy/Kotlin) | Data (TOML) |
| Shape | Finite lifecycle | Open-ended graph | Finite + conventions |
| Everyday ergonomics | Weak CLI, heavy files | Powerful, high ceremony | Cargo/uv-style CLI |
| Flexibility | Plugins, limited | Near-unlimited | Plugins + hatch outside TOML |
| Reproducibility | Possible | Possible | **Default** (lockfile law) |
| Market fit today | **#1 Java overall** | **#2; #1 Android** | The declarative successor |
| Migration story | — | Foreign to many Maven shops | Central-compatible; import path |

**One line:** Maven proved the market wants a **predictable build**. Gradle proved some
teams need a **programmable build**. JumpKick is for the majority path — **predictable,
modern, and pleasant** — without making every repository maintain a second codebase just
to ship classes.

---

## Sources

- JetBrains, [*The State of Java 2025*](https://lp.jetbrains.com/the-state-of-java-2025/) — Maven ~67% as most widely used build system; multi-year Maven vs Gradle chart.  
- JetBrains, [*State of Developer Ecosystem 2022 — Java*](https://www.jetbrains.com/lp/devecosystem-2022/java/) — Maven ~73%, Gradle ~50% (multi-select).  
- JetBrains Developer Ecosystem surveys (annual) — stable Maven-first, Gradle-second pattern for Java tooling questions across recent years.  
- Product contrast details vs Mill (maintainer-facing): [mill-comparison.md](mill-comparison.md).  
- Day-to-day product surface: [guide.md](guide.md), [architecture.md](architecture.md).

Survey percentages are self-reported and multi-select; treat them as **order-of-magnitude
market signal**, not exclusive marketshare. The signal is consistent: **declarative still
wins headcount; Gradle did not become the universal Maven replacement.**
