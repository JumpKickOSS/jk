# Web dashboard

```bash
jk web                 # ensure engine, print URL, open browser
jk web --no-open       # print only
```

The resident engine serves a small dashboard (thin renderer; the engine owns all build
logic). `jk engine status` also prints the authenticated URL.

The dashboard is the **supervisor's view**. When an agent drives the builds, the human
watching has no terminal of their own to read; this page shows what ran, who asked, why it
failed and how long it took, from the same facts as `target/jk-results.md` and MCP.

## Who asked

Every run carries its **trigger** — `cli`, `mcp`, `web` or `bsp` — and, when the surface has
one, the **session** that asked: an MCP connection is `<client> <id>` (`claude-code 3f9a`,
from the client's `initialize` and the id the engine minted for that connection), a BSP client
is the IDE's `displayName` plus a per-process id (`IntelliJ-BSP 7b2c`). A shell run is `cli`
with no session. The Activity card shows it as `MCP · claude-code 3f9a`; `jk-results.md`
prints the same as `trigger: mcp · session: claude-code 3f9a`, and `jk history` has a
Trigger column.

**By session** on the Activity feed regroups the feed: one block per session, newest first,
with its runs as a timeline in the order they happened — outcome, `#build`, kind and wall
per run — and the block's tallies (ok / failed / cancelled / running, total wall). Two agents
on one project are two blocks; your own `jk build` is a third.

## Following a run

The project page shows one run above its build history: the **newest** by default (a live
one first), so an MCP result's `dashboard` link lands on the job it just started and keeps
following as the agent iterates. Click a history row to **pin** that run; the route carries
the pin (`#project/<id>/run/<n>`) so a reload keeps it, and **Follow newest** returns to
following. A per-attempt view of what changed between runs is the remaining dashboard item in
[the 1.0 plan](../contributors/plan-1.0.md).

The link ends in `#t=<token>`; a project link from MCP carries it as `#project/<id>?t=<token>`.
The page stores the token in `sessionStorage` / `localStorage` and scrubs it from the address
bar, keeping the route. Every `/api/*` call sends
`Authorization: Bearer <token>` — including loopback. Static shell assets stay open so
an unauthenticated tab can render **Access Denied** (no paste field: recover with
`jk web`).

Project routes use a durable **project id**, not a filesystem path:

```text
#project/<projectId>              # follows the newest run
#project/<projectId>/run/<n>      # pinned to build #n
#project/<projectId>/files
```

New project: a modal that calls the same scaffolder as `jk new` (templates, layout as
file placement). See [Templates](templates.md).

Live events: `GET /api/events` (SSE). Cancel: `POST /api/cancel`. Implementation of the
SPA and HTTP contract: [contributor web client](../contributors/webclient.md),
[contributor HTTP](../contributors/http.md).

MCP shares this server: [MCP](mcp.md).
