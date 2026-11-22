# Web dashboard

```bash
jk web                 # ensure engine, print URL, open browser
jk web -n              # print only
jk web -O jsonl        # one {url, engineId} object; opens nothing
jk web -O jsonl --with-token   # the same, with the engine's bearer token
```

The JSONL form with `--with-token` is how an editor extension (the VS Code extension, for one)
finds the engine your `jk` uses and authenticates to it: it runs `jk` and reads the line, so it
never reads the engine's state directory itself and lands on the right engine inside a WSL distro
or a container. Without `--with-token` the line has no token: that flag is the one way a command
prints it in machine output.

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
dashboard at once: the Windows engine on port 8910, the WSL one on a port of its own between 8911 and
8999, which stays the same across restarts ([Engine § HTTP and MCP](engine.md#http-and-mcp)). Open the URL `jk web` prints on the machine you mean, and use
that URL in bookmarks and MCP configs. A tab that finds another engine on its address than the one it
was showing stops and names both, rather than showing the other engine's builds. `[http] port` in
`~/.jk/config.toml` or `JK_HTTP_PORT` names a port yourself.

The host engine's dashboard is the front door to the others. When more than one engine runs, the
engine's name in the header is a menu: pick another engine to see its dashboard under
`/engines/<id>/` on the host's own address, with the same token. Each engine keeps its own builds;
the front door only shows them, and the other engine's token never reaches your browser. A WSL engine
pairs with the Windows engine by itself: it writes its port and token into your Windows JumpKick
home, and removes them when it stops. An engine that has not paired is listed but cannot be opened.

## The console

`#console` is the landing page. From top to bottom and left to right:

- **Needs you**: the decisions an agent is blocked on, oldest first, each with who asked, how
  long ago, and **Review**, which opens the decision's dialog (`#decision/<id>`). Absent when nothing waits, and on an engine without a decision queue.
- **Sessions**: one card per live or idle session, and those that ended within the last hour. A
  card shows the host (`claude-code 3f9a`, `IntelliJ-BSP 7b2c`, `terminal 4c1e9a`), the current episode's
  title and where it came from (*goal from the agent*, *last goal*, *inferred*, *your own
  commands* for a terminal, or *shell commands* for an agent's shell), the modules its runs saw
  edited while the session is not ended (`in core, web`), the project, and the state:
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
  the run page. The timeline ends with what the episode **saved** so far: the figures of the
  [saved panel](#what-jumpkick-saved) it counted, over its own runs and actions (`nothing yet`
  before it counted any), and the tokens the agent used by its own count, for an agent that records them
  ([config](config.md#agents-token-counts); Copilot CLI's labelled `whole session`), refreshed when one of its runs finishes. **Earlier episodes** of the
  session fold below it, each with what it saved; open one for its timeline.
- **This session changed**: the files its runs changed, migrations (`V7 new`), dependency
  changes, the newest guard run's outcome, and the tests its runs fixed and broke.
- **How JumpKick served the agent**: tool calls (every action and run), runs, JumpKick's wall time across
  them, and the share of tasks the cache or an up-to-date check skipped.
- **Apps running**: each application an agent started with MCP [`run`](mcp.md#run), where it
  answers (`localhost:8080`, a link), and the session that started it (`claude-code 3f9a`).
  Absent when none runs.
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
one, the **session** that asked: an MCP connection is `<client> <tag>` (`claude-code 3f9a`,
from the client's `initialize` and the first hex digits of the id the engine minted for that connection), a BSP client
is the IDE's `displayName` plus a per-process id (`IntelliJ-BSP 7b2c`). A command-line run is
`cli`, and its session names who ran it ([below](#command-line-sessions)): `terminal 4c1e9a` for a
terminal tab or SSH connection, `script` with no terminal, `claude-code (shell) 9b20e1` for the
commands an agent runs through its shell tool, or whatever `JK_BUILD_SESSION` says. The run page and the build history show it as `MCP · claude-code 3f9a`; `jk-results.md`
prints the same as `trigger: mcp · session: claude-code 3f9a`, and `jk history` has a
Trigger column.

Every run also records where it came from (`source` in `GET /api/history?id=<run>`): the
checkout's repository (its `origin` remote as `https://host/path`, credentials and `.git` left
out) and branch, read from the checkout's git files; the CI system, run id, run page and event when
the shell that ran `jk` is a GitHub Actions, GitLab CI, Buildkite or CircleCI job (`ci` for any
other server that sets `CI`), the branch a pull request builds in place of the merge ref; and the
host's `<os>-<arch>`, processors and memory. `jk history show` prints the branch and the CI run.
These are what the run's own shell says, shown as such; nothing is decided by them. The client
sends the engine only the CI variables these fields read, each by its exact name
(`RequestEnvNames.CI_RUN`), never a CI server's whole prefix, so a job's `CI_JOB_TOKEN` or
registry password stays in its shell.

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
graph's `query`, `usages`, `read` and `find`, `rename`, `delete`, `move`, `pull_up` and
`extract_interface`, and the dependency
changes `deps`, `update` and `workspace`.

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
[`build`, `deps`, `rename` and `update`](mcp.md#goal): a new goal starts a new episode titled by
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

A project's page has three tabs. **Runs** holds its tiles, the run page and its build history;
**Sessions** (`#project/<id>/sessions`) lists every session whose project is this one, newest
first, with the episodes it spent here (title, outcome, runs, actions, wall, when), for answering
"what happened here yesterday". A session's name opens it on the console. **Flaky tests**
(`#project/<id>/flaky`) lists the checkout's quarantined test classes, with the reason, the date and
the issue from `.jk/test/quarantine.toml` (its id, linked to its URL when it has one), and every other class that passed on a re-run in its last runs,
each with those runs as a row of cells, oldest first: green passed, amber passed on a re-run, red
failed, and a thin grey one where it was released ([quarantine](test.md#flaky-tests-are-quarantined-jk-flaky)). **Build** in the header
starts a build of the checkout the page shows; the page then follows it.

### What JumpKick saved

The Projects tab opens with a **saved** panel for every project, and a project's Runs tab with the
same panel for that project. Both cover the last 7 days and show what `jk stats` prints:

| Figure | What it counts |
| --- | --- |
| time avoided | Build and test time the cache avoided. An estimate: each run's cold-cache critical path less its wall ([Cache](cache.md)); `measured` is the share of cache hits backed by a measured time |
| tokens saved | Tokens the code questions and refactorings saved the agents, and what they would have cost at API list prices. An estimate ([below](#tokens-and-dollars-saved)) |
| steps from cache | Steps whose result came from the cache instead of running, and of those, test suites |
| classes not re-run | Test classes a red re-run left out because they passed under the same inputs ([Test](test.md)) |
| code answers | Code questions an agent asked through MCP (`query`, `usages`, `read`, `find`) |
| refactorings · edits | Refactorings the code graph applied (`rename`, which also moves a type or a package), and dependency edits made: each coordinate a `deps` call added, removed or pinned, each applied `update` or `workspace` change |
| guard stops | Guard-rule violations a run stopped before a commit, each rule broken in each file counted once however many runs it stopped, and the runs they stopped |

```bash
jk stats                      # the last 7 days
jk stats --since 30d          # the last 30 days; also 12h, 2w
jk stats --since 2026-11-01   # since midnight that day, where you are
jk stats --since all          # all the history kept
jk stats -O jsonl             # one `stats` object per row: `scope` `all` first, then one per project
```

The same figures, over one episode's runs and actions, end that episode on the console and the
`jk-results.md` of each run in it ([results](machine-output.md)).

The status bar at the foot of every page keeps a running total of the tokens and dollars saved over
all the history kept, every project's and, on a project's page, that project's. It is read again a
moment after each finished run and each agent action, and its tip names the date of the prices.

#### Tokens and dollars saved

The tokens are an estimate, never a count of what your agent spent. The benchmark in
[Why JumpKick](why.md#asking-about-the-code) runs each agent on the same tasks with JumpKick's code
graph and without it. For each task it takes each kind of token (input, output, cache read, cache
write) the agent spent without the graph less what it spent with it, the median of each, adds those
up over the tasks and divides by the graph calls the agent made in them with the graph. Each code
answer (`query`, `usages`, `read`, `find`) is credited what one graph call saved in the code-question
tasks, and each refactoring what one saved in the rename tasks, at the rates measured for the agent
whose MCP client asked: Claude Code, Codex, Cursor, Grok, Muse or Antigravity.

A search over a large codebase costs an agent far more than one over a small one, so the benchmark
also measures that rate in each repository it ran in, from a few hundred lines to several hundred
thousand. A call is credited the rate of the repository nearest its project's size: the non-blank
lines of the project's Java, Kotlin and Groovy sources, main and test, outside build output, counted
at most once an hour. A call whose project JumpKick cannot count is credited the rate over every
task. A call from an agent
the benchmark has not run, or of a kind it has not measured for that agent, is not credited at
another agent's rates and not counted as zero: it is left out of the total, and the figure says how
many calls were (`not measured by the bench, not counted`).

The dollars are those tokens at the API list price of the model the agent was on, which JumpKick reads
from the agent's own session files while `[agents] read-usage` is on, else the model the benchmark
ran it on. The price table is the one JumpKick ships with or a newer signed one the engine fetched
([config](config.md#updates)); the tip names the date the newest price behind the figure was
read from its vendor's page. Tokens on a model the table has no price for are counted but left out
of the dollars, and the tip says how many; they are priced once a table that knows the model
arrives. An agent on a subscription pays its plan instead. A cached step or a test class not re-run
saves time but no tokens the benchmark has measured, so it adds none. The rates ship with JumpKick
and change with a new release; the tip names the date of the newest benchmark run behind them, and
**how it is estimated** on the panel, or **est.** in the status bar, opens this section.

Nothing new is recorded for it: the engine adds up the run records and session actions it keeps,
so a period reaches back only as far as `[history]` keeps runs and a session keeps its last 1000
actions. `GET /api/stats?since=<epoch ms>` answers the same rows
([HTTP](../contributors/http.md#get-apistats)).

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
under a per-run route that carries a report token in its path (so the report's own pages and
stylesheets resolve) and in a sandboxed origin, so a report cannot script the dashboard. The report
token opens that run's report and nothing else, for 8 hours, so the address in your browser's
history is not the dashboard token; a report link that has expired answers "report link expired or
not valid", and the dashboard's link takes a fresh one. The
figures are the record's `coverage[]` rows — the same facts as `target/jk-results.md` and MCP
`build`; the baseline is the nearest earlier record of the project whose coverage is not
empty, as the results file chooses it.

At an interactive terminal the link `jk web` and `jk engine status` print ends in `#t=<token>`,
and the browser `jk web` opens is always handed that link. Anywhere a command's output is
captured, the link is printed without the token: when the output is piped or redirected, under
`--agent` or `-O json`/`jsonl`, with `CI` set, or in a coding agent's shell (`CLAUDECODE`,
`CODEX_THREAD_ID`, `CURSOR_AGENT`, `GEMINI_CLI`, …). The token opens this engine, and through a
front door every engine on the machine, so it does not belong in an agent transcript, a CI log or
a recorded terminal. Links the engine answers with — an MCP result's dashboard or decision link,
the new-project wizard an agent offers, `jk decide`'s and `jk doctor --fix`'s decision link —
never carry it; only the browser the engine opens itself (`[ui] ask = open`) and its desktop
notification do. A browser that has opened a signed link once keeps the token and opens these
links signed in; one that has not shows **Access Denied**: run `jk web` at a terminal.
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
#project/<projectId>/sessions        # the Sessions tab
#project/<projectId>/flaky           # the Flaky tests tab
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

## Machine

On Windows the header has a **Machine** tab: JumpKick for Windows and each WSL distro, with the
JumpKick it has (a stopped distro is not started just to look), whether its engine is paired with this
dashboard, and its last install or remove. **Install** puts the JumpKick release that matches yours
into the distro and pairs it; **Remove** takes JumpKick out of that distro and leaves Windows' own
alone. A distro you declined in the setup decision installs from here like any other. It is the same
operation as `jk self setup-wsl` ([Engine](engine.md#http-and-mcp)); each outcome is in Activity.

## Admin

**Admin** holds the engine's vitals: host CPU and RAM, version, pid, uptime, connections, the
builds running, MCP sessions, heap and RSS, the workers' budget, the backpressure counts and idle
connections dropped once there are any (the same rows as `jk engine status`), build metrics, the
action cache and artifact store, the effective configuration and the engine log. The status bar at the bottom of
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
for each machine-setup result, and when a test run quarantines a flaky test class: the toast under
the bell links to the project's Flaky tests tab. Installs of a JDK, Node.js or a tool don't ring: builds fetch them
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
Dock**. Firefox does not install it; the dashboard works in a tab.

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
