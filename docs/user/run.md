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

### Debug the app JVM

```bash
jk run --debug-jvm                      # localhost:5005, suspended until a debugger attaches
jk run --debug-jvm=0 . -- args…         # a free port; use `=` when args follow
jk run --debug-jvm=6006,suspend=n
```

The app JVM starts with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=…`
as its first option; nothing else jk forks for the build sees the flag. The address is printed
on stderr right before the program starts; attach a stock remote debugger (IDEA *Remote JVM
Debug*, VS Code `java` `attach`) — recipe in [IDE and BSP](ide.md#debugging-through-bsp).
A native image is passed over under `--debug-jvm` (JDWP needs a JVM), and a device artifact
(an APK) cannot be debugged this way. The flag applies to jk project runs, not to tools,
scripts or coordinates.

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

| Key | Meaning | Default |
|---|---|---|
| `command` | A string split like a shell would — whitespace separates, quotes group, and a backslash is literal unless it sits right before a quote, a space, or another backslash, so `C:\tools\node.exe run dev` splits as written; nothing is expanded and no shell runs. When the string rules get in the way, write the argv array: `command = ["npm", "run", "dev"]` | required |
| `cwd` | Working directory, relative to the manifest that declares the sidecar | `"."` |
| `env` | Values laid over the inherited environment. `.env` at the workspace root and the module apply first, the way they do for every process jk spawns | none |
| `ready` | An HTTP(S) URL polled every 250 ms (HTTP/1.1) until it answers 2xx/3xx. A `localhost` URL is tried on both `127.0.0.1` and `[::1]` — Node binds only `::1` on many hosts | none |
| `ready-pattern` | A regex matched against the sidecar's stdout and stderr lines — the other probe; a sidecar has one or the other. With neither, one second alive is ready | none |
| `ready-timeout` | `"60s"`, `"2m"`, `"500ms"`, or a bare number of seconds. A probe that times out **fails the session** — a broken dev server is not a warning | `"60s"` |
| `front-door` | Print this sidecar's `ready` URL once everything is up: `jk watch run: ready · http://localhost:5173 (java -cp … com.example.App)`. With no front-door sidecar the app is the front door, and the `ready ·` line returns after every process restart of the app | `false` |
| `restart` | `never` (the exit is reported once, the session continues) or `on-exit` (restart with backoff, up to five failures in a row; a run that passed its probe or stayed up 30 s starts the count over) | `"never"` |

The table is also in [`jk.toml.schema.json`](jk.toml.schema.json), and
[examples/vite-sidecar](examples/vite-sidecar/) is the whole thing running: a JVM API on 8080, Vite
on 5173 proxying `/api`, one `jk dev`.

Sidecars start once per session and survive the app's restarts — Vite watches its own tree. Ctrl-C
stops the app and every sidecar together, along with everything they spawned. Editing
`[dev.sidecars]` mid-session is reported, not applied — restart `jk dev`. `jk dev --no-sidecars`
runs the app alone for one invocation.

### Output

The app's lines are its own — it is the module being developed — and every sidecar line is
prefixed `web │ ` with the name in a colour chosen from the name, so `web` is the same colour in
every session and two sidecars never share one. Lines arrive in the order the processes write
them, stdout and stderr alike; a carriage-return progress line (a bundler's percentage, a spinner)
shows as the row's final state rather than as every repaint. `--no-ansi`, `JK_NO_ANSI=1`, and
`NO_COLOR` turn the colour off and leave the prefix.

```text
jk watch run: watching src — process restart on change. Ctrl-C stops.
web │
web │   VITE v8.3.0  ready in 212 ms
web │
web │   ➜  Local:   http://localhost:5173/
jk watch run: ready · http://localhost:5173 (java -cp … demo.Api)
listening on http://localhost:8080
web exited with 1
```

Under `--output json` every line is an event with its source — `sidecar-output` with `name`,
`stream`, and `line`; `sidecar-started`, `sidecar-ready`, `sidecar-exited` for the lifecycle;
`dev-ready` for the `ready ·` line — and the app is piped too, as `app-output`, so stdout stays one
JSONL stream. Field by field:
[Machine output](machine-output.md#jk-dev).

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
