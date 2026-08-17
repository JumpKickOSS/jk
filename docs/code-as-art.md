# Code as Art

Maintainer spec for how JumpKick is written. Not a product doc — listed
under maintainer notes in [README.md](README.md) only.

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

## Charter

1. **Correctness first.** Race protocols, cancel, journal, lock, and
   cache invariants are not optional. If a refactor is prettier and
   wrong, it is wrong. Ticket comments on races (JK-1470, 1478, 1474,
   1521, 1725, 1837, 1861) move with the code or become types that make
   the race unrepresentable.
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
map (JK-1474).

Load-bearing races — preserve the *invariant*, not the method shape:

| Ticket | Invariant |
|---|---|
| JK-1470 | Plan slot claimed under `lifecycleLock` in the same breath as the drain check |
| JK-1478 | Register the runner *before* `start`; reverse it and cancel is a no-op |
| JK-1474 | Teardown retires the session; late writes no-op |
| JK-1521 | EOF after a reported failure is not cancel |
| JK-1725 | Release the plan slot *before* `request-finish` |
| JK-1837 | SSE snapshot + attach is one critical section |
| JK-1861 | Peak connections is one metric across UDS + SSE |

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
HTTP POST  ──decode──┼── VerbRequest ── JobEnvelope.submit ── HostedVerb.run
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
    String kind();
    VerbShape shape();
    VerbRequest decode(VerbInput in);
    void run(VerbContext ctx);
}
```

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
shared/wire                        protocol codec — improve it if it is ugly
```

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
- Comments state the **current** type or method only — no `JK-` ids, no
  historical essays, no agent decision records. Policy:
  [AGENTS.md — Comments and Javadoc](../AGENTS.md#comments-and-javadoc).

### Quality bar

DRY across CLI / HTTP / MCP. Readable in one sitting. Performant on a
**256 MiB** engine heap (no per-event framework, no session intern
pool). Concise: delete the copy, do not wrap it. Elegant: the right
types, and the absence of the wrong ones. Modern: Java 25 idioms, not
2014 POJOs and not annotation-processor theater.

### Size

| Soft | Hard | Exception |
|---|---|---|
| 400 lines | 800 lines | 1,200 with a comment naming the invariant that must not be split |

A commit is progress only if a number goes down: lines in the god file,
map count, overload count, boolean flags, duplicated envelopes, or FQCN
count. Annotations without a deleted map do not count.

### Java 25

- Data → **record** (compact ctor, `List.copyOf` on inbound collections).
- Closed variants → **sealed** + exhaustive `switch`. No
  `default` → `"should never happen"`.
- Work → **`final` class**, one constructor, `private final` fields
  unless mutability *is* the spec.
- `var` for obvious locals only. Never fields, params, returns.
- Pattern `switch` over `instanceof` ladders. `.toList()`, not
  `Collectors.toList()`.
- `Optional` is a return type, never a field or parameter.
- Never return `null` from a collection method — `List.of()`.
- No FQCN except collisions.
- Preview APIs only with an explicit ticket. Virtual threads are
  already house style.

### JSpecify

Zero-runtime. Safe on the Graal CLI. Dogfoods `jk init`.

1. New packages ship `package-info.java` `@NullMarked`.
2. Under `@NullMarked`, unannotated means non-null. **`@Nullable` is
   required** on every type use that can be null. Do not write
   `@NonNull`.
3. No JetBrains / JSR-305 / Lombok nullness in engine or shared.
   IntelliJ keeps JetBrains because the platform API uses it.
4. Mark new packages first, residue last. NullAway is a later ticket.
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
- One concern per change. The engine stays bootable. Touching
  `EngineServer` and `BuildPlanner` together is allowed only when a
  single improvement requires both.

---

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

**Phase 7 — Lombok sweep.** Shipped (JK-2003): root `lombok.config`
(fluent + chain + generated annotation), compile-only + AP on every
Java module, `@Builder` on field-copy request types, `@RequiredArgsConstructor`
on assignment-only composition roots, ticket-id comments gone.
Remaining hand-rolled builders are accumulators (`Task`, `BuildPlan`,
`JkBuild`, `WizardStep`, `Invocation`, graph/import builders) — keep
them. Records stay records. JSpecify still owns nullness.

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

---

## After EngineServer

Same charter, one patient at a time. **Shipped** (JK-1933–1941):

| File | Before | After | How |
|---|---|---|---|
| `EngineServer` | 3,418¹ | 1,088 | elect/accept/drain/close + composition root |
| `JobEnvelope` | — | 683 | one submit path |
| `HttpEngineServer` | 1,592 | 768 | router / history / project / live |
| `EngineClient` | 2,616 | 942 | spawn, wire, hosted verbs |
| `EngineProtocol` | 3,379 | 464 | discriminators; builders in `Proto*` families |
| `BuildService` | 1,618 | 507 | lock-guard / execute / fold |
| `BuildPlanner` | 5,348 | 1,191 | `Planner*` step clusters; facade `coreBuilder` |
| `JkBuildParser` | 2,238 | 391 | `Manifest*` table parsers |
| `JkManager` / `NewCommand` | 2,204 / 1,403 | 1,178 / 1,164 | view/color + wizard |

¹ EngineServer entered this batch at 3,418 lines; the earlier JK-1875..1922 charter had already
taken it from its 7,073-line peak.

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

When in doubt: fewer maps, fewer flags, fewer copies — and a process
class a careful stranger can read before lunch. Make it correct. Then
make it beautiful. Then stop.
