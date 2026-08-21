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
MCP: [MCP](mcp.md).

## Warmup / AOT

Short-lived CI engines should set `JK_AOT_TRAIN=off` (skip train-on-miss; still use
existing `.aot` caches). Worker AOT is HotSpot 25+ only. Knobs:
[Config](config.md), [contributor warmup](../contributors/install-optimize.md).

## Related

[Install](install.md) · [Agents](agents.md) · [Architecture](../contributors/architecture.md)
