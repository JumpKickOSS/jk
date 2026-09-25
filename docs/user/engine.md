# Engine

Build work runs in a **resident JVM engine** (memory-capped, default heap on the order of
**256 MiB**). The CLI is a slim native client. The engine starts automatically on first
use.

```bash
jk engine status          # also prints dashboard URL + MCP URL
jk engine stop
jk engine start
jk jobs                   # running + recent jobs (jid); aliases: builds, activity
jk cancel                 # this project’s live jobs; alias: kill
jk cancel 42              # by jid
jk web                    # open the dashboard
```

You rarely need these. Status, MCP, and the dashboard share one HTTP listener (loopback,
token-gated `/api/*` by default).

## Jobs and cancel

Every engine-hosted operation gets a **jid** at admission.

| Action | Behavior |
|--------|----------|
| **Ctrl-C** | Cancels the engine job(s) for this project, then exits (bounded teardown; `JK_CANCEL_GRACE_MS`) |
| **`jk cancel`** | All live jobs for the current project directory |
| **`jk cancel <jid>`** | That job (unknown/finished jid → clear error) |
| **Web / MCP** | `POST /api/cancel` with `{"jid":N}` · MCP `cancel` |

A second same-kind build in the same checkout is rejected: **Build #N already running**.
Worktrees are different slots. The rule holds across engines too: the checkout's
`target/.jk/build.lock` is held for the job's lifetime, so another engine on the machine sees
the running build and refuses with its number.

### Live and queued jobs

`jk engine status` prints the engine's version and, when the engine was installed by
`jk install` from a checkout, a `Source` row naming that checkout — the one whose workers this
engine forks (`installSource` in `--output json` and `GET /api/status`; see
[install](install.md#on-disk-layout)). It lists every job the engine holds under its `Live Jobs`
and `Queued` rows — jid, kind, project directory, when it was admitted or arrived, its live worker
processes and how long since its last task event:

```
 Live Jobs: 1
            #739 test /home/me/app · since 22:36 (2h 05m) · 1 worker · last event 3m ago
 Queued...: 2 (waiting for engine memory)
            #741 build /home/me/lib · behind 0 · waiting 12m
            #742 format /home/me/tool · behind 1 · waiting 4m
```

`--output json` carries the same rows as `jobs` (`jid`, `kind`, `dir`, `state` = `live` |
`queued`, `since`, `workers`, `lastEventAt`, `ahead`); `GET /api/status`, the socket status
frame, the dashboard's live `status` frame and the MCP `status` tool carry the identical array,
and `POST /api/cancel {"jid":N}` takes any jid it lists.

### Queued for memory

The engine admits a build, test or lock job only when its own heap can hold it beside the jobs
already running; otherwise the job **queues** — first come, first served — until one of them
finishes. A queued job is not an error and never dies for lack of memory: the CLI prints
`waiting for engine memory (2 jobs ahead); live: test /home/me/app since 22:36` when it joins the
queue and `queued behind 2 jobs for 5m, live: test /home/me/app since 22:36` once a minute while
it waits, then proceeds as usual; `jk engine status` shows `Queued: N (waiting for engine
memory)` and the rows above while any job waits (`--output json`: `queuedBuildPlans`, `jobs`);
the dashboard's Activity feed shows the card as *Queued for memory* until it turns live. Ctrl-C
and `jk cancel` dequeue a waiting job the same way they cancel a running one.

The cost of a job is estimated from what it parses whole — the workspace `jk-lock.toml` and the
project's metrics ledger; for `jk import`, the reactor's `pom.xml` files, build outputs pruned —
so a small project queues behind a large one only when the heap is genuinely short. A lock is
sized by the larger of the lock on disk and 64 KiB per distinct dependency the workspace's
manifests declare, so a first lock of a large reactor is sized before it starts. What a build
reads per classpath jar is bounded by design — a jar's ABI is keyed from its central directory a
window of names at a time — so the size of the largest jar on a classpath is not part of the
estimate. An idle engine admits any job the heap holds alone. Raising `[engine] max-heap-mb` lets more jobs run at once;
the default cap runs one build of a large workspace at a time.

A job whose estimate exceeds the whole cap is **refused at once** rather than admitted to die of
`OutOfMemoryError` and take the engine's other jobs with it: `a lock of /home/me/reactor is
estimated to need 300 MiB of engine heap and the engine's cap is 256 MiB — set [engine]
max-heap-mb in ~/.jk/config.toml (or JK_ENGINE_MAX_HEAP_MB) to at least 332, then jk engine stop`.
The engine cannot grow its own heap; the next command starts one under the new cap.

**Fairness.** One long job never holds the whole budget for hours. Two rules admit past the
plain arithmetic, both only when the host itself has memory to spare (256 MiB beyond the job's
estimate, read from the OS's available-memory figure):

- A **brief job** — any kind other than `build`, `test`, `compile`, `native` and `image`:
  `format`, `guard`, `lock`, `explain`, `import`, `tree`, `update`, … — is judged by the ledger
  of estimates alone, never by the heap a long job has committed (mostly garbage a collection
  returns), and does not wait its turn behind queued long jobs. `jk format --check` runs beside a
  running `jk test`.
- The **head of the queue**, whatever its kind, is admitted after **five minutes** of waiting,
  even when the estimate says the heap is short.

A job that has waited `queue-wait-ms` (default one hour) without being admitted **gives up**
with an error that names the jobs ahead of it and the live job holding the heap —
`gave up after waiting 1h 00m for engine memory behind 2 jobs; live: test /home/me/app (jid 739)
since 22:36` — rather than a connection that closes without a result; `0` waits without bound.

**Silence.** When a live job has emitted no task event for thirty minutes the engine writes one
log line naming it — jid, kind, directory, the silence and its live worker processes — and
another after each further thirty minutes. It never cancels the job: a single test JVM working
through a long suite is silent and healthy. `jk cancel <jid>` is the operator's call.

## HTTP and MCP

HTTP is **on by default** (loopback). Turn it off with `[http] enabled = false` or
`JK_HTTP_ENABLED=false`. MCP rides the same server; disable with `[mcp] enabled = false`
without killing the dashboard.

A resident engine **does not idle out**. It exits on `jk engine stop`, version-skew
replacement, or (if displaced/orphaned) after draining. Details of lifetime and auth:
[contributor HTTP](../contributors/http.md). User-facing dashboard: [Web](web.md).
MCP: [MCP](mcp.md). Token, bind, and reporting: [Security](security.md).

### A silent engine

A client that connects to the engine's socket and gets no handshake within two seconds does not
conclude the engine is dead. It probes again — four probes over about eight seconds — and then
reads the process the pid file names for the life it shows without a reply: how long it has been
up, how many worker processes it has forked, whether its CPU time is still advancing. An engine
younger than two minutes is loading; one with workers, or whose CPU advances between readings, is
busy — a coordinator mid-build answers its socket late, not never. Either is left alone: the client
keeps probing for thirty seconds plus fifteen per worker (three minutes at most) and, if the
handshake still has not come, fails —

```
the build engine (pid 2945622) is alive and busy — up 3m 12s, 6 worker processes — but has not
answered a handshake in 2m 00s; it is not displaced, since that would kill the jobs it runs for
other terminals. Retry in a moment; `jk engine status` shows its jobs, and `jk engine stop --now`
stops it regardless
```

— rather than kill the jobs the engine runs for other terminals. Only a holder that shows no life
across two readings — past its startup, no workers, no CPU — is displaced: hard-killed once and
replaced, with `displacing unresponsive engine (pid N)` in the engine log. `jk engine status` says
when the engine it cannot reach is alive and busy; `jk engine stop --now` stops it regardless.

## Configuration

`[engine]` in `~/.jk/config.toml` is machine-scoped: not project-overridable. The **Read**
column says when a change takes effect: keys read at *engine start* need `jk engine stop`
(the resident engine keeps the env it was spawned with), keys read *each command* or
*each job* take effect on the next one, and *each idle cycle* keys are re-read from the
file between builds. Precedence is **env > file > default**, resolved at that moment.
`jobs` also has CLI `-j` / `--jobs`, which wins over env.

`CI=1` / `true` raises the *unset* heap default 256 → 512 and the *unset* `continue`
default fail-fast → keep-going. An explicit file or env value still wins. `vfs-max-mb`
and `auto-warmup` do not follow CI.

<!-- engine-config:start -->
| Key | Env | Default | Read | Meaning |
|---|---|---|---|---|
| `max-heap-mb` | `JK_ENGINE_MAX_HEAP_MB` | 256; 512 when CI is set | engine start | Engine process heap ceiling (-Xmx). 0 = uncapped. |
| `jobs` | `JK_JOBS` | cores (0 = all cores) | each command | Concurrent module/worker budget. CLI -j wins. |
| `continue` | `JK_CONTINUE` | false; true when CI is set | each job | Keep going after a failed module. Does not change the verdict. |
| `vfs-max-mb` | `JK_ENGINE_VFS_MAX_MB` | 32 | engine start | Per-job input-tree retain in MiB. 0 = off. CI does not bump this. |
| `auto-warmup` | `JK_AUTO_WARMUP` | true | each idle cycle | Idle host calibration. false skips it. |
| `log-max-mb` | `JK_ENGINE_LOG_MAX_MB` | 16 | engine start | Engine log size cap in MiB; at the cap the log rolls to .1. 0 = no cap. |
| `log-level` | `JK_LOG_LEVEL` | info | engine start | Engine log threshold: debug, info, warn or error. debug adds the perf probes. |
| `detached-deadline-ms` | `JK_ENGINE_DETACHED_DEADLINE_MS` | 3600000 | engine start | Wall deadline for a detached HTTP/MCP job, in ms; a request's own deadline wins. 0 = off. |
| `queue-wait-ms` | `JK_ENGINE_QUEUE_WAIT_MS` | 3600000 | engine start | How long a job waits for engine memory before it gives up naming the live job, in ms. 0 = no bound. |
<!-- engine-config:end -->

Warmup details: [contributor warmup](../contributors/install-optimize.md). Heap vs VFS:
[per-job VFS](../contributors/vfs.md).

### Out of memory

The engine runs under its `max-heap-mb` cap with `-XX:+ExitOnOutOfMemoryError` and
`-XX:+HeapDumpOnOutOfMemoryError`. The first `OutOfMemoryError` writes a heap dump to
`~/.jk/state/engine/java_pid<pid>.hprof` (beside the engine log, one file per exit) and ends
the process; the next `jk` command starts a fresh engine and prints one line naming the dump.
`jk engine status` and `jk doctor` show the newest dump while one exists; the engine deletes
dumps older than seven days between builds. Raise `[engine] max-heap-mb` (or `JK_ENGINE_MAX_HEAP_MB`) and run
`jk engine stop` to apply it, or shrink what the engine holds with `jk cache prune`. Open
the `.hprof` with any Java heap analyser.

### Memory after a build

An idle engine's memory returns to a floor. When the last job finishes the engine drops its
per-build memos, runs a full collection so the heap uncommits down to its live data (SerialGC
with a low `MaxHeapFreeRatio`), and returns freed native memory to the operating system
(`malloc_trim`, the same operation as `jcmd <pid> System.trim_native_heap`). Thirty seconds
of idleness later it does both again, once the harvest and the client disconnects that trail a
job have finished, and also empties the memos a next build of the same workspace would have
reused — parsed manifests, scanned TOML files, parsed versions, the interned-string table, the
file-hash and ABI stores (persisted first; the next build reloads them from disk), and the
resolve memos: effective POMs, repository hits, version lists and Gradle module metadata — so an
engine that has built many workspaces does not keep every one of their manifest trees or POM
graphs. The workspace built last keeps its manifests and TOML files, so a first build after a
pause on a large reactor re-reads nothing. It logs one `idle trim:` line with what came back,
what was dropped and which root was kept. Between trims every memo is bounded by entries and
starts over past its cap — 4,096 manifests or TOML files, 8,192 effective POMs, 16,384
repository hits — so a long session that locks many projects cannot hold every POM it built.
The remembered not-found answers stay through a trim: they expire on their own, and a re-lock
after a pause is what they save. Two settings on the
spawn line keep the native side bounded between trims: HotSpot's periodic trim
(`-XX:TrimNativeHeapInterval`, every 30 s) and a glibc arena cap (`MALLOC_ARENA_MAX=4`,
inherited from the shell when it sets its own). `jk engine status` shows heap and RSS.

Worker JVMs are job-scoped: compiler lanes, test runners and plugin workers exit when their
job ends. The build-script host (`.jk/*.kts`) is the one worker that outlives a job, and it
shuts down after ten idle minutes.

### Compiler worker heap

A compiler worker's heap follows the module it compiles. Each worker starts with the larger
of the memory plan's per-worker share and an estimate from the module's inputs — a 384 MiB
base, one MiB per classpath entry, one byte per eight bytes of jar and 96 bytes per byte of
source — rounded up to a multiple of 256 MiB and capped at what the host can give a single
worker, so a module with a several-hundred-jar test classpath and a large test tree gets a
worker of its own size while modules of about one size share one. A worker that still runs
out of heap is replaced once by one with twice the heap; a second exhaustion fails the step
with a message naming the module and both heaps. A pinned worker heap (`--ram-percent`,
`[jvm] args` with `-Xmx`) switches the sizing and the retry off: your number is the heap.

### Downloads and repository legs

The engine holds a bounded number of downloads in flight, however many rows a lock has:
`DownloadSlots` is four slots per core, one per 4 MiB of engine heap, within [8, 64]. Rows
are submitted through a window of that width, so a lock of a thousand rows keeps about
sixty tasks alive rather than a parked thread per row. Each repository leg — one
repository asked for one POM, catalog or artifact, during the solver's warm-up as much as
during materialize — takes a leg slot of its host before it is handed to the io pool: four
per request permit of that host, so a repository that answers slowly queues at most that
many legs and holds back only the legs bound for it. Per host, six requests run at once
(twenty on the Central mirror, which also takes every Central-bound request for four hours after
Central answers a 429 or a Cloudflare 403 — see
[Repositories](repositories.md#when-central-refuses-this-host)).

### Log

The engine writes its log to `~/.jk/state/engine/<key>.log`, beside its socket and pid file.
When a fresh engine starts, `<key>.log.1` becomes `<key>.log.2` and the log becomes `.1`, so
the last lines of the engine before the last one are still there after a crash and the respawn
that follows it. Two clients that start an engine within the same 15 seconds — the second yields
to the first — share one log: the second spawn appends to the first's rather than rotating it
again. The running engine rolls the log to `.1` on its own when it reaches `log-max-mb` (default
16 MiB; `0` = no cap), so a resident engine that warns in a loop for weeks cannot fill the disk.
`jk engine status` prints a `Log` row with the current size and when this engine last rolled it;
`--output json` carries `logBytes` and `logRolledAt` (epoch millis, `-1` = never).

Every line is leveled: `HH:mm:ss.SSS LEVEL message key=value …`, with `LEVEL` one of `DEBUG`,
`INFO`, `WARN`, `ERROR`. `log-level` (or `JK_LOG_LEVEL`) sets the threshold, default `info`; it
is read at engine start, so `jk engine stop` first, then `JK_LOG_LEVEL=debug jk build` starts an
engine that also writes the perf probes (`perf <label> ms=<n>`) and the cause of every
fail-open path it took. Secrets a `.env` file declares, and repository credentials jk resolved,
are masked as `***` at every level.

### Idle connections

A client that connects and never sends a request is closed after 10 seconds; one that has sent
a request is held to the same `JK_STREAM_IDLE_MS` bound the client applies to the engine (off
while a build owns the connection). `jk engine status` prints the count as a `Dropped` row;
`--output json` and `GET /api/status` carry it as `idleDropped`.

A client that stops reading — a terminal suspended mid-build, a pipe nobody drains — costs the
other clients nothing. Each connection is served on its own thread and its lines are written by
its own writer, so a build's progress goes into that client's queue and the build goes on; `jk
engine status` and a new client's handshake are answered as usual. Once 8 MiB of lines wait for
such a client, or its oldest unread line is older than `JK_STREAM_IDLE_MS`, the engine drops it:
its connection is closed, its job ends the way it does when a client disconnects, and the engine
log says `dropped a client that stopped reading its stream`. A connection no job owns — a probe,
a status request, a cancel — is held to 10 seconds instead: a reply is one line its client is
waiting for, and a client that has not read it in that long is gone.

### Process environment

These are not `[engine]` keys. They configure how the engine process is spawned or how
jobs run inside it.

The engine itself starts from an allow-list of the spawning shell, not from the shell: `PATH`,
`HOME`, `USER`, `LOGNAME`, `SHELL`, `TERM`, `TMPDIR` / `TMP` / `TEMP`, `TZ`, `LANG` / `LANGUAGE` /
`LC_*`, `JAVA_HOME`, `GRAALVM_HOME`, `SSH_AUTH_SOCK`, `ANDROID_HOME` / `ANDROID_SDK_ROOT`,
`MISE_DATA_DIR`, `NO_COLOR`, `NERD_FONT`, every `JK_*` variable, the proxy variables
`http_proxy` / `https_proxy` / `no_proxy` in either case — as the fallback only: they ride each
request from the shell running `jk`, and the request's values win
([Config § Network](config.md#network)) — and on Windows the system roots (`SystemRoot`,
`ComSpec`, `PATHEXT`, `USERPROFILE`, …). JVM
switches (`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`), build-tool options, cloud keys
and forge tokens stay behind: a resident engine outlives the shell that started it, and what it
inherited once would be every later terminal's truth. Workers are narrowed again —
[Build § Worker environment](build.md#worker-environment-env).

<!-- engine-process:start -->
| Env | Default | Meaning |
|---|---|---|
| `JK_ENGINE_EXE` | unset | Override engine binary instead of the product-lib jar. |
| `JK_ENGINE_JDK` | unset | JDK the engine JVM runs on. Same pin as [toolchain].jdk. |
| `JK_ENGINE_TRANSPORT` | unix; tcp on Windows | Force tcp or unix for the client-engine wire. |
| `JK_ENGINE_HEARTBEAT_MS` | 30000 | Heartbeat while async jobs run. 0 disables. |
| `JK_ENGINE_JOB_DEADLINE_MS` | 0 | Wall deadline for a job a client owns over its socket, in ms. 0 = off. |
| `JK_ENGINE_JOB_DEADLINE_GRACE_MS` | 30000 | Join grace after a deadline cancel, in ms. |
| `JK_CANCEL_GRACE_MS` | 500 | Shared SIGTERM-to-SIGKILL window for forked workers on cancel, in ms; clamped to 5000. |
<!-- engine-process:end -->

A resident engine keeps that environment for its whole life, so anything that must follow the
*calling* shell rides each request instead: `JK_JVM_ARGS`, `JK_JVM_GC`, `JK_JVM_STRING_DEDUP` and `JK_MAX_RAM_PERCENT`
are the shell spellings of `--jvm-arg` / `--ram-percent`, read by the client per invocation and
sent with the request, so two terminals exporting different values get their own worker-JVM flags
from one engine. The same holds for `JK_REPO_*` credentials and host bindings
([Repositories](repositories.md)).

### Signals

The engine detaches into its own session and catches `SIGINT` / `SIGHUP` itself, so a Ctrl-C
aimed at the terminal that started it never reaches it; cancel is a wire request. A shell that
had those signals *ignored* — a background job of a script, `nohup` — would otherwise hand the
ignore down to the engine and from there to every worker, test JVM and `jk dev` sidecar it forks,
and their Ctrl-C would do nothing. The engine therefore resets both to the default disposition
before it forks anything, and logs the mask it inherited. `jk engine status` prints a `Signals`
row only when an ignore survived that reset; `--output json` carries it as `ignoredSignals`
(`""` = none).

HTTP / MCP knobs are `[http]` / `[mcp]`: [Config](config.md), [Web](web.md), [MCP](mcp.md).

## Related

[Install](install.md) · [Agents](agents.md) · [Architecture](../contributors/architecture.md) · [Per-job VFS](../contributors/vfs.md)
