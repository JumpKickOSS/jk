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

## CommandWedge is the look

Almost every human command settles with a **CommandWedge** (green ok / red fail / blue work chip), or embeds wedge chrome inside:

- **BoxTable** title bar (`≡ Title` chip + box fill)
- **Tree** / plan step list under a wedge+progress header
- **Wizard** steps with indigo/title chips

API: `cc.jumpkick.cli.tui.CommandWedge` (delegates to `BuildPlanWedge`).

### Progress variants

| Work shape | Chrome |
|------------|--------|
| Bounded known progress | CommandWedge + progress bar (plan `CommandManager`, or `SpinnerProgressBar`) |
| Indeterminate / short | Spinner-only wedge (`CommandWedge.analyzing` / `Spinner.showWedge`) |
| Settled | Static icon — `CommandWedge.ok` / `fail` / `chip` (never leave a spinner running) |

### Blank-line envelope (JK-1373)

Every **wedge-bearing** human command prints:

1. Exactly **one blank line before** the **first** chrome of the invocation — whichever comes first: prep spinner (`EnsureFreshLock` / `CommandWedge.analyzing`), open spinner, live `CommandManager` bar, or settle chip  
2. **No** automatic blank after the last settle line (extra empty row before the shell prompt)

`CommandWedge.envelopeStart()` is **idempotent per leaf command**. Dispatch calls `CommandWedge.resetEnvelope()` before `run`. Spinners, `CommandManager`, and `printOk`/`printFail` all go through `envelopeStart`, so conditional paths (cache-hit vs rebuild, lock freshen before explain) cannot skip or double the blank.

Helpers:

- One-shot success: `CommandWedge.printOk(command, message)`  
- One-shot failure: `CommandWedge.printFail(command, message)`  
- Multi-line chrome: `envelopeStart()` then body lines  
- Live plans: `CommandManager` opens the leading blank if prep has not already  
- **Exec handoff** (`jk run`): command may print a single separator before `inheritIO`  

Optional blank lines **between** chrome and follow-up tips (e.g. after `jk add`) are fine — that is content spacing, not a trailing envelope.

**Script-mode** commands must **not** use the envelope (paths, tokens, shell hooks, `jk --version`).

**Do not** print raw `BuildPlanWedge.chipLine` / `CommandWedge.ok` without `printOk` or `envelopeStart` — that is how the fully-cached `jk build` fast path skipped the blank.

## Script-mode allowlist (no wedge)

These commands intentionally emit only machine-consumable stdout:

| Command | Typical stdout | Consumer |
|---------|----------------|----------|
| `jk activate <shell>` | Shell hook script | `eval "$(jk activate bash)"` |
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

### BoxTable + title wedge

| Command | Table |
|---------|-------|
| `jk library list` | Name · Coordinates (· Layer with `--show-layer`); per-layer tables with `--group-by-layer` |
| `jk library search` | Name · Coordinates (· Layer) · Cached |
| `jk tool list` | Tool · Coordinates · Source · Launcher |
| `jk history list` | status · Id · Project · Kind · Took · When · Saved · Notes |
| `jk tasks` | Name · Stage · Description (per module at a workspace root) |
| `jk jdk list` | (wave 1 — the exemplar) |
| `jk storage usage` | Element · File Count · Size (Jar Files / Native Bins / OCI Images / Worker JARs), plus Total, a spanning utilization row, and a last-cleaned footer (hand-rolled on the shared BoxTable chrome) |

Use `cc.jumpkick.cli.tui.BoxTable.render(title, headers, rows)`; it opens the envelope
via the caller's `CommandWedge.envelopeStart()` and degrades to ASCII under `--no-ansi`.

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

## Glyph modes (JK-1376)

| Mode | Trigger | Chrome |
|------|---------|--------|
| **nerd** | ANSI + `[global].nerdfont = true` | Powerline PUA caps (``) + Unicode glyphs |
| **ansi** | ANSI, nerdfont false | Colored chips, Unicode glyphs, **no** PUA (bg-colored space cap) |
| **plain** | `--no-ansi` / `NO_COLOR` / `TERM=dumb` / `CI` | ASCII `+` / `!` / `*` prefixes; progress `#`/`-`; **no animations** |

Rules:

- Prefer `Glyphs.check()` / `cross()` / `pulse()` (and friends) over hardcoding `✓` when emitting markers outside wedges.
- Nerd PUA only via `BuildPlanWedge.cap(..., nerdfont)` / `GlobalConfig.nerdfont()`.
- Plain mode: no CSI color, no spinner animation frames, no OSC taskbar required for correctness.

## One-shot vs plan

| Kind | Example | Pattern |
|------|---------|---------|
| One-shot settle | `jk add`, `jk export`, `jk jdk pin` | `CommandWedge.printOk` / `printFail` |
| Live plan | `jk build`, `jk test`, `jk lock` | `CommandManager` + settle wedge |
| List / table | `jk library list`, `jk doctor` | BoxTable + title wedge (wave 2: JK-1375) |

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
| Settled wedge | `cli/tui/CommandWedge.java`, `BuildPlanWedge.java` |
| Glyphs | `cli/tui/Glyphs.java` |
| Live plan | `cli/tui/CommandManager.java` |
| Spinner / bar | `Spinner.java`, `ProgressBar.java`, `SpinnerProgressBar.java` |
| Tables | `BoxTable.java` |
| Theme / ANSI gate | `cli/theme/Theme.java` |
| Mode fixtures | `cli/tui/TuiModeFixturesTest.java` |
