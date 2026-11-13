# Web dashboard

```bash
jk web                 # ensure engine, print URL, open browser
jk web -n       # print only
```

The resident engine serves a small dashboard (thin renderer; the engine owns all build
logic). `jk engine status` also prints the authenticated URL.

The dashboard is the **supervisor's view**. When an agent drives JumpKick, the human watching has no
terminal of their own to read. The landing page answers three questions for every live session:
what is each agent trying to do, what did it change, and does it need you. Every number comes from
the same facts as `target/jk-results.md`, the journal and MCP, and colour means state: blue
working, green done, red failed, amber needs you, cyan identity.

## Which engine you are looking at

The header names the engine under the JumpKick logo: `Windows on BocaMiniPC`, `WSL Ubuntu-26.10 on
BocaMiniPC`. Windows with a WSL distro is two engines, each with its own builds, and both can serve a
dashboard at once: the Windows engine on port 8910, the WSL one on a port of its own from 8911 up,
which stays the same across restarts. Open the URL `jk web` prints on the machine you mean, and use
that URL in bookmarks and MCP configs. A tab that finds another engine on its address than the one it
was showing stops and names both, rather than showing the other engine's builds. `[http] port` in
`~/.jk/config.toml` names a port yourself.

## The console

`#console` is the landing page. From top to bottom and left to right:

- **Needs you**: the decisions an agent is blocked on, oldest first, each with who asked, how
  long ago, and **Review**, which opens the decision's dialog (`#decision/<id>`). Absent when nothing waits, and on an engine without a decision queue.
- **Sessions**: one card per live or idle session, and those that ended within the last hour. A
  card shows the host (`claude-code 3f9a`, `IntelliJ-BSP 7b2c`, `terminal 4c1e9a`), the current episode's
  title and where it came from (*goal from the agent*, *last goal*, *inferred*, *your own
  commands* for a terminal, or *shell commands* for an agent's shell), the project, and the state:
  **waiting on you**, **working** (a run in flight or its episode open), **idle** or **ended**.
  Each terminal is a session like any other, so your `jk test` sits beside the agent's, and two
  terminals are two cards. Click a card to select it; the route carries the choice
  (`#console/<session-id>`). With none chosen the console holds the first card.
- **The selected session's current episode**: its outcome, title and title source; episode *n* of
  *m*, the project, its wall (to now while it works), the time JumpKick spent on its actions and runs,
  and the files its runs saw change; an **attempts arc**, one segment per run, red, green or blue;
  and its **timeline**, one line per action in the order it happened: code-graph questions with
  the first line of their answer, renames, dependency changes, runs, and `edited N files` just
  before the run that saw the edits (JumpKick sees edits at the next run, through its file delta). A run
  line shows its kind, its number and outcome chip (`#1066 · 2 failed`), the one-chip summary of
  what changed since the run before, and the first failing test or error. Clicking the chip opens
  the run page. **Earlier episodes** of the session fold below it; open one for its timeline.
- **This session changed**: the files its runs changed, migrations (`V7 new`), dependency
  changes, the newest guard run's outcome, and the tests its runs fixed and broke.
- **How JumpKick served the agent**: tool calls (every action and run), runs, JumpKick's wall time across
  them, and the share of tasks the cache or an up-to-date check skipped.
- **Engine**: host CPU and RAM, the action cache's size and the builds running, with a link to
  Admin.

### A live build is one line

A run still in flight is one line in its session's timeline: what it is doing (`testing service ·
QuoteServiceIT`), one progress bar with its ETA, and one strip of module cells, one small cell per
module coloured by state (blue running, green done, red failed, grey not started). A 55-module
workspace is one row of cells. Hovering a cell names the module and its phase; clicking anywhere on
the line opens the run page, and the cancel button stops the run.

## Who asked

Every run carries its **trigger** — `cli`, `mcp`, `web` or `bsp` — and, when the surface has
one, the **session** that asked: an MCP connection is `<client> <id>` (`claude-code 3f9a`,
from the client's `initialize` and the id the engine minted for that connection), a BSP client
is the IDE's `displayName` plus a per-process id (`IntelliJ-BSP 7b2c`). A command-line run is
`cli`, and its session names who ran it ([below](#command-line-sessions)): `terminal 4c1e9a` for a
terminal tab or SSH connection, `script` with no terminal, `claude-code (shell) 9b20e1` for the
commands an agent runs through its shell tool, or whatever `JK_BUILD_SESSION` says. The run page and the build history show it as `MCP · claude-code 3f9a`; `jk-results.md`
prints the same as `trigger: mcp · session: claude-code 3f9a`, and `jk history` has a
Trigger column.

The one chip beside a run in a timeline is the strongest signal of its
[iteration strip](#since-the-previous-run): a test that broke, else a diagnostic that appeared,
else a test fixed, else a diagnostic gone, else the files changed, else the wall against the
previous attempt; hovering it shows the whole strip.

## Sessions and episodes

The engine keeps who asked as records of their own, beside the runs and under the same
`[history]` retention: a **session** is one origin talking to the engine — an MCP connection, a
BSP client, the dashboard, or one terminal, script or agent shell on the command line — and an **episode** is one goal inside it. Every run
record carries the `episode` it belongs to (`mcp-3f9a-mfx2k1.2`: the session's id, then the
episode's number).

A session is **live** while it acts, **idle** after ten minutes of quiet, and **ended** when its
MCP connection closes (`DELETE /mcp`), when the terminal or agent behind a command-line session
exits, or once it has had no activity for the session expiry (`[mcp] session-expiry`, default 24
hours). That rule covers every session: an agent's whose connection is still open, a
terminal's, the dashboard's, and one a previous engine left open. The
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

### Command-line sessions

Each `jk` command names the session it belongs to, so the rail shows who actually ran it rather
than one card for every command on the machine:

- `JK_BUILD_SESSION`, when set, is the session as written: a script or CI job that wants its
  runs under one name sets it.
- An agent's shell commands are one session per agent session: Claude Code's are `claude-code
  (shell) <key>` (from `CLAUDE_CODE_SESSION_ID`), Codex's `codex (shell) <key>`. They sit beside
  the agent's MCP session on the rail.
- Otherwise each terminal tab or SSH connection is its own session, `terminal <key>`, and so is a
  script run from it; a command with no terminal is `script <key>`.

`<key>` is a short hash, so no process id or terminal name appears on the dashboard. A terminal's
session ends as soon as its shell exits, and an agent's when the agent exits, rather than at the
session expiry.

An episode starts with the session's first action, and again when the session moves to another
project, when it acts after ten minutes of quiet, and after a red run streak ends green (the
green run closes the episode it fixed). An agent names its task with the `goal` argument of
[`run`, `deps`, `rename` and `update`](mcp.md#goal): a new goal starts a new episode titled by
it, and a green run does not close an episode the agent named a goal for. Work with no goal
that starts an episode takes the session's last goal as its title (*last goal*). With no goal at
all, the title is inferred from what the episode did, in this order:

| What happened | Title |
|---|---|
| A run failed tests | `Fixing QuoteTest#threeNightWeekendStay (+2 more)`: the first red run's failing tests |
| A `deps` add, remove or pin | `Adding org.postgresql:postgresql` |
| An `update` | `Updating 14 pins` |
| A `rename` | `Renaming PricingRules.weekendUplift` |
| Only code-graph questions | `Exploring the code` |
| Anything else | `Working in <module>` |

An episode is **working** while something acts in it: a fact within the last ten minutes, or one of
its builds in flight. Ten quiet minutes with nothing running, or an engine restart for a session no
connection stands behind (the shell, the dashboard), close it. A closed one is **green** or **red** by its last run (or, with no
run, its last action), and **abandoned** when nothing in it was judged. `GET /api/sessions` lists
live and recent sessions with their current episode; `GET /api/sessions/<id>` returns one with its
episodes and a page of its timeline ([HTTP](../contributors/http.md#get-apisessions)).

## The project page

A project's page has two tabs. **Runs** holds its tiles, the run page and its build history;
**Sessions** (`#project/<id>/sessions`) lists every session whose project is this one, newest
first, with the episodes it spent here (title, outcome, runs, actions, wall, when), for answering
"what happened here yesterday". A session's name opens it on the console. **Build** in the header
starts a build of the checkout the page shows; the page then follows it.

### The run page

The Runs tab shows one run above the build history: the **newest** by default (a live one first),
so an MCP result's `dashboard` link lands on the job it just started and keeps following as the
agent iterates. Click a history row, or a run's chip on the console, to **pin** that run; the route
carries the pin (`#project/<id>/run/<n>`) so a reload keeps it, and **Follow newest** returns to
following.

Its header names the episode and the attempt (`attempt 2 of "Fix weekend uplift rounding"`), which
links back to the session on the console. Under the iteration strip and the coverage block comes
one cell per module, then the modules worth opening: the **failed** ones, with their phase chain
and failure report, and the **slow** ones (at least twice the run's median module wall and five
seconds or more; at most three), marked `slow`. Every other module folds under one line; its phase
chain is the tool for "why is this build slow". A run in flight also shows what it is doing, its
bar and ETA, its downloads and the newest output lines.

## Since the previous run

Under the run's header sits the **iteration strip**: what changed since the run before it from
the same origin (the same MCP session, IDE window or agent shell; your own command-line runs
compare against your last one from any terminal). Counts first — `since #12 · failed · 8.4s · 3 files · +0 / −2 diagnostics · tests: 1
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

## Activity

**Activity** (in the header, or the bell menu's last line) is what happened on this machine, newest
first: the notifications JumpKick showed, each decision with who asked it, where it was answered
(the terminal, the dashboard or the agent's host), how it reached you (in the dashboard, a browser
notification, a desktop notification), and how it was settled, the changes JumpKick made outside
your projects with who asked for each (a `jk` command line, an agent's session and goal, an
import), machine-setup results, and installs.

Filters narrow it by kind, project, agent session and surface; they are part of the address, so
Back undoes one. Click an entry to open it: a change shows its diff (credentials masked), a
decision its outcome and a link to its dialog, and either links to its project when JumpKick knows
it. Opening the page clears the bell's number.

Nothing is stored twice: Activity reads the [change journal](config.md#what-jumpkick-changed), the
decision history and the bell's notices, so each kind is kept as long as its source keeps it. The
page loads 50 entries at a time; **Show older** loads the next.

```text
#activity                                   # everything
#activity?kind=change&surface=import        # the changes imports made
#activity/change:<id>                       # one entry, opened
```

## Admin

**Admin** holds the engine's vitals: host CPU and RAM, version, pid, uptime, connections, the
builds running, MCP sessions, heap and RSS, the workers' budget, build metrics, the action cache
and artifact store, the effective configuration and the engine log. The status bar at the bottom of
every page shows the builds running and whether the page is live.

## New project

**New project** (on the Projects page) opens the wizard at `#new`. It asks the same form `jk new`
asks on a terminal and MCP `new` asks through elicitation, served by the engine: JumpKick's step
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

## Serve the dashboard from a directory

The engine serves the dashboard shipped in its own jar and nothing else from disk. To work on the
dashboard, or to serve a modified copy, name a directory:

```toml
[http]
web-root = "/home/you/src/jk/clients/web/src/main/resources/web"
```

`JK_HTTP_WEB_ROOT` takes the same value and wins over the file. A file in the directory replaces the
shipped file of the same path; a path it lacks is still served from the jar. Restart the engine
(`jk engine stop`) after changing either.

The dashboard's scripts run with your bearer token in reach, so point `web-root` only at a directory
that you alone can write, never one a build or another process writes into.

## Decisions

Your agent asks you; the dashboard is where the details live. An operation that waits for a person
(a destructive clean, an uninstall) is a decision ([MCP](mcp.md#decisions)). The bell in the header
lists the open decisions on every page ([below](#the-bell)). A bell entry, a Needs-you row, the link a
desktop notification carries under `[ui] ask = notify`, the agent host's URL elicitation and the
link a `NEEDS-YOU` result names all open `#decision/<id>`: a dialog over whatever page is showing,
with the question, who asked and where, the lines that explain it, and its answers.

The answers are the buttons. A confirmation shows one button per choice, labelled as the agent's
host labels it and in the same order (**Yes, delete them** and **No, leave it as it is**): the yes
accepts, the no declines. A longer list of choices is a column of buttons. Only a form with real
inputs shows fields, **Submit** and **Decline**. Closing the dialog (Esc, the close button, a click
outside it) answers nothing: the decision stays open in the bell.

The first answer wins, wherever it came from. When another surface answers first, an open dialog
says how and where it was settled and closes, and the host's question, the desktop notification,
the bell entry and the Needs-you row close.

### The bell

The bell's number counts what you haven't seen: each decision that opens, and anything else
JumpKick tells you. Opening the bell, opening a decision's dialog, or clicking a notification (which
opens the dialog) clears it, in every dashboard tab and the installed app at once; a dialog that
opens by itself in the tab you are using is seen as it opens, so the number never jumps up and back: the engine keeps
one "seen up to" marker for the machine, and it survives an engine restart.

Seen is not answered. A decision you have seen but not answered no longer counts, but the bell
stays amber, with no number, while any decision waits on you. Its menu has two parts: **Waiting on
you**, the open decisions, and **Just now**, the recent notices, with the ones you hadn't seen
marked. Older history is not kept in the bell: the menu's last line, **See all activity**, opens [Activity](#activity).

Besides decisions, the bell rings once for each batch of changes JumpKick makes outside your
projects (an edit of `config.toml`, a stored login, a cache clean, an import's repositories) and
for each machine-setup result. Installs of a JDK, Node.js or a tool don't ring: builds fetch them
as a matter of course. They are in Activity under **Installs**.

### Notifications

Under `[ui] ask = notify` ([Config](config.md#decisions)) JumpKick tells you about a decision once,
by the first of these that applies:

1. **You are using a dashboard tab.** Nothing pops up outside the page. A decision opens its dialog
   in that tab by itself, since an agent is waiting on it, with guards so a key or click meant for
   the page never answers it:
   - **Not while you type.** While a text field, a list or the code editor has focus, or you
     pressed a key in the last second, a card under the bell says **An agent needs you** with an
     **Open** link instead, and the dialog opens by itself once you stop.
   - **A moment's pause.** For about half a second after it opens, its buttons fill up and take no
     clicks or keys.
   - **One at a time.** A second decision waits and opens when the first dialog closes.
   - **Your keyboard stays put.** The dialog does not take focus: Tab or a click moves into it, and
     Esc closes it with the decision still open in the bell.

   Anything else JumpKick tells you wiggles the bell once (it lights up instead when your system
   asks for reduced motion) and shows a one-line card under it with a **View** link for about four
   seconds; hovering the card keeps it, and it never takes the keyboard. Only the tab you are using
   does this: your other tabs only update their bell.
2. **A dashboard tab may notify.** One browser notification, **JumpKick needs you**, with the
   question. A click brings the dashboard forward with the dialog open: the window you used last,
   the installed app's when you [installed it](#install-as-an-app), or a new one when none is open.
   It closes when the decision is answered anywhere. The desktop notification is skipped.
3. **Otherwise** the desktop notification, as [Config](config.md#decisions) describes.

"Using" means the tab is visible, its window has focus, and you moved the mouse or typed in it in
the last two minutes. A dashboard showing on a second monitor while you work in your editor, or
left open on a laptop nobody is at, is visible but not in use, so the browser or desktop
notification still reaches you. The page notes its own last input, so nothing asks for the
browser's idle-detection permission.

To let the dashboard notify, open the bell and click **Turn on notifications**; the browser asks
once. The bell never asks on its own, and the button is gone once you have answered either way
(change it later in the browser's site settings). A tab the browser froze, or a machine that slept,
stops answering the engine; after 45 seconds it counts for nothing and the desktop notification
fires instead.

The browser keeps the permission per origin, and the port is part of the origin: each engine's
dashboard port (`127.0.0.1:8910`, `127.0.0.1:8920`, `localhost:8910`) asks separately.

A decision answered by one choice, such as a confirmation, carries its choices as buttons on the
notification in Chrome and Edge. A button answers as the dialog's button would, from the dashboard
window it brings forward. When no dashboard window is open, it opens one on the dialog and answers
nothing until you click there. Other browsers show the notification without buttons.

### Install as an app

The dashboard installs as an app named JumpKick, with its own window, its own taskbar or Dock
entry and the JumpKick icon, and its notifications show under the JumpKick name. In Chrome or Edge,
open **About & help** (the **?** in the header) and click **Install JumpKick**; the entry appears
only once the browser offers to install, and is gone once the app is installed. The browser's own
install button in the address bar does the same. Safari on macOS installs it with **File > Add to
Dock**. Firefox does not install it; the dashboard works in a tab as before.

The app opens the dashboard root, and keeps no copy of the dashboard or of any engine data: it
loads everything from the engine each time, so after an engine upgrade it shows the new dashboard,
and with the engine stopped it shows the browser's error page. Notifications in the app follow the
same rule as in a tab: the app's window counts as a tab, in use or not.

An installed app belongs to its origin, the scheme, host and port together, like the notification
permission. Each engine port is its own app: `http://127.0.0.1:8910` and `http://localhost:8910`
install separately, and an engine on another port needs its own install. Installing and browser
notifications need a secure origin, which a loopback address is; a dashboard reached over plain
HTTP from another machine can do neither.
Settled decisions are kept under the same `[history]` retention as sessions.
`GET /api/decisions` lists open decisions first, then recently settled ones; `GET
/api/decisions/<id>` returns one; `POST /api/decisions/<id>` answers it; every change is an SSE
`decision` event ([contributor HTTP](../contributors/http.md#decisions)).

```text
#decision/<id>
```

Live events: `GET /api/events` (SSE). Cancel: `POST /api/cancel`. Implementation of the
SPA and HTTP contract: [contributor web client](../contributors/webclient.md),
[contributor HTTP](../contributors/http.md).

MCP shares this server: [MCP](mcp.md).
