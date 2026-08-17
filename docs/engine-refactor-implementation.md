# Engine integration surfaces — design review and target architecture

**Deliverable of** [engine-refactor.md](engine-refactor.md). Maintainer analysis, not a
product doc. Planning/phasing lives in KanArtist (JK-2116 epic); this file records the
verdict, the evidence, and the target design a stranger could implement from.

Evidence lines cite the working tree as of 2026-08-17 (`main` @ e9296758).

---

## A. Verdict

**O2 — one domain core, N thin transports. Keep S1. Finish Typed Envelope; it stopped
halfway.**

Typed Envelope succeeded at the *job lifecycle*: all four surfaces reach one
`JobEnvelope.submitSocket` (admission `JobAdmit.admit`, drain check, journal stub, live
registration, watchdog — `jobs/JobEnvelope.java:135-462`) and one
`JobEnvelope.cancelJob` (`:522`). It never landed at the *verb* and *event* layers:

- `VerbRegistry`/`HostedVerb` is referenced by exactly one production class,
  `EngineServer` (`:313`, `:745`, `:830`). **Zero** HTTP routes or MCP tools reach a
  `HostedVerb`. `EngineHttpFront.java:171-537` is a second implementation of the verb
  set (`runWorkspace`, `runLock`, `runCompile`, …) with dropped options — HTTP `lock`
  loses features, sources, and the conservative-freshness gate (`:307-322` vs
  `verbs/LockCascade.java:48-100`). 20 of 21 MCP tools reimplement rather than decode.
- `EngineEvent` is the CLI-wire vocabulary only. The SSE/MCP side is driven off a
  parallel `Hooks` callback graph (`EngineListeners.java:170-208`,
  `SsePublisher.publishEvent`) and never sees an `EngineEvent`. Four vocabularies
  carry the same facts: `EngineEvent` records, `EngineProtocol` tokens, `JsonlShape`
  types, `SsePublisher` type strings.

The requester's HTTP-only lean is wrong, and here is the one sentence that would be
wrong if it shipped tomorrow: **deleting the JSONL wire deletes a 464-line
discriminator table plus thin `Proto*` builders — none of the actual duplication —
while adding an SSE parser, reconnect, token bootstrap, epoch handling, and
ensure-over-HTTP to a `-Os` / 128 MiB / serial-GC native binary, and it downgrades
Ctrl-C from connection-owned cancel-on-EOF to fire-and-forget-plus-explicit-cancel.**

And the sentence that would be wrong if we keep S1 forever without further deletion:
**"the wire is one of four verb implementations and one of four event vocabularies" is
not a transport cost — it is the residual defect, and every capability added before it
is fixed is written three times** (§3 of the coverage report: a fully-exposed new verb
touches ~13–15 files today; the registry's "one class + one line" claim is true only
for S1).

### Rejected options

**O1 — HTTP-only control plane.** Rejected:
- Deletes the wrong thing: the wire codec is now small (`EngineProtocol` 464 lines +
  `Proto*`); the duplication lives in `EngineHttpFront`/`McpHandler`, which O1 keeps.
- The native CLI gains a long-lived SSE consumer (none exists today — grep is clean),
  reconnect/backoff, `engineEpoch` handling, and ensure-over-HTTP, inside a binary
  tuned `-Os`/128 MiB/SerialGC; `java.net.http` is already linked but only for
  request/response.
- `SocketWatch` semantics are load-bearing: `job-start` with `detailsPath`, on-wire
  heartbeat, cancel-on-EOF (`JobEnvelope.java:330-347`), bounded join. HTTP replaces
  connection-owns-job with jid-plus-reconnect — strictly more client state for the
  same guarantee.

**O3 — local HTTP (possibly over UDS) as the CLI transport.** Rejected:
- All of O1's client costs (SSE consumer, reconnect, bootstrap) for none of its
  deletions — the engine must still speak plain HTTP for the browser, so HTTP-over-UDS
  is a *third* bind mode, not a replacement.
- Process lifecycle (`hello`/`ping`/`shutdown`/takeover/election) still needs a
  pre-HTTP handshake — `EngineSpawn.probe` runs before any server is known healthy.
- Solves no observed defect: nothing in the coverage matrix or traces is caused by the
  socket being JSONL rather than HTTP.

**O4 — something better.** Nothing qualifies. Every candidate that deletes more than it
adds is already O2 (finish the existing design); the forbidden list (gRPC, IDL, DI,
EventBus) stays forbidden.

---

## B. Assumption scorecard

| # | Hypothesis | Verdict | Evidence |
|---|---|---|---|
| 1 | "Multiple redundant ways to do the same thing" | **Partial — confirmed, but not where claimed.** The job lifecycle is single (`JobEnvelope`, `JobAdmit`, `cancelJob`). The redundancy is verb bodies (S2/S4 reimplement all of them: `EngineHttpFront.java:171-537`, `http/mcp/*`), read models (project summary ×4, dependency graph ×2, metrics encoder ×2 with different `?dir=` filter semantics), and event vocabularies (×4). |
| 2 | "HTTP should be the single API; the JSONL wire is bespoke cost" | **Killed.** Remaining wire cost: `EngineProtocol.java` = 464-line discriminator table; builders in `Proto*` families; `EngineWire` client. CLI has no SSE consumer to reuse (`grep text/event-stream clients/cli/src/main` = 0); UDS needs no auth at all; Windows needs TCP+token either way (`EngineTransport.useLoopbackTcp`, `EngineServer.java:430-446`). The cost being paid is duplication above the transport, which O1 does not remove. |
| 3 | "REST is the API, therefore REST for everything" | **Killed.** Hosted work is verb + `jid` + stream (`POST /api/build` → 202 + jid + SSE is already verb-shaped, not resource-shaped); MCP is JSON-RPC; `hello`/`ping`/`shutdown`/`auth`/takeover is process lifecycle handled before dispatch (`EngineServer.java:679-822`) and has no REST shape. |
| 4 | "SSE required ⇒ control plane should be HTTP" | **Killed.** SSE is required for browser + MCP and already exists (`HttpEvents`, one hub, `FrameStyle.MCP`). Unifying the *event vocabulary* is the real gap — today SSE is not even fed from `EngineEvent` (Hooks graph), which is a defect O1 would inherit unchanged. |
| 5 | "A surviving non-HTTP protocol should overlap REST/SSE semantics" | **Partial.** Overlap the domain: same verbs, same `jid`, same `EngineEvent`, same error codes — yes. Overlap the encoding — no: cancel-not-found is legitimately `cancel-ack cancelled=false` on the wire, `404` on REST, `cancelled:false` result on MCP, as three codecs of one typed result. The defect is that today those are three *hand-written semantics*, not three projections (`EngineServer.java:856` / `HttpReadApi.java:288` / `McpHandler.java:701-703`). |
| 6 | "Plugins should ride the same protocol as clients" | **Killed.** S7 shares exactly one thing with S1: the `Jsonl` string codec (`shared/jsonl/.../Jsonl.java`, imported by `EngineProtocol.java:4`). Different discriminator (`t` vs `type`), disjoint vocabulary, no version field, no auth (trust is at spawn: `PluginBuild.java:842-846`), prefix-tagged stdout framing vs bounded socket lines, cancel by process kill (`JobWorkers.shutdownForRequest`) vs cancel token. Merging would add a client-server auth/lifetime model to a forked child that gets EOF and `destroy()`. Hard boundary; S7 gets its own hygiene pass (dead constants, two spec-writer lanes disagreeing on `name` vs `task`, unbounded reader). |
| 7 | "Schema freeze means shapes cannot change" | **Killed (false).** Policy is: version integers stay 1; shapes may break when every in-tree consumer updates in the same change ([architecture.md](architecture.md#schema-freeze-until-10), [code-as-art.md](code-as-art.md) charter §2). |

---

## C. Surface map (as-is)

| ID | Surface | Shares with the domain core today | Residual fork (defect) |
|---|---|---|---|
| S1 | CLI↔engine wire (JSONL; UDS / TCP+token) | Everything: `VerbRegistry` dispatch, `JobEnvelope`, `EngineEvent`→`WireEventSink` | Was the only surface that used `HostedVerb`. It also carried `EngineDelegate` — nine request types intercepted *before* the envelope to run a **pinned older engine** as a `--job` child (no jid, no resident journal row, uncancellable by jid). Resolution: downward delegation is deleted outright, not fixed (see Tool version policy below). |
| S2 | HTTP REST (`/api/*`, bearer) | `JobEnvelope.submitAsync`, `cancelJob`, journal, `StatusSnapshot` | Verb bodies reimplemented in `EngineHttpFront` (options dropped); one submission route (`POST /api/build`) so REST cannot start `test`/`lock` while MCP on the same server can; phantom request type `"cache-clear-request"` exists nowhere in `EngineProtocol` (`EngineHttpFront.java:196-200`); `EngineHttpJobs.trigger` default method silently drops `modules`/tags and aliases `update`→`lock` (`EngineHttpJobs.java:39,66-74`). |
| S3 | HTTP SSE (`GET /api/events`) | `HttpEvents` hub; chrome (`status`/`cache`/`run-snapshot`) correctly dashboard-only (`HttpEvents.publish` dashboardOnly, `LiveVitals`) | Fed by the `Hooks` graph, not `EngineEvent`; SSE event name ≠ payload `type` in two cases (`plan-progress`/`progress`, `diagnostic`/`error` — `SsePublisher.java:569-574`, `:521-526`); a second `workspace-progress` throttle beside the one upstream coalescer (`SsePublisher.java:297-311`). |
| S4 | MCP (`/mcp` JSON-RPC + SSE) | Same `HttpEvents` hub (`FrameStyle.MCP`); `jk_cancel` → `cancelJob` | 20 of 21 tools reimplement: `jk_run` re-enters `EngineHttpFront`'s copies; `jk_why`/`jk_explain`/`jk_outdated` call domain ops with private encoders; `jk_history` has **no build-like kind gate and no redaction** on `view=full` raw journal records (`McpHistoryViews.matches:37`, supplier `HttpEngineServer.java:171`); `jk_disk clean|nuke` runs `CachePlans.pruneBuildPlan(...).run()` inline with **neither** `cacheGate` write lock **nor** `.prune.lock` (`McpMachine.diskAction:252-290` vs `CacheMaintenanceVerb.java:54-67`) — it can delete under an in-flight build. |
| S5 | CLI stdout JSONL (`--output json\|jsonl`) | `JsonlShape` (schema 1, `progress` rider) | A client-side *re-derivation*: wire events are decoded back into listener callbacks (`EngineBuildListenerAdapter.java:783-1048`) and re-encoded. Acceptable as a projection; the vocabulary drift (§F) is not. |
| S6 | `details.jsonl` session log | Same `JsonlShape` — by construction identical to S5 (`CliSessionTranscript`) | None; this pair is the model the other surfaces should follow. |
| S7 | Engine↔plugin worker (`PluginProtocol`, `t` discriminator, prefix-tagged stdout) | Only the `Jsonl` codec | Out of scope for client-protocol consolidation (**category error** — see scorecard #6). In-scope hygiene: two parallel spec writer/reader lanes disagreeing on the step-name field (`SpecWriter` `"name"` vs `PluginBuild.SpecWriter` `"task"`); dead constants (`OP_TEST`, `OP_RUN`, `PROGRESS`, `OUT` — the real line is `command-out`, absent from the vocabulary); `DONE` emitted by nine plugins, consumed by nobody; plain `BufferedReader` where S1 uses `BoundedLineReader`; inert `jk-compat`. |
| S8 | Declarative plugin layer (`jk-plugin.toml`) | No RPC at all — engine applies contributions as data (`PluginContributions`) | None. Not a protocol surface. |

Answers to §4 of the brief: (1) domain *lifecycle* types are shared by S1–S4; domain
*verb and event* types are S1-only. (2) Forked implementations: verb bodies (S2/S4),
read encoders (S2/S4, plus a fourth project-card copy in
`HttpEngineServer.projectMap:599`), success-law evaluators ×3 (envelope, accumulator
`toRecord`, verb exit codes), cancel-not-found semantics ×3, `?dir=` metrics filter
semantics ×2. (3) Look-alike but different by design: S5/S6 (same shape, different
lifetime — fine), S3 chrome vs S4 (correctly filtered). (4) S7 is excluded with a hard
boundary; merging it would import auth/lifetime machinery a trusted forked child does
not need and cannot use.

---

## D. Target architecture (present tense)

The engine has **one domain core** and **thin codecs per surface**. A capability
exists exactly once; a surface is a decoder in front of it and an encoder behind it.

### Verbs

`HostedVerb` stays the strategy interface; `VerbRegistry.standard(...)` stays the
explicit list. It gains one optional surface hook:

```java
interface HostedVerb {
    String wireType();
    String kind();                       // stable capability name: "build", "lock", …
    VerbShape shape();                   // SyncRead | AsyncPlan | CacheMaint
    VerbRequest decode(VerbInput in);            // S1 wire line
    default VerbRequest decodeJob(HttpJobSpec s) // S2/S4 job submission
        { throw new UnsupportedOperationException(kind()); }
    JobOutcome run(VerbContext ctx);
}
```

- `EngineHttpJobs.trigger(HttpJobSpec)` is `registry.byKind(spec.kind()) → decodeJob →
  JobEnvelope.submit(request, FireAndForget)` — the ~20 lines the code-as-art
  scoreboard always specified for `EngineHttpJobs`. Every `EngineHttpFront.run*` body
  is deleted; HTTP `lock` regains features/sources/conservative gating because there is
  no second body to forget them in.
- MCP `jk_run`/`jk_build`/`jk_test`/`jk_lock` build an `HttpJobSpec` and enter the same
  method (they already do — the fix is what is behind it).
- REST job submission accepts `kind` on the one existing route (`POST /api/build`
  gains `kind`, defaulting to `build`); exposing a verb to HTTP/MCP = implementing
  `decodeJob`. No new route family, no OpenAPI.
- `VerbShape` has **three** arms. The `Lifecycle` arm is deleted: `hello`/`ping`/
  `status`/`shutdown`/`auth`/`cancel-request` stay on `EngineServer.serveConnection`,
  which is what the code already does (`dispatchVerb` throws on `Lifecycle` today).
- MCP/wire *reads* share one result type per capability: `WhyVerb` and `jk_why` both
  encode the same `WhyReport`; history list/show share one record filter
  (build-like gate) and one redaction pass on **every** surface.

Adding `jk quux` on all surfaces is: `QuuxVerb` (with `decodeJob` if exposed) + one
registry line + the `Proto*` request/ack builder pair + the CLI command. The HTTP and
MCP sides require **zero** new engine code beyond `decodeJob`; the MCP tool entry is a
schema declaration, not a reimplementation.

### Jobs

`JobEnvelope` keeps the one submit path; `JobTransport` becomes the *real* seam:

- `submitAsync` is deleted; HTTP/MCP call `submit(line, request,
  new JobTransport.FireAndForget())`. The `boolean detached` and nullable
  reader/writer parameters of `submitSocket` fold into a switch on the sealed
  transport (today `FireAndForget` is never constructed and `writerOf` has zero call
  sites — the algebra is dead code).
- The watchdog is transport-aware: `SocketWatch` heartbeats on the wire;
  `FireAndForget` starts a watchdog only when a wall deadline is set (today every HTTP
  job burns a 30 s wakeup to write into a null writer).
- A deadline or cancel kill **records its reason** on the accumulator, so the journal
  row and SSE `request-finish` say *why* — today `ERR_DEADLINE` goes to
  `sendQuiet(null, …)` and an HTTP job killed by deadline is indistinguishable from a
  user cancel.
- One fingerprint function: `BuildJobFingerprint.ofRequest` serves both surfaces
  (`ofHttp` and the literal `""` fingerprints are deleted).
- Admission refusal is one typed result (`AlreadyRunning(jid)`), projected per
  surface: wire error line, HTTP 409, MCP `-32000` — codecs, not three semantics.

### Success law

`JobBody`/`HostedVerb.run` returns `JobOutcome(success, exitCode, cancelled?)`; the
envelope stamps it on the accumulator. `BuildAccumulator.effectiveSuccess` remains the
single evaluator; a verb can no longer forget `accOutcome` and silently fall back to
`!anyFailure`. The three evaluators (envelope locals, `toRecord`, per-verb exit lines)
become one stamp read three times.

### Events

One spine: **producers emit `EngineEvent`; every consumer is an `EventSink`.**

- `BridgingPlanListener`/`BridgingWorkspaceListener` emit `EngineEvent` only. The
  parallel `Hooks` graph is deleted.
- Sinks: `WireEventSink` (CLI socket), `SseEventSink` (feeds `HttpEvents`, which keeps
  its dashboard/MCP framing and the `requestId` filter), `CompositeEventSink` in
  production composing both, `RecordingEventSink` for tests.
- `EngineEvent` gains the variants that today exist only as SSE strings:
  `RequestStart`, `RequestFinish`, `Plan`. `PlanFinishLine(encodedWireLine)` becomes a
  typed `PlanFinish` — no sink receives a pre-encoded string of another sink's format.
- Chrome stays off the spine: `status`/`cache` samples (`LiveVitals`) and connect-time
  `run-snapshot` (`LiveRuns`) publish dashboard-only into `HttpEvents` directly. They
  are not build events and MCP must not see them.
- Coalescing happens **once**, in `CoalescingBuildPlanListener` above the composite
  sink; the second workspace-progress throttle in `SsePublisher` is deleted.
- S5/S6 stay client-side projections of the wire (`JsonlShape`), unchanged in role.

### Vocabulary (one, enforced by the spine)

Canonical field names are the frozen wire conventions: discriminator `type`, job
handle **`jid` only** (the `requestId` alias is deleted on output and input, and the
MCP SSE filter param becomes `?jid=`), `task`/`stage` (never `step`/`phase` on any
encoding), SSE `event:` name equals `data.type`. Schema integers stay 1.

### Process model (unchanged, restated)

The CLI ensures a version-matched engine (`EngineSpawn.ensure`: probe → skew ⇒ takeover
spawn → endpoint repoint → displaced engine drains). Browser and MCP cannot spawn;
therefore the engine never idles out while streams are attached
(`orphanedAndUnused()` counts SSE permits). Windows differs by **bind and auth only**:
loopback TCP + token line on the wire, same HTTP loopback + bearer as POSIX. Cancel
entry points all reach `JobEnvelope.cancelJob`; `POST /api/cancel` and `jk_cancel`
gain the dir-scoped form the wire already has.

### Tool version policy: newer always wins

The lockfile pins **inputs** (artifacts, checksums, BOMs) — never the operator. Pinning
jk itself is how teams freeze JDK 8 and Gradle 7; the product already rejects that for
JDKs (`java = 25` is a language floor, not a runtime pin), and the tool follows the same
rule. Downward delegation (`EngineDelegate`, `EngineMain --job` children) is deleted, not
repaired: it contradicted the envelope (no jid, no journal, no cancel), HTTP/MCP already
ignored it, and it taught the Gradle-wrapper pin before there are users to protect.

The lock's field is a **floor**: `jk-min = "x.y.z"`. Running jk ≥ floor → run here (a
0.15 machine never spawns 0.12). Running jk < floor → typed refusal on every surface
naming the required version and `jk self update`; nothing fetches a historical engine.
`generated-by` stays as provenance only. The floor does not auto-bump per `jk lock`; it
moves only when the lock format actually requires a newer reader (a named constant in
the writer). The old `jk = { version, sha256 }` artifact pin — engine-jar CAS hash
included — is deleted; a floor needs no artifact.

The wrapper follows: `./jk` / `jk.bat` are **bootstrappers** (newest installed jk that
satisfies the floor, else current stable verified against the release's `SHA256SUMS`),
never pin-on-first-use. Client-newer engine **takeover** stays — the thing on PATH is
the law.

Stated bargain: same lock ⇒ same resolved graph across jk versions until `jk update`;
tool behavior (action keys, lock extras, events) may change pre-1.0, and 1.0 is when
lock/manifest/CLI meaning stops breaking. `jk update` (re-resolve deps) and
`jk self update` (new tool) are two deliberate steps.

---

## E. Unique paths that survive

- **S1 JSONL wire (UDS / TCP+token).** Cannot be the common path's HTTP because it
  predates a healthy HTTP server (ensure/handshake/takeover) and owns jobs by
  connection (cancel-on-EOF). Shares: `HostedVerb`, `JobEnvelope`, `EngineEvent`,
  error codes.
- **HTTP REST snapshot reads** (`/api/status`, `/api/history`, `/api/cache`,
  `/api/project*`, `/api/config`, `/api/log`, `/api/fs`, `/api/metrics`,
  `/api/templates`). Browser-shaped request/response with etags, epochs, and byte
  caps; no wire equivalent needed. Shares: journal, `StatusSnapshot`, one redaction
  pass, one project-card/read-model implementation each.
- **HTTP SSE + MCP stream** — one hub (`HttpEvents`), two framings. Cannot ride the
  socket (browser). Shares: `EngineEvent` via `SseEventSink`.
- **MCP JSON-RPC facade** — protocol adapter (tools/resources/prompts) with session
  bind state. Never a second build model. Shares: `decodeJob`, domain read results,
  the stream hub.
- **CLI stdout JSONL + `details.jsonl`** — projections of the wire inside the CLI
  process; the engine does not know they exist. Shares: the event vocabulary
  (field-for-field).
- **S7 plugin worker protocol** — engine-spawned trusted forked JVMs; spec file +
  prefixed stdout; cancel by process kill. Merging it into the client protocol adds
  auth/handshake/lifetime machinery a child process cannot use. Shares: the `Jsonl`
  codec only, by design.
- **Wire-only verbs** (sync, audit, publish, import, provision, train, install,
  git-fetch, tool-resolve, script-prepare, single-build, forecast, exec-plan,
  ide-model, deny-check, generate, plugin-command, optimize, calibrate, tree, edit).
  They stay wire-only until a client needs them — exposure is now one `decodeJob`
  method, so this is a product decision per verb, not an architecture hole.

---

## F. Deletion list

Verb layer:
- `EngineHttpFront.runWorkspace/runLock/runUpdate/runCompile/runFormat/runImage/
  runNative/runClean/finishPlan/nativeEligibleTargets` (`EngineHttpFront.java:205-537`)
  — replaced by `decodeJob` → registry → envelope.
- Phantom request type literal `"cache-clear-request"` (`EngineHttpFront.java:196-200`).
- `EngineHttpJobs.trigger(HttpJobSpec)` default method (silently drops
  modules/tags/skipTests; aliases `update`→`lock`) (`EngineHttpJobs.java:39,66-74`).
- `VerbShape.Lifecycle` and the throwing dispatch arm (`VerbShape.java:8`,
  `EngineServer.java:845-846`).
- MCP reimplementations that a shared read result replaces: private encoders in
  `McpReads.why/explain/outdated`, `McpProjectCards.card` + the fourth copy
  `HttpEngineServer.projectMap:599-618`, `McpHistoryViews`' ungated/unredacted view,
  `McpMachine.diskAction`'s lockless prune, `McpMachine.jdkInstall`'s
  `FreshenCatalogVerb` bypass.
- Read-model duplicates: one project summary (today ×4), one metrics encoder + one
  `?dir=` filter semantics (today `sameBaseDir` vs exact-equals), one dependency-graph
  model behind `jk tree` and `/api/project/graph`.
- Layering inversion: `verbs/HistoryListVerb` and `verbs/MetricsVerb` importing
  `engine.http.JsonOut`.

Job layer:
- `JobEnvelope.submitAsync` (`:127-129`); `boolean detached` + nullable reader/writer
  threading in `submitSocket`; dead `JobTransport.writerOf` (`JobTransport.java:20-22`).
- `BuildJobFingerprint.ofHttp` and the `""` fingerprints (`EngineHttpFront.java:200,214,221,229`).
- The unconditional 30 s watchdog wakeup for writerless jobs with no deadline
  (`JobEnvelope.java:286-316` + `sendQuiet(null,…)` no-ops at `:686-688`).

Event layer:
- The `Hooks` parallel graph: `EngineListeners.planHooks`/hub plumbing
  (`EngineListeners.java:170-208`) and `SsePublisher`'s hook-driven publish surface —
  replaced by `SseEventSink` over `EngineEvent`.
- `EngineEvent.PlanFinishLine(String encodedWireLine)` (`EngineEvent.java:60`).
- The second workspace-progress throttle (`SsePublisher.java:297-311`).

Vocabulary (every in-tree producer and consumer in the same change; goldens updated):
- `requestId` alias: dual emission (`SsePublisher.java:345-346`,
  `JobEnvelope.java:437-438`, `HttpReadApi.java:290-291`), input fallbacks
  (`HttpReadApi.java:269-276`, `EngineServer.java:851-852`),
  `HttpEvents.extractRequestId` parsing only `requestId`, MCP `?requestId=` filter
  param (`HttpEngineServer.java:559`) → **`jid` everywhere**.
- SSE name/payload mismatches: `plan-progress` frames carrying `"type":"progress"`
  and `diagnostic` frames carrying `"type":"error"` (`SsePublisher.java:569-574`,
  `:521-526`).
- `schema` rider inconsistency on the wire: `progressLike` stamps `"schema":1`,
  `label`/`output` do not (`ProtoEvents.java:228,303,314`).

S7 hygiene (behind the hard boundary, its own ticket):
- Dead vocabulary: `OP_TEST`, `OP_RUN`, `PROGRESS`, `OUT` (add the real
  `command-out`), the unconsumed `DONE` handling decision, the two-arg
  `PluginClient(prefix, typeKey)` constructor.
- The second spec lane: `PluginBuild.SpecWriter`/`appendConfig`/`appendProject`
  (`PluginBuild.java:689-905`) merges with `shared/plugin-sdk` `SpecWriter`; the
  `"name"` vs `"task"` step-name split is resolved to one constant in
  `PluginProtocol`.
- Plain `BufferedReader` on the worker pipe → `BoundedLineReader` (already in
  plugin-sdk).

Tool-version pin (JK-2128/JK-2129):
- `EngineDelegate` (all of it: `maybeDelegate`, `runAsChild`, `DELEGATABLE`,
  `pinnedVersionDiffering`), the `serveConnection` delegation gate, `EngineMain --job`,
  `EngineServer.serveJob`/`jobMode`, and `EngineDelegateTest`.
- `Lockfile.JkToolchain` and the `jk = { version, sha256 }` lock line (engine-jar sha
  included) → `jk-min = "x.y.z"` floor; `LockfileWriter.runningEngineSha` dies.
- The wrapper scripts' lock-pin resolution and `jk wrapper <version|latest>`
  springboard machinery → bootstrap-current-stable with release-`SHA256SUMS`
  verification.
- `PinnedProjectRefused` → the floor refusal (`LockFloorRefused`: running < `jk-min`).

Docs (stale claims, rewritten post-implementation — see §H):
- code-as-art.md: "HTTP lock skips `admitJob`" (false since JK-1928 — it goes through
  `JobAdmit.admit` via `EngineHttpFront.java:217-222`) and "HTTP/MCP jobs get the same
  heartbeat and deadline as CLI" (deadline yes, heartbeat no).

---

## G. KanArtist tickets

Filed in `../kanartist` as **JK-2116 (epic) + JK-2117…JK-2127**, statuses `ready`,
unclaimed. The epic records the O2 verdict; children are one-concern changes:
verb decode unification (2117), transport seam (2118), event spine (2119),
vocabulary (2120), MCP read sharing + history gate/redaction (2121), the P0 cache-lock
bug (2122), success law + cancel semantics (2123), the delegation bypass (2124 —
superseded by the floor policy; see 2128/2129), kill-downward-delegation + lock floor
(2128), wrapper-as-bootstrapper (2129),
SyncRead hygiene (2125), S7 hygiene (2126), read-model dedup (2127). None duplicate
JK-1923 children — that epic is `done` and these are the residues it left.

---

## H. Product doc touch list (rewrite as current state, after implementation)

- `architecture.md` — wire-freeze table asserts `jid` (no alias); liveness table's
  heartbeat row states the transport-aware rule; Versioning states newer-always-wins,
  `jk-min` as the lock floor, and that no delegation to older engines exists.
- `http.md` — `POST /api/build` documents `kind`; cancel documents the dir form;
  publish map replaces `Hooks` publisher names with the sink; asserts SSE `event:` ==
  `data.type`.
- `machine-output.md` — the convergence table loses the `requestId` alias row and the
  `plan-progress`/`diagnostic` renaming caveats; the 8-step consistency checklist
  shrinks to "add the `EngineEvent` variant + per-surface codec".
- `code-as-art.md` — scoreboard rows updated to what shipped (envelope claims
  corrected: heartbeat, lock admit); "Architecture: Typed Envelope" diagram asserts
  HTTP/MCP decode to `HostedVerb`.
- `plugins.md` — unchanged in stance; worker-wire sentence names the single spec
  writer and the bounded reader.
- `webclient.md` — `api.js` notes the `jid` field name and any SSE type renames.

No page gains a "how it used to work" section.

---

## I. Open questions for a human

1. ~~Pinned-version jobs over HTTP/MCP~~ — **resolved**: downward pins are dead
   (Tool version policy, §D). The only remaining refusal is "this binary is too old for
   this lock" → typed upgrade error on every surface (JK-2128). No `--job` child, no
   SSE proxy, no fetching old engines.
2. **Wire-only verbs worth exposing.** `decodeJob` makes exposure a one-method product
   decision per verb. The MCP follow-up epic (JK-2013: `jk_new`, `jk_install`,
   `jk_publish`, `jk_import`/`export`, `jk_ide`) should ride the new seam instead of
   adding more `EngineHttpFront`-style bodies — sequencing note added to that epic,
   pending your confirmation.
3. **`jk_job wait` registry.** MCP waits on `LiveRuns`/`InFlightBuilds` while cancel
   uses `JobEnvelope.liveJobs`, and the runner releases the in-flight hold one
   statement before unregistering the live job — a small "finished but still
   cancellable" window. Fold the two registries (envelope owns liveness, `LiveRuns`
   reads it), or accept the window? Ticketed under JK-2123 as fold; veto if you rely
   on `InFlightBuilds` shape elsewhere.
