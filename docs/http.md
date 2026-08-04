# The engine's embedded HTTP server

The engine serves a dashboard (the SPA in `clients/web`) and an MCP surface over an embedded HTTP
server. This file is cited from `api.js`, `EngineStatusCommand`, `EngineRotateTokenCommand` and the
engine tests; it had never been written, which is how the guarantee below came to be relied on by
client code without being recorded anywhere.

## On by default

`[http]` is **enabled by default** — loopback bind, token-gated mutations. It is not opt-in. Turn it
off with `[http] enabled = false` in `~/.config/jk/config.toml` or `JK_HTTP_ENABLED=false`; a malformed
config yields empty and fails closed (no server).

`[mcp] enabled = false` disables only the MCP surface, never the server. Machine-scoped, not
project-overridable, read once at engine start.

That default matters for reasoning about engine lifetime: since most engines have HTTP on, any rule
of the form "engines with HTTP behave differently" applies to nearly all of them.

## The engine never idles out

**A resident engine does not self-terminate for being idle.** There is no idle timeout, and adding
one would be a breaking change to the dashboard's contract, not a tuning knob.

The SPA is written against this. `api.js`:

> `EventSource` reconnects on its own; an HTTP-enabled engine never idles out, so `'offline'` only
> ever means an explicit stop, an upgrade respawn, or a crash.

So the client treats a lost stream as an anomaly. If engines idled out, `offline` would become
routine while the UI still reported it as exceptional.

The reason is an asymmetry between clients, and it is the whole argument:

- The **CLI** ensures a live, version-matched engine before every request, spawning one if none is
  listening. An engine that exited costs a cold start, not an error.
- A **browser tab** cannot spawn anything. If the engine it was talking to is gone, the SPA is dead
  until the user knows to drop to a terminal and run a jk command — which is an astonishing thing to
  ask of someone whose primary interface is the dashboard.

Concretely: a developer working through the Web UI who leaves the tab open overnight must find it
working the next morning.

## When an engine *does* exit

Three states, decided by whether the `<key>.endpoint` pointer names this engine:

| Pointer | State | Behaviour |
|---|---|---|
| names this engine | **primary** | Never self-terminates. Exits on `jk engine stop` or version-skew replacement. |
| names another | **displaced** | Surrenders the HTTP port **immediately**, then drains in-flight jobs and exits. |
| absent | **orphaned** | Exits once genuinely unused: no in-flight jobs **and** no attached SSE stream. |

### Displacement surrenders the port unconditionally

A newer engine taking over needs the port. A displaced engine calls `stopNow()` on its HTTP server
right away — attached dashboard streams get **no vote**. This is correct because there *is* a
successor: the tab reconnects to it, and the HTTP token is deliberately preserved across the respawn
so it does not have to re-authenticate. Client-side reconnect is the SPA's job.

### An orphan waits for an attached tab

An orphaned engine is one no pointer names: no CLI will reach it again, and no successor wants its
port either. Before this was handled, such an engine served forever — the displacement check required
the pointer to *exist*, so a deleted one left an unreachable engine running indefinitely (JK-1293).

It now exits when unused, but an attached SSE stream vetoes that. Unlike displacement there is no
successor to hand the tab to, so exiting under an open dashboard would strand it with nothing to
reconnect to.

The count comes from the SSE admission budgets rather than a separate counter, so it cannot drift from
what actually holds a slot: a stream keeps its permit for the life of the connection.

## Stopping engines

The engine identity is a hash of the state directory **and** the artifact store, so a machine can
hold several at once — one per `(state dir, store)` pair. `jk engine status` lists every running
engine with its id and pid; `jk engine stop` addresses the one this directory resolves to,
`--all` stops all of them, and `--pid <pid>` stops one by pid.

Every form of stop escalates to a hard kill if the engine does not exit, and reports what actually
happened. Reliably stopping an engine must never require the user to reach for `kill` or hunt through
Task Manager.

## Auth tiers

Loopback binds serve the dashboard without a token; mutations are token-gated. Non-loopback origins
carry the token — `EventSource` cannot send headers, so streams pass it as an `access_token` query
parameter, and the SPA bootstraps from a `#t=` fragment.

**Sensitive reads need the token even on loopback**, because on a shared machine another local
account must not have the engine owner's filesystem and build history for free:

| Endpoint | Why |
|---|---|
| `GET /api/fs` | lists the filesystem with the owner's permissions |
| `GET /api/log` | build diagnostics |
| `GET /api/history`, `GET /api/history/artifact` | diagnostics with source excerpts and absolute paths |
| `GET /api/project` | path-existence oracle |
| `GET /api/metrics` | every project dir and coordinate ever built |
| `GET /api/projects/defaults` | derives from the owner's git identity and home layout |

Aggregate-only reads (`GET /api/status`, `GET /api/cache`) and the activity stream
(`GET /api/events`) stay open on loopback, so a tokenless dashboard still shows live builds.

The token file persists across restarts precisely so an open tab survives an upgrade or crash
respawn. `jk engine rotate-token` is the explicit way to invalidate it.
