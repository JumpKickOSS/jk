# CLI ↔ web visual alignment

**Ticket:** JK-1081 · **Status:** done (design freeze + one closed mismatch)  
**Source of truth for hex:** web `clients/web/.../style.css` `:root`  
**CLI implementation:** `JkDarkTheme` / `Theme` (`clients/cli/.../theme/`)

## Contract

**Color means state, never decoration.** Terminal and dashboard should read as one product:

| State | Meaning | Hue family |
|-------|---------|------------|
| **working / run** | in progress | blue (`--run` / `--runc`) |
| **done / ok** | success | green (`--ok` / `--okc`) |
| **error** | failure | red (`--err`) |
| **warn** | cancelled, issue, attention | amber (`--warn`) |
| **identity / chrome** | brand accents, coords, nav | cyan (`--cg` / `--cn`), violet gradient (`--prog-*`) |

Chips and wedges use the same vocabulary: **status on a chip**, not rainbow decoration.
Command wedges (`CommandWedge`): working → blue plan chip; success → green chip; fail → red chip;
cancel → black on badge gray (`GRAY` / `--mu`), same fill as `jk explain` pills.

## Token map (web CSS → CLI)

| Web token | Hex | CLI constant / slot | Role |
|-----------|-----|---------------------|------|
| `--run` | `#3D9BFF` | `PRIMARY`, `NORMAL_BLUE`, `primary()` | Working / brand primary |
| `--runc` | `#124A8C` | `PRIMARY_DARK`, `indigoBadge` | Deep blue fill / selection |
| `--run-line` | `#5AB0FF` | `BRIGHT_BLUE`, `blue()` | Brighter running accent |
| `--ok` | `#00FF87` | `BRIGHT_GREEN`, `brightGreen()` | Success glyph / bright ok |
| `--okc` | `#00B368` | `NORMAL_GREEN`, `success()`, completed rail | Deep green text/fill |
| `--err` | `#FF3366` | `NORMAL_RED`, `error()`, failure chip | Error |
| `--warn` | `#FFB800` | `NORMAL_YELLOW`, `warning()` | Warning / cancelled |
| `--cg` | `#00D4E0` | `NORMAL_CYAN`, `cyan()`, coord group | Secondary cyan |
| `--cn` | `#00F0FF` | `BRIGHT_CYAN`, coord name | Accent cyan |
| `--prog-b` | `#C04DFF` | `ACCENT`, `NORMAL_MAGENTA` | Gradient end (violet) |
| `--prog-a` | `#4D8BFF` | *(see deferred)* | Web progress start |
| `--tx` | `#CFD8DC` | `FOREGROUND`, `NORMAL_WHITE` | Body text |
| `--bright` | `#ECEFF1` | `BRIGHT_WHITE` | Focused white |
| `--mu` | `#90A4AE` | `GRAY` | Muted / badge bg |
| `--path` | `#969DD4` | `PATH`, `path()` | Paths |
| `--bg` | `#090C11` | *(web canvas; CLI has no page bg)* | Dashboard surface |

BuildPlan chips (CLI):

| Chip | Fill | Maps to |
|------|------|---------|
| Working (`pipelineChip`) | `HEADER_BLUE` `#0F4786` | Dark royal blue (AA white text) |
| Success | `PIPELINE_GREEN` = `--okc` × 0.7 | Darker deep green for contrast |
| Failure | `NORMAL_RED` = `--err` | Same as web failed badges |

Test-failure assertion bodies (AssertJ Expected / But Was values) are **uncolored on both
surfaces** (CLI d8dd7760, web JK-2111): values are data, not verdicts — the FAILED chip carries
the verdict.

Gradients (CLI title / spinner / progress): blue → violet (`PRIMARY`/`BRIGHT_BLUE` → `ACCENT`/`BRIGHT_MAGENTA`), matching web’s electric blue → neon violet story (`--prog-a` → `--prog-b`).

## Intentionally not synced

| Item | Why |
|------|-----|
| GitHub syntax palette | Compiler snippets should look like github.com, not Jk Dark |
| Legacy `tip` / `helpHint` SGR bodies | 16-color error accents; keep stable bytes |
| Web-only surface tokens (`--s1`…`--s3`, radii, motion) | Dashboard layout, not TTY |
| Pixel / glyph parity | Non-goal of JK-1081 |

## Closed in this ticket

**Web `app.js` sparkline fallbacks** still used old Material defaults (`#4caf50`, `#e91e63`, …) while claiming to match `:root`. Fallbacks now equal the CSS tokens so a pre-stylesheet flash still means the same states.

## Deferred (not blocking)

1. **CLI progress gradient start** uses `--run` (`#3D9BFF`) rather than web `--prog-a` (`#4D8BFF`). Visually close; unify only if a side-by-side review cares.
2. **Wedge label vocabulary** vs dashboard badge copy — keep iterating under UX tickets when a concrete mismatch appears; no second chrome language.
3. **Light theme** — neither surface ships one; out of scope.

## How to keep them aligned

1. Change hex in **web `style.css` `:root` first**.
2. Mirror named hues in **`JkDarkTheme`** (and any `cssVar` fallbacks in `app.js`).
3. Prefer semantic slots (`error()`, `planSuccessChip()`) over raw RGB at call sites.
4. Golden ANSI / theme tests catch accidental CLI drift.
