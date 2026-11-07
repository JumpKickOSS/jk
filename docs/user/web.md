# Web dashboard

```bash
jk web                 # ensure engine, print URL, open browser
jk web -n       # print only
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

## Sessions and episodes

The engine keeps who asked as records of their own, beside the runs and under the same
`[history]` retention: a **session** is one origin talking to the engine — an MCP connection, a
BSP client, the dashboard, or the shell — and an **episode** is one goal inside it. Every run
record carries the `episode` it belongs to (`mcp-3f9a-mfx2k1.2`: the session's id, then the
episode's number).

A session is **live** while it acts, **idle** after ten minutes of quiet, and **ended** when its
MCP connection closes (`DELETE /mcp`) or once it has had no activity for the session expiry
(`[mcp] session-expiry`, default 24 hours). That one rule covers every session: an agent's whose
connection is still open, the shell's, the dashboard's, and one a previous engine left open. The
next run after it opens another session, and an agent whose session expired is asked to start a new
one. Besides its runs, a session's timeline
holds the MCP calls that are not runs, each as one line with its outcome and time: the code
graph's `query`, `callers`, `read` and `find`, `rename`, and the dependency changes `deps`,
`update` and `workspace`.

A session survives the engine. When an upgrade replaces the engine, or the engine dies and the
next command starts another, the new engine carries on every session the old one left open: an
agent's next call keeps its session, its label and its current episode, and a run that was still
finishing on the old engine lands in that episode. A session nobody returns to ends at the
session expiry, like any other.

An episode starts with the session's first action, and again when the session moves to another
project, when it acts after ten minutes of quiet, and after a red run streak ends green (the
green run closes the episode it fixed). Its title is inferred from what it did, in this order:

| What happened | Title |
|---|---|
| A run failed tests | `Fixing QuoteTest#threeNightWeekendStay (+2 more)`: the first red run's failing tests |
| A `deps` add, remove or pin | `Adding org.postgresql:postgresql` |
| An `update` | `Updating 14 pins` |
| A `rename` | `Renaming PricingRules.weekendUplift` |
| Only code-graph questions | `Exploring the code` |
| Anything else | `Working in <module>` |

An open episode is **working**. A closed one is **green** or **red** by its last run (or, with no
run, its last action), and **abandoned** when nothing in it was judged. `GET /api/sessions` lists
live and recent sessions with their current episode; `GET /api/sessions/<id>` returns one with its
episodes and a page of its timeline ([HTTP](../contributors/http.md#get-apisessions)).

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

The strip and the `## Since the previous run` section of `target/jk-results.md` are one
computation: the engine compares the two journal records at
the end of the run. Files come from a content-hash snapshot of the build's inputs each run leaves
in its journal entry (`sources.tsv`: the manifests, the lock, the guard rules, the `.jk/scripts/` scripts
and every module's source, test and resource roots — nothing else in the checkout — with a tree
past 20,000 files leaving the file comparison out); tests from every test's
outcome (`test-outcomes.tsv`). A run with no earlier run from its origin, or one whose previous
run recorded no tests, shows the comparisons it has.

## Coverage

A coverage run (`jk test -T`, or a module with `[test] coverage = true`) adds a
**coverage** strip under the run's header: covered lines and branches as percentages, the module
count for a workspace, and `Δ vs run #N` when an earlier run of the project measured coverage.
Clicking the strip opens the table the results file prints under `## Coverage` — one row per
module, `85.2% (1204/1413)`, signed points against the baseline (`+1.3`, `−0.5`, `±0.0`, `new`
for a module the baseline did not have), an **all** row for a workspace. **HTML report** opens the
JaCoCo report of that run in a new tab: the one module's `index.html`, or the workspace roll-up
that links each module's report. The engine serves the report from the checkout the run measured,
under a per-run route that carries the dashboard token in its path (so the report's own pages and
stylesheets resolve) and in a sandboxed origin, so a report cannot script the dashboard. The
figures are the record's `coverage[]` rows — the same facts as `target/jk-results.md` and MCP
`run`; the baseline is the nearest earlier record of the project whose coverage is not
empty, as the results file chooses it.

The link ends in `#t=<token>`; a project link from MCP carries it as `#project/<id>?t=<token>`.
The page stores the token in `sessionStorage` / `localStorage` and scrubs it from the address
bar, keeping the route. Every `/api/*` call sends
`Authorization: Bearer <token>` — including loopback. Static shell assets stay open so
an unauthenticated tab can render **Access Denied** (no paste field: recover with
`jk web`).

Project routes use a durable **project id**, not a filesystem path:

```text
#project/<projectId>                 # follows the newest run
#project/<projectId>?dir=<checkout>  # one worktree of a repository several build under
#project/<projectId>/run/<n>         # pinned to build #n
#project/<projectId>/files
```

The id is the `project-id` your committed `jk-lock.toml` carries, so every git worktree of the
repository shares it — and its build numbers. While one worktree is alive the page just shows
it. Once two have built, the project page lists the **checkouts** and asks you to pick one to
follow and edit (**all** shows every worktree's runs with a Checkout column); the files pane and
its Save work in the picked tree only, and a link from `jk` or an MCP result already names the
tree the build ran in. A deleted worktree drops out of the list on the next build of a sibling.

## New project

**New project** (on the Projects page) opens the wizard at `#new`. It asks the same form `jk new`
asks on a terminal and MCP `new` asks through elicitation, served by the engine: jk's step
(language, template, name, group, directory, and the layout when the template renders more than
one), then the chosen template's own questions ([Templates](templates.md)), with its advanced
ones folded, then a review. Picking another template or language brings that template's
questions; a question shows only while its condition holds.

Beside the form, a preview redraws as you answer: the file tree the project would get and its
`jk.toml`, scaffolded into a scratch directory, so it is exactly what Create writes. The review
lists the answers and the equivalent command, with **Copy**:

```text
jk new shop -t java/spring-boot/webmvc-security-actuator-jpa-h2 --group org.example --dir /home/you/src -L simple
```

The command spells every answer that differs from its default, and always the group and the
directory, which a terminal derives from where it runs. **Create** scaffolds the project, opens its
page and starts its first build. If the template changed while you were answering, the wizard
takes its new questions and asks you to check them.

A link opens the wizard pre-filled: each answer is a query pair of the route, a multi-select
joined with commas.

```text
#new?template=cli&name=tool&dir=/home/you/src&layout=traditional
```

MCP `new` hands out such a link when its client supports only URL elicitation
([MCP](mcp.md#new)); the wizard then says an agent is waiting, and the agent's call returns the
project you create. A create after the call stopped waiting (five minutes) is still wired for that
agent, and the new project's page says the agent was not told.

Live events: `GET /api/events` (SSE). Cancel: `POST /api/cancel`. Implementation of the
SPA and HTTP contract: [contributor web client](../contributors/webclient.md),
[contributor HTTP](../contributors/http.md).

MCP shares this server: [MCP](mcp.md).
