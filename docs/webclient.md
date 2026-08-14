# The engine dashboard (web client)

The resident engine serves a small single-page dashboard from `clients/web`
(`src/main/resources/web/`). It is a **thin renderer over the HTTP surface documented in
[http.md](http.md)** — the engine owns all build logic; the page owns presentation only. This is
the doc the shell's source files cite.

## Layout

| File | Role |
| --- | --- |
| `index.html` | The shell: in-DOM Vue template, CDN pins, brand chrome |
| `app.js` | Vue app + components (`<jk-icon>` inline SVG iconography, tabs, cards) |
| `api.js` | **The only file that talks HTTP**: token bootstrap, fetch wrappers, SSE client |
| `fold.js` | Folds `/api/events` into activity cards — pure `(cards, event)` functions |
| `style.css` | Chrome; JetBrains Mono via Google Fonts |

## Auth

Token bootstrap rides the URL fragment: `jk web` (and `jk engine status`) print a dashboard link
ending in `#t=<token>`; on load `api.js` stashes the token in `sessionStorage` and `localStorage`
and scrubs the fragment from the address bar (fragments never leave the browser). Later
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
`index.html` in the same change. There is no bundler and no npm build step: the shell ships as
static resources inside the engine jar.

**Monaco is the one partial exception.** Its version is pinned and `loader.js` carries SRI
(`MONACO_LOADER` in `code.js`), but the loader then fetches `editor.main.js`, `editor.main.css` and
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
#project/<projectId>
#project/<projectId>/files
#project/<projectId>/files/src/Main.java?line=42
```

The cyan folder **Browse this codebase** control (same icon button as Activity’s workspace
picker) sits next to **Build** and opens `#project/<id>/files` (tree). It is hidden *on* the files
pane — you are already browsing there — which is also where the header's back control drops its
label: a bare chevron that goes up one level to `#project/<id>`, not out to the project list.
Selecting a file appends the workspace-relative path as extra hash segments (each `encodeURIComponent`;
`/` stays a separator). Optional `?line=` is a 1-based highlight for fail-report jumps.
Test-failure paths (module-relative) join `rel(checkout, module.dir)` + `rep.file` first;
basename-only paths stay text.

The tree pane itself is GitHub-shaped: collapsible folders (chevron + `folder`/`folder-open`
glyph) above files (generic `file` glyph), directories before files at every level, names sorted
case-insensitively. `buildFileTree` **compacts single-child directory chains** into one row
(`main/java/cc/jumpkick`) so a Java source file is a few rows deep instead of a dozen; a compacted
node keys off its deepest path, which is what `ancestorDirs` yields for files under it. Folders are
closed by default, except the ancestors of the open file — so a `?line=` deep link or a fail-report
jump lands with its file revealed and selected. `visibleRows` flattens only the open parts, so the
whole tree is one non-recursive `v-for` (2000 paths, no recursive components). Typing in the filter
box switches to a **flat list of matching full paths** — the tree is for browsing, the filter
answers like GitHub's file finder; every row carries its full path as a `data-tip`.

The pane lists `GET /api/project/files` and reads `GET /api/project/file` (see [http.md](http.md)).
Monaco **0.56.0** loads lazily from unpkg (AMD loader SRI-pinned; see the CDN section) only when
`/files` is open, and renders a **read-only** editor in the built-in **Visual Studio Dark**
(`vs-dark`) theme: Monaco's own line numbers, folding, minimap and find widget, no context menu or
suggestions. The theme is registered as `jk-vs-dark` — vs-dark inherited verbatim with a single
override, `editor.background` read from style.css's `--console-bg`, so a source pane reads as the
same surface as the console tail and log panels instead of VS Code's `#1e1e1e`. `?line=` is a
whole-line decoration (`.code-line-hl`) plus `revealLineInCenter`, not a selection. Monaco ships no
Groovy or TOML grammar, so `.groovy` tokenizes as `java` and `.toml` as
`ini` (`MONACO_LANG` in `code.js`); anything unknown falls back to `plaintext`. Highlighting is
skipped above 200 KiB / 4000 lines, and when the CDN is unreachable; the file then renders as
plain text with a gutter so `?line=` can still scroll.

`code.js` is tested headlessly (`node --test` via `WebClientCodeTest`), same shape as `fold.js`.

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
status triggers a full page reload so static assets match the new engine.

While the stream is **live**, the SPA does **not** poll `/api/status` or `/api/cache` on a timer.
REST hydrate runs on load/reconnect. **Offline** status fallback uses stepped backoff (5 s → 30 s
cap) and pauses when the tab is hidden (`document.hidden`); EventSource stays open. Metrics are
**view-scoped** (Status / Projects / project detail), not a global chrome poll. All REST GETs go
through a single-flight gate (`fetchOnce`) so reconnect cannot stack duplicate in-flight calls.
Relative “ago” labels use a local 1 s `now` tick only (no network).

**Hard refresh mid-build:** `GET /api/history` enriches in-flight rows with live `requestId`,
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
headlessly with `node --test` via the `WebClientFoldTest` JUnit wrapper (it stages `fold.js` as
`fold.mjs` and passes the path in `JK_FOLD_MJS`):

```bash
./gradlew :web:test
```

Server-side rendering/auth behavior is covered by `HttpEngineServerTest` in `server/engine`.
