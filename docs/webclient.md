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
`jk web` (opens an authenticated tab) or by opening the `#t=…` URL printed by
`jk engine status` (or the same link copied into the address bar).

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

## Project page: dependency graph (lazy)

On `#project/<dir>`, the **Dependencies** control opens a panel that renders the module DAG with
ECharts (`series-graph`). Complex graphs are expensive server- and client-side, so:

- the panel is **closed by default**;
- `GET /api/project/graph` runs **only** when the panel opens (`module-dep-graph` mounts then);
  scope checkboxes (default **export / main / runtime**, same as `jk tree`) and a **Transitive**
  toggle (off by default) re-fetch with `scopes=` / `transitive=`;
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
