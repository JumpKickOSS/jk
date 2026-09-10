# Run, watch, and REPL

## `jk run`

```bash
jk run -- args…
```

At a workspace root, runs the module that has `[application] main`. Needs a main class.
Launches from `target/classes/` (not a packaged jar), so [AOT cache](build.md#jvm-startup-cache-app-aot--cds)
is not available on this path.

`jk run` is a **wrapper** around the process: Ctrl-C is owned by JumpKick (cancels the
engine job, then the process). It is not `exec`-replace.

## Live loops (`jk watch` / `jk dev`)

One mechanism: re-run a verb when sources change. **`jk dev` is only an alias for
`jk watch run`.**

Watches **`src/`**, **`test/src/`**, and project-root **`jk.toml`** by default — not
`target/`, `out/`, `build/`, or VCS trees. Editor save bursts are debounced (default
**150 ms**).

```bash
jk watch compile
jk watch test
jk watch build               # package loop (--skip-tests)
jk watch run
jk dev                       # same as jk watch run
jk dev -- --port=8080        # app args after --
jk watch test --debounce-ms 300
```

`watch run` / `dev` use classes-dir execution, Spring Boot DevTools when present,
otherwise process restart. Android projects redeploy via the packaging plugin.

## Sidecars (`[dev.sidecars]`)

A service with a frontend has two live loops: the JVM, and the frontend's own dev server
(Vite, webpack) with hot reload and `/api` proxied to the JVM. `jk dev` runs both:

```toml
# app/jk.toml
[dev.sidecars]
web  = { command = "npm run dev", cwd = "../web", ready = "http://localhost:5173", front-door = true }
docs = { command = ["mkdocs", "serve"], env = { PORT = "8001" }, ready-pattern = "Serving on", restart = "on-exit" }
```

| Key | Meaning |
|---|---|
| `command` | A string split like a shell would (quotes group; nothing is expanded, no shell runs) or an argv array |
| `cwd` | Working directory, relative to the manifest that declares the sidecar. Default `.` |
| `env` | Values laid over the inherited environment. `.env` at the workspace root and the module apply first, the way they do for every process jk spawns |
| `ready` | An HTTP(S) URL polled every 250 ms (HTTP/1.1) until it answers 2xx/3xx. A `localhost` URL is tried on both `127.0.0.1` and `[::1]` — Node binds only `::1` on many hosts |
| `ready-pattern` | A regex matched against the sidecar's output lines — the other probe; a sidecar has one or the other. With neither, one second alive is ready |
| `ready-timeout` | `"60s"` (default), `"2m"`, `"500ms"`, or seconds. A probe that times out **fails the session** — a broken dev server is not a warning |
| `front-door` | Print this sidecar's `ready` URL once everything is up: `jk watch run: ready · http://localhost:5173 (java -cp … com.example.App)` |
| `restart` | `never` (default: the exit is reported once, the session continues) or `on-exit` (restart with backoff, up to five failures in a row) |

Sidecars start once per session and survive the app's restarts — Vite watches its own tree. Their
output is interleaved with the app's, each line prefixed `web │ `. Ctrl-C stops the app, then every
sidecar and everything a sidecar spawned. `jk dev --no-sidecars` runs the app alone.

The workspace root may declare `[dev.sidecars]` too; `jk dev` in a module unions root and module
entries, the module winning a name clash. `jk run`, `jk build`, and `jk test` never read the table,
and nothing about a sidecar enters an action key: `jk.toml` and `jk-lock.toml` still fully describe
the artifact.

## jshell / REPL

```bash
jk jshell                    # build --skip-tests if needed, then jshell on compile classpath
jk jshell --no-build         # existing target/classes + lock deps only
jk repl                      # alias
```

Requires a full JDK with `jshell` on `JAVA_HOME` / `java.home`. Run from a **module**
directory (not a pure workspace root). Extra args after the verb are forwarded to jshell.

## Related

[Build](build.md) · [Projects](projects.md) · [Aliases](aliases.md)
