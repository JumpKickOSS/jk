# Code as Art

Maintainer spec for how JumpKick is written. Not a product doc — listed
under maintainer notes in [../README.md](../README.md) only.

Agents executing this file: **maximum correctness is the goal.** We are
pre-1.0.0. We do not preserve leftover APIs, SPIs, file formats, wire
protocols, JSON shapes, or package names out of habit. Breaking changes
that make the product better are **encouraged**. Leave the tree coherent
in the same change — client, engine, dashboard, MCP, tests, goldens, and
docs must agree. A half-migrated protocol is a defect, not a compromise.

The product we want is the best one we can write: **DRY, healthy,
readable, performant, concise, elegant, modern.** A sloppy kernel is a
product defect. A 7,000-line class can pass every test and still fail
that standard.

---

## Contents

<!-- checkFileSizeCaps verifies this list against the headings below;
     a heading added without its entry fails the build. -->

- [Charter](#charter)
- [Patient zero: `EngineServer`](#patient-zero-engineserver)
- [Architecture: Typed Envelope](#architecture-typed-envelope)
  - [Types](#types)
  - [Layers](#layers)
- [House rules](#house-rules)
  - [Correctness](#correctness)
  - [Reach before discipline](#reach-before-discipline)
  - [Quality bar](#quality-bar)
  - [Size](#size)
  - [Java 25](#java-25)
  - [JSpecify](#jspecify)
  - [Lombok (project-wide)](#lombok-project-wide)
  - [Patterns](#patterns)
  - [Tests](#tests)
- [Enforcement, or it did not happen](#enforcement-or-it-did-not-happen)
  - [Where a guard runs](#where-a-guard-runs)
  - [How to write one](#how-to-write-one)
  - [The guard registry](#the-guard-registry)
  - [Verify the number before it lands in a Done criterion](#verify-the-number-before-it-lands-in-a-done-criterion)
  - [A test that passes without executing is worse than a missing test](#a-test-that-passes-without-executing-is-worse-than-a-missing-test)
  - [A delete that follows a symbolic link is not a delete](#a-delete-that-follows-a-symbolic-link-is-not-a-delete)
  - [The repo builds itself twice, so it has two manifests and one truth](#the-repo-builds-itself-twice-so-it-has-two-manifests-and-one-truth)
  - [A test that reads the source tree names it from the checkout root](#a-test-that-reads-the-source-tree-names-it-from-the-checkout-root)
- [Campaign](#campaign)
  - [Scoreboard](#scoreboard)
- [After EngineServer](#after-engineserver)
- [Anti-goals](#anti-goals)

---

## Charter

1. **Correctness first.** Race protocols, cancel, journal, lock, and
   cache invariants are not optional. If a refactor is prettier and
   wrong, it is wrong. Race invariants move with the code or become types that
   make the race unrepresentable.
   **Withdrawing a peel is a success, not a failure.** If the extracted
   types cannot carry the invariant, the extract does not land, and the
   proposal is recorded as rejected with the reason. Round 3 proposed
   peeling `yieldListeners` / `awaitDrainComplete` / the `SHUTDOWN` arm /
   `tryStartBuildPlan` out of `EngineServer`, then withdrew it:
   `tryStartBuildPlan` *is* that invariant, and any extraction hands
   `lifecycleLock` to a second object — spreading the invariant across two
   files instead of making it unrepresentable. That was the correct call.
   Do not re-propose it.
2. **Pre-1.0 is a gift.** Rename the wire. Collapse duplicate events.
   Delete the second HTTP job envelope. Change `jk-lock.toml` fields,
   SSE payloads, MCP tool shapes, `EngineProtocol` tokens — if the
   result is simpler and every in-tree consumer is updated. Do not
   keep a field because “something out of tree might parse it.”
3. **One design, every surface.** CLI JSONL, dashboard SSE, and MCP
   are views of the same domain events. They must not invent private
   semantics for cancel, progress, or success.
4. **Types over maps and flags.** A new `ConcurrentHashMap<Long, T>`
   or a third boolean overload is a failed extract.
5. **Java 25, composition, no container.** Records, sealed types,
   pattern switches, virtual threads. Constructor args are the DI
   framework. No Spring, no Guice, no `Foo` + `FooImpl`.

---

## Patient zero: `EngineServer`

`EngineServer` is 7,073 lines / ~290 methods. The class comment says
resident host (election, accept, hosted ops). Reality:

| What | Where it hides today |
|---|---|
| Process host | election, UDS/TCP, drain, AOT sidecar |
| Job envelope | `handleAsyncBuildPlanRequest` (~320 lines) **and** HTTP twins that reimplement it |
| Progress DB | **14** request-id `ConcurrentHashMap`s |
| Verbs | ~20 `run*` JSONL decoders over `BuildService` / `runtime.*Plans` |
| Event encoding | three anonymous listener graphs (CLI / SSE hub / workspace wrap) |
| Outcome law | inner `BuildAccumulator` (~480 lines) |

The bug was not file length. It was **two job lifecycles** (CLI vs HTTP)
and **three serializers** of the same facts. Before the envelope, HTTP
jobs had no heartbeat and no wall deadline, HTTP lock skipped
`admitJob`, and a late progress emit after teardown could resurrect a
map.

Load-bearing races — preserve the *invariant*, not the method shape:

| Invariant |
|---|
| Plan slot claimed under `lifecycleLock` in the same breath as the drain check |
| Register the runner *before* `start`; reverse it and cancel is a no-op |
| Teardown retires the session; late writes no-op |
| EOF after a reported failure is not cancel |
| Release the plan slot *before* `request-finish` |
| SSE snapshot + attach is one critical section |
| Peak connections is one metric across UDS + SSE |

Existing peels to copy, not relitigate: `BuildService`, `runtime.*Plans`,
`InFlightBuilds`, `EngineMaintenance`, `http/*`,
`CoalescingBuildPlanListener`. `EngineMain` is the composition root.
There is no DI container and there must not be one.

Other giants (`BuildPlanner` 5,348, `EngineProtocol` 3,379,
`EngineClient` 2,616, `JkBuildParser` 2,238, `HttpEngineServer` 1,592)
get the same treatment **after** this campaign. Do not open them in the
same change as an `EngineServer` extract unless the extract *requires*
a protocol or API improvement — then change them, completely.

---

## Architecture: Typed Envelope

```
CLI JSONL  ──decode──┐
HTTP POST  ──decode──┼── JobRequest ── JobEnvelope.submit ── HostedVerb.run
MCP tools  ──decode──┘         │
                               ├── EventSink → Wire (CLI)
                               ├── EventSink → SSE (dashboard + MCP)
                               └── JobSession + Journal
```

`EngineServer` keeps process ownership: elect, accept, drain, close,
four-arm dispatch. It does not own progress maps, verb bodies, or a
second HTTP cancel registry.

### Types

**`JobSession`** — one row per request. Replaces the fourteen maps.
`close()` / `retire()` is the only teardown. Late events cannot
`computeIfAbsent` a zombie.

**`JobKind`** (sealed) — replaces `plan` / `workspaceStream` booleans.

```java
sealed interface JobKind {
    record Workspace(String verb) implements JobKind {}
    record Plan(String verb) implements JobKind {}
    record Maintenance(String verb) implements JobKind {}
}
```

**`VerbShape`** (sealed) — dispatch is three arms, forever (process lifecycle —
hello, ping, status, shutdown — stays on the process, never the registry):

- `SyncRead` — explain, tree, why, edit, ide-model, …
- `AsyncPlan` — fork + watch; joins `activeBuildPlans`; cache read lock
- `CacheMaint` — idle-boundary; takes the cache write lock itself

**`HostedVerb`** — interface (the set still grows; tests need fakes).
Not sealed. Not `ServiceLoader`. Not a marketplace.

```java
interface HostedVerb {
    String wireType();
    JobKind jobKind();
    VerbShape shape();
    String threadPrefix();
    JobOutcome run(String requestLine, Session.CancelToken cancel, @Nullable BufferedWriter writer);
    default List<String> jobKinds() { return List.of(); }   // HTTP/MCP submission
    default JobRequest toJobRequest(String requestLine) { … }
}
```

`run` returns a **`JobOutcome`** — the sealed `Succeeded | Failed(exitCode)
| Cancelled | Declined`. That signature is load-bearing: a `void` plus a
nullable side-channel cannot distinguish "declined to rule" from "aborted
before producing any facts", and the accumulator read both as success.
Sixteen verbs journaled a green build that had thrown.

`VerbRegistry.standard(...)` is an explicit list. Adding `jk quux` is
one class + one registry line. It is automatically admitted, heartbeaten,
cancelled, journaled, and visible on every transport.

**`JobEnvelope`** — `final` class, one submit path. Not a hierarchy.
Not `AbstractAsyncJob`.

**`JobTransport`** (sealed) — the *only* CLI vs HTTP difference:

- `SocketWatch(reader, writer)` — connection owns the job
- `FireAndForget()` — return the request id; progress is the sink

`httpCancelTokens`, `httpJobThreads`, `cancelHttpJob`, and
`startHttpWorkspace` / `startHttpLock` are gone. HTTP lock goes through
`admitJob`. HTTP/MCP jobs share the envelope's admission, deadline, and
cancel; heartbeats are wire lines, so detached jobs run only the
wall-deadline watchdog.

**`EngineEvent`** (sealed) + **`EventSink`** — one domain vocabulary.
Implementations: `WireEventSink`, `SseEventSink`, `CompositeEventSink`,
`RecordingEventSink`. One `BridgingWorkspaceListener` + one
`BridgingPlanListener` under the existing `CoalescingBuildPlanListener`.
Redact `.env` secrets on every sink.

If today’s wire tokens, field names, or event split are worse than
`EngineEvent`, **change the wire** and update `EngineProtocol`,
`EngineClient`, the SPA, MCP, and goldens in the same change. Do not
preserve a bad discriminator because proto is currently `1`.

**A wire message is a record with `encode()` and `decode(String)` in one
file.** Not a constant table of key names: a `WireKeys` holder moves the
literals into one file and still lets a decoder spell a key no encoder
writes. The existing `*Ack` / `*Report` records are the shape, and
`ReportDiscriminatorTest` already guards them; `engine.journal.Json`
(pure `BuildRecord` ↔ map shape-mapping over `MiniJson`) is the exemplar
for the decode side. `clients/web` cannot import Java, so its wire
vocabulary must be **generated** from `EngineProtocol` + `Proto*` rather
than hand-typed — hand-typing route paths, SSE names and JSON keys in JS
is how the SPA accumulated readers for fields the engine never emits.

**`ProgressLedger`** lives *on* `JobSession`, not beside it.

**`BuildAccumulator`** moves to `engine.journal`. Keep the cancel/success
law; delete accidental coupling to `EngineServer`.

### Layers

```
EngineMain                         composition root
EngineServer                       elect, accept, drain, close, 4-arm dispatch
  engine.jobs.JobEnvelope          admit / heartbeat / cancel / join
  engine.jobs.JobSession           one request-scoped row
  engine.progress.ProgressLedger   tracker + remaining + emit
  engine.journal.BuildAccumulator  outcome fold
  engine.verbs.*                   decode → runtime.*
  engine.listen.*                  EventSink + bridges
  engine.http.*                    dashboard / MCP (shrink, do not grow)
cc.jumpkick.runtime.*              BuildService, *Plans
cc.jumpkick.task.*                 CAS / action cache
cc.jumpkick.wire.*                 protocol codec, paths, transport, DTOs
```

`plugins/` holds **two** architectures, not sixteen styles:

```
SPI plugin      jk-plugin.toml descriptor, kebab config keys, engine loads
                by descriptor, wire prefix from PluginManifest.protocolPrefix
forked worker   no descriptor, camel config keys, engine hardcodes argv,
                wire prefix a PROTOCOL_PREFIX constant in the driver
```

Any uniformity rule is written per family, and a rule that reads as a
style violation across the two is usually a family boundary being crossed
correctly. Of the 15 modules under `plugins/`, 7 carry a descriptor and 8
are workers — a clean partition, and `checkPluginFamily` (G25) holds it:
each module's own `check` derives its family from the descriptor and
asserts the architectural consequence, which is the **shape of its
wire-prefix pair**. An SPI plugin's prefix is named by its descriptor and
its plugin class and by no engine source; a worker's is named by its
worker class and by exactly the engine source that hardcodes its argv.
`checkWireProtocolPrefixPairs` (G5) keeps the pair a pair; G25 says which
family owns it.

**The `plugins/` vs `workers/` directory split is withdrawn**.
The descriptor's presence already declares the family. A second directory
declaring it again is a copy that has to be kept in sync, and a guard
reading the directory would still have to consult the descriptor to know
whether the directory was right — two spellings of one fact, which is the
defect this campaign exists to remove. The directory also carries no
build-graph meaning: Gradle project names here are family-neutral
(`:android`, `:auditor`), so every `project(":x")` reference is invariant
under a rename, *including* the worker-jar lists in
`clients/cli/build.gradle.kts` and `server/engine/build.gradle.kts`. What
a rename would buy is legibility, at the cost of 8 `git mv`s, 15
`projectDir` lines, `jk.toml`, a `jk-lock.toml` re-lock and 13 test files.
What G25 buys instead is the case a directory name cannot see: a module
whose family and whose actual engine wiring disagree.

An earlier proposal asked for **two** convention scripts. There is one,
`jk.plugin-conventions`, with a family arm inside it — because everything
else in that script (thin fat-jar, flattened worker POM, staged repo,
`installLocal`) is identical for both families, and splitting it would
duplicate 340 lines to express one boolean.

Manual constructor injection of concrete `final` classes. An interface
exists only when there are two production implementations, a process
boundary, or a sealed algebra. No `IEngineServer`. No package-per-layer
`api`/`impl`.

---

## House rules

Apply to every new file and every extract, not only the engine.

### Correctness

- Prefer making an illegal state unrepresentable over a comment that
  begs the next editor not to swap two lines.
- When you break a format or protocol, update every in-tree reader and
  writer in that change. Grep is part of the work. Tests that encoded
  the old shape are updated or deleted — never skipped.
- Do not “simplify” cancel, exclusive fingerprints, journal completion,
  or SSE connect ordering. If the new type cannot express the old
  invariant, the type is unfinished.
- `jk format` before every commit.
- **Change what a key hashes → turn the salt, in the same commit.** Every
  action key composes the product version with `BuildIdentity.CACHE_KEY_SALT`.
  A change to the bytes a key covers (a new input, a dropped one, a different
  hashing order) without a salt bump lets a stale entry answer for the new
  key on every machine that has one; bumping it retires every entry at once.
  `BuildIdentityTest` pins that the salt reaches both the release and the
  dev-build branches of the key.
- Comments state the **current** type or method only — no `JK-` ids, no
  historical essays, no agent decision records. Policy:
  [AGENTS.md — Comments and Javadoc](../../AGENTS.md#comments-and-javadoc).
- **One XML parser, one hardening posture.** A
  `javax.xml.parsers.DocumentBuilderFactory` belongs to a single declared
  owner, and that owner sets all six flags: `FEATURE_SECURE_PROCESSING`,
  `disallow-doctype-decl`, both external-entity features,
  `load-external-dtd`, and `setExpandEntityReferences(false)`. Round 3
  found seven parsers at four hardening levels, two with no XXE flags at
  all, both reading third-party input — an AAR's `res/values/*.xml` from
  any Maven artifact, and a git dependency's `pom.xml` inside the resident
  engine. A text scan cannot tell *which* factory instance a `setFeature`
  call configures, so requiring the flags is not enforceable in general;
  requiring one owner is — and *then* the flags become enforceable too,
  because the owner is the one file where "the factory in this file" is
  unambiguous. That is why the owner is the rule and not a convenience.
  The owner is `cc.jumpkick.host.DomXml`, and it lives in `shared/host`
  rather than `server/io` because `:android` sees only `:plugin-sdk` and
  `:toolchain-jdk` only `:core` + `:client-io` — neither can reach `:io`.
  Enforced by `checkSingleXmlParserOwner` (G3), over `src/test/java` too.
- **Redaction is three concerns with three owners, and the type carries
  it — not the call site.** `.env`-sourced text: `ProtoEvents.workspaceFinish`
  takes `List<Redacted>`, so forgetting is a compile error rather than a
  missing call. URIs: `SafeUri.forMessage` — userinfo in a repository URL
  reached `MavenRepo.baseUrl()` and was written into `jk-lock.toml`'s
  `source` field, i.e. into version control. Process argv: a secret goes
  in a file (`-storepass:file`), never on a world-readable command line.
  Do **not** widen `SecretRedactor` past its source-based contract
  (`EnvLookup.isFromFile`) — a name-heuristic mode is exactly what makes a
  redactor untrustworthy, and its own javadoc rejects it.

### Reach before discipline

**An owner a caller cannot import is not an owner.**

Before filing "call the owner", check the module graph. Twelve of sixteen
plugins hand-rolled exit codes, hex loops, stamp predicates and jar
timestamps — not out of sloppiness, but because `shared/plugin-sdk`
declared `api(project(":host"))` and nothing else, and the owners were
off their classpath. That is a module-graph defect, and no amount of
review fixes it. Twelve auditors filed it as twelve module-discipline
problems.

`shared/host` (`cc.jumpkick.host`) is the one leaf every module in the
tree can reach: zero project dependencies, JDK 17 floor, an `api`
dependency of both `:core` and `:plugin-sdk`. A primitive that must be
callable from a plugin worker, the native CLI **and** the engine goes
there and nowhere else — today that is `Hashing`, `PathUtil`, `Errors`,
`Os`, `Classpaths`, `DeterministicZip`, `BuildStamps`, `CacheTree`,
`AotCacheFiles`, `DomXml`, `EnvValues`, `JdkFingerprint`, the JSONL codec
and the `Exit`/command vocabulary.

Two of those kept their original package when they moved
(`cc.jumpkick.config.EnvValues`, `cc.jumpkick.jdk.JdkFingerprint`), which
cost zero import churn across ~50 call sites. A split package is a real
smell and this is the one case that outweighs it: a move that touches
nothing is a move that cannot break anything, and it does not put a
delegating shim in the tree either.

`:core` must **never** appear on a plugin worker's classpath: it declares
`api(libs.tomlj)`, which would drag tomlj and ANTLR onto every thin
worker's POM-rebuilt launch classpath.

When a primitive cannot move down, say so in the owner's javadoc and name
the module that cannot reach it. And when you *do* move one: "no
non-`java.*` imports" is not the same as "no dependencies". A
same-package call has no import line and is invisible to an import scan.

### Quality bar

DRY across CLI / HTTP / MCP. Readable in one sitting. Performant on a
**256 MiB** engine heap (no per-event framework, no session intern
pool). Concise: delete the copy, do not wrap it. Elegant: the right
types, and the absence of the wrong ones. Modern: Java 25 idioms, not
2014 POJOs and not annotation-processor theater.

### Size

Caps are per language, because the languages do not split at the same
cost. `checkFileSizeCaps` parses this exact table and fails the build if
it disagrees with the guard, so the two cannot drift.

| Language | Extensions | Soft | Hard | Exception |
|---|---|---|---|---|
| Java / Kotlin | `.java` `.kt` | 400 | 800 | 1,200 with a comment naming the invariant that must not be split |
| JavaScript | `.js` `.mjs` | 600 | 1,200 | 1,600 with the same comment |
| CSS | `.css` | — | — | exempt |

The count is **code lines**, not `wc -l`. Comments (including Javadoc), blank
lines, and `package` / `import` lines do not count. A statement with a trailing
comment still counts. String and text-block contents count — a fixture is
data. The cap is on behaviour in one file, not on the envelope `jk format`
writes or the comments a type needs. An import the formatter adds cannot grow
a file past the cap.

JS gets the wider band for a narrower reason than "no bundler." There is
no bundler, but there has been an ES module graph all along —
`index.html` loads exactly one local script,
`<script src="/app.js" type="module">`, and every other file is reached
by static `import`. The browser topologically orders the graph, and a bad
specifier or a missing export is a link error before a line runs. A JS
split there costs an `import`, exactly like Java. Splits took `app.js`
2,786 → 601, `code.js` 1,966 → 775 and `fold.js` 1,734 → 971 on that
basis, and all three left this baseline.

What the band actually buys is narrower: the module graph is checked at
**load** time, not build time, so a broken split fails when the page
opens rather than when the build runs — and there is no type system, so
an extraction loses whatever inference the editor had. That is a real
cost and a smaller one. Treat 1,200 as provisional; if the SPA stays this
shape, JS should converge on the Java caps.

CSS is exempt outright — splitting a cascade on line count is a
regression risk with no readability win, so `clients/web/.../style.css`
is absent from the guard by design.

**The caps are per language, not per directory — so tests are capped too,
by the same numbers.** `checkFileSizeCaps` scoped itself to `src/main` for
most of this campaign, and the cost of that was measurable: the largest
file in the tree was a *test*, `JkBuildParserTest` at 2,334 lines — 2.9x
the hard cap for its own language, and 5.6x its 416-line subject, which it
had grown around by accretion one config table at a time. No guard could
see it, and neither could the doc/guard parity check below, which compares
extensions and is blind to directory scope. The scan now covers
`src/test/java`, `src/test/kotlin`, `src/test/js` and `src/fixtures`.

The objection to capping tests is real and it is not an exemption:
splitting a suite can duplicate a fixture, and duplicated scaffolding is
this tree's actual defect vector. That argues for the **exception band**,
which already exists and costs a `size-baseline.txt` entry plus a stated
invariant — a reviewable diff. It does not argue for a second number for
the same extension, which is precisely the drift the parity check exists
to prevent. In practice the band was not needed for Java: the two contract
suites most likely to need it, `EngineServerTest` (real socket) and the
SPA/route tests, both split mechanically into one shared harness apiece
with no assertion touched. When this landed, 975 Java test files measured
p50 104 lines, p99 638, and none over 800.

Soft caps are a review signal. The hard caps are the gate, enforced by
`checkFileSizeCaps` against the checked-in `size-baseline.txt`, wired to
both `check` and `jar`. Three rules:

1. A listed file may only shrink.
2. An unlisted file must be at or under its language's hard cap. Listing
   it is the escape hatch, and it is a reviewable diff.
3. Every entry carries the invariant comment above it. Re-baselining a
   number upward means deleting the invariant that justified the old one
   and writing one that justifies the new one, in the same diff.

The baseline is the ratchet, and it is deliberately *not* a second
ceiling. A listed file above the exception band is named debt, not a
moved band. A shrink passes and prints the tightened line to paste back,
so the ratchet never blocks progress.

A commit is progress only if a number goes down: lines in the god file,
map count, overload count, boolean flags, duplicated envelopes, or FQCN
count. Annotations without a deleted map do not count.

**But count copies, not lines, and measure before you promise.** A
duplication estimate taken from file sizes measures *similarity*, not
duplication. Two sets of twins looked like a 450-line saving and shared
**146 identical lines of code**; the rest was per-language behaviour that
was never duplicated, and the estimate charged nothing for the four new
owners at 25–40 lines each. The honest delta was −42. That is still the
result worth having: a third JVM language is now one `switch` arm and one
spec writer instead of a sixth copy of five layers. Copies are what
carried every confirmed defect in this tree; lines are a proxy that
sometimes lies.

### Java 25

- Data → **record** (compact ctor, `List.copyOf` on inbound collections).
- Closed variants → **sealed** + exhaustive `switch`. No
  `default` → `"should never happen"`.
- Work → **`final` class**, one constructor, `private final` fields
  unless mutability *is* the spec.
- `var` for obvious locals only. Never fields, params, returns.
- Pattern `switch` over `instanceof` ladders. `.toList()`, not
  `Collectors.toList()`.
- `Optional` is a **return type only** — never a field, never a
  parameter, and never a **record component**. A record component *is* a
  field plus an accessor: the component is `@Nullable T`, and the
  accessor may return `Optional<T>`. `Optional<T>` as a component costs a
  heap object per instance, defeats `@With`, and makes the positional
  constructor unreadable at the call site.
- Never return `null` from a collection method — `List.of()`.
- No FQCN except collisions. `jk format` shortens what it can reach;
  `checkNoFqcn` ratchets the rest against `fqcn-baseline.txt`, where
  every surviving reference is filed under the reason it survives.
- A step is named once, in `cc.jumpkick.run.TaskNames`. Typing
  `"compile-java"` at a producer and again at a consumer makes a typo a
  missing dependency edge instead of a compile error;
  `checkNoBareTaskName` bans the literal in `src/main/java`. Tests keep
  theirs — an assertion on rendered output is a golden.
- **One encoder per format, and it is not yours.** JSON → `Jsonl`
  (flat) / `MiniJson` (tree) in `shared/host`. TOML scalars →
  `MinimalToml`. XML text and attributes → `MinimalXml`. Archive entries
  → `DeterministicZip` in `shared/host`. Archive instants → `BuildStamps`.
  A second escaper, a second parser, or a "tiny dep-free" copy is a
  defect regardless of how small it is: `:host` is *itself* the minimal
  reflection-free release-17 encoder, so every copy is strictly weaker on
  the axis it claims to protect.
  **But two escapers can be correct.** `MinimalToml.quote` next to
  `Jsonl.quote` is not duplication — TOML basic strings admit `\U` and
  forbid `\/`; JSON is the reverse. Merging them would be wrong, not DRY.
  Judge by *spec*, not by body similarity. The same test applies to
  ownership generally: the cache bookkeeping files and `sha256` each have
  two legitimate owners, and round 3 pinned those seams with tests rather
  than forcing a false consolidation.
- **One substrate per audience, one memo, one layering type.** `TomlScan`
  (line scanner, native-image safe) owns machine config; tomlj owns
  manifest config; `checkCliNoParseTypes` keeps the full parser off the
  CLI's reachability graph. Precedence (built-in → file → env) is
  expressed once, not six times. Staleness is one stamped-memo type, not
  five hand-rolled memos with three stamp rules. A tolerant reader and a
  strict reader of the same file are **two policies on one reader**
  (a sealed policy plus a sealed on-bad arm), never two implementations.
- Preview APIs only when scheduled in KanArtist. Virtual threads are
  already house style.

### JSpecify

Zero-runtime. Safe on the Graal CLI. Dogfoods `jk init`.

1. New packages ship `package-info.java` `@NullMarked`.
2. Under `@NullMarked`, unannotated means non-null. **`@Nullable` is
   required** on every type use that can be null. Do not write
   `@NonNull`.
3. No JetBrains / JSR-305 / Lombok nullness in engine or shared.
   IntelliJ keeps JetBrains because the platform API uses it.
4. The public API boundaries (`shared/jk-api`, `shared/wire`, and `shared/plugin-sdk`) compile with
   Error Prone + NullAway in `OnlyNullMarked` JSpecify mode at error severity. Every production
   package in those modules is marked; `checkNullMarkedApiPackages` prevents unmarked additions.
5. Three-state `Boolean success` on the accumulator is correct (unset /
   ok / fail). Mark `@Nullable`; do not “fix” it to `boolean`.

Add `org.jspecify:jspecify` as `compileOnly` via `jk.java-conventions`.

### Lombok (project-wide)

Use Lombok **liberally** to erase boilerplate and keep types small.
There is no module ban: `shared/*`, `clients/cli`, `server/*`,
`plugins/*`, and IDE clients may all use it. Lombok is
**compile-time only** (`annotationProcessor` / `[processor-dependencies]`
plus `compileOnly` / `[provided-dependencies]`) — it is never a runtime
dependency and does not grow the `jk` binary.

**Records first when they fit.** Prefer a Java record for plain
immutable data carriers with no builder story. Prefer Lombok when the
type needs mutability, a builder, composition-root constructors, or
derived equals/hashCode/toString without hand-written noise.

**Fluent accessors are house style.** Project-root `lombok.config`:

```properties
lombok.accessors.fluent = true
lombok.accessors.chain = true
```

Call sites use `version()`, not `getVersion()`. Setters chain
(`obj.version(v).name(n)`). Do not mix bean-style getters with fluent
on the same surface.

**Use freely when they reduce code or enforce a pattern:**

| Annotation | When |
|---|---|
| `@Builder` / `@SuperBuilder` | Multi-field construction, optional fields, protocol / request shapes |
| `@Data` | Mutable beans where getters+setters+equals+hashCode+toString are the whole type |
| `@Getter` / `@Setter` | Partial surface when `@Data` is too broad |
| `@EqualsAndHashCode` / `@ToString` | Value semantics without `@Data`; prefer explicit `of = {…}` when identity is a subset of fields |
| `@RequiredArgsConstructor` / `@AllArgsConstructor` / `@NoArgsConstructor` | Composition roots and DI-by-constructor; drop hand-written ctor noise |
| `@Value` | Immutable class when a record is awkward (inheritance, builder) |
| `@With` | Copy-with-field on immutable types that are not records |
| `@Slf4j` | Logging without a hand-declared logger field |
| `@UtilityClass` | Pure static helpers (prefer package-private top-level when possible) |

**Still banned (nullness and foot-guns):**

- Lombok nullness (`@NonNull` on fields as a nullness system) — **JSpecify**
  is the house nullness story (`@NullMarked` / `@Nullable`).
- `@SneakyThrows` except rare, local, justified cases (prefer explicit
  handling or a real signature).
- `@ExtensionMethod` — implicit static imports hide call sites.

Wire Lombok into the monorepo Gradle conventions the same way jspecify
is wired: `compileOnly` + `annotationProcessor`, never `implementation`.
User projects scaffold Lombok under `[processor-dependencies]` and
`[provided-dependencies]` (see `NewScaffolder`).

### Patterns

| Use | Do not use |
|---|---|
| Facade (`BuildService`, `JobEnvelope`) | `EngineServerFacade` / `Impl` |
| Strategy (`HostedVerb`) | `ServiceLoader`, verb marketplace |
| Bridge (`EngineEvent` × `EventSink`; UDS vs TCP) | `AbstractEngine` |
| Decorator (`CoalescingBuildPlanListener`) | A second coalescer “for HTTP” |
| Composite (`CompositeEventSink`) | EventBus |
| `@Builder` / fluent Lombok where multi-field construction is real | Hand-rolled builders that only restate fields |
| Proxy (`PluginClient`) | Dynamic proxies for tests |
| Process-as-singleton | `getInstance()`, static maps that outlive `close()` |
| Existing listeners | A second observer SPI |
| CAS / action cache as flyweight | Interning `JobSession` |
| `switch` on sealed types | Visitor / `accept` / Template Method `AbstractAsyncJob` |

Builds do not undo. Cancel is not undo. The latch + `CancelToken` +
accumulator stamps are the state machine — do not draw a 12-type
`JobState` hierarchy.

### Tests

- `./gradlew test` is the default loop. New UDS/HTTP/e2e:
  `@Tag("integration")`.
- `EngineServerTest` stays a real-socket contract. New units sit under
  the extracted type (`JobEnvelopeTest`: fake verb, fake transport,
  controllable clock).
- Goldens lock the *current* wire, not a historical one. When the wire
  improves, the golden changes in the same commit.
- **A fixture that impersonates a third party must not use our writer.**
  `MockMavenServer` hand-writes `maven-metadata.xml` in a shape jk never
  emits — no prolog, no `<latest>`/`<release>` — and that is deliberate:
  routing it through jk's own writer would let a broken writer feed its
  own broken reader, and the round trip would pass. The same holds for a
  POM fixture, an OSV response, a registry manifest. Where the fixture
  stands in for *our* output, share the writer; where it stands in for
  someone else's, write it by hand and say so in its javadoc, or it gets
  re-filed as duplication.
- One concern per change. The engine stays bootable. Touching
  `EngineServer` and `BuildPlanner` together is allowed only when a
  single improvement requires both.
- **A `--tests` subset is a development signal, not a verdict.** Which
  siblings share a JVM fork changes the outcome: a four-class subset
  reproducibly failed 17 of one class's 24 tests while the class passed
  alone *and* the full tier passed 65 of 65 suites. Iterate with subsets;
  cite a whole-tier run in a Done criterion, and re-check a
  subset-only failure whole before filing it. The dangerous direction is
  the quiet one — a subset that passes says nothing about the tier.

---

## Enforcement, or it did not happen

**The defect is usually non-use, not absence.** Round 3's single most
repeated finding was an owner that already existed and was not called.
`CacheTree` was built as a *total* table of cache-tier names precisely so
that an omitted tier could not be silently dropped, and three separate
call sites kept their own hand-written tier list next to it: one left ten
of thirteen tiers on disk while reporting a successful `jk cache nuke`,
one let a cache root full of non-tier trees report "empty" and
short-circuit the nuke, one under-reported `jk status`'s disk usage by
about 30%. So when a fact has an owner and a caller has its own copy,
**adding the missing entries to the copy is not the fix** — it passes the
guard and moves the same defect one entry later. Call the owner.

A house rule with no mechanical check is a suggestion. Round 3 measured
what that costs. `jk format`'s `optimize-imports` pass is default-on and
could not resolve a single type, because the OpenRewrite parser was built
with no `.classpath(...)` — so **4,306 fully-qualified references** stood
in a tree whose house rules say "no FQCN except collisions". Twelve
auditors filed that as twelve module-discipline problems. It was one
broken feature.

So: every rule in this document that a text scan can check **is**
checked. Scope is `src/main/java` by default, and `src/test/java` too
when the rule holds there — G3 does, because a test that builds its own
XML parser is a second posture regardless of which source set it sits in. The model is `clients/cli/build.gradle.kts` —
`checkCliRuntimeClasspath` and `checkCliNoParseTypes`: a
`tasks.registering` block with declared `inputs`, a text scan in
`doLast`, `throw GradleException`, wired to both `check` and `jar`. No
annotation processing, no bytecode analysis, no new dependency.

### Where a guard runs

**The repo builds itself twice, so a rule enforced by one build is enforced
half the time.** Every guard therefore has a home in each build, and the two
homes are not symmetrical.

| | Gradle | jk |
|---|---|---|
| Home | `Guards` in buildSrc, applied by `jk.verification` / `jk.plugin-conventions` / a module script / the root build | `.jk/after-build.kts` at the workspace root |
| Unit | one Gradle task per guard, wired from `Guards` to `check` and `jar` | one `guard(id, name) { … }` block per guard |
| Scope | per module, `inputs.files(...)` declared | the whole tree, walked once |
| Re-runs | when a declared input changes | when any file in the checkout changes |
| Reports | the first task to fail | every broken rule, in one message |

**A rule that reads one module is a test, not a guard.** The warning
further down — that a guard shipped as a test is subject to the up-to-date
check — is about **cross-module** reads: `ActionTreeTest` passed with a
violation reintroduced because nothing declared *other* modules' sources
as its inputs. That reasoning does not reach a rule whose whole corpus is
one module; the module's own test inputs already cover it, under both
builds. So seven rules live as tests in the module they govern
(`ForecastKeyParityTest` and `SpikeCacheTempDirTest` in `:engine`,
`CliSourceRulesTest` and `IdeClientWiringTest` in `:cli`), and the gate
keeps only what genuinely spans modules. They get assertions, a debugger
and a failure report; the gate got 660 lines shorter.

Ask which it is before writing one: *does this rule read more than one
module's files?* Yes → the gate. No → a test beside the code.

The gate is the **workspace root's** build logic — `after-build`, the root's
own anchor, so it runs once after every member module with the whole tree on
disk. It briefly lived in `tools/gate`, a sourceless member invented only
because a root did not run build logic at all until the root itself could;
that module is gone.

**Scope and caching are the same decision.** A root script's action key
covers every file in the checkout bar build output and VCS metadata, so an
unchanged tree skips the gate and an edit anywhere re-runs it. Keyed to a
module, as it was in `tools/gate`, a green verdict would have outlived
changes to the very files the guards read — the module had no sources, so
its key covered nothing.

Its script writes **nothing** to `outDir`, and the cache understands that:
an empty output is recorded as a *verdict* (`ActionCache.storeVerdict`),
which is what a check produces. Only a success is recorded, so a red gate
goes red again rather than replaying itself. That is the same property
Gradle needs `inputs.files(...)` for, obtained from the other direction —
and it is why the gate is build logic rather than a test. (`ActionTreeTest`
is the cautionary case: as a test it went `UP-TO-DATE` and passed with the
violation reintroduced.)

Measured on this repo: ~9 s when it runs, ~60 ms when the tree is unchanged.
Keep the first number down with the primitives, not by narrowing the key —
the lexer is memoised per file and the vocabulary guards ask each file which
of the banned literals it contains rather than asking each literal which
files contain it.

**When each build runs them.** `jk build` runs the gate on every build that
does work. On the Gradle side the tree-wide root guards (`checkSingleHomeRoot`,
`checkNoTicketIds`, `checkNoHistoricalNarration`, `checkGuardRegistry`,
`checkGuardParity`, …) are wired to the root `check`, which the root `build`
depends on, so `./gradlew build` and `./gradlew check` reach them exactly as
`./gradlew checkFast` does. That was not always so: the root had no `base`
plugin, so `./gradlew build` ran every module's lifecycle and none of the root
guards — `build dist` once passed with ticket ids in the tree that only
`checkAll` caught, commits later. G51 compares the *set* of letters between the
two builds, not their triggers, so a gap like that is invisible to it; the
lifecycle wiring is what closes it. The four tree-wide text guards cost
about 0.7 s on a no-op `./gradlew build` — the whole invocation, five guards
up-to-date (their input fingerprints are the cost) —
against ~9 s when they run. The bar for a code change is still `checkFast`
(AGENTS.md); `build` is now at least as strict as `jk build`, not a weaker
sibling with a stronger name.

**Two arms stay Gradle-only, and say why here rather than going quietly
missing.** `checkPublishedPomCoordinates` (G19) reads the POM
`maven-publish` generates into `build/publications`; jk writes a POM at
`jk publish`, so at build time there is no file to read. The first arm of
`checkTestFixturesStayOutOfProduction` (G34) reads the same generated
metadata. G34's *wiring* arm — the text scan over every build script, which
is where the mistake is actually typed — runs in both.

### How to write one

- **Read the ban list from the owner, never re-type it.** `G12` reads the
  step names out of `TaskNames.java` and `G13` reads the filenames out of
  `ManifestPaths.java` at task time. A guard that restates its owner's
  facts is a tenth copy of them.
- **But know what that buys you: closure, not coverage.** A guard whose
  ban list comes from the owner can only find literals the owner already
  knows about. It proves the owner's vocabulary is used consistently; it
  says nothing about vocabulary the owner has never heard of. `G12` was
  green while **eight step names had no owner at all** — none of them is
  declared in `TaskNames.java`, so none was in the ban list. Two of the
  eight are single words, and `G12` bans only hyphenated tokens, so they
  will still be unenforced after they are owned: the guard's shape and the
  defect's shape did not match. An ownership guard therefore wants a
  second arm asking the inverse question — *is there vocabulary of this
  kind with no owner?* — or, where that is not answerable, a header note
  saying so, rather than a green result implying coverage it does not have.
- **A green guard is evidence about the guard, not about the tree.**
  Record the violation count a guard was measured against, in its header,
  so a green result can be told apart from a blind one. Four of this
  tree's twenty guards were blind when checked: `G9` reported zero because
  it could not see `HexFormat.of()`; `G7` under-counted fourfold because
  it saw one side of a boolean; `G12` was green over eight unowned names;
  and `G1` bans two spellings that match **nothing in the tree** while 22
  hand-rolled sites exist, four of them Windows-broken through a shape its
  regex cannot see.
- **Exempt by shape, not by filename.** `G2` eliminates its one text-block
  false positive by blanking string bodies before scanning, which is why
  it ships with an empty allowlist. A filename exemption expires silently;
  a shape exemption does not.
  And when the exemption must name a *file*, declare that file as a task
  `inputs.file` rather than matching its name in a regex. `G12` exempts
  two owners that way, so moving one fails the build loudly — `Input file
  does not exist / Property 'graalLauncher'` — where a filename string
  would have silently stopped matching and left the guard green over a
  vocabulary it no longer covered.
  The strongest form of this is exemption **by spec**. `G21` bans a JSON
  escaper outside `cc.jumpkick.jsonl`, and `MinimalToml.quote` is
  character-for-character identical to `Jsonl.quote` — so no body
  comparison can tell them apart. It keys on context instead: the write
  arm requires a `"key":` JSON-object literal *alongside* the escaper, and
  the read arm keys on `\/`, which is legal in JSON and illegal in a TOML
  basic string. The result flags exactly the six wrong copies and none of
  the nine correct non-JSON escapers, with no allowlist at all. When two
  implementations are identical and only one is a defect, the difference
  is always in what surrounds them.
- **Prefer a zero-allowlist ban to a ratchet, and a ratchet to nothing.**
  An allowlist is a feature, not a concession: it *is* the ownership map,
  checked in and reviewable. When a rule has too many violations to gate
  on today, ship it as a ratchet — check in the current violating-file
  list, fail on any file not on it, delete entries as fixes land. That
  converts an unwinnable rule into a monotonic one on day one.
- **Do not write a guard that a dead call satisfies.** A "every
  `workspaceFinish` call site is preceded by `redactEnv`" scan is
  trivially defeated, and it would not have caught the three real
  violations, which were structural. Use the type:
  `workspaceFinish(…, List<Redacted> errors, …)`. A compile error beats a
  grep.
- **A guard's violation count is bounded by its detection pattern, not by
  the defect.** `G9` reported zero violations while covering under a third
  of the hex problem, because `HexFormat.of()` — 16 of 19 sites — is
  invisible to its regex. `G7` had the same shape twice over: its pattern
  saw `"true"`/`"1"` only in receiver position, and even widened it could
  not see the FALSE half of the truth set, where five more hand-rolled
  readers (`AotSettings`, `CentralMirror`, `HostWarmup`, `ChromeTimeline`,
  `CliSessionTranscript`) were sitting. Check a new pattern against every
  shape the bypass takes before believing its number.
- **If the thing you are counting has an opposite, count the opposite
  too.** That truth set was reported at 15 sites, corrected to 22, and
  measured at **82**, because each pattern in turn matched only the `true`
  side — so `G7` shipped as a ratchet at the wrong number and never gated
  five real bypasses. Whenever a concept has a complement (true/false,
  include/exclude, add/remove, open/close), a one-sided pattern measures
  at most half the defect, and the invisible half is not the smaller one.

### The guard registry

Letters are allocated when a guard lands and are never reused.

<!-- guards:start -->
| id | task | rule | form |
|---|---|---|---|
| G0 | `checkCorpus` (`.jk/after-build.kts`) | the gate's own corpus shrinking below the population every scan below it was measured against — module dirs, `src/main/java`, `src/test/java`, `plugins/*`. Not a rule about the code: a floor under the *other* guards, so a broken file tree reports green instead of scanning nothing | ban, self-hosted build only |
| G1 | `checkNoHandBuiltJavaBinary` | a hand-built `<javaHome>/bin/java` (use `JdkFingerprint`) | ban |
| G2 | `checkNoBareExitCode` | `System.exit` / `halt` with an integer literal (use `Exit`) | ban, no allowlist |
| G3 | `checkSingleXmlParserOwner` | an XML parser outside `cc.jumpkick.host.DomXml`, plus the six flags required inside it by name | ban |
| G4 | *(folded into `checkCliNoParseTypes`)* | the planned `org.tomlj` / `TomlValues` ban in `clients/cli/src/main/java` landed as two entries on the existing CLI guard rather than a new letter — one rule, one owner, no new task | ban |
| G5 | `checkWireProtocolPrefixPairs` | a `##JK*:` protocol prefix not named exactly twice | ban |
| G6 | `checkOneDigestSurface` | a `MessageDigest` lookup outside `cc.jumpkick.host.Hashing` | ban |
| G7 | `checkSingleTruthSet` | a hand-rolled boolean truth set, true **or** false side (use `EnvValues.parseBool`) | ban |
| G8 | `checkSingleArchiveInstant` | an archive entry stamped outside `DeterministicZip` | ban |
| G9 | `checkNoHandRolledHex` | a per-byte hex loop, or `java.util.HexFormat` outside `cc.jumpkick.host.Hashing` (use `Hashing.hex`) | ban, no allowlist |
| G10 | `checkFileSizeCaps` | growth past `size-baseline.txt` or over a language's hard cap | ratchet |
| G11 | `checkNoFqcn` | a file gaining a fully-qualified class name (`fqcn-baseline.txt`) | ratchet |
| G12 | `checkNoBareTaskName` | a step name typed as a literal (use `TaskNames`) | ban, no allowlist |
| G13 | `checkNoBareManifestName` | a jk file name typed as a literal (use `ManifestPaths`) | ban |
| G14 | `ForecastKeyParityTest` (`:engine`) | a forecast key hashing a different fact set than the build key | ban |
| G15 | `checkNoBareTierName` | a cache-tier directory typed as a literal (use `CacheTree`) | ban |
| G16 | `checkSingleCentralAddress` | a Central URL, its `repo1.maven.org` alias, or the repo name `central` typed as a literal (use `RepositorySpec`) | ban |
| G17 | `checkNoBareWireType` | a hyphenated wire message type typed as a literal (use `EngineProtocol`) | ban |
| G18 | `checkNoBareTargetDir` | jk's build output directory typed as a literal (use `BuildLayout.TARGET`) | ban |
| G19 | `checkPublishedPomCoordinates` | a generated POM naming a coordinate this build does not publish (`unspecified`, the `jk` fallback group, or an unpublished artifact in a group we do publish) | ban, no allowlist |
| G20 | `checkSingleHostSurface` | an `os.name` read outside `cc.jumpkick.host.Os`, or a path separator outside `Classpaths` (`-cp`) and `SearchPath` (`PATH`) | ban |
| G21 | `checkOneJsonCodec` | a JSON escaper or an escape-decoding parser outside `cc.jumpkick.jsonl` — exempt by spec, so `MinimalToml.quote` beside `Jsonl.quote` passes | ban, no allowlist |
| G22 | `IdeClientWiringTest` (`:cli`) | an IDE client naming a command, verb, class or wire field that does not exist, or pinning `untilBuild` — five arms, each self-failing on an empty scan | ban, no allowlist |
| G23 | `checkNoOrphanTestTags` | a `@Tag` no test task runs, a tag no tier owns, or a `TestTiers` table that does not partition its own vocabulary — three arms, exhaustive over the 2⁴ tag subsets, plus an import-vs-literal blindness balance | ban, two named fixture exceptions |
| G24 | `checkSingleAotMarkerSpelling` | the `.noaot` refusal-marker suffix typed outside `cc.jumpkick.host.AotCacheFiles` — banned outright in `src/main/java`, and in `src/test/java` as a bare suffix (a whole fixture file name is allowed) | ban, no allowlist |
| G25 | `checkPluginFamily` | a plugin module whose family (SPI plugin vs forked worker, decided by the presence of `jk-plugin.toml`) disagrees with its wire-prefix wiring, or an SPI plugin reading a config key its `[schema]` does not declare — four arms, per module, each self-failing on an empty scan | ban, no allowlist |
| G26 | `checkPluginForkOwner` | a plugin forking a process outside `TaskExec.ToolRun.start()` | ban, one commented file exemption (a container runtime named on `PATH`, which `ToolRun` cannot express yet) |
| G27 | `CliSourceRulesTest` (`:cli`) | a `clients/cli` command inheriting stdio outside `CliOutput.handOffTerminal` — comment-blind, plus a self-fail arm on the owner still calling `inheritIO()` | ban, no allowlist |
| G28 | `checkNoRetiredWireSpelling` | a retired wire-key spelling typed as a field key in production source | ban, declared inputs + self-fail floors |
| G29 | `checkWorkerOfflineFromSpec` | a `JK_OFFLINE` / offline-property read in worker sources (use `TaskExec.offline()`) | ban, no allowlist |
| G30 | `checkPropertiesStoreOwner` | a `Properties.store()` call in main sources (use `DeterministicProperties.render`) | ban, no allowlist |
| G31 | `CliSourceRulesTest` (`:cli`) | a `:cli` test reading the ambient state root without `@IsolatedState` | ratchet |
| G32 | `SpikeCacheTempDirTest` (`:engine`) | a spike-cache test that does not root its project in a `@TempDir` | ban, one env-gated exception |
| G33 | `checkCatalogLockParity` | `gradle/libs.versions.toml` and `jk-lock.toml` disagreeing on a shared module version | ban |
| G34 | `checkTestFixturesStayOutOfProduction` | a `testFixtures(...)` dependency on a non-test configuration, or a test-fixtures/JUnit/AssertJ jar on the CLI's runtime classpath — scans every build script, plus a self-fail arm | ban, no allowlist |
| G35 | `checkTestPathsFromCheckoutRoot` | a test locating a checkout file from the working directory — `getProtectionDomain` outside `cc.jumpkick.testing.RepoRoot`, or a `user.dir` line escaping with `..`; comment-blind, plus a self-fail arm on the fixture's signatures | ban, no allowlist |
| G36 | `checkManifestDepParity` | a module whose `build.gradle.kts` and `jk.toml` declare different workspace dependencies, in either direction, including a Gradle `testFixtures(...)` edge with no `fixtures = true` twin — plus a self-fail arm on the project-path-to-artifact-name map | ban, no allowlist |
| G37 | `checkOneRecursiveDelete` | a hand-rolled children-first delete outside `cc.jumpkick.host.PathUtil`, or `FOLLOW_LINKS` in any file that deletes — comment-blind, plus a self-fail arm on the owner still using `walkFileTree` and `NOFOLLOW_LINKS` | ban; four commented exemptions, each a *selective* delete rather than a tree delete |
| G38 | `checkToolchainEnvFromRequest` | a toolchain env var read straight from the daemon's own environment instead of the request (use `BuildEnv.forModule` / `ambient`) — `JK_JDK=temurin-21 jk build` was silently ignored, so which JDK you compiled against depended on how the resident engine happened to be started | ban; `server/` + `shared/` main sources only (`clients/` is exempt by shape — there `System.getenv` **is** the request), one narrow in-scope exemption |
| G39 | `checkCheapestRejectionFirst` | an `isRegularFile` filter placed before a free name-only predicate — the walk already read the attributes, and re-resolving the path costs 10.3 µs on NTFS against 1.0 on ext4 | ban |
| G40 | `checkRunnableOwner` | a `Files.isExecutable` outside `PathUtil.isRunnable` — 33.4 µs on Windows against 0.52 on Linux, for a question Windows does not answer that way (there the extension decides) | ban, one exemption (`ActionCache.executableBit`, which wants the bit itself) |
| G41 | — | never allocated. Letters are issued when a guard lands; this one never was, and is not reusable. G44 briefly carried the same claim and was wrong: it is live in the self-hosted build. | — |
| G42 | `checkTreeCopyOwner` | a hand-rolled recursive copy outside `PathUtil.copyTree` — the mirror of G37 for the write direction; all twelve callers shared the same three defects (`createDirectories` per *file*, no byte-identity check, walk attributes discarded) | ban, commented exemptions, each a copy deliberately not the owner's shape |
| G43 | `checkArchiveStreamOwner` | an archive byte sink that bypasses `DeterministicZip.archiveStream` / `newArchive` — `ZipOutputStream` inherits a 512-byte buffer, turning a 9 MB jar into ~18,000 `write(2)` calls where 64 KB gives ~143 | ban + self-fail on the owner still offering the sink |
| G44 | `checkBothBuildsSeeEveryModule` (`.jk/after-build.kts`) | a module `settings.gradle.kts` and the root `jk.toml` `[workspace]` do not both see — Gradle never builds it, or `jk build` never compiles it and `jk test` never runs its suite; plus a stale `singleBuildModules` exception that one of the builds has since picked up | ban, one declared single-build exception (`clients/intellij`) |
| G45 | `checkBlindWalkRatchet` | a module gaining a blind `Files.walk` / `walkFileTree` / `newDirectoryStream` / `list` in `src/main/java` (prefer `PathUtil.forEachRegularFile`, which hands the walk's attributes to the visitor) | ratchet against `walk-baseline.txt`; a module may only shrink |
| G46 | `checkJdkRemovalConfined` | JDK removal reachable from anything but an explicit `jk jdk` verb — an ordinary build deleted the JDK it was running on, twice in one afternoon, taking four installs including both GraalVMs | ban + self-fail on `JdkGarbage` still carrying the members it reads |
| G47 | `checkCaseConversionLocale` | a `toLowerCase()` / `toUpperCase()` in `src/main` without `Locale.ROOT` — under tr_TR/az `'i' ⇄ 'I'` do not round-trip, so an identifier parser is wrong for an entire locale family; one silently rewrote an MCP client's `runtime` scope into `main` | ban + a self-fail arm on scanning zero files |
| G48 | `checkNoGluedInlineTag` | an inline Javadoc tag glued to its payload (`{@code.asc}`) — renders as literal garbage, and blocks FQCN shortening for the whole file | ban, no allowlist |
| G49 | `checkSingleHomeRoot` (root project) | a path spelling from the pre-`~/.jk` layout, or a retired per-role `JK_*_DIR`, anywhere a reader can see it — sources, tests, docs and installers, deliberately **not** comment-blind, since comments are the surface being protected; extension-blind like G50; self-fail arms: stale allowlist entry, fixture set no longer caught, marker isolation, empty candidate set | ban; nine-file allowlist, each entry carrying the reason it reads another program's layout |
| G50 | `checkNoTicketIds` (root project) | a KanArtist ticket id anywhere in the tree — extension-blind, because every scope this rule was given by extension is where it was missed next: `*.kts` held 153 after the first sweep reported clean, the web client's CSS/JS held ~50 after the second, and a Giter8 template wrote one into a user's own new project | ban, two exemptions (`AGENTS.md`'s board protocol, this page's ban examples) + a self-fail on an empty candidate set |
| G51 | `checkGuardParity` (root project) + `.jk/after-build.kts` | a guard letter enforced by one build and not the other — G46 through G50 lived on the Gradle side only, so `jk build` printed "house rules clean" while enforcing 36 of the 41 it claimed, and neither gate's count was wrong about itself. Deliberately implemented twice: a parity check only one build runs has the shape of the problem it prevents. The exception list is single-owner (`guard-parity.txt`), so a letter cannot be excused on one side and demanded on the other | ban; exceptions carry the reason parity is impossible, and "not ported yet" is not one |
| G52 | `checkTestTierDocs` (root project) + `.jk/after-build.kts` | the contributor tier table differs from `TestTiers` task names, include/exclude tags, order, or `checkAll` membership | exact generated-block comparison in both builds |
| G53 | `checkNullMarkedApiPackages` (root project) + `.jk/after-build.kts` | a production package in `shared/jk-api`, `shared/wire`, or `shared/plugin-sdk` lacks package-level `@NullMarked`, or the measured 14-package corpus shrinks | ban, no allowlist |
| G54 | `checkPluginSdkBoundary` (root project) + `.jk/after-build.kts` | a first-party plugin adds an unclassified project dependency, or an exception disappears without removing its allowlist row | SDK/host baseline plus the current invariant table in `docs/contributors/plugins.md`; server dependencies are banned |
| G55 | `checkEngineConfigDocs` (root project) + `.jk/after-build.kts` | the published engine-config tables differ from `EngineControls` keys, env names, defaults, or meaning | exact generated-block comparison in both builds |
| G56 | `checkBootstrapVersions` (root project) + `.jk/after-build.kts` | the wrapper task version disagrees with `gradle-wrapper.properties`, or a `setup-node` step does not read `.nvmrc` | exact version comparison in both builds |
| G57 | `checkCiCadence` (root project) + `.jk/after-build.kts` | nightly CI no longer runs `benchTest`, `coverageReport`, or the macOS/Windows product smoke | workflow text scan in both builds |
| G58 | `checkSecurityDocs` (root project) + `.jk/after-build.kts` | the security-reporting page, the GitHub `SECURITY.md` pointer, or the advisory URL is missing | file and pointer scan in both builds |
| G59 | `checkNoHistoricalNarration` (root project) + `.jk/after-build.kts` | a comment or doc narrates a previous design instead of the current invariant | phrase scan in both builds; AGENTS.md and comments.md exempt because they name the ban |
| G60 | `checkOneJsonSplicer` (root project) + `.jk/after-build.kts` | a JSON object spliced by hand in `src/main/java` — a closing brace chopped and appended to, or a literal `{` opened onto another object's tail — outside `Jsonl.append` | ban in both builds; self-fail when the owner stops splicing or the scan sees too few sources |
| G61 | `checkInstallTestsRedirectM2` (root project) + `.jk/after-build.kts` | a test that runs the install verb without `--m2-dir`, which publishes the fixture into the developer's real `~/.m2` | ban in both builds; self-fail when the scan stops finding install invocations |
<!-- guards:end -->

`checkCliRuntimeClasspath` and `checkCliNoParseTypes` predate the letters.
Both read only `clients/cli`, so both are now arms of `CliSourceRulesTest`
— jk checks the manifest where Gradle checks the resolved classpath,
which is the half each build can see.

**This table drifted twice, and that is worth recording.** Six guards landed
carrying no letter at all — G28 through G33 above were lettered when the
drift was found, not when they shipped. It then happened again and larger:
the table sat at G37 while the build ran through G48, which is how landing
G49 nearly collided with a live letter. A registry that lags the code is
the same defect as a baseline that lags the tree, and it has the same fix:
reconcile by listing both sides and diffing them.

Twice by hand is the argument for the third time not being by hand.
`Guards` in buildSrc is the typed catalog: letters, task names, descriptions,
attach set, and fast-gate membership. `checkGuardRegistry` diffs this table
against `Guards.tableMarkdown()`. Add a row by adding a `GuardSpec`; the
marked table is generated, not edited. Retired and never-issued letters keep
a row saying so, because the set has to be total for the diff to mean
anything.

    guards in gradle:   `Guards` in `buildSrc/src/main/kotlin/Guards.kt`
    guards in the gate: grep -o 'guard("[A-Z0-9]*", "check[A-Za-z]*"' \
                          .jk/after-build.kts
    rules as tests:     ls */*/src/test/java/**/*RulesTest.java \
                          */*/src/test/java/**/{ForecastKeyParity,IdeClientWiring,SpikeCacheTempDir}Test.java

A new Gradle `check*` task that is not in `Guards` fails `checkGateCoverage`.
Wire it with `registerGuard("checkFoo") { … }`.

A guard does not have to live in `buildSrc`. G19, G22 and G24 sit in the
`build.gradle.kts` of the module that owns the fact — which is the right
home when the ban list comes from one module's source. Wire it to that
module's `check` **and** `jar`, and remember `checkAll` now depends on
every module's `check`, so it will run.

A third home: a **convention script**. G25 and G26 live in
`jk.plugin-conventions`, so each of the 15 plugin modules checks *itself*
— the failure names the offending module, the fix is local, and the
revert check is one narrow task (`./gradlew :auditor:checkPluginFamily`)
rather than a tree-wide scan whose message has to say where it looked.
Use this when the rule is per-module and the family already has a script.

**On the jk side there is one home, the workspace root's `.jk/`, and
per-module guards loop rather than fan out.** A `.jk/` of its own for
`:cli` or `:engine` would buy the same locality, and would cost a second
`kotlinc -script` start-up per module and a second copy of every text
primitive — which is the duplication the rest of this file exists to
prevent. The failure still names the module and the file, so the fix stays
local. See [Where a guard runs](#where-a-guard-runs).

**A guard that ships as a test is subject to Gradle's up-to-date
check.** `ActionTreeTest` enforces an owner-sourced ban list from
`:host:test`, and its first revert check *passed with the violation
reintroduced* — because `:host:test` was `UP-TO-DATE`: nothing declared
the other modules' sources as its inputs. A guard task declares
`inputs.files(...)`; a test that does a guard's job has to declare the
same tree-wide fileTree on the test task, or it silently stops looking.
Verify it fires **without** `--rerun-tasks`, which is the only way to see
this.

**Give every guard a self-fail arm.** G22, G23 and G24 each fail when
their own scan comes back empty or their owner-read returns nothing — so
a renamed constant or a narrowed glob is a loud failure rather than a
silent green. G24's message names the numbers: *"scanned 24 main and 14
test files, measured against 1,236 and 922."* That is the cheapest
possible defence against the blindness that made five of the first twenty
guards useless.

### Verify the number before it lands in a Done criterion

Round 3's audit described mechanisms and magnitudes. **Every mechanism was
real.** The magnitudes were another matter: of the campaign's large
counts, exactly one was right on first measurement, and the usual cause of
a wrong one was a detection pattern narrower than the defect — a truth-set
scan that only matched `"true"` in receiver position, an FQCN count that
missed qualified static calls, an exit-code count that missed 78 already-named
constants. Twice the real number was *larger* than reported and the
proposed fix would have made the tree worse: renumbering usage from `64`
to `2` would have touched 85 sites to save 20.

So a count in a Done criterion is re-measured against the tree before the
criterion is written, and the criterion records the measurement. Retractions
and reconciliations belong in a corrections log, not in a silent edit.

### A test that passes without executing is worse than a missing test

A missing test is visible. Round 3 found nine distinct mechanisms by
which a suite reported success while proving nothing, each individually
plausible:

1. An assumption that is always false, so the guarded branch never runs.
2. A stub HTTP server answering `405` to every `GET`, so the failure path
   under test was the only path ever taken.
3. A test tier that does not declare its inputs, so it replays stale
   results after the code it covers changes.
4. A cached artifact replayed from the action cache, so the producer under
   test never runs at all.
5. A subprocess pass-criterion — exit status alone — that a run executing
   **zero** tests also satisfies.
6. A worker that dies mid-run and is reported as a successful, complete
   run.
7. A test that asserts a value's **shape** rather than its **content** —
   `assertThat(digest).hasSize(64)` passes for `e3b0c442…`, the SHA-256 of
   nothing, which turned out to be the only value that code path could
   ever produce. A length assertion on a digest is not a test of a digest.
8. A branch entered **vacuously**: the loop is reached, the collection is
   empty, the body never runs. Coverage tooling counts the branch.
9. A test that exercises the **wrong path to the same outcome**. A
   dead-runner test written over a socket transport passed, and kept
   passing with the fix reverted, because the cancel stamps carried it —
   the bug lived on the detached `FireAndForget` path. Rewritten to that
   path, it went red. The test was correct about the *outcome* and wrong
   about the *route*.

All nine share one shape: the assertion is true, and it is true for a
reason unrelated to the behaviour under test.

**Fail-closed defaults are a trap for exactly this.** If absent means
"refuse", then a test that only asserts refusal passes when the value
never arrived at all — the safe default and the correct plumbing are
indistinguishable. Make the receiver report *that it was told*, not just
what it decided, and assert on both.

So when you assert that a mechanism *fired*, ask what else produces the
same observable — and if the answer is "the absence of the mechanism,"
the assertion is worthless. A Ctrl-C test that checks for exit 130 passes
against a process with **no handler at all**, because `SIG_DFL` yields
130 too; assert on the cancel request the engine received instead.

Number nine was caught **by the revert check**, not by review — the test
looked right and the fix was right, and only reverting the production
change exposed that the two were not connected. That is the argument for
the revert check in one sentence: it is the only step that tests the
*test*.

The countermeasures are cheap and are now house style: read `tests=` and
`skipped=` out of the `TEST-*.xml`, not just the exit status; and after a
test passes, **revert the production change and confirm it goes red.** A
test that has never been seen to fail has not been seen to work.

---

### A delete that follows a symbolic link is not a delete

Two of the maintainer's JDKs were emptied in place — contents gone, the
directory left behind. jk discovers host-installed toolchains and links
its own registry entries to them, so an sdkman or IntelliJ JDK is a
routine link target, and a delete that follows a link reaches files jk
never installed.

`PathUtil.deleteRecursively` is the owner, and it was *mostly* right:
`Files.walk` declines to follow links by default, so links met inside a
tree were already unlinked rather than entered. Three things were wrong
anyway, and the shape of them is the lesson.

**The rule was the absence of a parameter.** No `FileVisitOption` meant
no following. So the rule was unstated, untested, and one word from
being reversed — "cleanup left files behind" reads exactly like a
missing `FOLLOW_LINKS`. It is now a line of code with three tests on it,
and G37 fails the build on that word appearing in any file that deletes.

**A dangling link survived every cleanup.** The method gated on
`Files.exists`, which follows: a broken link answered "absent" and
returned early. So the one case where a link is *unambiguously* garbage
was the one case that was never collected.

**The tally followed links too.** `Files.isRegularFile` and
`Files.size` both follow, so `jk clean` credited itself with the size of
whatever a link pointed at — space that is still in use. Taking the
attributes from the walk, which already has them and already has them
NOFOLLOW, fixes the count and saves a `stat`.

**And nineteen files had their own copy of the walk.** Each one decided
the symlink question independently; two of them were in the JDK-symlink
machinery itself. `MinifiedJarPackager`'s copy carried the reason
"Local copy by design: plugins stay dependency-free of jk's kernel
modules" — while the same file imported `cc.jumpkick.host.BuildStamps`
and `DeterministicZip`, and while `:host`'s own description reads "the
JDK-only floor the CLI, the engine and every plugin worker share" and
names `PathUtil` in it. That is the eighth invariant comment this
campaign has checked and found false.

Fifteen of the nineteen were verbatim tree deletes and now call the
owner. The four that remain delete *selectively* — only empty
directories, only unowned files, only the contents and not the
directory — and G37 names each one with its reason, because "this is a
different operation" and "this is a copy" look identical from a
distance.

### The repo builds itself twice, so it has two manifests and one truth

jk builds jk. Every module carries a `build.gradle.kts` **and** a
`jk.toml`, and the two say the same thing in different vocabularies:
`implementation(project(":core"))` is `jk-core.workspace = true`,
`testImplementation` is `[test-dependencies]`, and Gradle's
`testFixtures(project(":host"))` is jk's
`jk-host = { workspace = true, fixtures = true }`.

Nothing was checking that they agreed. Thirteen edges were out of step at
once, all in the direction of "Gradle knows, jk does not", so
`./gradlew checkAll` was green while `jk test` could not compile the tree.
The maintainer found it the only way it could be found: by wiping
`~/.jk` and running `jk test`.

The drift is not symmetric and the asymmetry is the lesson. Gradle was
right about nine edges that jk had never been told (`clients/web` taking
`:wire` for `WireTokenParityTest`; the five modules that consume `:host`'s
test fixtures; the four plugin modules that consume `:plugin-sdk`'s).
**jk** was right about two that Gradle still declared after a cleanup
deleted them as unimported — so a guard that only checked one direction
would have "fixed" the tree by re-adding dead edges. G36 checks both.

**A ratchet on one build is not a ratchet.** Every rule in this document
about owners, guards and baselines assumed one build graph. Two build
graphs means every structural claim needs asking twice, and the second
build is the one nobody runs before pushing.

### A test that reads the source tree names it from the checkout root

The same round found the smaller sibling of that defect. Gradle runs a
test with CWD at the owning module; a workspace `jk build` runs it with
CWD at `~/.jk/state/engine`. A source tripwire that spells its
target `Path.of(System.getProperty("user.dir"), "../../plugins/android/…")`
therefore reads `~/.jk/state/plugins/android/…` under jk — green under
Gradle for months.

Fourteen test classes had solved this, each with its own copy of the
walk-up-from-my-own-class-file loop, and each copy with a different
per-module marker baked in (`settings.gradle.kts` + `server/engine`,
`jk.toml` + `shared/plugin-sdk`, `jk.toml` + `install.sh`…). That is why
they were not literally identical, and it is why nobody noticed the
fifteenth site had been written without one.

Three of the fourteen degraded to a **skip** when the search failed —
`assumeTrue(mainOpt.isPresent(), "… skip scan")`, and in one case a
`@BeforeAll` whose own comment said "fail hard if it disappears" above an
`Assumptions.assumeTrue`. A broken search read as a pass. Mechanism 1 of
the nine above, three more times.

One fixture replaced all fourteen: `cc.jumpkick.testing.RepoRoot`, in
`shared/host`'s test fixtures, reachable from anywhere in the tree. It
walks to the **checkout** root — the one anchor both builds share, since
neither the module directory nor the CWD is reliably an ancestor of the
loaded classes — and every path is then spelled from there, where the two
layouts agree. It throws rather than returning null, which is what turned
the three skips back into tests. `WebClientJsTest` alone lost sixty-eight
lines of layout guessing for nine.


## Campaign

Each phase is a mergeable change. The tree builds and tests green.
Commit messages carry the scoreboard: `EngineServer 7073 → N`,
`maps 14 → N`, `envelopes 2 → 1`.

**Phase 0 — Tooling.** JSpecify on all Java modules. Short pointer from
`CONTRIBUTING.md` to this file. Inventory every `run*` / `handle*` /
`startHttp*` (kind, shape, exclusive, HTTP?).

**Phase 1 — Types.** Imports, not FQCN. `BuildAccumulator` →
`engine.journal`. Sealed `JobKind` + `JobRequest`; delete the three
`handleAsync*` overloads. `JobSession` replaces the maps.
`@NullMarked` on `engine.journal` and `engine.jobs`.
Exit: `EngineServer` ≤ 4,500, maps ≤ 2.

**Phase 2 — CLI envelope.** `handleAsyncBuildPlanRequest` becomes
`JobEnvelope.submit`. Existing `this::runLock` adapters are fine.
Gate: cancel, deadline, `CancelStampGuardTest`, `EngineServerTest`.

**Phase 3 — One envelope.** HTTP/MCP become `FireAndForget`. Delete the
second registry. HTTP jobs share `admitJob`, the deadline, and cancel.
Success semantics converge on `effectiveSuccess`. This is an
improvement, not a regression to document and keep.

**Phase 4 — Event bridge.** Sealed `EngineEvent` + sinks. Collapse the
three listeners. If the wire is the ugly one, fix the wire and every
consumer. Redaction on every path.

**Phase 5 — Verbs.** `VerbRegistry` + `HostedVerb`, by cluster:
workspace/test → lock family → hosted plans → cache maint → sync reads.
`serveConnection` is the four-arm `VerbShape` switch.

**Phase 6 — Residue.** `IdleHousekeeping`, `AotTrainer`, `EngineVitals`
leave if `EngineServer` is still over 1,200. `EngineMaintenance`
already exists — finish moving, do not invent a parallel chore type.

**Phase 7 — Lombok sweep.** Done: root `lombok.config`
(fluent + chain + generated annotation), compile-only + AP on every
Java module, `@Builder` on field-copy request types, `@RequiredArgsConstructor`
on assignment-only composition roots. Remaining hand-rolled builders are
accumulators (`Task`, `BuildPlan`, `JkBuild`, `WizardStep`, `Invocation`,
graph/import builders) — keep them. Records stay records. JSpecify still
owns nullness.

**Phase 8 — Reach and guards.** `shared/jsonl` becomes
`shared/host` with `cc.jumpkick.host`, so every module — plugin worker,
native CLI, engine — can see `Exit`, `Hashing`, `Os`, `PathUtil`,
`DeterministicZip`, `BuildStamps`, `CacheTree`. Then the mechanical
sweeps, each landing **owner, then sweep, then guard**, in that order and
in one change. Exit criteria: the guard registry above is green, `jk
format` actually shortens an FQCN, and `jk audit` fails a build on a real
CVE.

### Scoreboard

| Surface | Now | Done | Cap |
|---|---|---|---|
| `EngineServer.java` | 7,073 | 900–1,200 | 1,500 |
| Job envelopes | 2 (CLI + HTTP) | 1 | 1 |
| Process-lifetime maps | 14 | 1 (`sessions`) | 2 |
| Boolean flags on submit | 2 | 0 | 0 |
| `JobEnvelope` | (embedded) | 450–550 | 700 |
| `JobSession` + ledger | (maps) | 350–450 | 500 |
| `BuildAccumulator` | ~480 inner | ≤ 450 own file | 500 |
| Events + sinks + bridges | ~800 anonymous | 500–700 | 800 |
| Per-verb class | (methods) | 80–180 | 250 |
| `EngineHttpJobs` | ~250 with copies | ~20 | 40 |
| Hand-rolled getters/setters/builders where Lombok fits | field-copy + assignment-only ctors | 0 (accumulators kept) | 0 |

**Done** when a new hosted verb is one class + one registry line, rides
one envelope on every transport, and nobody opens `EngineServer.java`
to add it.

That table is the round-1 target sheet and is closed. The live numbers are
`size-baseline.txt`, which the build reads on every `check`; the table
below is its human-readable summary.

---

## After EngineServer

Same charter, one patient at a time. Current sizes:

| File | Before | Floor | Today | Cap | How |
|---|---|---|---|---|---|
| `EngineServer` | 3,418¹ | 1,064 | 587 | 800 | elect/accept/drain/close + composition root |
| `JobEnvelope` | — | 683 | 500 | 800 | one submit path |
| `HttpEngineServer` | 1,592 | 768 | 558 | 800 | router / history / project / live |
| `EngineClient` | 2,616 | 942 | 573 | 800 | spawn, wire, hosted verbs |
| `EngineProtocol` | 3,379 | 464 | 142 | 800 | discriminators; builders in `Proto*` families |
| `BuildService` | 1,618 | 507 | 281 | 800 | lock-guard / execute / fold |
| `BuildPlanner` | 5,348 | 1,191 | 512 | 800 | `Planner*` step clusters; facade `coreBuilder` |
| `JkBuildParser` | 2,238 | 391 | 328 | 800 | `Manifest*` table parsers |
| `JkManager` | 2,204² | 1,178 | 719 | 800 | facade: live region + Ctrl-C handoff |
| `JkManagerView` | — | — | 455 | 800 | paint |
| `JkManagerColor` | — | — | 380 | 800 | token colour |
| `NewCommand` | 1,403 | 1,164 | 546 | 800 | wizard |

¹ EngineServer entered this batch at 3,418 lines; an earlier Typed Envelope peel had
already taken it from its 7,073-line peak.

² `JkManager` was one 2,204-line class. The triad that replaced it shipped at 2,467 lines total and
is **3,421** today — larger than the god class. `JkManagerView` and `JkManagerColor` are already out
at the natural seam, which is why the facade is the row that has to come down.

**A shipped number is a floor, not a fact.** Every row above regrew after
its peel: `EngineServer` finished at 1,064 and was over 1,200 eleven days
later, and the `JkManager` triad — the facade plus `JkManagerView` and
`JkManagerColor` — went 2,467 → 3,421 in ten days, ending up *larger than
the 2,204-line god class it replaced*. A one-shot "After" column is
exactly what let that happen unnoticed.

So the **Cap** column is not aspirational: it is the file's
`size-baseline.txt` entry (or its language hard cap when unlisted), and a
commit that pushes a file past it fails the build. **Today** and **Cap**
are code lines (comments, blanks, and `package` / `import` lines excluded);
**Before** and **Floor** are the historical physical counts from the peel.
The column is kept current by the ratchet itself — `checkFileSizeCaps`
prints the tightened line whenever a listed file shrinks, and the same
commit that pastes that number into `size-baseline.txt` updates **Today**
here. Re-baselining upward requires deleting the invariant comment that
justified the old number and writing one that justifies the new one.

No listed file remains over a hard cap. A new exception is a reviewable
`size-baseline.txt` entry plus the invariant that must not be split.

---

## Anti-goals

- Compatibility theater. We have no public users to protect. Do not
  keep a worse name, field, or type “just in case.”
- Half-migrated anything. Break it, then finish the move.
- DI framework, EventBus, `ServiceLoader` verb marketplace, generated
  control plane.
- Template Method `AbstractAsyncJob`. The boolean-overload tower was
  that pattern. We are deleting it.
- `@NullMarked` on 7,000 unmarked lines in one weekend.
- Hand-rolled boilerplate that Lombok or a record already expresses.
  Types are still the modernization; Lombok is how we keep those types
  short.
- Growing public product docs for this. This file is enough.
- **A second copy "because the owner is unreachable."** Fix the module
  graph, or schedule follow-up work in KanArtist. Copying is never the
  answer, and round 3 proved it: every confirmed correctness defect in the
  tree was a diverged copy of a routine that was correct once — an XXE
  posture that lost five of six flags, an `isWindows` that tested `win`
  where the owner tested `windows`, a lock writer that dropped `[[plugin]]`
  rows, a stamp predicate that never learned about `.gstamp`.
- **A comment standing in for a compiler check.** If two things must
  agree, make one derive from the other, or add a guard. Do not write the
  sentence. A "keep in sync with X" comment is a defect report about the
  code it sits in, and several in this tree guarded constants with zero
  readers.

When in doubt: fewer maps, fewer flags, fewer copies — and a process
class a careful stranger can read before lunch. Make it correct. Then
make it beautiful. Then stop.
