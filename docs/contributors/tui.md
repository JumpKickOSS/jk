# TUI style guide

**Audience:** CLI authors and agents touching `clients/cli` human output  
**Status:** living guide
**Related:** [machine-output.md](../user/machine-output.md) (agents / CI / JSONL)

## Product stance

| Consumer | Channel | Goal |
|----------|---------|------|
| **Human at a TTY** | CommandWedge, tables, trees, wizards, progress | Fast eyeball success/failure |
| **Human debugging** | `-v` / `--verbose` + `target/` | Scrollable detail |
| **Agents / scripts / CI** | `--output jsonl` / `json`, or **script-mode** plain stdout | Parseable lines, no chrome scrape |
| **Restricted terminals** | `--no-ansi` / `NO_COLOR` / `TERM=dumb` / `CI` | ASCII prefixes, no animation |

**Do not** scrape wedges, progress bars, or box tables in scripts. Prefer JSONL or a script-mode command.

## JkWedge is the look

Almost every human command settles with a **JkWedge** (green ok / red fail / blue work chip), or embeds wedge chrome inside:

- **Table** title bar (`≡ Title` chip + box fill)
- **Tree** / plan step list under a wedge+progress header (`Gap.NONE` / `EACH` / `CHILDREN`)
- **Wizard** steps with indigo/title chips

API: `cc.jumpkick.cli.tui.JkWedge` (`CommandWedge` is the print helpers).

### Progress variants

| Work shape | Chrome |
|------------|--------|
| Bounded known progress | JkWedge + Progress. Painted live by `LiveLine` (`ProgressRow`) or by `JkManagerView` (the plan header) — one geometry, two regions |
| Indeterminate / short | Spinner-only wedge (`CommandWedge.analyzing` / `Spinner.showWedge`), also a `LiveLine` row |
| Settled | Static icon — `CommandWedge.ok` / `fail` / `chip` (never leave a spinner running) |

### Process-output peek (Ctrl-O)

On an interactive TTY live plan (`jk build`, `jk test`, `jk lock`, …), worker and tool stdout/stderr
are buffered in a **sliding window of at most 200 lines**, hidden by default so the wedge + progress
stay clean. Long tools (e.g. `native-image`) stream into this channel live — not only on
`--verbose` or failure — so Ctrl-O can show them mid-run.

| Action | Behavior |
|--------|----------|
| **Ctrl-O** | Toggle process-output peek. When **on**: buffered lines are committed above a full-width braille rule (`⠒… ↑ output ↑ …⠒`); only the rule + wedge/tree is the live region (normal spinner line-diff). New output lifts that small region, appends one line, and repaints the wedge — no full-screen redraw. When **off**: the rule is **replaced by a blank line** (not deleted); already-printed lines stay in scrollback. Always keep either the rule or that blank between process output and the wedge. The listener uses `:cli-terminal` `InputMode.PLAN_KEYS` (ISIG on) so Ctrl-C stays `GlobalCancel`'s SIGINT. |
| **`[config] build-output = true`** (or `JK_BUILD_OUTPUT=true`) | Start with the peek **open** on live plans. Default **`false`** (hidden until Ctrl-O or force-show). Machine or project `[config]`. |
| **Failed tool/worker** (non-zero sub-process exit, e.g. `native-image`) | Force-opens the pane while the plan is still live. |
| **Test failures** | Do **not** force-open — curated test-failure chrome owns that path. |
| Plan settle | Does **not** dump the buffer. If process lines were committed (pane open or force-show), they stay in scrollback; the live region is wiped; **one blank** is printed between that output and the settle chip. |
| `-v` / `--verbose` | Per-step cargo-style lines (and tool stdout). |
| non-TTY / `--no-progress` / JSONL | Unchanged; no key listener. |
| **plain** (`--no-ansi`) | Tool stdout (compiler, tests, native-image) is **suppressed** unless `-v`. Diagnostics and test-failure chrome still print. Progress is append-only `jk: * Build > …` lines. |

Document only — no on-screen “press Ctrl-O” hint. InheritIO handoffs (`jk run`, `jshell`, …) are out of scope.

**Type-ahead is consumed during animated plans.** The Ctrl-O listener holds the controlling TTY
in raw, no-echo mode for the life of the plan and discards every key except Ctrl-O (Ctrl-C still
raises SIGINT via ISIG). Keys typed during a build — a queued-up shell command, stray Enters —
are *not* delivered to the shell afterwards, unlike the pre-peek behavior where the tty buffered
them. This is inherent to listening at all: any byte read is consumed, and there is no portable
way to push bytes back into the tty input queue (`TIOCSTI` is root-gated or compiled out on
modern kernels). Accepted as the cost of the peek; piped/non-TTY runs install no listener and
are unaffected.

**Settle restores `:cli-terminal` modes, never process fds.** The controlling TTY is a process
singleton (`Terminals.controlling()`). `ModeGuard` pop returns `COOKED`; `close()` on the session
restores original attrs and does **not** close native fds or drop `isLive`. Only
`Terminals.shutdown()` (from `Jk.main` finally and `GlobalCancel`) closes fds. Timed reads are
clock-driven `poll` / `WaitForSingleObject` then `PeekConsoleInputW` (Windows) or `O_NONBLOCK`
POSIX `read` — no helper thread, no FD 0. Prompt/Confirm paint stays on stderr.

### Module-selection caption

When a module selection is in effect (working directory is a workspace member, or `-m` / `--modules`
was passed), a caption is printed once above the wedge (scrollback — it stays after settle). Names
come from each member's `jk.toml` `name`, not the workspace path:

```
 …building module jk-cli…
 ● Build  █▋                                       4% · ETA ~2m 56s · 5s
```

```
 …building modules jk-engine, jk-cli…
 ● Build  █▋                                       4% · ETA ~2m 56s · 5s
```

`module` vs `modules` follows the count. The whole line is dark-gray. JSON output omits it.

Under `--no-ansi` the caption and every other jk chrome line is prefixed with `jk: ` so tool output (when `-v` is on) is distinguishable:

```
jk: * Build > initializing...
jk: * Build > cc.jumpkick:jk :: 0% - prepare
jk: * Build > cc.jumpkick:jk :: 4% (ETA ~1m 14s) - start
jk: * Build > cc.jumpkick:jk-cli :: 4% (ETA ~1m 14s) - resolving
jk: * Build > cc.jumpkick:jk-cli :: 4% (ETA ~1m 14s) - generating
jk: * Build > cc.jumpkick:jk-cli :: 4% (ETA ~1m 14s) - compiling 12 sources
jk: * Build > cc.jumpkick:jk-cli :: 4% (ETA ~1m 12s) - running 80 tests
jk: * Build > cc.jumpkick:jk-cli :: 9% (ETA ~45s) - running 50 tests
jk: * Build > cc.jumpkick:jk-cli :: 47% (ETA ~47s) - packaging
jk: * Build > cc.jumpkick:jk-cli :: 52% (ETA ~42s) - native compiling
jk: * Build > cc.jumpkick:jk-cli :: 90% (ETA ~8s) - built
jk: * Build > cc.jumpkick:jk :: 100% - done
jk: + Build > Build successful for 1 module - took 1m 16s
```

`subject :: percent% (ETA ~…) - status`. The workspace coordinate is on `prepare`, `start`, and `done`. Compile shows a source count (`compiling 12 sources`); tests show a remaining count (`running 80 tests`) that refreshes on the stage-entry line and on the 30s heartbeat. Other phases stay as lowercase gerunds. Lines print on stage/phase changes, every **30 seconds** while a stage stays active (fresher percent/ETA/status details), when a module finishes (`built`), as soon as an ETA is known (`start`), and on settle (`done`) — not on percent ticks. Same module+phase reprints are otherwise suppressed. Sub-step detail (e.g. native classpath size) is appended only with `-v`.

### Completed-module tail

Workspace plans (`jk build`, `jk test`, `jk run`, `jk native`, `jk image`) keep the last few
`✓ [N of M] group:artifact took …` lines **in the live region under the wedge**, newest first.
They are not written into terminal scrollback and they are not part of the process-output peek.
Settle wipes the live region, so those lines do not remain after the result chip.

### Blank-line envelope

Every **human** command prints:

1. Exactly **one blank line before** the **first** chrome of the invocation — whichever comes first: prep spinner (`EnsureFreshLock` / `CommandWedge.analyzing`), open spinner, live `JkManager` bar, or settle chip  
2. Exactly **one blank line after** the **last** chrome when the command exits (breathing room before the shell prompt)

`CliOutput` owns the rule. The first `out` / `err` / `stdout()` / `stderr()` write of a leaf command inserts the leading blank. Dispatch calls `CliOutput.beginCommand(scriptMode)` before `run` and `CliOutput.closeEnvelope()` after — including on exception, after the error line, which goes through `CliOutput.err`. The trailing blank lands on whichever stream wrote last, so a command that ends on a stderr failure gets its gap there and a redirected stdout stays clean. Settles do **not** close. Plugin-declared commands (dispatched over the wire, not via `CliCommand`) share the same envelope.

`JkManager`, `LiveLine` (for `Spinner` and `ProgressRow`), and wizards (`markEnvelopeStarted` after their own leading blank) share the same flag, so conditional paths (cache-hit vs rebuild, lock freshen before explain) cannot skip or double the blanks.

Commands do **not** have to remember `envelopeStart` / `printOk` for the blank to appear. `CliOutput.out(JkWedge.chipLine(…))` is enough.

Helpers (wedge *formatting*, not envelope enforcement):

- One-shot success: `CommandWedge.printOk(command, message)`  
- One-shot failure: `CommandWedge.printFail(command, message)`  
- Working / handoff: `CommandWedge.printWorking(command, message)` (stderr)  
- Pre-rendered wedge line: `CommandWedge.printLine(line)` / `printErrLine(line)`  
- Multi-line chrome: `envelopeStart()` / `envelopeStartErr()` then body lines  
- Live plans: `JkManager` opens the leading blank if prep has not already  
- **Exec handoff** — every `inheritIO` exec (`jk run`, `jk mvn`, `jk gradle`, `jk jshell`, `jk shell`, script files): call `CliOutput.skipTrailingBlank()` immediately before it, because the child owns stdout from there and `jk run > app.out` must not collect jk's closing blank  

Optional blank lines **between** chrome and follow-up tips (e.g. after `jk add`) are fine — that is content spacing, not the closing envelope.

**Script-mode** commands override `scriptMode(Invocation)` on `CliCommand`. Dispatch consults it once, before `run`, and it suppresses the envelope **and** the Unicode→ASCII rewrite on stdout, so the payload reaches its consumer byte-exact. stderr stays human — spaced and formatted — even then. `--output json` / `jsonl` enters the same mode. `jk --version` writes `System.out` directly and is outside the envelope, as are parse/usage errors and help screens.

Do **not** write human chrome with raw `System.out.println` — that bypasses `CliOutput` and skips the blank.

## Script-mode allowlist (no wedge)

These commands intentionally emit only machine-consumable stdout. Every row but `jk --version` — which prints before dispatch begins a command — is a `scriptMode(Invocation)` override; `ScriptModeAllowlistTest` fails when the table and the code drift.

| Command | Typical stdout | Consumer |
|---------|----------------|----------|
| `jk activate <shell>` | PATH + hooks + completions | `eval "$("$HOME/.jk/bin/jk" activate bash)"` |
| `jk hook-env -s <shell>` | Env sync lines | shell hook |
| `jk jdk home` | `export JAVA_HOME=…` | `eval "$(jk jdk home)"` |
| `jk auth token [provider]` | Single-line token | scripts / curl |
| `jk show` / `jk tasks show` | Absolute path (or `coord\tpath`) | command substitution |
| `jk tool dir` / `jk cache dir` / `jk storage dir` | Path | scripts / installers |
| `jk manual` | Playbook markdown | agents / MCP |
| `jk --version` / `-V` | `jk <version>` | CI |
| `jk explain --graph dot\|mermaid` | Graph source | `dot` / editors |
| `jk selective resolve` (+ `--json`) | Paths or JSON | CI selective plans |
| `jk bsp serve` (bare `jk bsp` is `serve`; `run` is an alias) | JSON-RPC on stdio | IDE BSP client |
| `jk ide --print-model` (and its `jk vscode` alias) | Wire JSON | IDE plugins |

Adding a new exception requires updating this table and the override that backs it.

## List/status surfaces and hybrid settles

Classification of wave-2 commands — implemented as listed; changing a row means
changing the code (and vice versa):

### Table + title wedge

| Command | Table |
|---------|-------|
| `jk library list` | Name · Coordinates (· Layer with `--show-layer`); per-layer tables with `--group-by-layer` |
| `jk library search` | Name · Coordinates (· Layer) · Cached |
| `jk tool list` | Tool · Coordinates · Source · Launcher |
| `jk history list` | status · Id · Project · Kind · Took · When · Saved · Notes |
| `jk tasks` | Name · Stage · Description (per module at a workspace root) |
| `jk jdk list` | (wave 1 — the exemplar) |
| `jk storage usage` | Element · File Count · Size (Jar Files / Native Bins / OCI Images / Worker JARs), plus Total and a last-cleaned footer |

Use `new Table(title).columns(...).row(...)` (or the `Table.render` static for string cells).
Rendered rows go out through `CliOutput`, so the envelope opens on the first one, and they degrade to ASCII under `--no-ansi`.

### Wedge header + rows

| Command | Chrome |
|---------|--------|
| `jk doctor` | `≡ Doctor` menu chip, then the checklist rows + summary |
| `jk auth status` | `≡ Auth status` chip, then per-forge status lines |
| `jk history show` | `≡ Build <id>` chip, then the detail block |
| `jk cache usage` | Element · File Count · Size (Class Files / Test Results / Normal·Shadow·Minified Jars / Native Bins / OCI Images / Format Stamps), plus Total (the action cache: index + blobs), a spanning utilization row, a last-cleaned footer, and two unbarred footer lines — incremental state against its own budget, derived caches with none |

### Hybrid settles (CommandWedge.ok/fail)

| Command | Settle |
|---------|--------|
| `jk auth logout` | `✓ Auth Logged out of …` |
| `jk tool install` | `✓ Tool Installed … → launcher` (PATH tip stays as follow-up content) |
| `jk verify` | `✓ Verify Reproducible` (mismatch path already fails via wedge) |
| `jk history rm` | `✓ History Deleted build <id>` |
| `jk format` (quiet/check) | already wedge-settled (wave 1) |
| `jk selective prepare` | already wedge-settled |
| `jk jdk ensure` / `graal` | settles via `JdkRender.available` under the envelope |
| `jk storage clean` | plan console (`Storage` chip); settles with the reclaim summary (`Finished cleaning store. …`), like `jk cache clean` |
| `jk install` (plugin module) | `✓ Install` plus local-repo publish of PluginMain workers |

### Documented exceptions (deliberately plain)

| Command | Why |
|---------|-----|
| `jk trust list` | scriptable one-path-per-line contract; agents/paste into shell |
| `jk repo search` | plain aligned `coordinate  versions` list; piped/scripted like `jk trust list` |
| `jk inspect` / `jk tasks inspect` | scrape-friendly `key: value` blocks for humans and agents |
| `jk shell` | prints one line then hands the terminal to the spawned shell — no chrome |
| `jk auth login` | device-flow prompts own the terminal; spinner while waiting, wedge on settle |

## Glyph modes

| Mode | Trigger | Chrome |
|------|---------|--------|
| **nerd** | ANSI + a PUA axis granted | Powerline PUA caps + Unicode glyphs |
| **ansi** | ANSI, no PUA axis granted | Colored chips, Unicode glyphs, **no** PUA (bg-colored space cap) |
| **plain** | `--no-ansi` / `NO_COLOR` / `TERM=dumb` / `CI` | `jk: ` prefix on chrome; ASCII `+` / `!` / `*` prefixes; progress `#`/`-`; **no animations** |

### The two PUA axes

jk emits exactly four PUA codepoints, and they are **not** equally available, so nerd capability is
a pair of flags (`NerdFontCaps`), not one boolean:

| Axis | Codepoints | `Glyphs` constant | Rendered by |
|------|-----------|-------------------|-------------|
| **wedge** | `U+E0B0`, `U+E0B2` (solid triangles) | `SEGMENT_END_NERD`, `SEGMENT_BACK_NERD` | any Powerline-patched font |
| **pill** | `U+E0B6`, `U+E0B4` (solid semi-circles) | `PILL_LEFT_NERD`, `PILL_RIGHT_NERD` | Nerd Font v2+ / Powerline-Extra only |

A classic Powerline patch draws the triangles perfectly and the semi-circles as tofu. That is why
`nerd-font = "wedge"` exists: under one boolean such a font would have to choose between tofu and no
chrome at all.

`nerd-font` accepts `false`, `true`, `"auto"` (default), `"wedge"`, or `"pill"`.
Precedence: color/ANSI gate > `JK_NERD_FONT` > `NERD_FONT` > config > `auto` detection.

Rules:

- Prefer `Glyphs.check()` / `cross()` / `pulse()` (and friends) over hardcoding a check mark when emitting markers outside wedges.
- Wedge PUA only via `JkWedge.cap(..., wedge)` gated on `ctx.wedge()`; pill PUA only via
  `Badge.pill(..., pillCaps)` gated on `ctx.pill()`.
- **Never branch glyph choice on `ctx.mode()`** — `NERD` there means "some PUA", which is too coarse
  and re-introduces the tofu bug for wedge-only fonts.
- Plain mode: no CSI color, no spinner animation frames, no OSC taskbar required for correctness.

### Wizards under plain mode

A `Wizard` on a live tty whose theme is not ANSI (`--no-ansi`, `TERM=dumb`, `CI`) never enters
`InputMode.PROMPT` and paints no cursor or erase sequence. `CookedWizard` drives the same steps as
line prompts on stderr, read as whole lines from stdin in the terminal's own cooked mode: an
`InputStep` is `Prompt [default]: ` (empty takes the default, the validator re-asks); a `RadioStep`
prints a numbered menu built from `RadioButton.renderInline` and accepts a position, an id, or empty
for the default (free text where the step has a custom row); a `MultiSelectStep` prints a numbered
checklist from `Checkbox.render` and accepts `1 3`, `1,3`, `all`, `none`, or empty for the defaults
(unknown tokens are custom entries where the step allows them); an `OutputStep` prints once. Settled
answers re-print as `-> answer` where the ANSI path paints `➜ ` in italic green. Keys, defaults and
validators are shared, so callers do not know which driver ran. Non-interactive (no live tty) still
returns an empty result and the command falls back to flags.

## One-shot vs plan

| Kind | Example | Pattern |
|------|---------|---------|
| One-shot settle | `jk add`, `jk export`, `jk jdk pin` | `CommandWedge.printOk` / `printFail` |
| Live plan | `jk build`, `jk test`, `jk lock` | `JkManager` + settle wedge |
| List / table | `jk library list`, `jk doctor` | Table + title wedge |

## JSONL / quiet

Under `--output json` / `jsonl`, suppress human chrome (no envelope, no wedge). See [machine-output.md](../user/machine-output.md).

## Checklist for new commands

1. Is this **script-mode** (eval / path / token / protocol)? If yes: plain stdout only; override `scriptMode(Invocation)` and add the row to the allowlist above.  
2. Otherwise: settle with **CommandWedge** (or table/tree/wizard that includes wedge chrome).  
3. Print through **`CliOutput`**, never raw `System.out` — the leading blank is automatic.  
4. Bounded work → bar; indeterminate → spinner; always settle to a **static** icon.  
5. Verify **nerd / ansi / plain** (`--no-ansi`) look intentional.  
6. Agents: document JSONL events if you add machine-visible facts.

## Code map

| Concern | Location |
|---------|----------|
| Styled text | `cli/tui/RichText.java` — markup (`[bold]`, `[#hex]`, `[link url]`, theme tokens) |
| Settled wedge | `cli/tui/JkWedge.java` (`CommandWedge` formats wedges and opens the envelope on a caller-supplied stream; `CliOutput` owns the envelope lifecycle) |
| Pill | `cli/tui/Pill.java` — nerd `label` / ansi padded / plain `[label]` |
| Coord | `cli/tui/Coord.java` + `theme/Coords` RichText factories |
| Code | `SourceCode.java` / `JavaCode` / `KotlinCode` / `GroovyCode` |
| Prompt | `Prompt.java`, `Confirmation.java` (`Confirm` façade) |
| Wizard parts | `Wizard` (ANSI key loop), `CookedWizard` (plain line prompts), `WizardSection`, `TextInput`, `Checkbox`, `RadioButton`, `RadioButtonGroup` |
| Progress | `cli/tui/Progress.java` + `ProgressBar.java` + `PlainPhase.java` (plain live cadence: stage changes, 30s heartbeat, `built`/`done`, `jk: ` prefix). Bar width is a parameter: `Progress.DEFAULT_SEGMENTS` 40, `NARROW_SEGMENTS` 32 where the caller does not control the trailing text |
| Live one-row region | `cli/tui/LiveLine.java` — the only one-row live loop: animator, cursor, OSC taskbar (re-told every frame), in-place repaint, Ctrl-C settle, `LiveRegion` registration. Takes an already-clipped row per frame (`JkWedge.renderLiveLine`), so it never decides how anything looks. Splits silent (`--no-progress`, script mode) from plain (`--no-ansi`: no moving row; the animator calls the owner's heartbeat hook instead) from animating. Owners: `Spinner`, `ProgressRow` |
| Tables | `cli/tui/Table.java`. Append snaps child rails to parent edges |
| Trees | `cli/tui/Tree.java` — optional title/root, {@code Gap} / {@code BodyFit}, pills, hanging rich text |
| Glyphs | `cli/tui/Glyphs.java` |
| Live plan | `cli/tui/JkManager.java` |
| Spinner / bar | `Spinner.java` (frame content, `update` message, plain 60s heartbeat cadence and `working...`/`done.` lines — the region itself is `LiveLine`), `SpinnerProgressBar.java` |
| Theme / ANSI gate | `cli/theme/Theme.java` (`styleNamed` for RichText tokens) |
| Mode fixtures | `cli/tui/TuiModeFixturesTest.java` |
