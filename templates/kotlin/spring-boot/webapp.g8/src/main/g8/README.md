# $name$

A Kotlin Spring Boot API and a Vite + React single-page app, in one jk workspace of two modules:

```text
app/   the service: GET /api/hello, and the SPA served from classpath:static with a fallback
web/   the front end: Vite + React + TypeScript; no JVM sources, only the bundle it produces
```

## The classpath seam

`web` is a **resource-only module**. Its `jk.toml` declares no sources, so with the simple layout
`web/resources/` is its resource root — and `web/vite.config.ts` sets `build.outDir` to
`resources/static`, so `npm run build` writes the bundle straight there. `app` depends on the module
(`web = { workspace = true }`), which puts the bundle on the app's classpath as a sibling jar with
`static/` at its root. Spring Boot serves `classpath:/static/` by default, so `index.html` and the
hashed `assets/` are reachable with no copy step between the two builds. jk refuses cross-module file
copies on purpose; a dependency edge is the sanctioned way for one module's output to reach another.

`web/resources/` is build output and is gitignored. Without it — no `npm` on the machine — the app
still builds and its API still serves; the module's jar is simply empty, and page requests are 404s.

`SpaFallback` handles client-side routing: a `GET` (or `HEAD`) that reaches no handler and no static
file, sits outside `/api`, has no file extension in its last segment, and accepts HTML gets
`index.html` with `Cache-Control: no-store`, so a deep link or a reload lands in the SPA's router.
Everything else that misses — `/api/...`, a missing `.png`, a `POST` — is a 404 problem detail. `/`
itself is Boot's welcome page, the same `index.html`.

## Build

```bash
(cd web && npm ci && npm run build)   # the bundle, into web/resources/static
jk build                              # both modules; app's tests drive it over HTTP
jk run -m app                         # http://localhost:8080
```

The app's tests check `/api/hello`, a `/api` miss, and — when the bundle is present — that a client
route returns the shell. `jk image -m app` builds an OCI image on a JRE base with an AOT cache trained
at build time (`[image]` in `app/jk.toml`).

## Develop

```bash
cd app && jk dev
```

`jk dev` in `app/` recompiles and restarts the JVM on change **and** runs the Vite dev server beside
it as the `web` entry of `[dev.sidecars]`: `npm run dev` in `../web`, with `/api` proxied to `:8080`
and hot reload for the front end. jk waits for `http://localhost:5173` to answer before printing the
front door, prefixes the sidecar's output with `web │`, and stops both on Ctrl-C.
`jk dev --no-sidecars` runs the app alone.

## Tiers and guards

No Kotlin compiler plugins are wired: `Application` is `open` itself because `@SpringBootApplication`
is a `@Configuration` Spring subclasses at runtime.


`jk test` is the fast tier; `jk test --profile integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost. House rules come from the `spring` pack plus the `monorepo` pack's
workspace-shaped rules (`jk-guards.toml`); `jk guard explain` lists them, and a failure's `Instead:`
line says what to do.
