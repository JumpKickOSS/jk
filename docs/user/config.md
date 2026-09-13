# Config, chrome, and environment

Global CLI prefs live in **`[config]`** of `~/.jk/config.toml` and/or project
`jk.toml`. Precedence for most knobs: **flag > env > project > machine**.

```toml
# ~/.jk/config.toml or project jk.toml
[config]
color = "auto"          # auto | always | never
offline = false
quiet = false
verbose = false
no-progress = false
no-ansi = false         # ASCII-only; implies no-progress
force-ansi = false      # emit ANSI even under TERM=dumb / CI
no-osc = false
notify = "auto"         # auto | always | never
build-output = false    # live-plan process-output peek (Ctrl-O)
force = false
# directory = "/path"   # optional default -C
```

| Setting | CLI | Env |
|---------|-----|-----|
| `color` | `--color` | `JK_COLOR`, `NO_COLOR` |
| `no-progress` | `--no-progress` | `JK_NO_PROGRESS` |
| `no-ansi` | `--no-ansi` | `JK_NO_ANSI` |
| `force-ansi` | — | `JK_FORCE_ANSI` |
| `no-osc` | `--no-osc` | `JK_NO_OSC` |
| `notify` | `--notify` / `--no-notify` | `JK_NOTIFY` |
| `build-output` | — | `JK_BUILD_OUTPUT` |
| `quiet` / `verbose` / `offline` / `force` | `-q` / `-v` / `--offline` / `-F` | `JK_QUIET` / `JK_VERBOSE` / `JK_OFFLINE` / `JK_FORCE` |

**`notify`:** `auto` (default) sends an OSC desktop notification when a build’s ETA **or**
elapsed time is ≥ 1 minute; `always`/`true` always; `never`/`false` never.
`--no-progress` and `--no-osc` also suppress notifications.

**`force-ansi`:** ANSI is otherwise suppressed by `TERM=dumb` or a truthy `CI`, whichever the
host sets. `force-ansi` outranks both, so output can be pinned to ANSI where the environment
would strip it. `no-ansi` still wins when both are set — turning ANSI off is the safer
direction to honor.

Under `--no-ansi`, chrome lines start with `jk: ` so they stay distinct from compiler/test
output. Agents should use [machine output](machine-output.md), not scrape prose.

Interactive wizards (`jk new`, `jk jdk install`, `jk activate`) still work under `--no-ansi` on a
terminal: instead of arrow keys and live highlighting they ask one question per line. Text
questions show their default in brackets and take it on an empty line; choices print as a
numbered list and take a number, an id, or an empty line for the default; multi-picks take
several numbers (`1 3` or `1,3`), `all`, or `none`. Answers can be piped on stdin. Without a
terminal at all the wizard is skipped and the command's flags apply, as before.

## Nerd Font glyphs

Detected per launch — `nerd-font` defaults to `"auto"`. Pin only to override:

```bash
jk self setup-terminal --explain
jk self setup-terminal                  # write nerd-font = "auto"
jk self setup-terminal --mode on        # every PUA glyph
jk self setup-terminal --mode off       # no PUA glyphs
jk self setup-terminal --mode wedge     # powerline triangles only
jk self setup-terminal --mode pill      # half-circle pill caps only
```

`auto` grants both axes on Ghostty, kitty, WezTerm, and Windows Terminal; at least the
wedge on Alacritty, which draws the powerline triangles itself (and upgrades to both axes
when its font is a Nerd Font); on iTerm2, VS Code, and Zed it follows the configured font.
Nothing over SSH, under `CI`, on an unrecognised terminal, or on macOS Terminal.app —
which draws no powerline glyph of its own and stores its font where jk cannot read it, so
a patched font there has to be claimed with `--mode wedge` or `--mode on`. Env
`JK_NERD_FONT` takes all five values; host-wide `NERD_FONT` takes booleans only.

Contributor TUI rules: [TUI](../contributors/tui.md).

## Engine

Machine-scoped `[engine]` keys and `JK_ENGINE_*` process env: [Engine](engine.md#configuration).
They are not project-overridable.

## HTTP and MCP

```toml
[http]
# enabled = false          # default is on (loopback, token-gated /api)

[mcp]
# enabled = false          # 404 /mcp only; dashboard stays
# max-event-streams = 16
```

`JK_HTTP_ENABLED=false`, `JK_MCP_ENABLED=false`. Details: [MCP](mcp.md), [Web](web.md).

## Network

Corporate networks that only reach the internet through a proxy configure it once:

```toml
[network]
proxy = "http://proxy.corp:3128"          # every request; user:password@ for Basic
https-proxy = "http://proxy.corp:3129"    # https targets only, when they differ
no-proxy = ["nexus.corp", ".internal.corp", "10.0.0.5:8081"]
```

Without a `[network]` table, the shell's `https_proxy` / `HTTPS_PROXY` (https targets),
`http_proxy` / `HTTP_PROXY` (http targets) and `no_proxy` / `NO_PROXY` decide — lower case wins
when both are set, and both `no-proxy` lists apply. A proxy URL is
`http://[user:password@]host[:port]` (a bare `host:port` is http); https targets tunnel through it
with `CONNECT`, and a `user:password@` is sent to the proxy as Basic (only to the proxy — a
redirect to a host that goes direct never carries it). A `no-proxy` entry is
`*`, a host, a `.suffix` (a bare suffix covers its subdomains too), or `host:port` for one port.
Loopback targets always go direct.

Every download jk makes — Maven Central and your repositories, JDK and tool distributions, the
engine jar, release checks — goes through `Http`, so one setting covers them all. The decision is
made per request: `[network]` is re-read when the file changes, so it is the setting to change on
a laptop that moves between networks; the engine reads the six proxy variables from the shell
that spawned it, so after exporting new ones run `jk engine stop` and the next command starts an
engine that sees them. A credential in a proxy URL is never printed; an unusable value is reported
by the name that set it (`ignoring https_proxy: …`) and the request goes direct. For a proxy that
wants Basic on an https `CONNECT`, jk clears the JDK's `jdk.http.auth.tunneling.disabledSchemes`
in its own processes unless you set that property yourself. `--offline` still refuses every
request before any proxy is consulted.

## Other env

Every boolean jk reads — from a `JK_*` variable, from `CI`, or from a quoted value in
`jk.toml` — accepts the same set, trimmed and case-insensitive:

| true | false |
|---|---|
| `1` `true` `yes` `on` | `0` `false` `no` `off` |

Anything else means "unset", so the next layer down decides; a malformed value is never a
hard failure. There is one reader behind all of it, so `CI=yes` and `CI=1` mean exactly what
`CI=true` means everywhere.

Install / dirs: [Install](install.md). Format: [Format](format.md). Cache budgets:
[Cache](cache.md). `.env` layering: `jk env` on the install page.

```bash
jk doctor                 # host health (engine, dirs, JDKs, lock, current/login shell)
```
