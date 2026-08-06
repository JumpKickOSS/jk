# CLI/TUI brand parity audit (JK-1098)

Audit date: **2026-08-02**. Web tokens: `clients/web` CSS (`--run`, success/error). CLI:
`JkDarkTheme` / `Theme`.

## Alignment already in place

| Concern | CLI | Web |
|---------|-----|-----|
| Success | green chip / `Glyphs.CHECK` | success badge green |
| Failure | red chip / cross | error red |
| Running / work | blue pulse / plan chip | run blue `#3D9BFF` |
| Identity / plan | cyan accents | brand cyan |
| Plain `--no-ansi` | `PlainAscii` + ASCII glyphs | N/A |

## Mismatches found

| Item | Severity | Action |
|------|----------|--------|
| Progress bar gradient lead vs `--run` blue | Low | Deferred — gradient readability on dark TTY preferred |
| “Build Jobs” vs web “Jobs” wording | Fixed earlier (`jk jobs`) | Aligned |
| Logo / wordmark in TTY | N/A | Non-goal (no sixel) |
| Light theme | N/A | Non-goal |

## Process

Web owns brand hex in CSS; CLI mirrors via `JkDarkTheme` constants. After web brand changes,
diff CSS variables against `JkDarkTheme` and adjust accents only when state semantics match.

## Deferred

- Pixel-level chrome matching
- Progress gradient retune without a user-visible bug
