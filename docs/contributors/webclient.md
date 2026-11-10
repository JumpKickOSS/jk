# The engine dashboard (web client)

The resident engine serves a small single-page dashboard from `clients/web`
(`src/main/resources/web/`). It is a **thin renderer over the HTTP surface documented in
[http.md](http.md)** — the engine owns all build logic; the page owns presentation only. This is
the doc the shell's source files cite.

## Layout

There is no bundler and no npm, but there is a module system: `index.html` loads exactly one
script, `<script src="/app.js" type="module">`, and every other file is reached from it through a
static `import`. The browser orders the graph, so a split here costs an `import` line and not a
`<script>` tag. What it does cost is checked by `src/test/js/shell.test.mjs`: one entry point, no
module the graph never reaches, and no name the in-DOM template calls that the root component has
stopped providing.

| File | Role |
| --- | --- |
| `index.html` | The shell: in-DOM Vue template, CDN pins, brand chrome |
| `app.js` | The root component: auth, the SSE connection, the hash route, cross-view fetches |
| `api.js` | **The only file that talks HTTP**: token bootstrap, fetch wrappers, SSE client |
| `format.js` | One duration, one relative time, one byte size — every surface formats the same |
| `fold.js` | Folds `/api/events` build frames into run cards — pure `(cards, event)` functions |
| `outcome.js` | Derived state of a folded card: outcome badge, phase chain, module order |
| `label.js` | Step detail and the colour segments of a Java type / member / JDK-download label |
| `failure.js` | Diagnostics → the test and compile failure report models (CLI parity) |
| `cards.js` | A run card's methods for the console's live builds and the run page: progress, ETA, badge, diagnostics |
| `console.js` | The console's view models: the sessions rail, the session log's frames folded, an episode's timeline, its header and arc, earlier episodes, the right rail, the run page's attempt, the project's Sessions tab |
| `consoleview.js` | The console's root-component mixins: `/api/sessions`, one session's page, `/api/decisions`, the log and decision frames, the first-paint and event marks |
| `needs.js` | The open decisions the bell and the Needs-you banner list: the `decision` frame folded, a 404 read as "no queue" |
| `strip.js` | A run as one line: what it is doing, one cell per module, and which modules the run page opens (failed, slow) |
| `status.js` | The Status view: the `/api/{status,cache,metrics,log,config}` reads and its meters |
| `projects.js` | The Projects tab and Project page: history records grouped per project, and the run the page follows or pins |
| `coverage.js` | `<run-coverage>`: the Coverage block on the focused run — the record's `coverage[]` rows, deltas against the previous coverage run, the tokenized `/report/<token>/<id>/` link |
| `delta.js` | `<run-delta>`: the iteration strip — a record's `delta` as chips with the rows behind them |
| `sessions.js` | Who asked: origin labels, the followed-or-pinned run, the pin route |
| `wizard.js` | New Project and the directory browser its path fields open |
| `notify.js` | Telling the person once: presence reports, the `notify` frame, in-app delivery (the bell's wiggle and preview card), the notification adapters, the service worker's messages |
| `notices.js` | The bell's number: the engine's notices and seen marker (`/api/notices`, the `notice` frame), "Just now", and moving the marker |
| `pwa.js` | The installed app: the service worker's registration and the **Install JumpKick** entry |
| `sw.js` | The service worker, a classic script the page registers by URL, never imported |
| `manifest.webmanifest`, `icon-*.png` | The web app manifest and its icons |
| `decisions.js` | `<decision-dialog>`: the fixed-size dialog `#decision/<id>` opens over the page, its answers as buttons, and the root's bell and route glue |
| `icons.js` | `<jk-icon>`: the inline-SVG glyph set |
| `phase.js` | `<phase-chain>`: the coarse plan-phase strip on a card |
| `report.js` | `<fail-report>`: one failure body for the compact card and the module branch |
| `chart.js` | `<build-bars>` and `<module-dep-graph>`: the two ECharts surfaces |
| `code.js` | `<code-view>`: the `#project/<id>/files` pane |
| `fileviewer.js` | `<file-viewer>`: one workspace file read-only over the page, as an `edited N files` path opens it |
| `monaco.js` | Monaco and the preview CDNs: version pins, SRI hashes, AMD parking, editor options |
| `preview.js` | The Preview half: markdown, mermaid, graphviz, asciidoc, d2, images |
| `paths.js` | Workspace paths: extension → language, previewable kinds, markdown href resolution |
| `route.js` | The hash router: `#console/<session>`, `#project/<id>/files/<rel>?line=…` parsed and rendered |
| `tree.js` | The file-tree model: flat paths → a directory-first render tree |
| `tip.js` | The `data-tip` tooltip layer |
| `style.css` | Chrome; JetBrains Mono via Google Fonts |

## Auth

Token bootstrap rides the URL fragment: `jk web` (and `jk engine status`) print a dashboard link
ending in `#t=<token>`, and an MCP result's `dashboard` link carries it in a project route's
fragment query, `#project/<id>?t=<token>` (`DashboardLinks` engine-side); on load `api.js`
(`adoptFragmentToken`) stashes the token in `sessionStorage` and `localStorage` and scrubs it from
the address bar, keeping the route (fragments never leave the browser). Later
tabs/refreshes reuse the stored token. Every `/api` call then sends `Authorization: Bearer <token>`.
**All `/api/*` endpoints require a valid token**, including loopback (static shell assets stay open
so the SPA can render the auth dialog — see [http.md](http.md)).

**`jk web`** ensures the engine is running, prints the authenticated URL as an OSC-8 hyperlink, and
opens it in a browser (`$BROWSER` when set — word-split, so values with arguments work — else
`open` / `rundll32 url.dll,FileProtocolHandler` / `xdg-open`). Use `--no-open` to print only.

When a token is **missing or invalid** (bare `http://localhost:8910` with no stored token, a
rotated/stale token, or any `401` from the engine), the SPA opens a **blocking “Access Denied”
dialog** and freezes the rest of the UI — it does **not** paint the console, vitals, or a quiet
empty state. There is no paste field and no dismiss: recover by running
`jk web` (opens an authenticated tab and prints a clickable `#t=…` URL if the browser cannot
open). The dialog shows a console-style `jk web` snippet with a copy button.

## Tooltips

Native HTML `title=` is **OS chrome** (harsh white-on-black, no radius) and cannot be styled.
Hover help uses `data-tip="…"` (or `:data-tip`) plus `tip.js`, which paints a fixed `.jk-tip-float`
panel with the same soft Jk Dark shell as retention / the millis I/O tip. Do not reintroduce
`title=` for user-visible hover text.

## Dependencies: CDN, pinned, integrity-locked

Vue (and ECharts for history sparks **and** the Project-page module dependency graph) load from
the CDN, **version-pinned with an SRI `integrity` hash** — a CDN compromise must not be able to
script a page that can trigger builds. When bumping a pin, update the `integrity` hash in
`index.html` in the same change. Preview-only libraries (marked, DOMPurify, mermaid, viz-js,
Asciidoctor; D2 via dynamic ESM) are pinned in `monaco.js` and load **only** when Preview is used —
same unpkg origin, never self-hosted. marked and DOMPurify load as **ESM** (`import()` from unpkg) so Preview never parks Monaco's AMD
`define` (that race broke Monaco's on-demand markdown grammar: `define is not a function`).
Mermaid / viz / asciidoctor still use classic UMD with a refcounted `define` park, and only when
those diagram kinds are needed. There is no bundler and no npm build step: the shell ships as
static resources inside the engine jar.

**Monaco is the one partial exception.** Its version is pinned and `loader.js` carries SRI
(`MONACO_LOADER` in `monaco.js`), but the loader then fetches `editor.main.js`, `editor.main.css` and
the per-language chunks itself and has no way to pass an integrity hash down, so those bytes are
CDN-trusted. It is also what widened the shell CSP (`StaticContent`): `blob:` in `script-src` plus
`worker-src blob:` for the language workers it spawns from a generated Blob that `importScripts` the
CDN bundle, `'unsafe-inline'` in `style-src` for the inline style attributes it positions every view
line and widget with, and `data:` in `font-src` for the codicon font inlined into
`editor.main.css`. Monaco must load **after** the UMD globals above — its AMD loader claims
`window.define`, and a UMD script that loads afterwards would register as an AMD module instead of
setting its global. Vue/ECharts are `defer` tags in `index.html`, and Monaco only loads when a
user opens `/files`, so that ordering holds.

## The console

`#console` is the landing page (no hash, and any hash no route claims, land there too). It renders
three reads and three SSE frames through `console.js`'s pure models; `consoleview.js` owns the reads
and spreads the computeds into the root component.

| Read / frame | What it feeds |
| --- | --- |
| `GET /api/sessions?limit=50` | the sessions rail (`sessionRail`): live, idle, and ended within the hour |
| `GET /api/sessions/<id>` | the selected session's page: its current episode (`currentEpisode`), timeline (`episodeTimeline`), header (`episodeHeader`), earlier episodes, and the right rail (`sessionChanges`, `sessionService`) |
| `GET /api/decisions` | the Needs-you banner (`needs.js`); a 404 means the engine has no decision queue and the banner stays absent |
| SSE `session` / `episode` | `foldSessionFrame`, `foldEpisodeIntoRail` and `foldIntoDetail`: the rail rows and the selected page, replaced in place |
| SSE `action` | appended to the selected page once by `seq` |
| SSE `decision` | `foldDecision`: a decision opened or resolved |

Runs are not `action` frames. A run in flight is a folded card (`fold.js`) matched to its session by
trigger and label (`cardInSession`: `mcp` + `claude-code 3f9a`; the shell is `cli` with no label)
and placed at the end of the session's current episode; a finished run reaches the page on its next
read, which `request-finish` schedules. A run line joins its action to its journal record (by
episode and build number) for the outcome chip, the failing headline and the delta chip, and an
`edited N files` line goes before every run whose `delta.files` is not empty. Each of its paths is
a link that opens `<file-viewer>`: the file's current content from `GET /api/project/file` for the
run's project and checkout, in read-only Monaco, or as plain text past `canHighlight`. A file the
edit deleted (`(gone)`) stays plain text; a binary one opens to a message saying so. The live build is
`strip.js`: `doingSegments` (`doingLine` is its plain text, the hover tip), `moduleCells` (padded
with waiting cells to the record's `moduleCount`) and the card's own progress and ETA (`cards.js`).
The live line keeps the CLI's detail colours (`detailSegments`: the class gold, the method purple)
and stays on one row: a line too wide for its card loses its left end behind a leading `…`, so the
test class and method on the right stay in view (an `rtl` box around an isolated `ltr` run).

With no session in the route (`#console/<id>`), the console holds the rail's first card at load
(`consoleAutoSession`) so a reordering rail does not move the selection; a watcher on
`selectedSessionId` loads the page of whichever session becomes selected.

The wire tokens live in `wire.js`: `LOG` (`session`, `episode`, `action`, held to
`EventCatalog.DASHBOARD_ONLY` by `WireTokenParityTest`) and `QUEUE` (`decision`); `api.js` listens
for every one. The decision dialog (`#decision/<id>`, over whatever page is showing) and answering are the decision queue's.

### Decisions and notifications

`notify.js` is the tab's half of telling the person once ([HTTP](http.md#one-channel-per-decision)):
it answers the stream's `presence` ping with the tab's visibility, focus, last input
(`watchPresence`, `noteInput`; one idle timer re-armed for the remainder, so mouse moves cost
nothing) and `Notification.permission`, reporting again when the tab turns active or idle; shows
the `notify` frame's notification only from a tab that is not active and may; closes a settled
decision's notification by tag; and puts the one permission ask, behind a click, at the foot of
the bell's menu while the permission is `default`.

In the active tab a decision opens its dialog by itself (`decisionArrived`, `pumpAutoOpen`): it
joins `_autoQueue`, and the queue opens its oldest entry once the tab is active, nobody is typing
(`isTyping`: a text field, select, contenteditable or `.monaco-editor` in focus, or a key within
`TYPING_QUIET_MS`) and no dialog shows, checking again every `AUTO_RETRY_MS` while anything waits; a
settled decision leaves the queue. While typing holds it back, the `preview` card says **An agent
needs you · Open**. The dialog it opens is `guarded` (`decisionAuto`): it leaves focus where it
was, and its answers ignore input for `GUARD_MS` behind a CSS fill. Anything else is delivered by
`deliverInApp`: the bell wiggles once (`bellPulse`) and the `preview` card shows for `PREVIEW_MS`
unless hovered.

`notices.js` keeps the bell's number: the engine's notices and seen marker, folded from the
`notice` frame. Opening the bell (`toggleBell`) or showing a dialog (`routeDecision`) calls
`markSeen`, which posts the newest notice's time; a decision notice that arrives for a dialog this
tab shows, or is about to open by itself, is marked seen at once rather than counted. The tests
are `notify.test.mjs`, `notices.test.mjs` and `decisions.test.mjs`.

## The installed app

The dashboard is a web app the browser can install: `manifest.webmanifest` names it JumpKick, with
`display: standalone`, `start_url`, `scope` and `id` at the root, and the header's colour as
`theme_color` (the shell's `<meta name="theme-color">` says the same; `pwa.test.mjs` holds them
together). Its icons are rendered from `jk-logo.svg` by `scripts/render-icons.sh`, with the desktop
notification icons: `icon-192.png` and `icon-512.png` as drawn, and `icon-maskable-512.png`, the
mark without its border on a full-bleed canvas of the panel colour so a platform's mask never cuts
into it. Run the script after changing the logo and commit the output.

`pwa.js` registers `sw.js` on every load, with scope `/` and `updateViaCache: 'none'`, and keeps
the browser's `beforeinstallprompt` for the **Install JumpKick** entry in the About dialog instead
of letting the browser show its own banner. The entry shows only while the browser offers to
install, and never in the installed window (`display-mode: standalone`) or after `appinstalled`.

**The worker.** `sw.js` makes the dashboard installable and owns notification clicks. It has no
`fetch` handler, so every request goes to the engine as if no worker were installed: it caches
neither the shell nor any `/api` response, and takes no push. `sw.test.mjs` fails on a `fetch`
listener, a reference to Cache Storage or a push subscription. Current Chromium needs no fetch
handler to install, and a pass-through one would only add a hop to every request.

**Updates.** Because the worker serves nothing, a stale worker cannot pin an old dashboard: the
shell and its modules always come from the engine, revalidated (`Cache-Control: no-cache` and the
jar's ETag). The worker itself is re-checked on every load, bypassing the HTTP cache
(`updateViaCache: 'none'`); when an upgraded engine ships different bytes, the new worker installs,
`skipWaiting` and `clients.claim` put it in charge of open pages at once, and it holds no state an
open page relies on.

**Serving.** `StaticContent` serves `sw.js` at the root, so its scope is the whole dashboard, as
`text/javascript` with the shell's policy, whose `worker-src 'self'` lets the page register it, and `manifest.webmanifest` as `application/manifest+json`.
The worker comes only from where the shell comes from: the jar, or a directory the user named
(`web-root`, or a snapshot build's `web-overlay`: a dashboard source tree). A worker controls every
page of its origin and outlives its file, so nothing is read from a location a build can write by
default: `~/.jk/state/web` is never served.

**Notifications through the worker.** `notify.js` shows and closes notifications through an
adapter chosen per notification: while a worker controls the page (`navigator.serviceWorker.controller`)
it is `registration.showNotification` and `registration.getNotifications({tag})`, else the page's
own `Notification`. Closing asks both, so a notification shown before the worker took over still
closes by tag. The channel rule and the presence reports do not change.

A click on a worker's notification runs `notificationclick` in `sw.js`: it focuses the most
recently focused dashboard window and posts it `{type: 'open-decision', id}`, which the page turns
into `openDecision(id)`; with no window open it opens one at `#decision/<id>`.

**Buttons.** Only a worker's notifications may carry actions. A decision answered by one choice
(`decisionChoices`) gets one action per choice, in the dialog's order, when the browser has room
for all of them (`Notification.maxActions`, two in Chromium); otherwise none, never some. A button
click posts the focused window `{type: 'answer-decision', id, choice}`, and the page's
`answerDecision` reads the decision, finds the choice and sends the dialog's own body
(`choiceBody`) to `POST /api/decisions/<id>`; a settled decision or an unknown choice opens the
dialog instead. The page posts, not the worker: the bearer token stays in the page's storage, the
request carries the page's engine epoch like every other mutation, and a worker that can answer
decisions with no page open would be a second credential holder that outlives the tab. With no
window open, a button opens the dialog and answers nothing, so no URL can answer a decision by
being opened.

**Origins.** An installed app, its worker and its notification permission all belong to one
origin, scheme, host and port together, so each engine port is its own app.

**Browsers.** Chrome and Edge install from the entry or the address bar and show the buttons.
Safari on macOS installs with File > Add to Dock and has no `beforeinstallprompt`, so the entry
never shows; its notifications carry no buttons. Firefox registers the worker but does not install
web apps, and shows no buttons.

**First meaningful paint.** Once the rail and the selected episode (or the empty state) are
rendered, `markConsolePainted` waits for Vue's patch and the next frame, then sets
`performance.mark('jk-console-painted')` and `<html data-console-painted-ms>` (milliseconds since
navigation start), once per load. The scoreboard's `console` dimension
(`bench/scoreboard.d/console.py`, `bench/console/paint.mjs`) loads the page in headless Chrome over
the DevTools protocol against a private engine serving this tree's `web/` and banks the median as
`console.paint.p50`; the PRD target is 500 ms. The mounted hook starts the console's reads before
the history and status reads so the paint does not wait on them.

**Event marks.** Each `action` frame and each `decision` frame that opens one, once Vue has patched
it in and the browser has painted it, leaves `performance.mark('jk-console-event', { detail })`
(`markConsoleEvent`). `detail` holds the frame's `kind`, `id` and `publishedAt` (`frameStamp` in
`console.js`: an action's `at + millis`, a decision's `createdAt`), `paintedAt`, `latencyMs`
between them, and `shown`: the console is up and the frame is on it (the selected session's
timeline, the Needs-you banner). A hidden tab marks nothing, and a page clears its marks every 500.
The same dimension's `bench/console/live.mjs` reads them for `console.event.p95` (target 250 ms) and
`console.decision.p50` (target 2 s), and times the supervisor checklist's five answers on fresh
loads for `console.checklist.max` (target 5 s each).

## Project routes

Project detail is `#project/<projectId>` — a durable opaque id (from `project-id` in `jk-lock.toml`,
or hybrid git/path resolution), **not** an absolute filesystem path. Every git worktree of one
repository shares the id, so `GET /api/project?project=<id>` answers the id's live **checkouts**
from `identity.toml` under the builds state dir: one is implied; several come back as a list and
the page shows a picker. The chosen checkout rides the route as `?dir=<abs-path>` (an MCP
`dashboard` link and a CLI failure link spell it too); `selectedProjectDir` is that route value,
`projectDir()` folds in the engine's implied one, and the files pane sends it as `dir=` on every
list / read / raw / PUT so an edit lands in the tree the build ran in. With a checkout picked the
run the page follows and its history rows are that checkout's; **all** shows every checkout's
rows with a Checkout column.

Source files hang off the same route:

```text
#project/<projectId>                 (follows the project's newest run)
#project/<projectId>?dir=<checkout>  (one worktree of an id several build under)
#project/<projectId>/run/<n>         (pinned to build #n — a history row or console click; Follow newest clears it)
#project/<projectId>/sessions        (the Sessions tab)
#project/<projectId>/files
#project/<projectId>/files/src/Main.java?line=42&dir=<checkout>
```

The project page has two tabs, **Runs** and **Sessions**. Sessions renders `projectSessionRows`
(`console.js`) over `GET /api/sessions?limit=200` filtered to the sessions whose project (or
current episode's) is this one, and the pages of the newest twenty, for their episodes on it.

The **run page** above the build history renders `projectRun` (`projects.js`): the
pinned record when the route names a build number the project has, else the newest — a live
card first, then the newest journal record — folded through `historyCard` so one outcome rule
and one module shape serve every surface. Its header names the episode and the attempt
(`runAttempt` over the episode's session page, loaded when `runEpisodeId` changes; a run in flight
whose record does not name its episode yet takes its session's current one). Its modules split by
`moduleFocus` (`strip.js`): the failed and the slow (≥ 2× the median module wall and ≥ 5 s, at
most three) open with their phase chains and failure reports, and the rest fold under one line. `focusedRun` in `sessions.js` is the pure
rule and is tested headlessly; the panel markup itself is checked only by `shell.test.mjs`'s
name contract, not rendered.

Under the header the panel shows the iteration strip (`delta.js`) and, for a run that measured
coverage, the Coverage block (`coverage.js`): `historyCard` carries the record's `coverage[]` onto
the card (`seedFromHistory` copies it onto a live card on reconcile, as it does `delta`), and
`projectRun` supplies `previousCoverage` — the nearest older record of the project with non-empty
coverage — as the baseline. The **HTML report** link is `/report/<token>/<id>/`, the bearer token
in the path: a report is a tree of pages the browser follows by relative link, and a link carries
neither a header nor a query string into the next page. The engine redirects the bare run URL to the
report's entry page and serves only files under the report directories the record names, each
under `Content-Security-Policy: sandbox` ([HTTP](http.md#get-reporttokenidpath)).

**Build** in the project header is the dashboard's one build action (`POST /api/build` with the
checkout the page shows); the page lets go of a pinned run and follows the new one. The cyan
**code** control (**View/edit this codebase**) sits next to **Build** and opens
`#project/<id>/files` (tree). It is hidden *on* the files pane — you are already there — which is
also where the header's back control drops its label: a bare chevron that goes up one level to
`#project/<id>`, not out to the project list.
Selecting a file appends the workspace-relative path as extra hash segments (each `encodeURIComponent`;
`/` stays a separator). Optional `?line=` is a 1-based highlight; compiler jumps also add `&col=` so
Monaco lands on the diagnostic column. Fail-report and CLI OSC-8 jumps
add `&err=true` so the target line uses the error-red wash (plain `?line=` stays a soft cyan
rail). Compiler (and fail-report) jumps also pass `&msg=` — a short, URL-encoded note (capped at
800 characters) shown as a Monaco hover on the highlighted line and column.
The underlined path above a test-failure or compile-failure snippet is a real hash deep link
into that route (so middle-click / copy-link work). Visible text is `path:line` (and `:col`
for compiler jumps) so a copied snippet still names the locus after colour / OSC-8 is stripped.
Compiler errors use the same report chrome as test failures (`✘ Compile failure in coord`,
`error:` body, then a 5-line source window) instead of a mashed one-liner. Module-relative paths join `rel(checkout, module.dir)` +
`rep.file`; an empty live single-plan module dir leaves `rep.file` as already checkout-relative.
Basename-only paths stay plain text.

The CLI paints the same path with an OSC-8 hyperlink to that absolute dashboard URL (RichText
`[link …]`) when the engine HTTP surface is up and the checkout has a project id — click the
underlined path in a capable terminal to open the editor at the failure line. Auth still comes
from a prior `jk web` / stored token (`#t=` cannot share the hash with a `#project/…` route).

`/files` with **no path opens the workspace-root `jk.toml`** (`replaceState`, so the Back chevron
still leaves in one hop). Only that exact path counts — a member's `sub/jk.toml` is not the
workspace and an imported Maven/Gradle tree has none — and when there is none the pane shows a
centred *Select a file from the tree.* empty state instead.

The tree pane itself is GitHub-shaped: collapsible folders (chevron + `folder`/`folder-open`
glyph) above files (generic `file` glyph), directories before files at every level, names sorted
case-insensitively. It never holds the whole checkout. The engine answers one folder at a time
(`GET /api/project/dir`, 500 entries a page), already folding **single-child directory chains**
into one row (`main/java/cc/jumpkick`) so a Java source file is a few rows deep instead of a
dozen; a folded row keys off its deepest path. The pane keeps a map of the folders it has loaded
(`dirs`, `''` the root): opening a folder fetches it, closing one drops it and everything loaded
below (`dropSubtree`), so what is held is bounded by what is open, and a folder past one page ends
in a *Show N more* row. Folders are closed by default, except the ancestors of the open file:
`reveal` loads each folder on the way down (further pages too, until the next step shows up) so a
`?line=` deep link or a fail-report jump lands with its file revealed, selected and scrolled into
view.

`treeRows` flattens the open parts into rows and the list is **virtual**: rows are a fixed
`ROW_HEIGHT` (24 px), the scroller is as tall as all of them, and `visibleWindow` draws only the
rows in the viewport plus `OVERSCAN` either side, so a 20 000-entry folder or a fully opened
monorepo keeps a few dozen buttons in the DOM. Build output and files that are not text (`output`,
`lang: image|binary`) are listed, dimmed. Typing in the filter box asks `GET /api/project/find`
(debounced 200 ms, an older answer dropped) and switches to a **flat list of matching full
paths** — the tree is for browsing, the filter answers like GitHub's file finder; every row carries
its full path as a `data-tip`.

The pane lists `GET /api/project/dir`, finds through `GET /api/project/find`, reads `GET /api/project/file`, writes
`PUT /api/project/file`, and loads image Preview via `GET /api/project/file/raw` (see
[http.md](http.md)).

**Chrome** (files open):

```text
header:  [ ← ] [ coord ]                    [ Build ]
tab bar: [ file-name-pill ]   [ Copy ] [ Preview ] [ Save ]
```

- **Copy / Preview / Save** live on the editor tab strip (right-justified), cyan tinted-neon
  (same geometry as Build: `inline-flex`, `gap: 6px`, shimmer on hover, shared `min-width`).
- **Build** stays in the project header (green primary).
- **Save** is disabled until the Monaco buffer differs from the last load/save; oversized plain-text
  fallback and image-only opens are not editable. **Ctrl+S / ⌘S** (and the Save button) open an
  in-page themed confirm (Cancel / Save; Escape cancels) before writing. On success the label flips
  to **Saved** briefly
  (same pattern as Copy → Copied). Failures use plain-language messages (engine down, unauthorized,
  too large, concurrency). Saves send the load-time `etag`; a **409 file changed on disk** means
  another tab or process rewrote the file — reload to continue.
- **Preview** is enabled for markdown, images, mermaid (`.mmd`/`.mermaid`), Graphviz (`.dot`/`.gv`),
  AsciiDoc (`.adoc`/`.asciidoc`), and D2 (`.d2`). Renderers load **lazily from unpkg only** (never
  self-hosted); images use an auth-fetch → blob URL. Markdown also renders fenced
  ` ```mermaid ` blocks. Preview-eligible files open with Preview on by default (toggle to Source). The rendered
  pane sits above the editor; text buffers re-render on a short debounce while both are open.
  Markdown images (including raw HTML `<img>`): relative paths load via the workspace
  raw-file API as `blob:` URLs; remote `http(s)` stay as direct `<img src>` with
  `referrerpolicy=no-referrer` and **no** `crossorigin` (setting CORS mode broke GitHub
  user-attachments and badges). Images are **inline** (badge rows stay on one line). Relative
  markdown links (`[Status](docs/contributors/architecture.md)`) rewrite to `#project/…/files/…` so they open
  in the files pane; external links open in a new tab. D2 is WASM-heavy and may fail under CSP —
  the pane shows the error rather than shipping a binary.

Monaco **0.56.0** loads lazily from unpkg (AMD loader SRI-pinned; see the CDN section) only when
`/files` is open, and renders a **light editor** in the built-in **Visual Studio Dark**
(`vs-dark`) theme: Monaco's own line numbers, folding, minimap and find widget, no context menu or
suggestions. The theme is registered as `jk-vs-dark` — vs-dark inherited verbatim with a single
override, `editor.background` read from style.css's `--console-bg`, so a source pane reads as the
same surface as the console tail and log panels instead of VS Code's `#1e1e1e`. `?line=` is a
whole-line decoration (`.code-line-hl` soft/cyan, or `.code-line-err` red when `err=true`);
`?col=` marks the token (red wavy underline when `err=true`). The whole-line decoration
carries `hoverMessage` when `?msg=` is present (compiler key/value details from the CLI
OSC-8 link) — the column mark is visual only so Monaco does not stack the same note twice.
The editor reveals the position and opens that hover on landing. Monaco ships no
Groovy or TOML grammar, so `.groovy` tokenizes as `java` and `.toml` as
`ini` (`MONACO_LANG` in `monaco.js`); anything unknown falls back to `plaintext`. Highlighting is
skipped above 200 KiB / 4000 lines, and when the CDN is unreachable; the file then renders as
plain text with a gutter so `?line=` can still scroll (Save stays disabled in that branch).

The files pane's helpers are tested headlessly (`node --test` via `WebClientJsTest`), same shape
as `fold.js`.

## Project page: dependency graph (lazy)

On `#project/<projectId>`, the **Dependencies** control opens a panel that renders the module DAG with
ECharts (`series-graph`). Complex graphs are expensive server- and client-side, so:

- the panel is **closed by default**;
- `GET /api/project/graph` runs **only** when the panel opens (`module-dep-graph` mounts then);
  scope checkboxes (default **export / main / runtime**, same as `jk tree`) and a **Transitive**
  toggle (off by default; same as `jk tree -t` / `--transitive`) re-fetch with `scopes=` /
  `transitive=` — **debounced** (250 ms), so
  ticking several boxes in a row fires one request;
- the server caps transitive expansion (500 nodes / 2000 edges) and sets `truncated: true` when it
  clips; the panel shows a warning line so a clipped graph never reads as complete;
- node labels are **name only** (hover shows Group / Name / Version / Kind);
- `echarts.init` runs only after that payload lands;
- closing the panel (or leaving the project) unmounts the component (aborts in-flight fetch,
  disposes the chart).

Token-gated like other project metadata; without a session token the global auth dialog already
blocks the shell before the panel opens.

## Live updates

`api.js` opens one `EventSource` on `/api/events` (see [http.md](http.md#live-updates-get-apievents)).

A build event's frame is the event's wire record under `schema`, `type` and `jid` — the fields the
CLI's wire and `-O jsonl` carry, spelled the same (`millis`, `waitMillis`, `testClass`, …). A fold
that needs a field reads it from `d` under the record's name; there are no per-surface aliases.

| Path | Handler |
| --- | --- |
| Build activity | `fold.js` → run cards (hard bounds: `MAX_CARDS`, `MAX_OUTPUT_LINES`, `MAX_DIAGNOSTICS`), rendered as the console's live builds and the run page; `label` events drive the live detail (CLI tree-row parity) |
| `session` / `episode` / `action` / `decision` | The console (above) |
| `status` | The console's Engine card and Admin (CPU/RAM %, load avg, used GiB, heap) + the status bar's Builds Running; latches `engineEpoch` for hard-refresh on engine replace |
| `cache` | The Engine card's cache size (thin dual-surface frames); Admin loads the full breakdown via REST on view entry |

**Builds Running** stays in lockstep with the live cards (running card count while live). **API
calls** after the first status hydrate send `X-Jk-Engine-Epoch`; a **409** or a changed epoch on
status triggers a full page reload so static assets match the new engine. Classpath shell
files revalidate on every load (`Cache-Control: no-cache` plus a version+mtime `ETag`), so
that reload is not served a still-fresh copy of the previous jar.

While the stream is **live**, the SPA does **not** poll `/api/status` or `/api/cache` on a timer.
REST hydrate runs on load/reconnect. **Offline** status fallback uses stepped backoff (5 s → 30 s
cap) and pauses when the tab is hidden (`document.hidden`); EventSource stays open in a hidden tab.
A page that leaves (`pagehide`: reload, navigation, the back/forward cache) closes its EventSource,
and a page the cache restores (`pageshow` with `persisted`) reopens it and resyncs as after an
outage — a cached page holding its stream would keep one of the browser's six connections to the
engine for as long as the page is kept. Metrics are
**view-scoped** (Admin / Projects / project detail), not a global chrome poll. All REST GETs go
through a single-flight gate (`fetchOnce`) so reconnect cannot stack duplicate in-flight calls.
Relative “ago” labels use a local 1 s `now` tick only (no network).

**Hard refresh mid-build:** `GET /api/history` enriches in-flight rows with live `jid`,
`progress`, `startedAt`, residual/`R0`, and mid-flight `modules`/`tasks`. On SSE connect the
engine delivers **one** `run-snapshot` frame per running job to **that subscription only**
(phases + progress + ETA anchors + `startedAt`) — not a phase-by-phase replay, which filled the
256-frame SSE queue and left the SPA frozen for seconds while live ticks queued behind it.
`fold.js` applies the snapshot atomically, rebinds journal stubs (`h:…`), and then folds live
`workspace-progress` / task events in real time (TUI parity).

Build phase/progress is **inflicted SSE** at the same human cadence as the CLI wire
(`JK_WIRE_PROGRESS_MS`, default 500 ms for aggregate + plan ticks + output samples). The SPA
open-loops the bar/ETA between samples. Host vitals are sampled ~2 s and change-gated
server-side so unchanged free RAM does not repaint noise.

## Testing

`fold.js` is browser-free on purpose — pure functions of `(cards, event)` — and is tested
headlessly with `node --test`; so are the console's models (`console.js`, `needs.js`, `strip.js`:
`console.test.mjs`, `needs.test.mjs`, `strip.test.mjs`). The templates themselves are checked only
by `shell.test.mjs`'s name contract, not rendered. Fold-layer suites share `fold-harness.mjs` for the staged SPA
bindings so the export list has one owner. One JUnit wrapper, `WebClientJsTest`, runs every
`src/test/js/*.test.mjs`: it stages the SPA's modules in a `type:module` temp dir and hands each
one to Node as `JK_<NAME>_MJS` (plus `JK_APP_DIR`). The Node.js is the one `clients/web/jk.toml`
pins: JumpKick provisions it and puts it first on the test JVM's `PATH`, so the suites need no Node on
the host and never skip. See [CONTRIBUTING.md](../../CONTRIBUTING.md).

```bash
jk test -m clients/web
```

`pwa.test.mjs` covers the manifest, the registration and the install entry; `sw.test.mjs` runs
`sw.js` in a `node:vm` context with a fake worker global.

Server-side rendering/auth behavior is covered by `HttpStaticContentTest` and `HttpApiAuthTest` in
`server/engine` (both over the shared `HttpEngineServerHarness`).
