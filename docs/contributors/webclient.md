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
| `fold.js` | Folds `/api/events` into activity cards — pure `(cards, event)` functions |
| `outcome.js` | Derived state of a folded card: outcome badge, phase chain, module order |
| `label.js` | Step detail and the colour segments of a Java type / member / JDK-download label |
| `failure.js` | Diagnostics → the test and compile failure report models (CLI parity) |
| `cards.js` | The Activity feed's per-card methods: progress, ETA, badge, diagnostics |
| `status.js` | The Status view: the `/api/{status,cache,metrics,log,config}` reads and its meters |
| `projects.js` | The Projects tab and Project page: history records grouped per project, and the run the page follows or pins |
| `sessions.js` | Who asked: origin labels, the Activity feed grouped by session, the followed-or-pinned run, the pin route |
| `wizard.js` | New Project and the workspace directory browser |
| `icons.js` | `<jk-icon>`: the inline-SVG glyph set |
| `phase.js` | `<phase-chain>`: the coarse plan-phase strip on a card |
| `report.js` | `<fail-report>`: one failure body for the compact card and the module branch |
| `chart.js` | `<build-bars>` and `<module-dep-graph>`: the two ECharts surfaces |
| `code.js` | `<code-view>`: the `#project/<id>/files` pane |
| `monaco.js` | Monaco and the preview CDNs: version pins, SRI hashes, AMD parking, editor options |
| `preview.js` | The Preview half: markdown, mermaid, graphviz, asciidoc, d2, images |
| `paths.js` | Workspace paths: extension → language, previewable kinds, markdown href resolution |
| `route.js` | The hash router: `#project/<id>/files/<rel>?line=…` parsed and rendered |
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
dialog** and freezes the rest of the UI — it does **not** paint Activity, vitals, or a quiet
“No activity yet” empty state. There is no paste field and no dismiss: recover by running
`jk web` (opens an authenticated tab and prints a clickable `#t=…` URL if the browser cannot
open). The dialog shows a console-style `jk web` snippet with a copy button.

## Tooltips

Native HTML `title=` is **OS chrome** (harsh white-on-black, no radius) and cannot be styled.
Hover help uses `data-tip="…"` (or `:data-tip`) plus `tip.js`, which paints a fixed `.jk-tip-float`
panel with the same soft Jk Dark shell as retention / sysbox / the millis I/O tip. Do not reintroduce
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

## Project routes

Project detail is `#project/<projectId>` — a durable opaque id (from `project-id` in `jk-lock.toml`,
or hybrid git/path resolution), **not** an absolute filesystem path. `GET /api/project?project=<id>`
resolves the last-known checkout path via `identity.toml` under the builds state dir.

Source files hang off the same route:

```text
#project/<projectId>                 (follows the project's newest run)
#project/<projectId>/run/<n>         (pinned to build #n — a history row click; Follow newest clears it)
#project/<projectId>/files
#project/<projectId>/files/src/Main.java?line=42
```

The **focused run** panel above the build history renders `projectRun` (`projects.js`): the
pinned record when the route names a build number the project has, else the newest — a live
card first, then the newest journal record — folded through `historyCard` so the same outcome
rule and module rows apply as on the Activity feed. `focusedRun` in `sessions.js` is the pure
rule and is tested headlessly; the panel markup itself is checked only by `shell.test.mjs`'s
name contract, not rendered.

The cyan **code** control (**View/edit this codebase**) sits next to **Build** and opens
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
case-insensitively. `buildFileTree` **compacts single-child directory chains** into one row
(`main/java/cc/jumpkick`) so a Java source file is a few rows deep instead of a dozen; a compacted
node keys off its deepest path, which is what `ancestorDirs` yields for files under it. Folders are
closed by default, except the ancestors of the open file — so a `?line=` deep link or a fail-report
jump lands with its file revealed and selected. `visibleRows` flattens only the open parts, so the
whole tree is one non-recursive `v-for` (up to 5000 paths, no recursive components). Typing in the filter
box switches to a **flat list of matching full paths** — the tree is for browsing, the filter
answers like GitHub's file finder; every row carries its full path as a `data-tip`.

The pane lists `GET /api/project/files`, reads `GET /api/project/file`, writes
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
  markdown links (`[Status](docs/architecture.md)`) rewrite to `#project/…/files/…` so they open
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

| Path | Handler |
| --- | --- |
| Build activity | `fold.js` → activity cards (hard bounds: `MAX_CARDS`, `MAX_OUTPUT_LINES`, `MAX_DIAGNOSTICS`); `label` events drive the live detail after the running phase node (CLI tree-row parity) |
| `status` | Header sysbox (capacity + CPU/RAM % + load avg / used GiB) + footer Builds Running / Engine Heap; latches `engineEpoch` for hard-refresh on engine replace |
| `cache` | Footer **Cache** + **Store** (thin dual-surface frames); Status panels load full breakdown via REST on view entry |

**Builds Running** stays in lockstep with Live activity (running card count while live). **API
calls** after the first status hydrate send `X-Jk-Engine-Epoch`; a **409** or a changed epoch on
status triggers a full page reload so static assets match the new engine. Classpath shell
files revalidate on every load (`Cache-Control: no-cache` plus a version+mtime `ETag`), so
that reload is not served a still-fresh copy of the previous jar.

While the stream is **live**, the SPA does **not** poll `/api/status` or `/api/cache` on a timer.
REST hydrate runs on load/reconnect. **Offline** status fallback uses stepped backoff (5 s → 30 s
cap) and pauses when the tab is hidden (`document.hidden`); EventSource stays open. Metrics are
**view-scoped** (Status / Projects / project detail), not a global chrome poll. All REST GETs go
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
headlessly with `node --test`. Fold-layer suites share `fold-harness.mjs` for the staged SPA
bindings so the export list has one owner. One JUnit wrapper, `WebClientJsTest`, runs every
`src/test/js/*.test.mjs`: it stages the SPA's modules in a `type:module` temp dir and hands each
one to Node as `JK_<NAME>_MJS` (plus `JK_APP_DIR`). Node is required at the version in
[`.nvmrc`](../../.nvmrc) — a missing `node` fails the build rather than skipping; opt out
deliberately with `JK_WEB_JS_SKIP=1`. See [CONTRIBUTING.md](../../CONTRIBUTING.md).

```bash
jk test -m clients/web
```

Server-side rendering/auth behavior is covered by `HttpStaticContentTest` and `HttpApiAuthTest` in
`server/engine` (both over the shared `HttpEngineServerHarness`).
