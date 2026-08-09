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
tabs/refreshes reuse the stored token. Every `/api` call then sends `Authorization: Bearer <token>`
when present. On loopback the journal list (`GET /api/history`) is open without a token so a
hard-refresh still rehydrates Activity; mutations and sensitive reads stay token-gated (see
[http.md](http.md)).

**`jk web`** ensures the engine is running, prints the authenticated URL as an OSC-8 hyperlink, and
opens it in a browser (`$BROWSER` when set — word-split, so values with arguments work — else
`open` / `rundll32 url.dll,FileProtocolHandler` / `xdg-open`). Use `--no-open` to print only.

When a required token is **missing or invalid** (non-loopback binds, a rotated/stale stored token,
or a `401` from a gated call *while a token is held*), the SPA opens a **blocking authorization
dialog** and freezes the rest of the UI — it does not half-render open endpoints under a quiet
“Unauthorized” footer chip. The dialog explains how to recover: run `jk web` (opens a new
authenticated tab), open the printed URL, or paste the `#t=…` URL / token. Unauthorized is sticky
until a token is accepted; open loopback reads and SSE must not clear it.

**Tokenless loopback is watch-only, not an error** (JK-1530): the activity stream, journal list,
status and cache vitals stay live, while gated panels (metrics, engine log, configuration) simply
degrade — their `401`s never throw the dialog. Pasting a token upgrades the session in place.

## Dependencies: CDN, pinned, integrity-locked

Vue (and ECharts for history sparks **and** the Project-page module dependency graph) load from
the CDN, **version-pinned with an SRI `integrity` hash** — a CDN compromise must not be able to
script a page that can trigger builds. When bumping a pin, update the `integrity` hash in
`index.html` in the same change. There is no bundler and no npm build step: the shell ships as
static resources inside the engine jar.

## Project page: dependency graph (lazy)

On `#project/<dir>`, the **Dependencies** control opens a panel that renders the module DAG with
ECharts (`series-graph`). Complex graphs are expensive server- and client-side, so:

- the panel is **closed by default**;
- `GET /api/project/graph` runs **only** when the panel opens (`module-dep-graph` mounts then);
  scope checkboxes (default `main`) and a **Transitive** toggle (off by default) re-fetch with
  `scopes=` / `transitive=`;
- `echarts.init` runs only after that payload lands;
- closing the panel (or leaving the project) unmounts the component (aborts in-flight fetch,
  disposes the chart).

Token-gated like other project metadata; tokenless loopback watch-only shows a local error in the
panel rather than the global auth dialog.

## Live updates

`api.js` opens one `EventSource` on `/api/events` (see [http.md](http.md#live-updates-get-apievents)).

| Path | Handler |
| --- | --- |
| Build activity | `fold.js` → activity cards (hard bounds: `MAX_CARDS`, `MAX_OUTPUT_LINES`, `MAX_DIAGNOSTICS`); `label` events drive the live detail after the running phase node (CLI tree-row parity) |
| `status` | Header sysbox (CPU / RAM meters) + footer Builds Running / Engine Heap |
| `cache` | Footer **Cache** + **Store** (thin dual-surface frames); Status panels load full breakdown via REST on view entry |

While the stream is **live**, the SPA does **not** poll `/api/status` or `/api/cache` on a timer.
REST hydrate runs on load/reconnect. **Offline** status fallback uses stepped backoff (5 s → 30 s
cap) and pauses when the tab is hidden (`document.hidden`); EventSource stays open. Metrics are
**view-scoped** (Status / Projects / project detail), not a global chrome poll. All REST GETs go
through a single-flight gate (`fetchOnce`) so reconnect cannot stack duplicate in-flight calls.
Relative “ago” labels use a local 1 s `now` tick only (no network).

**Hard refresh mid-build:** `GET /api/history` enriches in-flight rows with live `requestId` /
`progress`; on SSE connect the engine re-publishes `request-start` + current `workspace-progress`
for every still-running job. `fold.js` rebinds journal stubs (`h:…`) to the live request id so the
bar, steps, ETA, and finish events resume — no need to wait for the run to end.

Build phase/progress must stay **near-realtime** (inflicted SSE). Host vitals are sampled ~2 s and
change-gated server-side so unchanged free RAM does not repaint noise.

## Testing

`fold.js` is browser-free on purpose — pure functions of `(cards, event)` — and is tested
headlessly with `node --test` via the `WebClientFoldTest` JUnit wrapper (it stages `fold.js` as
`fold.mjs` and passes the path in `JK_FOLD_MJS`):

```bash
./gradlew :web:test
```

Server-side rendering/auth behavior is covered by `HttpEngineServerTest` in `server/engine`.
