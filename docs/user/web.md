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
with its runs as a timeline in the order they happened — outcome, `#build`, kind, one chip of
what that run changed and its wall — and the block's tallies (ok / failed / cancelled / running,
total wall). The chip is the strongest signal of the run's [iteration strip](#since-the-previous-run):
a test that broke, else a diagnostic that appeared, else a test fixed, else a diagnostic gone, else
the files changed, else the wall against the previous attempt; hovering it shows the whole strip.
Two agents on one project are two blocks; your own `jk build` is a third.

## Following a run

The project page shows one run above its build history: the **newest** by default (a live
one first), so an MCP result's `dashboard` link lands on the job it just started and keeps
following as the agent iterates. Click a history row to **pin** that run; the route carries
the pin (`#project/<id>/run/<n>`) so a reload keeps it, and **Follow newest** returns to
following.

## Since the previous run

Under the run's header sits the **iteration strip**: what changed since the run before it from
the same origin (the same MCP session or IDE window; a shell run compares against the shell run
before it). Counts first — `since #12 · failed · 8.4s · 3 files · +0 / −2 diagnostics · tests: 1
fixed · −2.3s` — and hovering a chip lists the rows behind it: the files whose content changed
(`(new)` / `(gone)` for added or deleted ones), the diagnostics that appeared or went away, the
tests that flipped (fixed, broke, new, gone). Clicking the strip opens the same rows inline. Each
list is bounded (`+N more` past the first eight); the counts are exact. A three-attempt fix
session reads as three runs, each strip naming what that attempt changed and whether it helped.

The strip, the `## Since the previous run` section of `target/jk-results.md` and the `delta`
field of MCP `jk_results` are one computation: the engine compares the two journal records at
the end of the run. Files come from a content-hash snapshot of the build's inputs each run leaves
in its journal entry (`sources.tsv`: the manifests, the lock, the guard rules, the `.jk/` scripts
and every module's source, test and resource roots — nothing else in the checkout — with a tree
past 20,000 files leaving the file comparison out); tests from every test's
outcome (`test-outcomes.tsv`). A run with no earlier run from its origin, or one whose previous
run recorded no tests, shows the comparisons it has.

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
