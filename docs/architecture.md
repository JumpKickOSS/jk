# Architecture

How jk is structured today. For day-to-day usage see [guide.md](guide.md).

## Design tenets

1. **Declarative core** — `jk.toml` is data; logic lives in plugins and task scripts, not the manifest.
2. **Fast client, hosted build** — native CLI for UX; resident engine for resolution, pipelines, and memory coordination.
3. **Reproducibility by default** — lockfile, content-addressed cache, deterministic packaging.
4. **Diagnostics as product** — PubGrub prose, `jk why` / `jk explain`, machine-readable output.
5. **Adoption first** — `jk mvn` / `jk gradle`, import/export, Maven Central semantics.
6. **Resist plugin sprawl** — first-party batteries; no public marketplace before a frozen SPI.

## Process model

```
┌─────────────────────┐         JSONL (UDS / TCP)        ┌──────────────────────────┐
│  jk client          │ ──────────────────────────────── │  jk engine               │
│  (GraalVM native)   │                                  │  (HotSpot JVM, capped)   │
│  TUI, shell, jdk    │                                  │  resolve · build · cache │
│  terminal execs     │                                  │  fork plugin workers     │
└─────────────────────┘                                  └──────────────────────────┘
```

- **Client** — presentation, shell hooks, JDK install prompts, anything that owns your terminal
  (`jk run` exec, `jk mvn`/`gradle` interactive). Sub-50 ms cold start; no engine code in the
  native image.
- **Engine** — dependency resolution, action graph, CAS, toolchains, compiler/test workers,
  hosted verbs (`build`, `test`, `lock`, `publish`, …). Default heap ceiling **256 MiB**
  (`~/.jk/config.toml` → `[engine] max-heap-mb`, or `JK_ENGINE_MAX_HEAP_MB`).
- **Load-bearing** — if the engine cannot start, the command fails clearly (no silent
  in-process fallback for hosted work). That is how concurrent builds avoid RAM overcommit.
- **Lifecycle** — lazy start on first need; stays resident until `jk engine stop` or
  version-skew replacement. One engine per `JK_HOME` / state directory.
- **Versioning** — side-by-side installs under `~/.jk/versions/<v>/`; client and engine jar
  share a version; handshake detects skew and takes over.

```bash
jk engine start | status | stop
```

Transport: Unix domain socket on macOS/Linux; loopback TCP + shared-secret token on Windows.
Protocol is internal (same-version client/server), newline-delimited JSON.

### Wire freeze vocabulary (pre-1.0)

Same-version client/engine only — not a multi-version public API. Conventions frozen for 1.0:

| Rule | Shape |
|---|---|
| Envelope | One JSON object per line; discriminator field `"t"` |
| Auth (TCP only) | First line `{"t":"auth","token":…}` — never a raw token line |
| Handshake | `hello` / `hello-ack` carry `version`, `proto` (`EngineProtocol.PROTOCOL`), `purpose` (`connect`\|`probe`); `hello-ack` uses `startedAt` (millis) |
| Errors | `{"t":"error","code",…,"message",…}` (`auth`, `protocol`, `version-skew`, …) |
| Project path | Field name is always `dir` |
| Pipeline finish | `{"t":"pipeline-finish","kind":…,"dir":…,"success":…}` (+ kind-specific tails) |
| Session extras | Variant / client env / JVM tuning via typed `withSession` builders — no JSON string surgery |
| Line limits | Bounded line reader + idle timeout; unknown/`t`-less lines → `error`, not silent drop |

Builders and round-trip tests live in `shared/wire` / `EngineProtocolTest`.

## Repository layout

Bootstrap build: **Java 25 + Gradle** (until self-hosting CI is complete). Runtime modules:

| Area | Modules | Role |
|---|---|---|
| `shared/` | `jk-api`, `plugin-sdk`, `core`, `client-io`, `toolchain-jdk`, `wire` | Client-safe contracts, config/lock, CLI I/O, JDK tools, wire codec |
| `server/` | `io`, `resolver`, `toolchain`, `engine` | Repo fetch, PubGrub, import/export tools, build pipeline |
| `clients/` | `cli`, `cli-engine`, `web` | Native client, engine fat jar + JVM dist, dashboard SPA |
| `plugins/` | `java-compiler`, `kotlin-compiler`, `test-runner`, `auditor`, `publisher`, `image-builder`, `formatter`, `compat-bridge`, `spring-boot`, `android`, `protobuf`, `shrink` | First-party workers / build plugins |

**Layering:** `jk-api` → `core` → `{client-io, wire, …}` → server `{io, resolver, toolchain}` → `engine` → clients. Plugins depend on `plugin-sdk`, not on engine internals.

Ship layout (`./gradlew dist`): slim native `jk` + `lib/jk-engine-<version>.jar`.

## Dependency resolution

- **Algorithm:** PubGrub (same family as Dart `pub` / `uv`).
- **Conflict policy:** highest-version-wins across a graph; Maven nearest-wins is rejected.
- **Lockfile:** one root `jk.lock`; builds never re-resolve.
- **BOMs:** soft prefer (pin first, full candidate list); stricter floors may lift.
- **Scopes:** **main**, **test**, and **processor** graphs are solved separately so processor
  constraints do not force main versions. Dual lock rows are allowed when versions diverge;
  classpaths select by scope.
- **POM fidelity:** exclusions and Maven version ranges are honored on expand; optional deps
  stay out until features activate them.
- **Budgets:** `JK_RESOLVE_MAX_DECISIONS` (default 100 000), `JK_RESOLVE_TIMEOUT_MS` (default 120 s).

Package identity in the solver is `group:artifact:type:classifier` (defaults: type `jar`,
classifier empty → `g:a:jar:`). Legacy lock rows with bare `g:a` still load. BOM management
and exclusions stay GA-scoped.

## Build execution

1. Expand a verb (`build`, `test`, …) into a DAG of steps with typed inputs/outputs.
2. Hash inputs (sources, classpath ABI inputs, flags, toolchain, plugin code, …).
3. **Action cache** hit → restore outputs from the **CAS**; miss → run and store.
4. Compilers and tests run in **forked plugin processes** sized by a shared memory plan.

Local cache roots under `~/.jk/cache/` (content-addressed blobs + action mappings). CAS writes
are copy/atomic; build trees must not share inodes with immutable blobs.

## Plugins

Build plugins own a `jk.toml` table (`[spring-boot]`, `[android]`, …) via a jar containing
`jk-plugin.toml` plus optional code that runs **out of process**. The engine never classloads
plugin code. See [plugins.md](plugins.md).

There is no third-party marketplace yet; first-party plugins ship with jk and version together.

## Extension surface

- **Today:** CLI + engine HTTP dashboard; IDE project file generation (`jk ide` / export).
- **Intended:** additional front-ends over the engine API (IDE plugins, agents) after the wire
  protocol is frozen for 1.0.

## Status

Pre-1.0 alpha. Self-hosting is partially real (workspace `jk.toml` exists; bootstrap/CI still
uses Gradle for the shippable dist). Breaking changes remain acceptable until 1.0.
