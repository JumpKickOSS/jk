# Why JumpKick

How to *use* JumpKick: [manual](manual.md) · [getting started](getting-started.md) ·
[agents](agents.md). This page is the product bet, not a command reference.

**Audience:** Maven or Gradle users evaluating a switch, and anyone building JVM software
with coding agents (Grok, Claude, Codex, …).

---

## North star

> **JumpKick is the JVM build tool that coding agents can actually drive — and that humans
> enjoy enough to keep.**

Wall-clock parity with a tuned Gradle 9.x build is hygiene, not the conversion claim.
The reason to abandon Maven or Gradle is that the **edit → build → diagnose → fix →
rebuild** loop gets shorter for agents and for humans who work with agents.

That loop is the product. Features matter insofar as they shorten a step, reduce retries,
or remove a tool-switch.

```text
  intent ──► mutate project ──► build/test ──► observe ──► repair ──► repeat
     │            │                 │             │           │
  jk manual    TOML +            cache +       jk-results   structured
  / MCP        jk add/deps       warm engine   + diagnostics  edits +
  templates    format            ETA/explain   (not log scrape) format
               toolchain         lockfile law
```

| Loop step | What “winning” looks like |
|-----------|---------------------------|
| **Observe & repair** | Structured, token-cheap failures — not Gradle/Maven log archaeology |
| **Mutate without fear** | Small declarative surface agents and humans edit the same way |
| **Execute cheaply** | The *Nth* rebuild in a session feels free (cache + warm engine + low RSS) |
| **Stabilize the environment** | No turns burned on `JAVA_HOME`, wrappers, or bootstrap scripts |
| **Enter the ecosystem** | Import/export so migration time counts in cycle time |
| **Shared reality** | One build model; TTY, web UI, and MCP show the same facts |

**Human cycle time** is that loop with better aesthetics and less ceremony.
**Agent cycle time** is the same loop with structured I/O and safe mutation.
Selling only to humans undersells the moat. Selling only “AI features” undersells that
humans must love the daily driver.

---

## Feature ranking (what converts abandoners)

Think in **layers of the switch decision**, not a flat checklist.

### Tier 0 — The promise (lead with these)

| Rank | Feature | Why it converts |
|------|---------|-----------------|
| **1** | **Agent-native results + MCP** (`jk-results.md`, diagnostics, `jk manual`, MCP tools) | Agents stop scraping logs. Failures become structured, token-cheap, re-enterable. This is the unique moat vs Maven *and* Gradle. |
| **2** | **Declarative TOML + surgical edits** (`jk.toml`, `jk add`/`remove`, MCP `jk_deps` / `jk_manifest`) | Agents and humans share one small surface. Mutation is cheap and reviewable. |
| **3** | **Lockfile-as-law + PubGrub diagnostics** (`jk-lock.toml`, `why`, readable conflicts) | Removes overnight CI drift and “agent guessed a version.” Predictability is what Maven users actually loved. |

Alone, each is nice. Together they make the agent loop *possible*.

### Tier 1 — Prove it in the first ten minutes

| Rank | Feature | Role |
|------|---------|------|
| **4** | **Action cache + CAS + warm engine (low RSS)** | Shorter *repeated* cycles — not “we beat Gradle by 8%.” |
| **5** | **Beautiful CLI + accurate ETA** | Human trust on long runs; progress/ETA also exist as machine facts. |
| **6** | **Built-in format** | Closes the agent edit loop without a second toolchain. |
| **7** | **Templates / `jk new`** | Time-to-first-success for humans and agents scaffolding greenfield work. |

### Tier 2 — Remove the reason to stay

| Rank | Feature | Role |
|------|---------|------|
| **8** | **Maven import/export + `~/.m2` compatibility** | Lowers switching cost for the majority Maven camp. |
| **9** | **Gradle import/export (best-effort) + `jk gradle` / `jk mvn`** | Escape hatch so migration is not all-or-nothing. |
| **10** | **Integrated JDK / shell activation** | Agents and humans stop fighting `JAVA_HOME`. |
| **11** | **`jk update` / outdated** | Stay current by design; upgrades are a real resolve step. |
| **12** | **`jkx` / tool run** | One surface for ephemeral JVM tools (npx/uvx/JBang-shaped). |

### Tier 3 — Seal the deal after they have felt the loop

These impress on a feature matrix and retain power users; they rarely *cause* the switch:

| Rank | Feature | Notes |
|------|---------|-------|
| **13** | Web UI | Supervisory mirror of the same model; secondary to MCP for conversion. |
| **14** | Supply chain / SBOM / audit / CVE | Strong “enterprise yes,” weak “try tonight.” |
| **15** | OCI images | Ship path; not day-one abandon reason. |
| **16** | Git-as-dependency / git modules | Power-user depth after trust is earned. |
| **17** | Native-image / deep framework support | Keep them; do not lead with them. |

**Raw speed vs Gradle** is a **credibility footnote** beside this list: competitive on warm
builds; the win is fewer failed cycles and less agent thrash.

---

## Pitch shape (one page)

1. **Hero:** the JVM build tool coding agents can drive.  
2. **Proof:** `jk new` → `jk add` → `jk build` → open `target/jk-results.md` (or MCP).  
3. **Why leave Maven:** same declarative philosophy, modern surface, real lockfile, agent-readable outcomes.  
4. **Why leave Gradle:** warm/incremental ambition without “build is a second app.”  
5. **Speed (humble):** competitive with modern Gradle; designed so *repeated* local/agent cycles stay small.  
6. **Batteries (one line):** toolchain, format, audit/SBOM, images, git deps, web UI — delete five side tools.  
7. **Adoption:** import Maven today; keep `~/.m2`; escape hatches for Gradle.

Feature dumps belong under “what’s included.” Conversion happens in tiers 0–1.

---

## The hole in the market

### What the surveys still say

Public surveys of Java developers still put **Maven first** and **Gradle second** — for
years, not as a blip.

| Source | Maven | Gradle | Notes |
|--------|------:|-------:|-------|
| [JetBrains *State of Java 2025*](https://lp.jetbrains.com/the-state-of-java-2025/) | **~67%** | clear #2 | Maven still “most widely used build system.” |
| [JetBrains Dev Ecosystem 2022 — Java](https://www.jetbrains.com/lp/devecosystem-2022/java/) | **~73%** | **~50%** | Multi-select; overlap expected. |
| JetBrains series ~2019–2025 | Maven stays on top | Gradle plateaus as #2 | Stable duopoly, not a Gradle takeover. |

**Segment reality**

| Segment | Typical default | Why |
|---------|-----------------|-----|
| Spring / enterprise services | **Maven** | Parent POMs, BOMs, CI templates, reviewable config |
| Android / much of Kotlin mobile | **Gradle** | Platform tooling assumes it |
| Greenfield “custom everything” | Often **Gradle** (or Bazel-class) | Escape hatch is the product |
| Regulated / long-lived backends | Often **Maven** | Predictable shape over infinite flexibility |

Gradle did not fail commercially — it is an obvious #2 with monopoly power in Android.
It did **not** displace Maven as the Java default, because a large slice of the market
never wanted “a better programming language for builds.” They wanted **a better build
tool.**

### Maven: right shape, wrong surface

Maven’s enduring advantage is philosophical:

- A **standard lifecycle** and a **finite vocabulary**
- The build is **data** (`pom.xml`), not an open-ended program
- New contributors can read the project without learning a DSL

The 2026 tax: verbose XML, weak CLI next to Cargo/uv, lockfiles as bolt-ons, no default
“skip what you can prove is done,” and **nothing agent-native** about failure output.

### Gradle: more power, worse default for agents

Gradle fixed real Maven pain when the build is expertly written. Its product bet —

> Here is a box of Legos. Good luck.

— is hostile to agent edits. The build **is** software (Groovy/Kotlin DSL, plugins,
configuration-cache caveats, convention plugins). Every shop reinvents house style.
Agents thrash on DSL folklore; humans maintain a second product forever.

### What people actually want next

1. **More approachable than Maven** — no XML tax; modern config and CLI  
2. **More constrained than Gradle** — a finite build model, not an open language  
3. **Faster and more reproducible by default** — lockfile as law; cache what you can prove  
4. **Still on Maven Central** — same coordinates and gravity  
5. **Closed-loop for coding agents** — structured observe/repair, not log scraping  

That is JumpKick.

---

## JumpKick’s answer

JumpKick takes Maven’s winning idea (a **declarative, conventional build**) and Gradle’s
valid performance ambitions (skip work, graph awareness), then rejects both tools’ worst
ergonomics — and adds an **agent-closed loop** as a first-class surface.

| Principle | What it means |
|-----------|----------------|
| **Closed loop for agents** | `jk manual`, `jk-results.md`, MCP diagnostics/run — same model as the human CLI |
| **Data, not a program** | `jk.toml` is TOML — readable, editable, reviewable |
| **Finite shape** | Convention-over-configuration; plugins extend a known model |
| **Lockfile is law** | `jk-lock.toml` at the workspace root; `jk build` does not re-resolve when valid |
| **Correct resolve you can read** | PubGrub; `jk why` / conflict prose |
| **Cargo / uv ergonomics** | Native CLI, `add` / `remove` / `update` / `tree` / `outdated` |
| **Maven Central native** | Same GAV gravity; import / `jk mvn` / `jk gradle` when not ready to rewrite |
| **Power outside the manifest** | Advanced behavior in plugins and hatches — never “the build file became an app” |

```toml
# jk.toml — the whole default build story
group   = "com.example"
name    = "my-app"
version = "0.1.0"
java    = 25

[dependencies]
jnats = "latest"

[platform-dependencies]
spring-boot-dependencies = "4.1.0"
```

That is the upgrade path people meant when they said “there has to be something better
than a POM” — **without** answering “so write Kotlin to compile Java,” and **with** a
surface coding agents can drive.

---

## Positioning

| | Maven | Gradle | JumpKick |
|---|---|---|---|
| Build is… | Data (XML) | Code (Groovy/Kotlin) | Data (TOML) |
| Shape | Finite lifecycle | Open-ended graph | Finite + conventions |
| Agent I/O | Log scrape | Log scrape | Results + MCP first-class |
| Everyday ergonomics | Weak CLI, heavy files | Powerful, high ceremony | Terse CLI + ETA |
| Flexibility | Plugins, limited | Near-unlimited | Plugins + hatch outside TOML |
| Reproducibility | Possible | Possible | **Default** (lockfile law) |
| Market fit today | **#1 Java overall** | **#2; #1 Android** | Declarative successor + agent loop |
| Migration story | — | Foreign to many Maven shops | Import from Maven/Gradle |

**One line:** Maven proved the market wants a **predictable build**. Gradle proved some
teams need **power when the default isn’t enough**. Coding agents proved the next bottleneck
is **closed-loop diagnose and repair**. JumpKick aims at all three.

---

## Making the north star true

Pitch order is not build order. Engineering priority should maximize **agent loop latency
and success rate** (see KanArtist epic **JK-2000** and children).

**Private metric that matters:**

> For a fixed set of “agent breaks the build” scenarios: **median agent turns / tokens /
> wall time to green** on JumpKick vs Maven vs Gradle.

If that number does not win, polish the Tier 0 surfaces until it does. Feature count will
not save it.

Honesty today: the skeleton is real (`jk manual`, results, MCP, TOML edits, lockfile,
cache). The north star becomes *true* when failure coverage, recipe reliability, and
measured turns-to-green beat the incumbents — not when the README says so.

---

## Sources

- JetBrains, [*The State of Java 2025*](https://lp.jetbrains.com/the-state-of-java-2025/)  
- JetBrains, [*State of Developer Ecosystem 2022 — Java*](https://www.jetbrains.com/lp/devecosystem-2022/java/)  
- Day-to-day product surface: [manual](manual.md), [agents](agents.md), [MCP](mcp.md),
  [machine output](machine-output.md)

Survey percentages are self-reported and multi-select; treat them as **order-of-magnitude
market signal**. The signal is consistent: **declarative still wins headcount; Gradle did
not become the universal Maven replacement.** The new wedge is **agent-closed loops**.

## Related

- [Manual](manual.md)
- [Getting started](getting-started.md)
- [Agents](agents.md)
- [MCP](mcp.md)
- [Concepts](concepts.md)
- [README](../../README.md) — hero pitch
