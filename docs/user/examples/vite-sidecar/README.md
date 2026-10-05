# Vite beside the JVM (`[node] dir`)

A JVM API and a single-page frontend have two live loops in development: the JVM restarting on a
source change, and Vite serving the page with hot module reload and `/api` proxied to the JVM. This
sample runs both from one command — `jk dev` — with no wrapper script, no second terminal and no
`npm` step of your own: the front end is a node build beside the JVM sources, so jk provisions
Node.js, installs it, builds it and runs its dev server.

```text
vite-sidecar/
  jk.toml                         # [application] main + [node] dir = "web"
  jk-lock.toml                    # committed; pins the dependencies and the Node.js release
  src/main/java/demo/Api.java     # JSON on /api/hello, port 8080
  src/test/java/demo/ApiTest.java # the API answers
  web/
    package.json                  # vite; `build` and `dev` scripts
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

[node]
version = 24
dir     = "web"

[dev]
ready = "http://localhost:8080/api/hello"

[test-dependencies]
junit-jupiter = "6.1.3"
```

`[node] dir = "web"` makes `web/` a node build of this module ([Node.js](../../node.md#the-two-shapes)):
`jk build` runs `npm ci` from the committed lock and `vite build`, and the bundle rides in the jar
under `static/`. Under `jk dev`, jk runs `web/`'s `dev` script beside the JVM as the dev server
`vite-sidecar-node`, probes Vite's port and makes it the front door — the sidecar a hand-written
`[dev.sidecars]` entry would have spelled out. `[dev] ready` is the JVM's own probe: the `ready ·`
line (and `dev-ready` under `--output json`) waits until `/api/hello` answers, after the first start
and after every restart, so a fetch on `dev-ready` never races the JVM.

## Run it

```console
$ jk dev --no-ansi
+ Watch Successful: Built - took 46.0s

jk watch run: watching src -- process restart on change. Ctrl-C stops.
vite-sidecar-node │ > dev
vite-sidecar-node │ > vite
vite-sidecar-node │   VITE v8.3.0  ready in 144 ms
vite-sidecar-node │   ➜  Local:   http://localhost:5173/
listening on http://localhost:8080
jk watch run: ready - http://localhost:5173 (java -cp target/classes demo.Api)
```

The first run downloads Node.js 24 if no install on the machine matches the lock, and installs
`web/`; later runs reuse both. The JVM's line (`listening on …`) is unprefixed — it is the module
being developed. Every line from Vite carries its name. Open the front door and the page fetches
`/api/hello` through Vite's proxy, so the browser sees one origin:

```console
$ curl -s http://localhost:5173/api/hello
{"message": "hello from the JVM"}
```

Edit `Api.java`: jk recompiles and restarts the JVM; Vite is untouched and keeps its module graph.
Edit `web/main.js`: Vite hot-reloads the page; the JVM is untouched. Ctrl-C stops the JVM and Vite
together — nothing is left on 8080 or 5173.

`jk dev --no-sidecars` runs the JVM alone; the `ready ·` line then carries `[dev] ready`'s address.
`jk dev --output json` turns every line into an event with its source (`sidecar-output`,
`app-output`) — [Machine output](../../machine-output.md#jk-dev). A dev server jk does not build is
declared by hand under `[dev.sidecars]` ([Run § Sidecars](../../run.md#sidecars-devsidecars)).

## Build and test

```console
$ jk build --no-ansi
jk: + Build > Build successful. Built target/vite-sidecar-0.0.1.jar - took 12.0s

$ jk test --no-ansi
+ Test Successful: Passed 1 test - took 29ms
```

The nightly runs this sample the way this page does — `jk dev`, a fetch of `/api/hello` through
Vite, Ctrl-C — and fails if anything outlives the session or a committed lock moved.

## Related

[Node.js](../../node.md) · [Run](../../run.md#sidecars-devsidecars) ·
[Machine output](../../machine-output.md#jk-dev) · [Projects](../../projects.md)
