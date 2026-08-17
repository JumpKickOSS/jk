# TUI style guide

**Audience:** CLI authors and agents touching `clients/cli` human output  
**Status:** living guide (JK-1372)  
**Related:** [machine-output.md](machine-output.md) (agents / CI / JSONL)

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

API: `cc.jumpkick.cli.tui.JkWedge` (`CommandWedge` is envelope + printOk).

### Progress variants

| Work shape | Chrome |
|------------|--------|
| Bounded known progress | JkWedge + Progress (plan `JkManager`, or `JdkDownloadBar`) |
| Indeterminate / short | Spinner-only wedge (`CommandWedge.analyzing` / `Spinner.showWedge`) |
| Settled | Static icon — `CommandWedge.ok` / `fail` / `chip` (never leave a spinner running) |

### Process-output peek (Ctrl-O)

On an interactive TTY live plan (`jk build`, `jk test`, `jk lock`, …), worker and tool stdout/stderr
are buffered in a **sliding window of at most 200 lines**, hidden by default so the wedge + progress
stay clean. Long tools (e.g. `native-image`) stream into this channel live — not only on
`--verbose` or failure — so Ctrl-O can show them mid-run.

| Action | Behavior |
|--------|----------|
| **Ctrl-O** | Toggle process-output peek. When **on**: buffered lines are committed above a full-width braille rule (`⠒… ↑ output ↑ …⠒`); only the rule + wedge/tree is the live region (normal spinner line-diff). New output lifts that small region, appends one line, and repaints the wedge — no full-screen redraw. When **off**: the rule is **replaced by a blank line** (not deleted); already-printed lines stay in scrollback. Always keep either the rule or that blank between process output and the wedge. Opening the controlling TTY for this key listener must **not** install JLine's default native {@code SIG_DFL} handlers — those replace {@code GlobalCancel}'s Ctrl-C intercept. |
| **`[config] build-output = true`** (or `JK_BUILD_OUTPUT=true`) | Start with the peek **open** on live plans. Default **`false`** (hidden until Ctrl-O or force-show). Machine or project `[config]`. |
| **Failed tool/worker** (non-zero sub-process exit, e.g. `native-image`) | Force-opens the pane while the plan is still live. |
| **Test failures** | Do **not** force-open — curated test-failure chrome owns that path. |
| Plan settle | Does **not** dump the buffer. If process lines were committed (pane open or force-show), they stay in scrollback; the live region is wiped; **one blank** is printed between that output and the settle chip. |
| `-v` / non-TTY / `--no-progress` / JSONL | Unchanged; no key listener. |

Document only — no on-screen “press Ctrl-O” hint. InheritIO handoffs (`jk run`, `jshell`, …) are out of scope.

**Type-ahead is consumed during animated plans.** The Ctrl-O listener holds the controlling TTY
in raw, no-echo mode for the life of the plan and discards every key except Ctrl-O (Ctrl-C still
raises SIGINT via ISIG). Keys typed during a build — a queued-up shell command, stray Enters —
are *not* delivered to the shell afterwards, unlike the pre-peek behavior where the tty buffered
them. This is inherent to listening at all: any byte read is consumed, and there is no portable
way to push bytes back into the tty input queue (`TIOCSTI` is root-gated or compiled out on
modern kernels). Accepted as the cost of the peek; piped/non-TTY runs install no listener and
are unaffected.

### Completed-module tail

Workspace plans (`jk build`, `jk test`, `jk run`, `jk native`, `jk image`) keep the last few
`✓ [N of M] group:artifact took …` lines **in the live region under the wedge**, newest first.
They are not written into terminal scrollback and they are not part of the process-output peek.
Settle wipes the live region, so those lines do not remain after the result chip.

### Blank-line envelope (JK-1373)

Every **wedge-bearing** human command prints:

1. Exactly **one blank line before** the **first** chrome of the invocation — whichever comes first: prep spinner (`EnsureFreshLock` / `CommandWedge.analyzing`), open spinner, live `JkManager` bar, or settle chip  
2. **No** automatic blank after the last settle line (extra empty row before the shell prompt)

`CommandWedge.envelopeStart()` is **idempotent per leaf command**. Dispatch calls `CommandWedge.resetEnvelope()` before `run`. Spinners, `JkManager`, `JdkDownloadBar`, wizards (`markEnvelopeStarted` after their own leading blank), and the `print*` helpers all open the envelope, so conditional paths (cache-hit vs rebuild, lock freshen before explain) cannot skip or double the blank.

Helpers:

- One-shot success: `CommandWedge.printOk(command, message)`  
- One-shot failure: `CommandWedge.printFail(command, message)`  
- Working / handoff: `CommandWedge.printWorking(command, message)` (stderr)  
- Pre-rendered wedge line: `CommandWedge.printLine(line)` / `printErrLine(line)`  
- Multi-line chrome: `envelopeStart()` / `envelopeStartErr()` then body lines  
- Live plans: `JkManager` opens the leading blank if prep has not already  
- **Exec handoff** (`jk run`): command may print a single separator before `inheritIO`  

Optional blank lines **between** chrome and follow-up tips (e.g. after `jk add`) are fine — that is content spacing, not a trailing envelope.

**Script-mode** commands must **not** use the envelope (paths, tokens, shell hooks, `jk --version`).

**Do not** print raw `JkWedge.chipLine` / `CommandWedge.ok` / `CommandWedge.fail` via `CliOutput` without a `print*` helper or `envelopeStart` — that is how the fully-cached `jk build` fast path skipped the blank.

## Script-mode allowlist (no wedge)

These commands intentionally emit only machine-consumable stdout:

| Command | Typical stdout | Consumer |
|---------|----------------|----------|
| `jk activate <shell>` | PATH + hooks + completions | `eval "$("$HOME/.local/bin/jk" activate bash)"` |
| `jk deactivate` | Teardown script | activate proxy |
| `jk hook-env -s <shell>` | Env sync lines | shell hook |
| `jk jdk home` | `export JAVA_HOME=…` | `eval "$(jk jdk home)"` |
| `jk auth token [provider]` | Single-line token | scripts / curl |
| `jk show` / `jk tasks show` | Absolute path (or `coord\tpath`) | command substitution |
| `jk tool dir` | Tools root path | installers |
| `jk --version` / `-V` | `jk <version>` | CI |
| `jk explain --graph dot\|mermaid` | Graph source | `dot` / editors |
| `jk selective resolve` (+ `--json`) | Paths or JSON | CI selective plans |
| `jk bsp serve` | JSON-RPC on stdio | IDE BSP client |

Adding a new exception requires updating this table.

## List/status surfaces and hybrid settles (JK-1375)

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
| `jk storage usage` | Element · File Count · Size (Jar Files / Native Bins / OCI Images / Worker JARs), plus Total, a spanning utilization row, and a last-cleaned footer |

Use `new Table(title).columns(...).row(...)` (or the `Table.render` static for string cells).
`Table.print()` opens the envelope and degrades to ASCII under `--no-ansi`.

### Wedge header + rows

| Command | Chrome |
|---------|--------|
| `jk doctor` | `≡ Doctor` menu chip, then the checklist rows + summary |
| `jk auth status` | `≡ Auth status` chip, then per-forge status lines |
| `jk history show` | `≡ Build <id>` chip, then the detail block |
| `jk cache usage` | Element · File Count · Size (Class Files / Test Results / Event Logs / Normal·Shadow·Minified Jars / Native Bins / OCI Images / Format Stamps), plus Total (whole cache root), a spanning utilization row, and a last-cleaned footer |

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
| `jk storage clean` | plan console (`Repo` chip); settles with the sweep summary (`Finished sweeping store …`), like `jk cache clean` |

### Documented exceptions (deliberately plain)

| Command | Why |
|---------|-----|
| `jk trust list` | scriptable one-path-per-line contract; agents/paste into shell |
| `jk repo search` | plain aligned `coordinate  versions` list (formerly `jk cache search`); piped/scripted like `jk trust list` |
| `jk inspect` / `jk tasks inspect` | scrape-friendly `key: value` blocks for humans and agents |
| `jk shell` | prints one line then hands the terminal to the spawned shell — no chrome |
| `jk auth login` | device-flow prompts own the terminal; spinner while waiting, wedge on settle |

## Glyph modes (JK-1376, JK-1970)

| Mode | Trigger | Chrome |
|------|---------|--------|
| **nerd** | ANSI + a PUA axis granted | Powerline PUA caps + Unicode glyphs |
| **ansi** | ANSI, no PUA axis granted | Colored chips, Unicode glyphs, **no** PUA (bg-colored space cap) |
| **plain** | `--no-ansi` / `NO_COLOR` / `TERM=dumb` / `CI` | ASCII `+` / `!` / `*` prefixes; progress `#`/`-`; **no animations** |

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

## One-shot vs plan

| Kind | Example | Pattern |
|------|---------|---------|
| One-shot settle | `jk add`, `jk export`, `jk jdk pin` | `CommandWedge.printOk` / `printFail` |
| Live plan | `jk build`, `jk test`, `jk lock` | `JkManager` + settle wedge |
| List / table | `jk library list`, `jk doctor` | Table + title wedge |

## JSONL / quiet

Under `--output json` / `jsonl`, suppress human chrome (no envelope, no wedge). See [machine-output.md](machine-output.md).

## Checklist for new commands

1. Is this **script-mode** (eval / path / token / protocol)? If yes: plain stdout only; document here.  
2. Otherwise: settle with **CommandWedge** (or table/tree/wizard that includes wedge chrome).  
3. Apply the **blank-line envelope**.  
4. Bounded work → bar; indeterminate → spinner; always settle to a **static** icon.  
5. Verify **nerd / ansi / plain** (`--no-ansi`) look intentional.  
6. Agents: document JSONL events if you add machine-visible facts.

## Code map

| Concern | Location |
|---------|----------|
| Styled text | `cli/tui/RichText.java` — markup (`[bold]`, `[#hex]`, `[link url]`, theme tokens) |
| Settled wedge | `cli/tui/JkWedge.java` (`CommandWedge` is envelope + printOk) |
| Pill | `cli/tui/Pill.java` — nerd `label` / ansi padded / plain `[label]` |
| Coord | `cli/tui/Coord.java` + `theme/Coords` RichText factories |
| Code | `SourceCode.java` / `JavaCode` / `KotlinCode` / `GroovyCode` |
| Prompt | `Prompt.java`, `Confirmation.java` (`Confirm` façade) |
| Wizard parts | `WizardSection`, `TextInput`, `Checkbox`, `RadioButton`, `RadioButtonGroup` |
| Progress | `cli/tui/Progress.java` + `ProgressBar.java` (plain live cadence: 20% steps) |
| Tables | `cli/tui/Table.java`. Append snaps child rails to parent edges |
| Trees | `cli/tui/Tree.java` — optional title/root, {@code Gap} / {@code BodyFit}, pills, hanging rich text |
| Glyphs | `cli/tui/Glyphs.java` |
| Live plan | `cli/tui/JkManager.java` |
| Spinner / bar | `Spinner.java`, `SpinnerProgressBar.java` |
| Theme / ANSI gate | `cli/theme/Theme.java` (`styleNamed` for RichText tokens) |
| Mode fixtures | `cli/tui/TuiModeFixturesTest.java` |
