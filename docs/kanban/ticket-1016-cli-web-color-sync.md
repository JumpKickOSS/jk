# ticket-1016 — CLI theme color sync: adopt the web UI palette

**Priority:** P2 (polish / consistency)  
**Status:** done  
**Branch:** `ticket-1016-cli-web-color-sync`  
**Refs:** `JkDarkTheme`, `Theme`, `Diagnostics.Palette.DEFAULT`,
`GradientTest`, `SpinnerTest`, `ProgressBarTest`, `SpinnerProgressBarTest`

## Problem

The CLI theme (`JkDarkTheme`) and the web dashboard (`clients/web/.../style.css`) had drifted:
the CLI shipped a Material Indigo/Pink palette while the web UI uses a neon dark palette. "Color
means state, never decoration" is a shared contract across terminal and dashboard, so the two
should read as one product. This ticket re-points the CLI's named hues (and brand/gradients) at
the web `:root` palette, which is the source of truth.

## Mapping (web `--token` → CLI constant)

| Hue | CLI normal → | CLI bright → |
|---|---|---|
| green | `--okc` `#00B368` | `--ok` `#00FF87` |
| red | `--err` `#FF3366` | derived (brighter) |
| blue | `--run` `#3D9BFF` | `--run-line` `#5AB0FF` |
| yellow | `--warn` `#FFB800` | derived (brighter) |
| cyan | `--cg` `#00D4E0` | `--cn` `#00F0FF` |
| magenta | `--prog-b` `#C04DFF` | derived (brighter) |

Brand/gradients: `PRIMARY` → `--run`; `PRIMARY_DARK` / `SELECTION_BG` → `--runc` `#124A8C`;
`ACCENT` → `--prog-b` violet, so all three gradients read blue→violet. `SHELL_ORANGE` re-derived
from `--warn` amber warmed toward `--err` (the web has no orange). `indigoBadge` fill + powerline
cap moved to `PRIMARY_DARK` for AA white-text contrast.

Left as-is: neutrals (already equal to web `--tx`/`--bright`/`--mu`/`--path`), the GitHub
syntax-highlight palette (deliberately github.com-like), and the two legacy 16-color accents
(`tip`, `helpHint`).

## Acceptance

- [x] CLI named hues + brand/gradients match the web `:root` palette
- [x] `Diagnostics.Palette.DEFAULT` fallback kept in sync (cyan + red)
- [x] Golden ANSI assertions updated; `:cli-engine` gradient/spinner/progress tests green
- [x] `:cli` + `:resolver` compile; `GradientTest` green
