# Web dashboard

```bash
jk web                 # ensure engine, print URL, open browser
jk web --no-open       # print only
```

The resident engine serves a small dashboard (thin renderer; the engine owns all build
logic). `jk engine status` also prints the authenticated URL.

The dashboard is the **supervisor's view**. When an agent drives the builds, the human
watching has no terminal of their own to read; this page shows what ran, why it failed and
how long it took, from the same facts as `target/jk-results.md` and MCP. Trigger and session
per run, and a per-attempt view of what changed between runs, are the dashboard's items in
[the 1.0 plan](../contributors/plan-1.0.md).

The link ends in `#t=<token>`. The page stores the token in `sessionStorage` /
`localStorage` and scrubs the fragment. Every `/api/*` call sends
`Authorization: Bearer <token>` — including loopback. Static shell assets stay open so
an unauthenticated tab can render **Access Denied** (no paste field: recover with
`jk web`).

Project routes use a durable **project id**, not a filesystem path:

```text
#project/<projectId>
#project/<projectId>/files
```

New project: a modal that calls the same scaffolder as `jk new` (templates, layout as
file placement). See [Templates](templates.md).

Live events: `GET /api/events` (SSE). Cancel: `POST /api/cancel`. Implementation of the
SPA and HTTP contract: [contributor web client](../contributors/webclient.md),
[contributor HTTP](../contributors/http.md).

MCP shares this server: [MCP](mcp.md).
