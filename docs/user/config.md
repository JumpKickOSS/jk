# Config, chrome, and environment

Global CLI prefs live in **`[config]`** of `~/.config/jk/config.toml` and/or project
`jk.toml`. Precedence for most knobs: **flag > env > project > machine**.

```toml
# ~/.config/jk/config.toml or project jk.toml
[config]
color = "auto"          # auto | always | never
offline = false
quiet = false
verbose = false
no-progress = false
no-ansi = false         # ASCII-only; implies no-progress
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
| `no-osc` | `--no-osc` | `JK_NO_OSC` |
| `notify` | `--notify` / `--no-notify` | `JK_NOTIFY` |
| `build-output` | — | `JK_BUILD_OUTPUT` |
| `quiet` / `verbose` / `offline` / `force` | `-q` / `-v` / `--offline` / `-F` | `JK_QUIET` / `JK_VERBOSE` / `JK_OFFLINE` / `JK_FORCE` |

**`notify`:** `auto` (default) sends an OSC desktop notification when a build’s ETA **or**
elapsed time is ≥ 1 minute; `always`/`true` always; `never`/`false` never.
`--no-progress` and `--no-osc` also suppress notifications.

Under `--no-ansi`, chrome lines start with `jk: ` so they stay distinct from compiler/test
output. Agents should use [machine output](machine-output.md), not scrape prose.

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

`auto` grants both axes on Ghostty, kitty, WezTerm, and Windows Terminal; the wedge only
on Terminal.app; nothing over SSH, under `CI`, or on an unrecognised terminal. Env
`JK_NERD_FONT` takes all five values; host-wide `NERD_FONT` takes booleans only.

Contributor TUI rules: [TUI](../contributors/tui.md).

## Engine and HTTP

```toml
[engine]
jobs = 0
# auto-warmup = false

[http]
# enabled = false          # default is on (loopback, token-gated /api)

[mcp]
# enabled = false          # 404 /mcp only; dashboard stays
# max-event-streams = 16
```

`JK_HTTP_ENABLED=false`, `JK_MCP_ENABLED=false`, `JK_AUTO_WARMUP=off`. Details:
[Engine](engine.md), [MCP](mcp.md), [Web](web.md).

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
jk doctor                 # host health
```
