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
| **Web / MCP** | `POST /api/cancel` with `{"jid":N}` · MCP `jk_cancel` |

A second same-kind build in the same checkout is rejected: **Build #N already running**.
Worktrees are different slots.

## HTTP and MCP

HTTP is **on by default** (loopback). Turn it off with `[http] enabled = false` or
`JK_HTTP_ENABLED=false`. MCP rides the same server; disable with `[mcp] enabled = false`
without killing the dashboard.

A resident engine **does not idle out**. It exits on `jk engine stop`, version-skew
replacement, or (if displaced/orphaned) after draining. Details of lifetime and auth:
[contributor HTTP](../contributors/http.md). User-facing dashboard: [Web](web.md).
MCP: [MCP](mcp.md). Token, bind, and reporting: [Security](security.md).

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
| `auto-warmup` | `JK_AUTO_WARMUP` | true | each idle cycle | Idle AOT train and host calibration. false skips the whole pass. |
| `log-max-mb` | `JK_ENGINE_LOG_MAX_MB` | 16 | engine start | Engine log size cap in MiB; at the cap the log rolls to .1 (one generation kept). 0 = no cap. |
| `log-level` | `JK_LOG_LEVEL` | info | engine start | Engine log threshold: debug, info, warn or error. debug adds the perf probes. |
<!-- engine-config:end -->

Short-lived CI engines should set `JK_AOT_TRAIN=off` (skip train-on-miss; still use
existing `.aot` caches). Worker AOT is HotSpot 25+ only. Warmup details:
[contributor warmup](../contributors/install-optimize.md). Heap vs VFS:
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

### Log

The engine writes its log to `~/.jk/state/engine/<key>.log`, beside its socket and pid file.
The file is rotated to `<key>.log.1` when a fresh engine starts, and the running engine rolls
it to the same `.1` on its own when it reaches `log-max-mb` (default 16 MiB; `0` = no cap), so
a resident engine that warns in a loop for weeks cannot fill the disk. One previous generation
is kept. `jk engine status` prints a `Log` row with the current size and when this engine last
rolled it; `--output json` carries `logBytes` and `logRolledAt` (epoch millis, `-1` = never).

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

### Process environment

These are not `[engine]` keys. They configure how the engine process is spawned or how
jobs run inside it.

<!-- engine-process:start -->
| Env | Default | Meaning |
|---|---|---|
| `JK_ENGINE_EXE` | unset | Override engine binary instead of the product-lib jar. |
| `JK_ENGINE_JDK` | unset | JDK the engine JVM runs on. Same pin as [toolchain].jdk. |
| `JK_ENGINE_TRANSPORT` | unix; tcp on Windows | Force tcp or unix for the client-engine wire. |
| `JK_ENGINE_HEARTBEAT_MS` | 30000 | Heartbeat while async jobs run. 0 disables. |
| `JK_ENGINE_JOB_DEADLINE_MS` | 0 | Job wall deadline in ms. 0 = off. |
| `JK_ENGINE_JOB_DEADLINE_GRACE_MS` | 30000 | Join grace after a deadline cancel, in ms. |
| `JK_CANCEL_GRACE_MS` | 500 | Shared SIGTERM-to-SIGKILL window for forked workers on cancel, in ms; clamped to 5000. |
<!-- engine-process:end -->

HTTP / MCP knobs are `[http]` / `[mcp]`: [Config](config.md), [Web](web.md), [MCP](mcp.md).

## Related

[Install](install.md) · [Agents](agents.md) · [Architecture](../contributors/architecture.md) · [Per-job VFS](../contributors/vfs.md)
