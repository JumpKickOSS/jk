# Task: Harsh design review + consolidation proposal for engine integration surfaces

**Audience:** agents (and maintainers) executing this brief. Not a user guide.
Not a description of how the product works today — that lives in
[architecture.md](architecture.md), [http.md](http.md),
[machine-output.md](machine-output.md), and [code-as-art.md](code-as-art.md).

**Mode:** analysis and design only. Do **not** implement, refactor, or edit product docs
unless this conversation later asks you to. Do **not** invent a second Typed Envelope.
Do **not** write an origin story.

**Stance:** Pre-1.0. No external users. Backward compatibility is an **anti-goal**.
Breaking changes that make the design simpler and more correct are **encouraged**.
Do **not** flatter the requester. If a stated assumption is wrong, say so first,
with evidence from the tree. Prefer the smallest design that is actually robust.

**Correctness > reuse theater.** One code path that is wrong for cancel, admit,
heartbeat, or job ownership is worse than two thin codecs over one domain core.

---

## 1. What you are deciding

JumpKick is a resident engine with several **clients** and one **plugin worker**
isolation model. The requester believes we now carry redundant protocols and
wants the simplest integration story:

> Should every client (CLI, Web UI, MCP) speak one HTTP REST + SSE API, with MCP
> as a facade? Or keep a non-HTTP transport, but force all transports through one
> domain framework so verbs, rules, DTOs, cancel, progress, and errors cannot
> diverge?

You must **pick one architecture**, defend it, and name what to delete.
"It depends" is only allowed as a rejected alternative, not as the conclusion.

Hard requirements (treat as facts unless you disprove them from the tree):

| Fact | Consequence |
|---|---|
| The engine is a **resident daemon**. It is not going away. Design for that. | CLI ensure/spawn, Windows takeover, and "browser cannot respawn the engine" are load-bearing. |
| **HTTP + REST** is required (Web UI). | There will be an `/api/*` surface. |
| **SSE** is required (live progress + MCP streamable HTTP). | Incremental work is a stream, not a single RPC result. |
| **MCP** is required. | JSON-RPC 2.0 + SSE over the same HTTP server. MCP is a **facade**, never a second build model. |
| **Windows** is required. | If a non-HTTP transport survives, it is TCP + token on Windows (UDS on Linux/macOS). |
| Engine default heap is **256 MiB**. Native CLI is Graal. | No per-event framework, no session intern pool, no Spring/Guice, no generated control plane. |
| Plugins are **trusted** but **isolated**. Code-layer plugins run in a **forked JVM**. | Their protocol is not automatically the client protocol. |

The requester's leaning: HTTP/REST/SSE as the single external API; the bespoke
JSONL wire looks like maintenance we should stop paying. **You may conclude that
leaning is wrong.** You may also conclude it is right and Typed Envelope did not
go far enough.

Secondary goals: few unique paths (each survivor needs a one-sentence
justification), one place for verbs/rules/DTOs, an easy mental model, current-state
docs only.

---

## 2. Do not start from a blank slate

This is **not** a greenfield protocol design. JK-1923 (Code as Art / Typed Envelope)
and its children are **done**. The tree already has:

```
CLI JSONL / HTTP POST / MCP tools
        └── VerbRequest ── JobEnvelope.submit ── HostedVerb.run
                    ├── EventSink → Wire (CLI)
                    ├── EventSink → SSE (dashboard + MCP)
                    └── JobSession + Journal
```

`JobTransport` is already the intended CLI vs HTTP difference:
`SocketWatch` (connection owns the job) vs `FireAndForget` (return `jid`;
progress is the sink).

**Your job is:** evaluate whether that design is the right end state, whether
transports should collapse further, and what **residual** dual paths still
produce divergent behavior. Re-proposing JobEnvelope / HostedVerb / EventSink
from scratch is a failed review.

If the right answer is "finish / tighten Typed Envelope, do not make HTTP the
only transport," say that plainly and specify the remaining deletions.

---

## 3. Required reading (in this order)

Read these **before** writing findings. Quote file paths and types; do not
reason from the requester's origin story.

**Planning (how we record work, not how the product works):**

- `../kanartist/AGENTS.md`
- `../kanartist/projects/jk/tickets/JK-1923-epic-code-as-art-typed-envelope-preempts-all-oth.md`
- Children especially: JK-1927, JK-1928, JK-1929, JK-1930, JK-1937
  (align `EngineProtocol` with `EngineEvent` — confirm actual filenames)

**Current-state product / maintainer docs:**

- [code-as-art.md](code-as-art.md) — Typed Envelope, scoreboard, anti-goals
- [architecture.md](architecture.md) — process model, wire freeze, schema freeze
- [http.md](http.md) — HTTP lifetime, auth, SSE
- [machine-output.md](machine-output.md) — CLI JSONL vs SSE vs MCP vs wire vocabulary
- [webclient.md](webclient.md) — SPA contract (`api.js` is the only HTTP talker)
- [plugins.md](plugins.md) — declarative layer vs forked code layer
- [AGENTS.md](../AGENTS.md) — schema freeze, comment policy, test tiers

**Code (inventory, do not "redesign from names"):**

- `server/engine/.../EngineServer.java`, `EngineMain.java`, `EngineHttpFront.java`
- `server/engine/.../jobs/` (`JobEnvelope`, `JobSession`, `JobTransport`, `JobKind`)
- `server/engine/.../verbs/` (`HostedVerb`, `VerbRegistry`, `VerbShape`, `VerbRequest`)
- `server/engine/.../listen/` (`EngineEvent`, `EventSink`, bridges)
- `server/engine/.../http/` (`HttpEngineServer`, `ApiRouter`, `EngineHttpJobs`, `McpHandler`)
- `shared/wire/.../EngineProtocol.java` + `Proto*`
- `clients/cli` engine client / ensure / spawn
- `shared/plugin-sdk/.../plugin/protocol/PluginProtocol.java` and engine
  `plugin/PluginClient.java` / `PluginProcess.java`

---

## 4. Surface taxonomy (mandatory)

Before any recommendation, classify **each** of these as its own surface.
Do **not** collapse them under the word "wire."

| ID | Surface | Who speaks it | Transport today | Role |
|---|---|---|---|---|
| S1 | Client↔engine control plane | Native/JVM CLI | JSONL on UDS (POSIX) / TCP+token (Windows) | Ensure, handshake, hosted verbs, connection-owned jobs |
| S2 | HTTP REST | Web UI, curl, future IDE | Loopback HTTP, bearer token | Snapshot reads + fire-and-forget jobs |
| S3 | HTTP SSE | Web UI | `GET /api/events` | Live dashboard stream (includes chrome frames MCP must not get) |
| S4 | MCP | Agent hosts | `POST/GET /mcp` JSON-RPC + SSE | Facade over the same verbs/events |
| S5 | CLI stdout JSONL | CI / agents watching `jk` | Process stdout (`--output json`/`jsonl`) | Machine output of the **CLI process**, not the engine socket |
| S6 | Session log | Humans / support | `details.jsonl` | Same *shape* as S5 |
| S7 | Engine↔plugin worker | Forked plugin JVM | Separate JSONL (`PluginProtocol`, discriminator `t`) | Trusted but isolated workers. **Not** S1. |
| S8 | In-process plugin / declarative | `jk-plugin.toml` + engine | No RPC | Engine applies contributions; does not classload plugin code |

You must answer, with evidence:

1. Which surfaces share **domain types** today (`HostedVerb`, `EngineEvent`, DTOs)?
2. Which still have **forked implementations** (second admit, second cancel,
   second progress, second success law)?
3. Which **look** similar (all JSON-ish streams) but have different ownership,
   auth, or lifetime?
4. Is S7 in scope for this consolidation, or is merging it with S1/S2 a
   category error? Argue it; do not assume the requester was right to lump
   "plugins use the wire" with "CLI uses the wire."

---

## 5. Assumptions you must attack

Treat these as **hypotheses**. Confirm or kill each with code, not vibes.

1. **"We have multiple redundant ways to do the same thing."**
   Redundant *codecs* are not the same as redundant *job lifecycles*.
   Typed Envelope already aimed at the latter. Is the remaining pain
   transport, vocabulary drift, incomplete HTTP verb coverage, or
   leftover copies?

2. **"HTTP should be the single API; the JSONL wire is bespoke cost."**
   Cost the **actual** remaining wire: `EngineProtocol` is now a
   discriminator table + `Proto*` builders, not a 3k-line god file.
   Cost Graal HTTP in the native CLI (deps, image size, startup,
   cancel-on-EOF). Cost HTTP-over-UDS / loopback HTTP as a middle path.
   Cost Windows (TCP JSONL vs HTTP on 127.0.0.1 — sockets exist either way).

3. **"REST is the API we must have, therefore REST is the API everything
   should use."**
   REST is a **resource** style. Hosted work is **verbs + a job id + a
   stream**. MCP is **JSON-RPC**, not REST. CLI ensure/hello/ping/shutdown
   is process lifecycle, not `/api/build`. Do not pretend these are the
   same shape.

4. **"SSE is required, therefore the control plane should be HTTP."**
   SSE is required for **browser and MCP**. The CLI already has a
   bidirectional stream. Unifying *event vocabulary* is not the same as
   unifying *transport*.

5. **"If we keep a non-HTTP protocol, it should overlap REST/SSE
   semantics."**
   Overlap the **domain** (verb, `jid`, `EngineEvent`, error codes).
   Do not force HTTP status codes onto a socket, or resource URLs onto
   JSONL, unless that encoding is genuinely cheaper than two thin
   projections of one type.

6. **"Plugins should ride the same protocol as clients."**
   Plugin workers are engine-spawned, trusted, stdin/spec-file oriented,
   and speak a different discriminator (`t` vs `type`) and op set
   (`describe`, `run-step`, `compile`, …). Unifying S7 with S1/S2 may
   add a client-server security and lifetime model they do not need.
   Prove the merge pays for itself or exclude S7 with a hard boundary.

7. **"Schema freeze until 1.0 means we cannot change shapes."**
   False. Policy is: stay on **version number 1**; do not churn
   `proto` / `schema` integers. **Do** rename/remove fields if the
   result is better and every in-tree consumer updates in the same
   change. There are no out-of-tree parsers to protect.

---

## 6. Investigation you must perform

Do not propose until you have a **coverage matrix** and at least one
**end-to-end trace**.

### 6.1 Verb / capability matrix

For a representative set — at minimum `build`/`test`/`lock`/`cancel`/
`status`/`explain`/`tree`/`why`, plus process `hello`/`ping`/`shutdown`,
plus one HTTP-only read (`/api/project/graph`, `/api/history`) — record:

| Capability | S1 wire | S2 REST | S3 SSE | S4 MCP | S5 stdout | Domain entry (`HostedVerb` / other) | Divergent behavior? |
|---|---|---|---|---|---|---|---|

Call out holes (wire-only verbs, HTTP-only reads, MCP aliases that
reimplement rather than decode).

### 6.2 Trace one mutating job and one cancel

Trace `jk build` and `POST /api/build` / MCP `jk_run` to
`JobEnvelope.submit`. Trace Ctrl-C, `jk cancel`, `POST /api/cancel`,
MCP `jk_cancel` to the **same** cancel function. If they do not meet,
that is a defect in the current design, not a reason to add a fifth path.

### 6.3 Ownership, auth, lifetime

Document, as current fact:

- Who **spawns** the engine? (CLI can; browser/MCP cannot.)
- Who **owns** a job? (`SocketWatch` vs `FireAndForget`.)
- Auth: wire `auth` line vs HTTP bearer vs SSE `access_token` query.
- Idle policy: engine never idles out because of the dashboard
  ([http.md](http.md)). Any "just use HTTP" design must keep that.
- Progress cadence / coalescing: one human sample rate, not N.

### 6.4 What would HTTP-only actually delete?

List concrete types/modules that go away if S1 dies, and what the native
CLI must gain (HTTP client, SSE parser, token bootstrap, reconnect,
ensure-over-HTTP). Estimate complexity honestly. If HTTP-over-UDS
(POSIX) + HTTP loopback (Windows) lets the CLI keep a local transport
**without** a second protocol, evaluate that as its own option.

### 6.5 Plugin boundary

One page max: why S7 exists, what it shares with S1 (JSONL? framing?
auth? none?), and whether any consolidation with S1/S2 is justified.

---

## 7. Options you must score (then pick one)

Score each against: correctness (cancel/admit/heartbeat/deadline),
divergence risk, native CLI cost, Windows, browser/MCP fit, engine heap,
deletion volume, understandability.

**O1 — HTTP-only control plane.** Delete S1. CLI is an HTTP client
(loopback and/or HTTP-over-UDS). REST + SSE are *the* API. MCP stays a
thin facade. S7 stays or also moves — you must say.

**O2 — One domain core, N thin transports (Typed Envelope completed).**
Keep S1 for CLI process control + connection-owned jobs. Keep S2/S3 for
web. Keep S4 as facade. Delete leftover copies until a new verb is
literally one class + one registry line + automatic exposure. Align
`EngineProtocol` tokens with `EngineEvent` if they still disagree.
S5/S6 remain projections of `EngineEvent`, not a fourth schema.

**O3 — Local HTTP as the CLI transport, keep JSONL only if something
cannot be HTTP.** Same domain core as O2, but S1 becomes HTTP (possibly
over UDS). JSONL remains only for S5/S6 (stdout/log) and maybe S7.

**O4 — Something better than O1–O3.** Allowed only if you can name it
in one paragraph and show it deletes more than it adds. Forbidden:
gRPC-as-default, Cap'n Proto, a new IDL, a DI container, EventBus,
`ServiceLoader` marketplace, OpenAPI-generated servers, MCP-as-primary.

Pick **one**. State the rejected options in three bullets each.

---

## 8. Design constraints (non-negotiable)

- No DI container. `EngineMain` is the composition root. Constructor
  injection of concrete `final` classes.
- Interfaces only for two production implementations, a process
  boundary, or a sealed algebra.
- Java 25: records, sealed types, exhaustive switches, virtual threads.
- Size budgets: see [code-as-art.md](code-as-art.md). Do not grow `EngineServer`,
  `HttpEngineServer`, or `McpHandler` as dumping grounds.
- One success/cancel/progress law. HTTP jobs must not skip `admitJob`,
  heartbeat, or wall deadline.
- Dashboard chrome events stay off the MCP stream.
- Smart engine, dumb clients: aggregate progress is computed in the
  engine only.
- Comments/Javadoc: current facts only. No ticket ids, no "formerly",
  no migration essays. Policy: [AGENTS.md — Comments and Javadoc](../AGENTS.md#comments-and-javadoc).
- Product docs (`docs/`) describe **how it works now**. Planning,
  phasing, and "what we will delete" live in KanArtist tickets only.
- Schema integers stay **1** until 1.0. Shapes may break; version
  numbers may not churn.

---

## 9. Deliverables (write these, in this order)

### A. Verdict (≤ 20 lines)

The chosen option (O1/O2/O3/O4). The one sentence that would be wrong
if the requester shipped HTTP-only tomorrow. The one sentence that
would be wrong if we keep S1 forever without further deletion.

### B. Assumption scorecard

Table: hypothesis → **confirmed / killed / partial** → evidence path.

### C. Surface map (as-is)

The taxonomy from §4, filled in. Residual forks called out as defects.

### D. Target architecture (present tense)

How a hosted verb is added. How a job is admitted, heartbeaten,
cancelled, journaled. How an event reaches CLI / SSE / MCP / stdout.
How the CLI ensures an engine. How Windows differs (bind/auth only, if
that is the claim).

Use types that exist or that you propose to add. No sequence-diagram
novel.

### E. Unique paths that survive

Bullet list. Each bullet: path, why it cannot be the common path, what
still shares the domain types.

### F. Deletion list

Concrete: classes, maps, duplicate decoders, parallel event names
(`plan-progress` vs `progress`, `requestId` vs `jid`, …).

### G. KanArtist tickets (planning only)

In `../kanartist`, draft ticket **bodies** for the work (do not claim,
do not implement). Follow `../kanartist/AGENTS.md` and the existing
ticket template. Prefer a small epic + children over one mega-ticket.

Each ticket: problem, acceptance checklist, non-goals, links as
`jk://…` (never machine-local paths). No historical essays.

If the verdict is "Typed Envelope is the design; residual polish only,"
say whether new tickets are justified or existing leftover tickets
suffice. Do not open work that duplicates JK-1923 children.

### H. Product doc touch list (do not write the docs yet)

Which `docs/*` pages would need to be **rewritten as current state**
after implementation (`architecture.md`, `http.md`, `machine-output.md`,
`plugins.md`, …). One line each: what the page must then assert.
Explicitly: no "how it used to work" sections.

### I. Open questions for a human

Only questions that change the architecture (e.g. "is HTTP-over-UDS
acceptable for the native CLI?"). Provide options. Do not hide a
decision here that you were asked to make in §7.

---

## 10. What "done" means for this turn

- You read the required files and the code, not just the prompt.
- You classified S1–S8 instead of saying "the wire."
- You attacked the requester's HTTP-only lean and either adopted it
  with eyes open or rejected it with evidence.
- You chose one option and listed deletions.
- You did not implement.
- You did not add product-doc history.
- A stranger could implement from §9 D–G without re-litigating O1 vs O2.

If at any point you discover the tree already matches the right design
and the remaining issue is documentation drift or incomplete verb
exposure, **stop expanding scope**. Report that. Residual tickets only.
