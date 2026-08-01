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

Token bootstrap rides the URL fragment: `jk engine status` prints a dashboard link ending in
`#t=<token>`; on load `api.js` stashes the token in `sessionStorage` and scrubs the fragment from
the address bar (fragments never leave the browser). Every `/api` call then sends
`Authorization: Bearer <token>`. Auth tiers (read-only vs token-gated mutations) are defined in
[http.md](http.md).

## Dependencies: CDN, pinned, integrity-locked

Vue (and ECharts for the Projects tab) load from the CDN, **version-pinned with an SRI
`integrity` hash** — a CDN compromise must not be able to script a page that can trigger builds.
When bumping a pin, update the `integrity` hash in `index.html` in the same change. There is no
bundler and no npm build step: the shell ships as static resources inside the engine jar.

## Live updates

`api.js` subscribes to the `/api/events` SSE stream; `fold.js` reduces events into the activity
feed with hard bounds (`MAX_CARDS`, `MAX_OUTPUT_LINES`, `MAX_DIAGNOSTICS`) so a long-lived tab
cannot grow without limit.

## Testing

`fold.js` is browser-free on purpose — pure functions of `(cards, event)` — and is tested
headlessly with `node --test` via the `WebClientFoldTest` JUnit wrapper (it stages `fold.js` as
`fold.mjs` and passes the path in `JK_FOLD_MJS`):

```bash
./gradlew :web:test
```

Server-side rendering/auth behavior is covered by `HttpEngineServerTest` in `server/engine`.
