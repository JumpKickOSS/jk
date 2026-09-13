# Vite beside the JVM (`[dev.sidecars]`)

A JVM API and a single-page frontend have two live loops in development: the JVM restarting on a
source change, and Vite serving the page with hot module reload and `/api` proxied to the JVM. This
sample runs both from one command — `jk dev` — with no wrapper script and no second terminal.

```text
vite-sidecar/
  jk.toml                         # [application] main + one [dev.sidecars] entry
  jk-lock.toml                    # committed; jk build never re-resolves
  src/main/java/demo/Api.java     # JSON on /api/hello, port 8080
  src/test/java/demo/ApiTest.java # the API answers
  web/
    package.json                  # vite; `npm run dev` is the sidecar's command
    package-lock.json             # committed, like jk-lock.toml
    vite.config.js                # port 5173, /api → http://localhost:8080
    index.html, main.js           # fetches /api/hello on the same origin
```

The manifest:

```toml
group   = "com.example"
name    = "vite-sidecar"
version = "0.0.1"
java    = 25

[application]
main = "demo.Api"

# The frontend's own dev server runs beside the JVM under `jk dev` — and only there. `jk run`,
# `jk build`, and `jk test` never read this table; nothing about it enters the action cache.
[dev.sidecars]
web = { command = "npm run dev", cwd = "web", ready = "http://localhost:5173", front-door = true }

[test-dependencies]
junit-jupiter = "=6.1.3"
```

`command` is split like a shell would and run without one; `cwd` is relative to this manifest;
`ready` is polled until Vite answers; `front-door = true` makes 5173 the URL jk prints when the
whole stack is up. Every key and its default: [Run — Sidecars](../../run.md#sidecars-devsidecars).

## Run it

Once per checkout, install the frontend's dependencies; then one command owns the stack.

```console
$ (cd web && npm ci)

$ jk dev --no-ansi
+ Watch Successful: Built - took 470ms

jk watch run: watching src -- process restart on change. Ctrl-C stops.
web |
web | > dev
web | > vite
web |
listening on http://localhost:8080
web |
web |   VITE v8.3.0  ready in 488 ms
web |
web |   ➜  Local:   http://localhost:5173/
web |   ➜  Network: use --host to expose
jk watch run: ready - http://localhost:5173 (java -cp target/classes/main demo.Api)
```

The JVM's line (`listening on …`) is unprefixed — it is the module being developed. Every line
from Vite carries `web │ `, the name in a colour of its own on a terminal (`--no-ansi` above turns
the colour off and the bar into `|`). Open the front door and the page fetches `/api/hello`
through Vite's proxy, so the browser sees one origin:

```console
$ curl -s http://localhost:5173/api/hello
{"message": "hello from the JVM"}
```

Edit `Api.java`: jk recompiles and restarts the JVM; Vite is untouched and keeps its module graph.
Edit `web/main.js`: Vite hot-reloads the page; the JVM is untouched. Ctrl-C stops the JVM and Vite
together — nothing is left on 8080 or 5173.

`jk dev --no-sidecars` runs the JVM alone. `jk dev --output json` turns every line into an event
with its source (`sidecar-output`, `app-output`) and the lifecycle into `sidecar-started`,
`sidecar-ready`, `sidecar-exited` — [Machine output](../../machine-output.md#jk-dev).

## What the sidecar does not touch

```console
$ jk build --no-ansi
jk: + Build > Build successful. Built target/vite-sidecar-0.0.1.jar - took 6.9s

$ jk test --no-ansi
+ Test Successful: Passed 1 test - took 29ms
```

Neither command reads `[dev.sidecars]`; the jar and the lockfile are the same with the table and
without it. The nightly runs this sample the way this page does — `npm ci`, `jk dev`, a fetch of
`/api/hello` through Vite, Ctrl-C — and fails if anything outlives the session.

## Related

[Run](../../run.md#sidecars-devsidecars) · [Machine output](../../machine-output.md#jk-dev) ·
[Projects](../../projects.md)
