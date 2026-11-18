# Graph bench across agents: banked table (2026-11-17, daybreak WSL)

Arms: **A** `jk` (0.52.0 graph), **B** `jk-before` (0.51.1), **C** `jk-nograph` (no graph tools; see README.md),
**F** `jk` from main with gate 93's graph fixes (the sliver only).
Medians over runs that reported tokens; for the arm rows, all of the tier's tasks pooled. Est. cost is the API
list price of reported tokens (an API-equivalent estimate for subscription CLIs; README.md says how cache warmth
moves it). Savings: positive means the first arm lower. A row with "1 run" has no noise estimate.

Every table names its host. **daybreak** (Windows 11 + WSL Ubuntu 26.04, 12 GB) is the measurement host; tier 0
was first run on **bocabox** (Fedora) and is kept below as a second host's table, never pooled with daybreak's.

Status: tier 0 on daybreak complete (B redone with 0.51.1); tier 1 complete; sliver complete (A, B, C, F, all ten configs, codex from the catch-up). Stage R (main with the JK-3MF95 fix, arm M) next.

**Void rows are in no table** (README.md, "Defects found after tier 0"): daybreak's 40 tier-0 B rows ran 0.52.0
(rows-tier0-daybreak-VOID-B-ran-0.52.0.jsonl), and its 36 tier-1 codex rows ran without a codex login
(rows-tier1-daybreak-VOID-codex-login.jsonl); codex's first tier-0 B and sliver rows failed fast on the same lost login, and the codex catch-up replaced them
(rows-tier0-daybreak-VOID-codex-login.jsonl, rows-tier2-daybreak-VOID-codex-login.jsonl). Redone rows replace them as they are run.

**JK-3MF95:** every arm-A callers answer of tier 1's petclinic-callers-addpet (30 of 30 runs) ended "compile skipped ...:
search it for calls too" (a defect of 0.51.1 and 0.52.x, fixed on main); no sliver answer carries it. README.md says more.

## Tier 0 on daybreak (smoke fixture, 1 run per cell)

| Config | Arm | Runs | Green | Errors | Median tokens | Turns | Wall s | jk s | Est. cost | CLI versions |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A | 4 | 4/4 (100%) | 0 | 23.9k | 3.5 | 11.3 | 1.0 | $0.0160 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | B | 4 | 4/4 (100%) | 0 | 33.9k | 6 | 13.2 | 0.6 | $0.0257 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | C | 4 | 4/4 (100%) | 0 | 34.6k | 9.5 | 16.9 | 0.1 | $0.0379 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | A | 4 | 4/4 (100%) | 0 | 27.4k | 4 | 12.6 | 1.0 | $0.0343 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | B | 4 | 4/4 (100%) | 0 | 43.7k | 8 | 19.6 | 0.4 | $0.0870 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | C | 4 | 4/4 (100%) | 0 | 43.2k | 10.5 | 23.1 | 0.2 | $0.0938 | 2.1.326 (Claude Code) |
| codex / gpt-6.1-terra low (default) | A | 4 | 4/4 (100%) | 0 | 72.8k | 3 | 12.8 | 0.8 | $0.0104 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | B | 4 | 4/4 (100%) | 0 | 115.6k | 5.5 | 18.0 | 0.6 | $0.0154 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | C | 4 | 4/4 (100%) | 0 | 112.3k | 5 | 20.9 | 0.0 | $0.0198 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | A | 4 | 4/4 (100%) | 0 | 80.9k | 3 | 15.0 | 0.9 | $0.0283 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | B | 4 | 4/4 (100%) | 0 | 100.6k | 7 | 19.9 | 0.5 | $0.0401 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | C | 4 | 4/4 (100%) | 0 | 158.1k | 8.5 | 26.4 | 0.3 | $0.0495 | codex-cli 0.166.0 |
| grok / grok-4.7 high 256k | A | 4 | 4/4 (100%) | 0 | 41.6k | 5 | 24.6 | 4.7 | $0.0491 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | B | 4 | 4/4 (100%) | 0 | 107.2k | 8.5 | 69.5 | 8.2 | $0.1110 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | C | 4 | 4/4 (100%) | 0 | 325.1k | 19 | 148.4 | 11.6 | $0.3020 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | A | 4 | 4/4 (100%) | 0 | 32.9k | 4.5 | 11.9 | 2.8 | $0.0361 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | B | 4 | 4/4 (100%) | 0 | 78.2k | 8 | 26.2 | 6.5 | $0.0609 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | C | 4 | 4/4 (100%) | 0 | 199.9k | 16.5 | 61.9 | 11.4 | $0.1312 | grok 1.0.77 (50ca579fa996) [stable] |
| cursor / composer-2.5 | A | 4 | 4/4 (100%) | 0 | 62.1k | 4 | 32.8 | 0.8 | $0.0183 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | B | 4 | 4/4 (100%) | 0 | 148.4k | 9 | 34.5 | 0.6 | $0.0405 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | C | 4 | 4/4 (100%) | 0 | 154.5k | 9 | 35.7 | 0.3 | $0.0413 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | A | 4 | 4/4 (100%) | 0 | 54.9k | 3 | 36.7 | 0.9 | $0.0609 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | B | 4 | 4/4 (100%) | 0 | 139.8k | 6.5 | 63.5 | 0.8 | $0.1417 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | C | 4 | 4/4 (100%) | 0 | 240.5k | 11.5 | 136.2 | 0.8 | $0.2717 | 2026.11.13-eacade1 |
| agy / gemini-4-hydrogen-medium | A | 4 | 4/4 (100%) | 0 | 25.8k | 4 | 19.4 | 3.0 | $0.0291 | 1.5.10, 1.5.9 |
| agy / gemini-4-hydrogen-medium | B | 4 | 3/4 (75%) | 0 | 60.2k | 7 | 40.7 | 7.2 | $0.0502 | 1.5.10 |
| agy / gemini-4-hydrogen-medium | C | 4 | 4/4 (100%) | 0 | 120.4k | 11.5 | 60.7 | 8.1 | $0.0908 | 1.5.10 |
| muse / spark-1.3-contributor high | A | 4 | 4/4 (100%) | 0 | 141.0k | 7 | 55.3 | 0.9 | $0.0046 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | B | 4 | 4/4 (100%) | 0 | 344.0k | 13 | 115.6 | 0.7 | $0.0045 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | C | 4 | 4/4 (100%) | 0 | 481.4k | 20 | 91.5 | 0.6 | $0.0049 | Muse Code 1.4.23 (1.4.23-R8977.1) |

### Tier 0 on daybreak (smoke fixture, 1 run per cell): savings of A against B, A against C

Per config, summed over the tasks both arms measured (sum of per-task medians); positive means the first arm lower. Noise: with repeats, `overlap` means the arms' summed per-task ranges overlap, so the difference is inside run-to-run noise; with one run per cell there is no noise estimate.

| Config | Arm vs | Tasks | Green arm / other | Tokens arm → other | Δ tokens | Δ turns | Δ wall | Δ cost | Tokens noise |
|---|---|---:|---|---|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A vs B | 4 | 4/4 / 4/4 | 91.8k → 146.2k | +37% | +54% | +23% | +46% | 1 run |
| claude2 / sonnet-5-5 medium | A vs C | 4 | 4/4 / 4/4 | 91.8k → 137.0k | +33% | +66% | +34% | +60% | 1 run |
| claude2 / opus-5-5 medium | A vs B | 4 | 4/4 / 4/4 | 114.9k → 187.3k | +39% | +49% | +31% | +51% | 1 run |
| claude2 / opus-5-5 medium | A vs C | 4 | 4/4 / 4/4 | 114.9k → 166.9k | +31% | +55% | +38% | +55% | 1 run |
| codex / gpt-6.1-terra low (default) | A vs B | 4 | 4/4 / 4/4 | 276.5k → 485.6k | +43% | +48% | +34% | +27% | 1 run |
| codex / gpt-6.1-terra low (default) | A vs C | 4 | 4/4 / 4/4 | 276.5k → 495.0k | +44% | +40% | +39% | +51% | 1 run |
| codex / gpt-6.1-sol low (default) | A vs B | 4 | 4/4 / 4/4 | 312.9k → 384.4k | +19% | +53% | +27% | +26% | 1 run |
| codex / gpt-6.1-sol low (default) | A vs C | 4 | 4/4 / 4/4 | 312.9k → 598.4k | +48% | +56% | +37% | +42% | 1 run |
| grok / grok-4.7 high 256k | A vs B | 4 | 4/4 / 4/4 | 169.7k → 940.2k | +82% | +61% | +2% | +72% | 1 run |
| grok / grok-4.7 high 256k | A vs C | 4 | 4/4 / 4/4 | 169.7k → 1794.9k | +91% | +76% | +43% | +87% | 1 run |
| grok / grok-4.5 high | A vs B | 4 | 4/4 / 4/4 | 129.0k → 931.7k | +86% | +65% | +77% | +74% | 1 run |
| grok / grok-4.5 high | A vs C | 4 | 4/4 / 4/4 | 129.0k → 1143.7k | +89% | +77% | +87% | +82% | 1 run |
| cursor / composer-2.5 | A vs B | 4 | 4/4 / 4/4 | 261.1k → 776.1k | +66% | +61% | +11% | +61% | 1 run |
| cursor / composer-2.5 | A vs C | 4 | 4/4 / 4/4 | 261.1k → 1126.7k | +77% | +68% | +29% | +72% | 1 run |
| cursor / grok-4.7-high | A vs B | 4 | 4/4 / 4/4 | 233.0k → 727.7k | +68% | +59% | +64% | +65% | 1 run |
| cursor / grok-4.7-high | A vs C | 4 | 4/4 / 4/4 | 233.0k → 1037.7k | +78% | +73% | +74% | +79% | 1 run |
| agy / gemini-4-hydrogen-medium | A vs B | 4 | 4/4 / 3/4 | 105.8k → 328.6k | +68% | +52% | +54% | +51% | 1 run |
| agy / gemini-4-hydrogen-medium | A vs C | 4 | 4/4 / 4/4 | 105.8k → 485.9k | +78% | +67% | +69% | +68% | 1 run |
| muse / spark-1.3-contributor high | A vs B | 4 | 4/4 / 4/4 | 563.5k → 1443.8k | +61% | +50% | +61% | +12% | 1 run |
| muse / spark-1.3-contributor high | A vs C | 4 | 4/4 / 4/4 | 563.5k → 1804.8k | +69% | +62% | +52% | +4% | 1 run |

### Tier 0 on daybreak (smoke fixture, 1 run per cell): per task

| Config | Task | Arm | Runs | Green | Median tokens (min–max) | Turns | Wall s | Est. cost |
|---|---|---|---:|---:|---|---:|---:|---:|
| claude2 / sonnet-5-5 medium | smoke-callers | A | 1 | 1 | 20.4k | 3 | 9.6 | $0.0142 |
| claude2 / sonnet-5-5 medium | smoke-callers | B | 1 | 1 | 19.8k | 3 | 9.4 | $0.0142 |
| claude2 / sonnet-5-5 medium | smoke-callers | C | 1 | 1 | 29.9k | 6 | 12.5 | $0.0283 |
| claude2 / sonnet-5-5 medium | smoke-rename | A | 1 | 1 | 12.8k | 2 | 8.6 | $0.0105 |
| claude2 / sonnet-5-5 medium | smoke-rename | B | 1 | 1 | 12.4k | 2 | 8.5 | $0.0101 |
| claude2 / sonnet-5-5 medium | smoke-rename | C | 1 | 1 | 22.6k | 9 | 15.5 | $0.0360 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | A | 1 | 1 | 31.2k | 4 | 13.0 | $0.0223 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | B | 1 | 1 | 66.0k | 14 | 24.5 | $0.0595 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | C | 1 | 1 | 45.2k | 13 | 22.8 | $0.0573 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | A | 1 | 1 | 27.4k | 4 | 14.6 | $0.0178 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | B | 1 | 1 | 48.1k | 9 | 17.1 | $0.0373 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | C | 1 | 1 | 39.2k | 10 | 18.4 | $0.0399 |
| claude2 / opus-5-5 medium | smoke-callers | A | 1 | 1 | 28.3k | 4 | 12.5 | $0.0377 |
| claude2 / opus-5-5 medium | smoke-callers | B | 1 | 1 | 19.8k | 3 | 9.7 | $0.0624 |
| claude2 / opus-5-5 medium | smoke-callers | C | 1 | 1 | 28.8k | 4 | 12.8 | $0.0472 |
| claude2 / opus-5-5 medium | smoke-rename | A | 1 | 1 | 12.8k | 2 | 9.1 | $0.0218 |
| claude2 / opus-5-5 medium | smoke-rename | B | 1 | 1 | 19.1k | 3 | 10.6 | $0.0285 |
| claude2 / opus-5-5 medium | smoke-rename | C | 1 | 1 | 51.7k | 12 | 24.3 | $0.1039 |
| claude2 / opus-5-5 medium | smoke-rename-class | A | 1 | 1 | 26.4k | 4 | 12.6 | $0.0310 |
| claude2 / opus-5-5 medium | smoke-rename-class | B | 1 | 1 | 67.6k | 16 | 29.6 | $0.1266 |
| claude2 / opus-5-5 medium | smoke-rename-class | C | 1 | 1 | 47.6k | 15 | 28.4 | $0.1188 |
| claude2 / opus-5-5 medium | smoke-rename-field | A | 1 | 1 | 47.3k | 8 | 20.3 | $0.0696 |
| claude2 / opus-5-5 medium | smoke-rename-field | B | 1 | 1 | 80.9k | 13 | 28.7 | $0.1116 |
| claude2 / opus-5-5 medium | smoke-rename-field | C | 1 | 1 | 38.7k | 9 | 22.0 | $0.0836 |
| codex / gpt-6.1-terra low (default) | smoke-callers | A | 1 | 1 | 84.8k | 3 | 14.3 | $0.0159 |
| codex / gpt-6.1-terra low (default) | smoke-callers | B | 1 | 1 | 61.8k | 3 | 11.0 | $0.0104 |
| codex / gpt-6.1-terra low (default) | smoke-callers | C | 1 | 1 | 130.8k | 5 | 16.0 | $0.0221 |
| codex / gpt-6.1-terra low (default) | smoke-rename | A | 1 | 1 | 46.0k | 2 | 9.1 | $0.0064 |
| codex / gpt-6.1-terra low (default) | smoke-rename | B | 1 | 1 | 45.9k | 2 | 9.7 | $0.0062 |
| codex / gpt-6.1-terra low (default) | smoke-rename | C | 1 | 1 | 77.6k | 4 | 22.7 | $0.0105 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | A | 1 | 1 | 64.3k | 3 | 14.1 | $0.0084 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | B | 1 | 1 | 169.5k | 8 | 25.0 | $0.0204 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | C | 1 | 1 | 192.8k | 6 | 20.7 | $0.0384 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | A | 1 | 1 | 81.4k | 4 | 11.4 | $0.0125 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | B | 1 | 1 | 208.4k | 10 | 28.7 | $0.0220 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | C | 1 | 1 | 93.7k | 5 | 21.0 | $0.0175 |
| codex / gpt-6.1-sol low (default) | smoke-callers | A | 1 | 1 | 85.0k | 3 | 15.5 | $0.0369 |
| codex / gpt-6.1-sol low (default) | smoke-callers | B | 1 | 1 | 84.6k | 3 | 12.3 | $0.0520 |
| codex / gpt-6.1-sol low (default) | smoke-callers | C | 1 | 1 | 110.4k | 5 | 16.4 | $0.0382 |
| codex / gpt-6.1-sol low (default) | smoke-rename | A | 1 | 1 | 66.1k | 3 | 14.5 | $0.0185 |
| codex / gpt-6.1-sol low (default) | smoke-rename | B | 1 | 1 | 47.9k | 3 | 11.6 | $0.0198 |
| codex / gpt-6.1-sol low (default) | smoke-rename | C | 1 | 1 | 168.9k | 10 | 29.8 | $0.0497 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | A | 1 | 1 | 81.1k | 3 | 13.9 | $0.0294 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | B | 1 | 1 | 135.3k | 13 | 34.8 | $0.0441 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | C | 1 | 1 | 171.9k | 9 | 27.9 | $0.0561 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | A | 1 | 1 | 80.7k | 5 | 18.7 | $0.0272 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | B | 1 | 1 | 116.5k | 11 | 27.5 | $0.0361 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | C | 1 | 1 | 147.2k | 8 | 24.9 | $0.0492 |
| grok / grok-4.7 high 256k | smoke-callers | A | 1 | 1 | 41.2k | 5 | 13.4 | $0.0330 |
| grok / grok-4.7 high 256k | smoke-callers | B | 1 | 1 | 40.9k | 5 | 13.2 | $0.0447 |
| grok / grok-4.7 high 256k | smoke-callers | C | 1 | 1 | 333.8k | 19 | 100.8 | $0.2860 |
| grok / grok-4.7 high 256k | smoke-rename | A | 1 | 1 | 40.7k | 5 | 302.6 | $0.0629 |
| grok / grok-4.7 high 256k | smoke-rename | B | 1 | 1 | 34.3k | 4 | 14.8 | $0.0380 |
| grok / grok-4.7 high 256k | smoke-rename | C | 1 | 1 | 248.4k | 17 | 109.5 | $0.3017 |
| grok / grok-4.7 high 256k | smoke-rename-class | A | 1 | 1 | 45.8k | 5 | 22.9 | $0.0704 |
| grok / grok-4.7 high 256k | smoke-rename-class | B | 1 | 1 | 691.7k | 30 | 219.1 | $0.4568 |
| grok / grok-4.7 high 256k | smoke-rename-class | C | 1 | 1 | 896.5k | 30 | 247.1 | $0.6569 |
| grok / grok-4.7 high 256k | smoke-rename-field | A | 1 | 1 | 41.9k | 5 | 26.4 | $0.0354 |
| grok / grok-4.7 high 256k | smoke-rename-field | B | 1 | 1 | 173.4k | 12 | 124.3 | $0.1774 |
| grok / grok-4.7 high 256k | smoke-rename-field | C | 1 | 1 | 316.3k | 19 | 187.4 | $0.3023 |
| grok / grok-4.5 high | smoke-callers | A | 1 | 1 | 25.7k | 4 | 7.9 | $0.0214 |
| grok / grok-4.5 high | smoke-callers | B | 1 | 1 | 27.3k | 4 | 7.7 | $0.0221 |
| grok / grok-4.5 high | smoke-callers | C | 1 | 1 | 204.4k | 18 | 59.8 | $0.1313 |
| grok / grok-4.5 high | smoke-rename | A | 1 | 1 | 22.0k | 3 | 9.8 | $0.0346 |
| grok / grok-4.5 high | smoke-rename | B | 1 | 1 | 21.5k | 3 | 7.1 | $0.0440 |
| grok / grok-4.5 high | smoke-rename | C | 1 | 1 | 133.4k | 12 | 41.2 | $0.0928 |
| grok / grok-4.5 high | smoke-rename-class | A | 1 | 1 | 40.0k | 5 | 13.9 | $0.0376 |
| grok / grok-4.5 high | smoke-rename-class | B | 1 | 1 | 753.8k | 30 | 153.5 | $0.4069 |
| grok / grok-4.5 high | smoke-rename-class | C | 1 | 1 | 610.5k | 30 | 209.6 | $0.4356 |
| grok / grok-4.5 high | smoke-rename-field | A | 1 | 1 | 41.3k | 5 | 17.1 | $0.0494 |
| grok / grok-4.5 high | smoke-rename-field | B | 1 | 1 | 129.1k | 12 | 44.6 | $0.0779 |
| grok / grok-4.5 high | smoke-rename-field | C | 1 | 1 | 195.4k | 15 | 64.0 | $0.1311 |
| cursor / composer-2.5 | smoke-callers | A | 1 | 1 | 46.0k | 3 | 18.0 | $0.0123 |
| cursor / composer-2.5 | smoke-callers | B | 1 | 1 | 45.9k | 3 | 18.8 | $0.0124 |
| cursor / composer-2.5 | smoke-callers | C | 1 | 1 | 136.0k | 9 | 37.1 | $0.0373 |
| cursor / composer-2.5 | smoke-rename | A | 1 | 1 | 59.6k | 4 | 32.8 | $0.0185 |
| cursor / composer-2.5 | smoke-rename | B | 1 | 1 | 87.4k | 6 | 37.0 | $0.0254 |
| cursor / composer-2.5 | smoke-rename | C | 1 | 1 | 117.6k | 8 | 24.3 | $0.0300 |
| cursor / composer-2.5 | smoke-rename-class | A | 1 | 1 | 64.5k | 4 | 32.9 | $0.0182 |
| cursor / composer-2.5 | smoke-rename-class | B | 1 | 1 | 209.5k | 12 | 32.1 | $0.0557 |
| cursor / composer-2.5 | smoke-rename-class | C | 1 | 1 | 173.0k | 9 | 34.3 | $0.0454 |
| cursor / composer-2.5 | smoke-rename-field | A | 1 | 1 | 91.0k | 6 | 40.8 | $0.0294 |
| cursor / composer-2.5 | smoke-rename-field | B | 1 | 1 | 433.3k | 23 | 52.5 | $0.1067 |
| cursor / composer-2.5 | smoke-rename-field | C | 1 | 1 | 700.0k | 27 | 79.2 | $0.1680 |
| cursor / grok-4.7-high | smoke-callers | A | 1 | 1 | 52.4k | 3 | 20.8 | $0.0519 |
| cursor / grok-4.7-high | smoke-callers | B | 1 | 1 | 53.0k | 3 | 26.1 | $0.0611 |
| cursor / grok-4.7-high | smoke-callers | C | 1 | 1 | 204.8k | 11 | 86.5 | $0.2390 |
| cursor / grok-4.7-high | smoke-rename | A | 1 | 1 | 70.8k | 4 | 38.4 | $0.0701 |
| cursor / grok-4.7-high | smoke-rename | B | 1 | 1 | 54.9k | 3 | 38.2 | $0.0838 |
| cursor / grok-4.7-high | smoke-rename | C | 1 | 1 | 268.8k | 12 | 153.6 | $0.3045 |
| cursor / grok-4.7-high | smoke-rename-class | A | 1 | 1 | 56.9k | 3 | 35.6 | $0.0654 |
| cursor / grok-4.7-high | smoke-rename-class | B | 1 | 1 | 224.7k | 10 | 88.8 | $0.1996 |
| cursor / grok-4.7-high | smoke-rename-class | C | 1 | 1 | 212.2k | 10 | 118.9 | $0.2175 |
| cursor / grok-4.7-high | smoke-rename-field | A | 1 | 1 | 52.9k | 3 | 37.7 | $0.0565 |
| cursor / grok-4.7-high | smoke-rename-field | B | 1 | 1 | 395.1k | 16 | 212.0 | $0.3488 |
| cursor / grok-4.7-high | smoke-rename-field | C | 1 | 1 | 351.9k | 15 | 158.2 | $0.4185 |
| agy / gemini-4-hydrogen-medium | smoke-callers | A | 1 | 1 | 24.3k | 4 | 18.7 | $0.0257 |
| agy / gemini-4-hydrogen-medium | smoke-callers | B | 1 | 1 | 23.8k | 4 | 15.8 | $0.0247 |
| agy / gemini-4-hydrogen-medium | smoke-callers | C | 1 | 1 | 122.3k | 11 | 46.6 | $0.0946 |
| agy / gemini-4-hydrogen-medium | smoke-rename | A | 1 | 1 | 19.4k | 3 | 15.7 | $0.0233 |
| agy / gemini-4-hydrogen-medium | smoke-rename | B | 1 | 1 | 18.6k | 3 | 19.0 | $0.0216 |
| agy / gemini-4-hydrogen-medium | smoke-rename | C | 1 | 1 | 87.3k | 11 | 42.6 | $0.0668 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | A | 1 | 1 | 34.7k | 5 | 25.9 | $0.0341 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | B | 1 | 1 | 96.5k | 10 | 62.4 | $0.0758 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | C | 1 | 1 | 118.5k | 12 | 99.5 | $0.0870 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | A | 1 | 1 | 27.4k | 4 | 20.2 | $0.0326 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | B | 1 | 0 | 189.7k | 16 | 77.4 | $0.1160 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | C | 1 | 1 | 157.7k | 14 | 74.7 | $0.1085 |
| muse / spark-1.3-contributor high | smoke-callers | A | 1 | 1 | 57.7k | 3 | 41.7 | $0.0041 |
| muse / spark-1.3-contributor high | smoke-callers | B | 1 | 1 | 56.5k | 3 | 30.2 | $0.0021 |
| muse / spark-1.3-contributor high | smoke-callers | C | 1 | 1 | 210.7k | 10 | 58.9 | $0.0032 |
| muse / spark-1.3-contributor high | smoke-rename | A | 1 | 1 | 96.5k | 5 | 24.7 | $0.0042 |
| muse / spark-1.3-contributor high | smoke-rename | B | 1 | 1 | 57.7k | 3 | 67.2 | $0.0023 |
| muse / spark-1.3-contributor high | smoke-rename | C | 1 | 1 | 631.2k | 24 | 92.6 | $0.0057 |
| muse / spark-1.3-contributor high | smoke-rename-class | A | 1 | 1 | 223.8k | 11 | 68.9 | $0.0050 |
| muse / spark-1.3-contributor high | smoke-rename-class | B | 1 | 1 | 699.4k | 27 | 304.8 | $0.0067 |
| muse / spark-1.3-contributor high | smoke-rename-class | C | 1 | 1 | 584.3k | 23 | 216.2 | $0.0061 |
| muse / spark-1.3-contributor high | smoke-rename-field | A | 1 | 1 | 185.5k | 9 | 86.7 | $0.0050 |
| muse / spark-1.3-contributor high | smoke-rename-field | B | 1 | 1 | 630.3k | 23 | 163.9 | $0.0097 |
| muse / spark-1.3-contributor high | smoke-rename-field | C | 1 | 1 | 378.6k | 17 | 90.4 | $0.0041 |

## Tier 1 on daybreak (3 runs per cell, interleaved)

| Config | Arm | Runs | Green | Errors | Median tokens | Turns | Wall s | jk s | Est. cost | CLI versions |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A | 9 | 9/9 (100%) | 0 | 13.0k | 2 | 10.1 | 0.8 | $0.0112 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | C | 9 | 9/9 (100%) | 0 | 30.3k | 7 | 16.6 | 0.4 | $0.0296 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | A | 9 | 9/9 (100%) | 0 | 13.1k | 2 | 9.9 | 0.6 | $0.0255 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | C | 9 | 9/9 (100%) | 0 | 49.9k | 12 | 21.4 | 0.6 | $0.0891 | 2.1.326 (Claude Code) |
| codex / gpt-6.1-terra low (default) | A | 9 | 9/9 (100%) | 0 | 47.9k | 2 | 9.8 | 0.9 | $0.0091 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | C | 9 | 9/9 (100%) | 0 | 130.4k | 5 | 18.7 | 0.4 | $0.0196 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | A | 9 | 9/9 (100%) | 0 | 66.7k | 3 | 16.2 | 0.7 | $0.0256 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | C | 9 | 9/9 (100%) | 0 | 115.2k | 8 | 25.0 | 0.6 | $0.0415 | codex-cli 0.166.0 |
| grok / grok-4.7 high 256k | A | 9 | 9/9 (100%) | 0 | 32.8k | 4 | 13.8 | 2.9 | $0.0466 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | C | 9 | 9/9 (100%) | 0 | 202.5k | 15 | 80.6 | 12.2 | $0.2492 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | A | 9 | 9/9 (100%) | 0 | 23.0k | 3 | 8.2 | 1.6 | $0.0308 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | C | 9 | 9/9 (100%) | 0 | 191.6k | 17 | 47.6 | 18.0 | $0.1314 | grok 1.0.77 (50ca579fa996) [stable] |
| cursor / composer-2.5 | A | 9 | 9/9 (100%) | 0 | 49.5k | 3 | 21.5 | 0.9 | $0.0172 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | C | 9 | 9/9 (100%) | 0 | 76.8k | 5 | 23.3 | 0.4 | $0.0228 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | A | 9 | 9/9 (100%) | 0 | 52.6k | 3 | 25.6 | 0.6 | $0.0449 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | C | 9 | 9/9 (100%) | 0 | 588.9k | 23 | 214.2 | 0.9 | $0.6780 | 2026.11.13-eacade1 |
| agy / gemini-4-hydrogen-medium | A | 9 | 9/9 (100%) | 0 | 19.8k | 3 | 17.1 | 2.2 | $0.0251 | 1.5.10 |
| agy / gemini-4-hydrogen-medium | C | 9 | 9/9 (100%) | 0 | 110.8k | 7 | 43.3 | 4.9 | $0.0727 | 1.5.10 |
| muse / spark-1.3-contributor high | A | 9 | 9/9 (100%) | 0 | 77.4k | 4 | 41.4 | 0.6 | $0.0039 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | C | 9 | 9/9 (100%) | 0 | 321.8k | 14 | 65.3 | 0.6 | $0.0037 | Muse Code 1.4.23 (1.4.23-R8977.1) |

### Tier 1 on daybreak (3 runs per cell, interleaved): savings of A against C

Per config, summed over the tasks both arms measured (sum of per-task medians); positive means the first arm lower. Noise: with repeats, `overlap` means the arms' summed per-task ranges overlap, so the difference is inside run-to-run noise; with one run per cell there is no noise estimate.

| Config | Arm vs | Tasks | Green arm / other | Tokens arm → other | Δ tokens | Δ turns | Δ wall | Δ cost | Tokens noise |
|---|---|---:|---|---|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A vs C | 3 | 9/9 / 9/9 | 46.6k → 86.2k | +46% | +65% | +39% | +59% | separate |
| claude2 / opus-5-5 medium | A vs C | 3 | 9/9 / 9/9 | 46.9k → 131.7k | +64% | +77% | +52% | +66% | separate |
| codex / gpt-6.1-terra low (default) | A vs C | 3 | 9/9 / 9/9 | 177.2k → 375.1k | +53% | +56% | +48% | +46% | separate |
| codex / gpt-6.1-sol low (default) | A vs C | 3 | 9/9 / 9/9 | 215.9k → 358.4k | +40% | +57% | +21% | +41% | separate |
| grok / grok-4.7 high 256k | A vs C | 3 | 9/9 / 9/9 | 99.5k → 883.5k | +89% | +77% | +88% | +85% | separate |
| grok / grok-4.5 high | A vs C | 3 | 9/9 / 9/9 | 74.2k → 570.1k | +87% | +80% | +83% | +78% | separate |
| cursor / composer-2.5 | A vs C | 3 | 9/9 / 9/9 | 160.8k → 242.7k | +34% | +33% | +0% | +32% | overlap (noise) |
| cursor / grok-4.7-high | A vs C | 3 | 9/9 / 9/9 | 148.5k → 1627.2k | +91% | +86% | +85% | +91% | separate |
| agy / gemini-4-hydrogen-medium | A vs C | 3 | 9/9 / 9/9 | 63.8k → 300.1k | +79% | +58% | +62% | +64% | separate |
| muse / spark-1.3-contributor high | A vs C | 3 | 9/9 / 9/9 | 260.7k → 860.5k | +70% | +67% | +9% | -10% | separate |

### Tier 1 on daybreak (3 runs per cell, interleaved): per task

| Config | Task | Arm | Runs | Green | Median tokens (min–max) | Turns | Wall s | Est. cost |
|---|---|---|---:|---:|---|---:|---:|---:|
| claude2 / sonnet-5-5 medium | gs-multi-module-rename-message | A | 3 | 3 | 12.9k (12.9–12.9k) | 2 | 9.4 | $0.0107 |
| claude2 / sonnet-5-5 medium | gs-multi-module-rename-message | C | 3 | 3 | 20.4k (20.4–33.9k) | 7 | 17.7 | $0.0267 |
| claude2 / sonnet-5-5 medium | gs-uploading-rename-loadasresource | A | 3 | 3 | 13.0k (13.0–13.0k) | 2 | 10.1 | $0.0112 |
| claude2 / sonnet-5-5 medium | gs-uploading-rename-loadasresource | C | 3 | 3 | 30.2k (21.8–30.3k) | 8 | 16.3 | $0.0330 |
| claude2 / sonnet-5-5 medium | petclinic-callers-addpet | A | 3 | 3 | 20.7k (20.6–20.7k) | 3 | 11.4 | $0.0151 |
| claude2 / sonnet-5-5 medium | petclinic-callers-addpet | C | 3 | 3 | 35.6k (35.0–35.8k) | 5 | 16.6 | $0.0296 |
| claude2 / opus-5-5 medium | gs-multi-module-rename-message | A | 3 | 3 | 13.1k (12.9–19.7k) | 2 | 9.9 | $0.0255 |
| claude2 / opus-5-5 medium | gs-multi-module-rename-message | C | 3 | 3 | 49.9k (49.8–62.9k) | 12 | 21.4 | $0.0891 |
| claude2 / opus-5-5 medium | gs-uploading-rename-loadasresource | A | 3 | 3 | 13.0k (13.0–13.0k) | 2 | 9.0 | $0.0223 |
| claude2 / opus-5-5 medium | gs-uploading-rename-loadasresource | C | 3 | 3 | 52.5k (52.2–87.1k) | 14 | 28.1 | $0.1053 |
| claude2 / opus-5-5 medium | petclinic-callers-addpet | A | 3 | 3 | 20.7k (20.7–28.3k) | 3 | 11.3 | $0.0375 |
| claude2 / opus-5-5 medium | petclinic-callers-addpet | C | 3 | 3 | 29.3k (28.9–37.6k) | 5 | 13.6 | $0.0567 |
| codex / gpt-6.1-terra low (default) | gs-multi-module-rename-message | A | 3 | 3 | 46.1k (45.8–64.5k) | 2 | 9.8 | $0.0087 |
| codex / gpt-6.1-terra low (default) | gs-multi-module-rename-message | C | 3 | 3 | 132.6k (100.1–133.9k) | 6 | 21.1 | $0.0182 |
| codex / gpt-6.1-terra low (default) | gs-uploading-rename-loadasresource | A | 3 | 3 | 46.2k (46.2–47.9k) | 2 | 9.4 | $0.0094 |
| codex / gpt-6.1-terra low (default) | gs-uploading-rename-loadasresource | C | 3 | 3 | 112.1k (96.4–112.5k) | 5 | 18.9 | $0.0155 |
| codex / gpt-6.1-terra low (default) | petclinic-callers-addpet | A | 3 | 3 | 84.9k (62.2–85.0k) | 3 | 10.4 | $0.0160 |
| codex / gpt-6.1-terra low (default) | petclinic-callers-addpet | C | 3 | 3 | 130.4k (130.4–130.8k) | 5 | 17.0 | $0.0292 |
| codex / gpt-6.1-sol low (default) | gs-multi-module-rename-message | A | 3 | 3 | 65.0k (62.9–66.7k) | 3 | 16.2 | $0.0222 |
| codex / gpt-6.1-sol low (default) | gs-multi-module-rename-message | C | 3 | 3 | 146.1k (115.2–171.5k) | 8 | 29.9 | $0.0480 |
| codex / gpt-6.1-sol low (default) | gs-uploading-rename-loadasresource | A | 3 | 3 | 65.6k (47.7–90.2k) | 3 | 26.0 | $0.0182 |
| codex / gpt-6.1-sol low (default) | gs-uploading-rename-loadasresource | C | 3 | 3 | 100.9k (96.3–116.7k) | 8 | 23.9 | $0.0353 |
| codex / gpt-6.1-sol low (default) | petclinic-callers-addpet | A | 3 | 3 | 85.3k (85.1–85.6k) | 3 | 13.9 | $0.0371 |
| codex / gpt-6.1-sol low (default) | petclinic-callers-addpet | C | 3 | 3 | 111.5k (87.8–133.7k) | 5 | 16.8 | $0.0472 |
| grok / grok-4.7 high 256k | gs-multi-module-rename-message | A | 3 | 3 | 34.7k (26.1–35.8k) | 4 | 14.8 | $0.0507 |
| grok / grok-4.7 high 256k | gs-multi-module-rename-message | C | 3 | 3 | 498.4k (429.1–614.5k) | 24 | 198.4 | $0.5332 |
| grok / grok-4.7 high 256k | gs-uploading-rename-loadasresource | A | 3 | 3 | 31.9k (23.7–32.7k) | 4 | 13.8 | $0.0354 |
| grok / grok-4.7 high 256k | gs-uploading-rename-loadasresource | C | 3 | 3 | 202.5k (184.4–380.3k) | 15 | 80.6 | $0.2235 |
| grok / grok-4.7 high 256k | petclinic-callers-addpet | A | 3 | 3 | 32.9k (32.8–33.6k) | 4 | 13.4 | $0.0535 |
| grok / grok-4.7 high 256k | petclinic-callers-addpet | C | 3 | 3 | 182.6k (173.4–200.1k) | 14 | 71.5 | $0.1848 |
| grok / grok-4.5 high | gs-multi-module-rename-message | A | 3 | 3 | 22.8k (21.9–31.5k) | 3 | 8.5 | $0.0240 |
| grok / grok-4.5 high | gs-multi-module-rename-message | C | 3 | 3 | 213.8k (191.9–338.1k) | 17 | 58.2 | $0.1606 |
| grok / grok-4.5 high | gs-uploading-rename-loadasresource | A | 3 | 3 | 22.8k (20.7–23.0k) | 3 | 7.6 | $0.0325 |
| grok / grok-4.5 high | gs-uploading-rename-loadasresource | C | 3 | 3 | 170.7k (164.1–229.4k) | 14 | 46.2 | $0.1314 |
| grok / grok-4.5 high | petclinic-callers-addpet | A | 3 | 3 | 28.5k (27.8–29.5k) | 4 | 9.2 | $0.0326 |
| grok / grok-4.5 high | petclinic-callers-addpet | C | 3 | 3 | 185.6k (140.0–191.6k) | 19 | 43.7 | $0.1194 |
| cursor / composer-2.5 | gs-multi-module-rename-message | A | 3 | 3 | 65.0k (64.7–96.1k) | 4 | 28.4 | $0.0201 |
| cursor / composer-2.5 | gs-multi-module-rename-message | C | 3 | 3 | 69.6k (68.4–76.8k) | 4 | 24.7 | $0.0210 |
| cursor / composer-2.5 | gs-uploading-rename-loadasresource | A | 3 | 3 | 49.5k (34.4–112.4k) | 3 | 22.8 | $0.0138 |
| cursor / composer-2.5 | gs-uploading-rename-loadasresource | C | 3 | 3 | 71.2k (66.0–87.1k) | 4 | 22.8 | $0.0207 |
| cursor / composer-2.5 | petclinic-callers-addpet | A | 3 | 3 | 46.3k (46.3–46.3k) | 3 | 18.7 | $0.0125 |
| cursor / composer-2.5 | petclinic-callers-addpet | C | 3 | 3 | 101.9k (89.4–132.8k) | 7 | 22.7 | $0.0260 |
| cursor / grok-4.7-high | gs-multi-module-rename-message | A | 3 | 3 | 54.9k (40.7–55.1k) | 3 | 25.6 | $0.0444 |
| cursor / grok-4.7-high | gs-multi-module-rename-message | C | 3 | 3 | 662.6k (109.0–717.1k) | 23 | 245.2 | $0.7903 |
| cursor / grok-4.7-high | gs-uploading-rename-loadasresource | A | 3 | 3 | 40.5k (39.3–40.7k) | 2 | 28.9 | $0.0355 |
| cursor / grok-4.7-high | gs-uploading-rename-loadasresource | C | 3 | 3 | 814.2k (588.9–847.3k) | 28 | 215.6 | $0.6955 |
| cursor / grok-4.7-high | petclinic-callers-addpet | A | 3 | 3 | 53.1k (52.6–53.2k) | 3 | 25.2 | $0.0614 |
| cursor / grok-4.7-high | petclinic-callers-addpet | C | 3 | 3 | 150.4k (150.1–188.1k) | 8 | 65.6 | $0.1660 |
| agy / gemini-4-hydrogen-medium | gs-multi-module-rename-message | A | 3 | 3 | 19.6k (18.7–19.8k) | 3 | 14.8 | $0.0242 |
| agy / gemini-4-hydrogen-medium | gs-multi-module-rename-message | C | 3 | 3 | 72.9k (61.9–125.8k) | 8 | 55.3 | $0.0632 |
| agy / gemini-4-hydrogen-medium | gs-uploading-rename-loadasresource | A | 3 | 3 | 19.4k (18.8–26.5k) | 3 | 17.7 | $0.0251 |
| agy / gemini-4-hydrogen-medium | gs-uploading-rename-loadasresource | C | 3 | 3 | 116.4k (73.5–149.6k) | 9 | 48.5 | $0.0765 |
| agy / gemini-4-hydrogen-medium | petclinic-callers-addpet | A | 3 | 3 | 24.8k (24.5–26.4k) | 4 | 17.1 | $0.0272 |
| agy / gemini-4-hydrogen-medium | petclinic-callers-addpet | C | 3 | 3 | 110.8k (101.4–116.5k) | 7 | 27.5 | $0.0727 |
| muse / spark-1.3-contributor high | gs-multi-module-rename-message | A | 3 | 3 | 75.2k (74.9–79.1k) | 4 | 36.4 | $0.0040 |
| muse / spark-1.3-contributor high | gs-multi-module-rename-message | C | 3 | 3 | 458.3k (392.7–495.5k) | 21 | 74.9 | $0.0041 |
| muse / spark-1.3-contributor high | gs-uploading-rename-loadasresource | A | 3 | 3 | 57.2k (56.7–77.4k) | 3 | 30.2 | $0.0039 |
| muse / spark-1.3-contributor high | gs-uploading-rename-loadasresource | C | 3 | 3 | 321.8k (297.2–342.7k) | 14 | 46.4 | $0.0037 |
| muse / spark-1.3-contributor high | petclinic-callers-addpet | A | 3 | 3 | 128.3k (78.2–178.4k) | 6 | 88.7 | $0.0034 |
| muse / spark-1.3-contributor high | petclinic-callers-addpet | C | 3 | 3 | 80.4k (79.1–149.3k) | 4 | 49.6 | $0.0025 |

## Tier-2 sliver on daybreak (3 runs per cell, interleaved; F: main's graph fixes)

| Config | Arm | Runs | Green | Errors | Median tokens | Turns | Wall s | jk s | Est. cost | CLI versions |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | F | 12 | 12/12 (100%) | 0 | 52.8k | 8 | 62.8 | 45.4 | $0.0495 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | A | 12 | 11/12 (92%) | 0 | 49.1k | 8.5 | 62.5 | 45.0 | $0.0503 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | B | 12 | 11/12 (92%) | 0 | 58.4k | 12.5 | 66.8 | 44.8 | $0.0656 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | C | 12 | 11/12 (92%) | 0 | 71.6k | 12 | 62.4 | 44.3 | $0.0891 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | F | 12 | 12/12 (100%) | 0 | 65.2k | 12 | 71.1 | 45.6 | $0.1124 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | A | 12 | 12/12 (100%) | 0 | 69.7k | 13 | 71.8 | 45.0 | $0.1210 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | B | 12 | 12/12 (100%) | 0 | 81.7k | 14 | 75.8 | 44.8 | $0.1460 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | C | 12 | 12/12 (100%) | 0 | 96.8k | 14.5 | 46.7 | 5.7 | $0.1934 | 2.1.326 (Claude Code) |
| codex / gpt-6.1-terra low (default) | F | 12 | 12/12 (100%) | 0 | 149.5k | 6 | 68.2 | 48.4 | $0.0198 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | A | 12 | 10/12 (83%) | 0 | 148.2k | 6 | 35.2 | 8.6 | $0.0259 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | B | 12 | 9/12 (75%) | 0 | 200.3k | 7 | 117.5 | 91.1 | $0.0297 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | C | 12 | 10/12 (83%) | 0 | 164.5k | 6.5 | 64.6 | 7.3 | $0.0239 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | F | 12 | 12/12 (100%) | 0 | 233.3k | 8 | 102.3 | 85.9 | $0.0558 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | A | 12 | 9/12 (75%) | 0 | 189.5k | 7.5 | 75.7 | 52.5 | $0.0589 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | B | 12 | 11/12 (92%) | 0 | 240.0k | 9.5 | 79.3 | 55.6 | $0.0752 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | C | 12 | 10/12 (83%) | 0 | 162.8k | 9 | 38.9 | 8.1 | $0.0670 | codex-cli 0.166.0 |
| grok / grok-4.7 high 256k | F | 12 | 12/12 (100%) | 0 | 165.6k | 10.5 | 138.8 | 9.8 | $0.1459 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | A | 12 | 12/12 (100%) | 0 | 219.9k | 13 | 189.4 | 16.9 | $0.2520 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | B | 12 | 9/12 (75%) | 0 | 234.8k | 12.5 | 112.4 | 8.6 | $0.2390 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | C | 12 | 9/12 (75%) | 0 | 353.6k | 15 | 140.5 | 22.4 | $0.3541 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | F | 12 | 12/12 (100%) | 0 | 106.3k | 7 | 78.1 | 8.1 | $0.0843 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | A | 12 | 12/12 (100%) | 0 | 127.2k | 10 | 70.5 | 18.7 | $0.1027 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | B | 12 | 9/12 (75%) | 0 | 141.1k | 9.5 | 127.3 | 78.5 | $0.1237 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | C | 12 | 9/12 (75%) | 0 | 287.9k | 16.5 | 78.2 | 29.5 | $0.2421 | grok 1.0.77 (50ca579fa996) [stable] |
| cursor / composer-2.5 | F | 12 | 11/12 (92%) | 0 | 181.2k | 9 | 76.2 | 45.8 | $0.0501 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | A | 12 | 10/12 (83%) | 0 | 213.2k | 10.5 | 82.4 | 44.8 | $0.0531 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | B | 12 | 11/12 (92%) | 0 | 194.9k | 10 | 84.5 | 44.7 | $0.0528 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | C | 12 | 11/12 (92%) | 0 | 218.5k | 10 | 64.0 | 6.6 | $0.0559 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | F | 12 | 12/12 (100%) | 0 | 208.1k | 9 | 181.2 | 86.3 | $0.2195 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | A | 12 | 12/12 (100%) | 0 | 293.4k | 11.5 | 168.8 | 13.9 | $0.3120 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | B | 12 | 10/12 (83%) | 0 | 258.5k | 12.5 | 205.6 | 47.5 | $0.3263 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | C | 12 | 11/12 (92%) | 0 | 394.3k | 14.5 | 210.6 | 45.0 | $0.4700 | 2026.11.13-eacade1 |
| agy / gemini-4-hydrogen-medium | F | 12 | 12/12 (100%) | 0 | 83.3k | 8.5 | 107.8 | 59.7 | $0.0709 | 1.5.10, 1.5.11 |
| agy / gemini-4-hydrogen-medium | A | 12 | 12/12 (100%) | 0 | 93.6k | 9 | 57.0 | 20.2 | $0.0688 | 1.5.10, 1.5.11 |
| agy / gemini-4-hydrogen-medium | B | 12 | 3/12 (25%) | 0 | 297.6k | 13 | 179.1 | 105.8 | $0.1459 | 1.5.10, 1.5.11 |
| agy / gemini-4-hydrogen-medium | C | 12 | 3/12 (25%) | 0 | 337.8k | 14 | 159.4 | 90.2 | $0.1646 | 1.5.10, 1.5.11 |
| muse / spark-1.3-contributor high | F | 12 | 12/12 (100%) | 0 | 958.9k | 26.5 | 196.2 | 67.0 | $0.0098 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | A | 12 | 11/12 (92%) | 0 | 1225.7k | 34.5 | 179.0 | 50.8 | $0.0107 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | B | 12 | 8/12 (67%) | 0 | 1087.5k | 38 | 214.3 | 93.0 | $0.0112 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | C | 12 | 9/12 (75%) | 0 | 1133.4k | 35.5 | 146.4 | 44.0 | $0.0103 | Muse Code 1.4.23 (1.4.23-R8977.1) |

### Tier-2 sliver on daybreak (3 runs per cell, interleaved; F: main's graph fixes): savings of A against B, A against C, F against A

Per config, summed over the tasks both arms measured (sum of per-task medians); positive means the first arm lower. Noise: with repeats, `overlap` means the arms' summed per-task ranges overlap, so the difference is inside run-to-run noise; with one run per cell there is no noise estimate.

| Config | Arm vs | Tasks | Green arm / other | Tokens arm → other | Δ tokens | Δ turns | Δ wall | Δ cost | Tokens noise |
|---|---|---:|---|---|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A vs B | 4 | 11/12 / 11/12 | 203.2k → 246.9k | +18% | +31% | +21% | +17% | separate |
| claude2 / sonnet-5-5 medium | A vs C | 4 | 11/12 / 11/12 | 203.2k → 278.9k | +27% | +39% | +1% | +31% | separate |
| claude2 / sonnet-5-5 medium | F vs A | 4 | 12/12 / 11/12 | 199.2k → 203.2k | +2% | +6% | -7% | +4% | overlap (noise) |
| claude2 / opus-5-5 medium | A vs B | 4 | 12/12 / 12/12 | 271.3k → 325.1k | +17% | +18% | +12% | +24% | overlap (noise) |
| claude2 / opus-5-5 medium | A vs C | 4 | 12/12 / 12/12 | 271.3k → 358.3k | +24% | +14% | -48% | +29% | overlap (noise) |
| claude2 / opus-5-5 medium | F vs A | 4 | 12/12 / 12/12 | 250.0k → 271.3k | +8% | +2% | +7% | +5% | overlap (noise) |
| codex / gpt-6.1-terra low (default) | A vs B | 4 | 10/12 / 9/12 | 573.5k → 731.7k | +22% | +24% | +68% | +14% | overlap (noise) |
| codex / gpt-6.1-terra low (default) | A vs C | 4 | 10/12 / 10/12 | 573.5k → 705.6k | +19% | +15% | +49% | +9% | overlap (noise) |
| codex / gpt-6.1-terra low (default) | F vs A | 4 | 12/12 / 10/12 | 626.4k → 573.5k | -9% | +0% | -138% | +0% | overlap (noise) |
| codex / gpt-6.1-sol low (default) | A vs B | 4 | 9/12 / 11/12 | 747.9k → 973.4k | +23% | +18% | -1% | +20% | overlap (noise) |
| codex / gpt-6.1-sol low (default) | A vs C | 4 | 9/12 / 10/12 | 747.9k → 729.9k | -2% | +6% | -80% | +6% | overlap (noise) |
| codex / gpt-6.1-sol low (default) | F vs A | 4 | 12/12 / 9/12 | 1106.2k → 747.9k | -48% | +9% | -19% | -32% | overlap (noise) |
| grok / grok-4.7 high 256k | A vs B | 4 | 12/12 / 9/12 | 1678.5k → 1169.2k | -44% | -39% | -59% | -54% | overlap (noise) |
| grok / grok-4.7 high 256k | A vs C | 4 | 12/12 / 9/12 | 1678.5k → 2195.6k | +24% | +9% | -12% | +15% | overlap (noise) |
| grok / grok-4.7 high 256k | F vs A | 4 | 12/12 / 12/12 | 630.5k → 1678.5k | +62% | +41% | +38% | +58% | overlap (noise) |
| grok / grok-4.5 high | A vs B | 4 | 12/12 / 9/12 | 459.7k → 646.5k | +29% | +0% | +30% | +13% | overlap (noise) |
| grok / grok-4.5 high | A vs C | 4 | 12/12 / 9/12 | 459.7k → 1618.1k | +72% | +45% | +15% | +62% | separate |
| grok / grok-4.5 high | F vs A | 4 | 12/12 / 12/12 | 444.5k → 459.7k | +3% | +16% | +14% | +25% | overlap (noise) |
| cursor / composer-2.5 | A vs B | 4 | 10/12 / 11/12 | 781.4k → 780.9k | -0% | +0% | +15% | +6% | overlap (noise) |
| cursor / composer-2.5 | A vs C | 4 | 10/12 / 11/12 | 781.4k → 816.5k | +4% | +2% | -39% | +10% | overlap (noise) |
| cursor / composer-2.5 | F vs A | 4 | 11/12 / 10/12 | 737.0k → 781.4k | +6% | +2% | +14% | +1% | overlap (noise) |
| cursor / grok-4.7-high | A vs B | 4 | 12/12 / 10/12 | 1206.2k → 1326.3k | +9% | +0% | +13% | +16% | overlap (noise) |
| cursor / grok-4.7-high | A vs C | 4 | 12/12 / 11/12 | 1206.2k → 1669.7k | +28% | +20% | +8% | +20% | overlap (noise) |
| cursor / grok-4.7-high | F vs A | 4 | 12/12 / 12/12 | 737.2k → 1206.2k | +39% | +33% | +11% | +37% | overlap (noise) |
| agy / gemini-4-hydrogen-medium | A vs B | 4 | 12/12 / 3/12 | 370.4k → 1208.8k | +69% | +38% | +63% | +56% | separate |
| agy / gemini-4-hydrogen-medium | A vs C | 4 | 12/12 / 3/12 | 370.4k → 1281.2k | +71% | +35% | +58% | +56% | separate |
| agy / gemini-4-hydrogen-medium | F vs A | 4 | 12/12 / 12/12 | 376.6k → 370.4k | -2% | +6% | -63% | -4% | overlap (noise) |
| muse / spark-1.3-contributor high | A vs B | 4 | 11/12 / 8/12 | 5808.1k → 6249.9k | +7% | +0% | +9% | +20% | overlap (noise) |
| muse / spark-1.3-contributor high | A vs C | 4 | 11/12 / 9/12 | 5808.1k → 5420.2k | -7% | -5% | -34% | -6% | overlap (noise) |
| muse / spark-1.3-contributor high | F vs A | 4 | 12/12 / 11/12 | 5344.2k → 5808.1k | +8% | +7% | +6% | +4% | overlap (noise) |

### Tier-2 sliver on daybreak (3 runs per cell, interleaved; F: main's graph fixes): per task

| Config | Task | Arm | Runs | Green | Median tokens (min–max) | Turns | Wall s | Est. cost |
|---|---|---|---:|---:|---|---:|---:|---:|
| claude2 / sonnet-5-5 medium | petclinic-anchor-column-case | A | 3 | 2 | 77.7k (74.6–78.7k) | 15 | 76.5 | $0.1042 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-column-case | B | 3 | 3 | 87.8k (75.2–91.9k) | 15 | 78.4 | $0.0944 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-column-case | C | 3 | 2 | 103.1k (90.1–121.4k) | 16 | 88.3 | $0.1205 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-column-case | F | 3 | 3 | 75.1k (72.6–101.7k) | 15 | 87.5 | $0.1022 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-composed-route | A | 3 | 3 | 62.6k (58.6–71.9k) | 9 | 65.7 | $0.0532 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-composed-route | B | 3 | 3 | 49.0k (48.2–49.7k) | 9 | 61.4 | $0.0471 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-composed-route | C | 3 | 3 | 49.3k (46.2–69.8k) | 9 | 21.3 | $0.0509 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-composed-route | F | 3 | 3 | 69.7k (67.1–74.4k) | 10 | 69.2 | $0.0558 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-relaxed-env | A | 3 | 3 | 37.5k (37.4–39.6k) | 8 | 60.7 | $0.0301 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-relaxed-env | B | 3 | 2 | 36.5k (29.9–39.0k) | 9 | 61.7 | $0.0349 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-relaxed-env | C | 3 | 3 | 29.2k (28.7–35.7k) | 9 | 63.6 | $0.0355 |
| claude2 / sonnet-5-5 medium | petclinic-anchor-relaxed-env | F | 3 | 3 | 28.9k (28.9–38.5k) | 5 | 60.4 | $0.0203 |
| claude2 / sonnet-5-5 medium | petclinic-rename-telephone | A | 3 | 3 | 25.4k (25.3–26.6k) | 3 | 12.7 | $0.0435 |
| claude2 / sonnet-5-5 medium | petclinic-rename-telephone | B | 3 | 3 | 73.6k (67.2–74.3k) | 18 | 71.6 | $0.1033 |
| claude2 / sonnet-5-5 medium | petclinic-rename-telephone | C | 3 | 3 | 97.2k (73.3–117.6k) | 23 | 45.6 | $0.1254 |
| claude2 / sonnet-5-5 medium | petclinic-rename-telephone | F | 3 | 3 | 25.4k (25.4–25.5k) | 3 | 13.3 | $0.0435 |
| claude2 / opus-5-5 medium | petclinic-anchor-column-case | A | 3 | 3 | 83.6k (74.0–88.1k) | 17 | 74.4 | $0.1682 |
| claude2 / opus-5-5 medium | petclinic-anchor-column-case | B | 3 | 3 | 89.6k (85.6–104.5k) | 18 | 77.4 | $0.1805 |
| claude2 / opus-5-5 medium | petclinic-anchor-column-case | C | 3 | 3 | 101.5k (92.1–128.4k) | 17 | 79.3 | $0.2079 |
| claude2 / opus-5-5 medium | petclinic-anchor-column-case | F | 3 | 3 | 60.3k (46.3–92.3k) | 16 | 79.0 | $0.1401 |
| claude2 / opus-5-5 medium | petclinic-anchor-composed-route | A | 3 | 3 | 65.6k (65.0–77.2k) | 11 | 63.1 | $0.0935 |
| claude2 / opus-5-5 medium | petclinic-anchor-composed-route | B | 3 | 3 | 71.8k (69.9–77.8k) | 11 | 69.1 | $0.1154 |
| claude2 / opus-5-5 medium | petclinic-anchor-composed-route | C | 3 | 3 | 68.4k (67.7–108.0k) | 10 | 28.9 | $0.1077 |
| claude2 / opus-5-5 medium | petclinic-anchor-composed-route | F | 3 | 3 | 78.4k (73.9–104.5k) | 12 | 72.1 | $0.1122 |
| claude2 / opus-5-5 medium | petclinic-anchor-relaxed-env | A | 3 | 3 | 61.3k (57.3–73.7k) | 14 | 72.4 | $0.1113 |
| claude2 / opus-5-5 medium | petclinic-anchor-relaxed-env | B | 3 | 3 | 52.7k (52.2–53.2k) | 14 | 69.5 | $0.1082 |
| claude2 / opus-5-5 medium | petclinic-anchor-relaxed-env | C | 3 | 3 | 50.7k (49.3–60.6k) | 12 | 34.4 | $0.1061 |
| claude2 / opus-5-5 medium | petclinic-anchor-relaxed-env | F | 3 | 3 | 51.7k (50.1–83.9k) | 13 | 74.6 | $0.0979 |
| claude2 / opus-5-5 medium | petclinic-rename-telephone | A | 3 | 3 | 60.8k (59.4–82.9k) | 8 | 71.1 | $0.1295 |
| claude2 / opus-5-5 medium | petclinic-rename-telephone | B | 3 | 3 | 111.0k (98.4–120.5k) | 18 | 103.7 | $0.2533 |
| claude2 / opus-5-5 medium | petclinic-rename-telephone | C | 3 | 3 | 137.7k (110.0–272.4k) | 19 | 47.7 | $0.2848 |
| claude2 / opus-5-5 medium | petclinic-rename-telephone | F | 3 | 3 | 59.6k (36.1–70.1k) | 8 | 36.4 | $0.1259 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-column-case | A | 3 | 3 | 163.1k (141.5–179.8k) | 6 | 31.4 | $0.0262 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-column-case | B | 3 | 3 | 159.0k (135.9–475.1k) | 9 | 74.1 | $0.0291 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-column-case | C | 3 | 3 | 184.6k (162.7–212.1k) | 7 | 64.3 | $0.0233 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-column-case | F | 3 | 3 | 164.0k (124.8–222.9k) | 6 | 65.9 | $0.0299 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-composed-route | A | 3 | 3 | 173.0k (145.6–180.8k) | 7 | 33.8 | $0.0260 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-composed-route | B | 3 | 3 | 197.0k (193.4–211.4k) | 6 | 67.9 | $0.0278 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-composed-route | C | 3 | 3 | 163.3k (157.3–165.2k) | 5 | 75.7 | $0.0272 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-composed-route | F | 3 | 3 | 229.3k (193.5–232.7k) | 7 | 110.3 | $0.0295 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-relaxed-env | A | 3 | 1 | 150.8k (131.1–178.8k) | 5 | 38.6 | $0.0225 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-relaxed-env | B | 3 | 0 | 167.7k (98.1–217.1k) | 6 | 129.6 | $0.0192 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-relaxed-env | C | 3 | 1 | 193.8k (99.5–239.6k) | 7 | 68.5 | $0.0273 |
| codex / gpt-6.1-terra low (default) | petclinic-anchor-relaxed-env | F | 3 | 3 | 135.0k (81.7–179.2k) | 5 | 100.7 | $0.0171 |
| codex / gpt-6.1-terra low (default) | petclinic-rename-telephone | A | 3 | 3 | 86.5k (86.5–122.1k) | 4 | 22.1 | $0.0177 |
| codex / gpt-6.1-terra low (default) | petclinic-rename-telephone | B | 3 | 3 | 208.1k (203.5–212.5k) | 8 | 122.3 | $0.0317 |
| codex / gpt-6.1-terra low (default) | petclinic-rename-telephone | C | 3 | 3 | 163.9k (140.9–191.6k) | 7 | 37.0 | $0.0234 |
| codex / gpt-6.1-terra low (default) | petclinic-rename-telephone | F | 3 | 3 | 98.1k (86.6–100.7k) | 4 | 22.6 | $0.0157 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-column-case | A | 3 | 3 | 188.7k (101.2–440.4k) | 9 | 65.6 | $0.0491 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-column-case | B | 3 | 3 | 296.7k (226.9–379.1k) | 8 | 78.1 | $0.0724 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-column-case | C | 3 | 3 | 111.5k (96.9–198.2k) | 7 | 26.7 | $0.0563 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-column-case | F | 3 | 3 | 191.7k (188.4–272.1k) | 9 | 70.8 | $0.0508 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-composed-route | A | 3 | 3 | 223.6k (219.6–385.7k) | 13 | 112.6 | $0.0629 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-composed-route | B | 3 | 3 | 258.6k (233.3–317.2k) | 11 | 83.4 | $0.0801 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-composed-route | C | 3 | 3 | 216.2k (169.1–234.4k) | 9 | 39.5 | $0.0811 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-composed-route | F | 3 | 3 | 413.4k (242.6–494.0k) | 9 | 115.7 | $0.1137 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-relaxed-env | A | 3 | 0 | 190.4k (151.4–219.4k) | 6 | 101.2 | $0.0590 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-relaxed-env | B | 3 | 2 | 171.5k (152.9–177.0k) | 9 | 64.7 | $0.0468 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-relaxed-env | C | 3 | 1 | 132.3k (81.7–144.5k) | 8 | 34.1 | $0.0455 |
| codex / gpt-6.1-sol low (default) | petclinic-anchor-relaxed-env | F | 3 | 3 | 223.6k (222.3–224.0k) | 5 | 106.1 | $0.0520 |
| codex / gpt-6.1-sol low (default) | petclinic-rename-telephone | A | 3 | 3 | 145.3k (106.8–162.5k) | 5 | 36.6 | $0.0574 |
| codex / gpt-6.1-sol low (default) | petclinic-rename-telephone | B | 3 | 3 | 246.7k (205.8–301.8k) | 12 | 86.8 | $0.0853 |
| codex / gpt-6.1-sol low (default) | petclinic-rename-telephone | C | 3 | 3 | 269.9k (156.5–324.9k) | 11 | 74.8 | $0.0613 |
| codex / gpt-6.1-sol low (default) | petclinic-rename-telephone | F | 3 | 3 | 277.4k (131.9–381.7k) | 7 | 82.3 | $0.0841 |
| grok / grok-4.7 high 256k | petclinic-anchor-column-case | A | 3 | 3 | 664.2k (211.1–1112.1k) | 23 | 215.8 | $0.6323 |
| grok / grok-4.7 high 256k | petclinic-anchor-column-case | B | 3 | 3 | 293.0k (263.2–345.9k) | 11 | 104.0 | $0.2203 |
| grok / grok-4.7 high 256k | petclinic-anchor-column-case | C | 3 | 3 | 366.8k (271.5–673.6k) | 14 | 114.7 | $0.3917 |
| grok / grok-4.7 high 256k | petclinic-anchor-column-case | F | 3 | 3 | 241.7k (181.2–259.0k) | 11 | 133.7 | $0.2709 |
| grok / grok-4.7 high 256k | petclinic-anchor-composed-route | A | 3 | 3 | 158.1k (141.4–228.7k) | 12 | 136.5 | $0.1699 |
| grok / grok-4.7 high 256k | petclinic-anchor-composed-route | B | 3 | 3 | 165.4k (150.7–232.8k) | 10 | 96.8 | $0.1757 |
| grok / grok-4.7 high 256k | petclinic-anchor-composed-route | C | 3 | 3 | 256.2k (217.7–407.7k) | 14 | 119.7 | $0.3166 |
| grok / grok-4.7 high 256k | petclinic-anchor-composed-route | F | 3 | 3 | 162.1k (102.3–180.0k) | 12 | 155.8 | $0.1474 |
| grok / grok-4.7 high 256k | petclinic-anchor-relaxed-env | A | 3 | 3 | 184.9k (159.3–209.9k) | 12 | 179.0 | $0.2431 |
| grok / grok-4.7 high 256k | petclinic-anchor-relaxed-env | B | 3 | 0 | 234.0k (186.0–235.5k) | 13 | 117.7 | $0.1982 |
| grok / grok-4.7 high 256k | petclinic-anchor-relaxed-env | C | 3 | 0 | 290.8k (180.2–340.4k) | 15 | 143.1 | $0.2835 |
| grok / grok-4.7 high 256k | petclinic-anchor-relaxed-env | F | 3 | 3 | 132.9k (131.3–169.2k) | 10 | 141.9 | $0.1427 |
| grok / grok-4.7 high 256k | petclinic-rename-telephone | A | 3 | 3 | 671.3k (420.5–747.5k) | 21 | 283.8 | $0.5933 |
| grok / grok-4.7 high 256k | petclinic-rename-telephone | B | 3 | 3 | 476.8k (231.6–495.8k) | 15 | 194.5 | $0.4684 |
| grok / grok-4.7 high 256k | petclinic-rename-telephone | C | 3 | 3 | 1281.8k (986.7–2075.5k) | 32 | 353.2 | $0.9412 |
| grok / grok-4.7 high 256k | petclinic-rename-telephone | F | 3 | 3 | 93.8k (48.9–410.8k) | 7 | 75.3 | $0.1334 |
| grok / grok-4.5 high | petclinic-anchor-column-case | A | 3 | 3 | 121.7k (117.4–230.7k) | 9 | 76.4 | $0.1793 |
| grok / grok-4.5 high | petclinic-anchor-column-case | B | 3 | 3 | 127.8k (113.5–168.7k) | 7 | 79.8 | $0.1099 |
| grok / grok-4.5 high | petclinic-anchor-column-case | C | 3 | 3 | 190.5k (133.4–829.2k) | 9 | 80.7 | $0.1629 |
| grok / grok-4.5 high | petclinic-anchor-column-case | F | 3 | 3 | 171.5k (143.8–179.3k) | 9 | 94.8 | $0.1307 |
| grok / grok-4.5 high | petclinic-anchor-composed-route | A | 3 | 3 | 105.6k (97.9–193.9k) | 9 | 113.1 | $0.0744 |
| grok / grok-4.5 high | petclinic-anchor-composed-route | B | 3 | 3 | 116.8k (115.8–135.0k) | 8 | 124.3 | $0.0978 |
| grok / grok-4.5 high | petclinic-anchor-composed-route | C | 3 | 3 | 227.9k (130.9–384.3k) | 13 | 70.1 | $0.1865 |
| grok / grok-4.5 high | petclinic-anchor-composed-route | F | 3 | 3 | 171.9k (153.2–201.7k) | 12 | 134.9 | $0.1086 |
| grok / grok-4.5 high | petclinic-anchor-relaxed-env | A | 3 | 3 | 136.3k (132.8–159.4k) | 12 | 138.7 | $0.1079 |
| grok / grok-4.5 high | petclinic-anchor-relaxed-env | B | 3 | 0 | 146.7k (135.4–287.5k) | 11 | 190.0 | $0.1187 |
| grok / grok-4.5 high | petclinic-anchor-relaxed-env | C | 3 | 0 | 229.5k (197.1–346.3k) | 15 | 75.0 | $0.2396 |
| grok / grok-4.5 high | petclinic-anchor-relaxed-env | F | 3 | 3 | 44.9k (44.5–45.6k) | 5 | 63.3 | $0.0443 |
| grok / grok-4.5 high | petclinic-rename-telephone | A | 3 | 3 | 96.2k (46.3–155.2k) | 7 | 45.7 | $0.0974 |
| grok / grok-4.5 high | petclinic-rename-telephone | B | 3 | 3 | 255.2k (208.0–262.4k) | 11 | 142.6 | $0.2003 |
| grok / grok-4.5 high | petclinic-rename-telephone | C | 3 | 3 | 970.2k (649.5–1298.3k) | 30 | 215.0 | $0.6111 |
| grok / grok-4.5 high | petclinic-rename-telephone | F | 3 | 3 | 56.2k (43.5–68.7k) | 5 | 28.3 | $0.0600 |
| cursor / composer-2.5 | petclinic-anchor-column-case | A | 3 | 3 | 234.8k (206.1–422.0k) | 12 | 86.8 | $0.0606 |
| cursor / composer-2.5 | petclinic-anchor-column-case | B | 3 | 3 | 203.2k (186.6–228.4k) | 10 | 74.1 | $0.0566 |
| cursor / composer-2.5 | petclinic-anchor-column-case | C | 3 | 3 | 217.3k (186.9–255.7k) | 10 | 40.4 | $0.0612 |
| cursor / composer-2.5 | petclinic-anchor-column-case | F | 3 | 2 | 215.8k (175.3–226.4k) | 11 | 75.9 | $0.0547 |
| cursor / composer-2.5 | petclinic-anchor-composed-route | A | 3 | 3 | 226.8k (145.7–234.2k) | 11 | 91.3 | $0.0566 |
| cursor / composer-2.5 | petclinic-anchor-composed-route | B | 3 | 3 | 186.4k (170.6–407.2k) | 10 | 120.0 | $0.0489 |
| cursor / composer-2.5 | petclinic-anchor-composed-route | C | 3 | 3 | 140.5k (129.1–150.5k) | 8 | 50.4 | $0.0386 |
| cursor / composer-2.5 | petclinic-anchor-composed-route | F | 3 | 3 | 218.7k (193.3–261.0k) | 12 | 82.7 | $0.0665 |
| cursor / composer-2.5 | petclinic-anchor-relaxed-env | A | 3 | 1 | 144.1k (105.8–360.4k) | 9 | 68.2 | $0.0408 |
| cursor / composer-2.5 | petclinic-anchor-relaxed-env | B | 3 | 2 | 166.4k (131.6–415.7k) | 11 | 73.7 | $0.0481 |
| cursor / composer-2.5 | petclinic-anchor-relaxed-env | C | 3 | 2 | 234.3k (219.7–434.1k) | 14 | 72.1 | $0.0585 |
| cursor / composer-2.5 | petclinic-anchor-relaxed-env | F | 3 | 3 | 125.9k (106.6–140.1k) | 8 | 76.5 | $0.0326 |
| cursor / composer-2.5 | petclinic-rename-telephone | A | 3 | 3 | 175.8k (77.1–220.4k) | 9 | 66.1 | $0.0444 |
| cursor / composer-2.5 | petclinic-rename-telephone | B | 3 | 3 | 224.9k (116.9–283.2k) | 10 | 100.1 | $0.0626 |
| cursor / composer-2.5 | petclinic-rename-telephone | C | 3 | 3 | 224.4k (150.0–660.3k) | 10 | 61.4 | $0.0653 |
| cursor / composer-2.5 | petclinic-rename-telephone | F | 3 | 3 | 176.6k (132.2–185.8k) | 9 | 34.9 | $0.0473 |
| cursor / grok-4.7-high | petclinic-anchor-column-case | A | 3 | 3 | 304.3k (223.4–612.1k) | 11 | 206.6 | $0.3113 |
| cursor / grok-4.7-high | petclinic-anchor-column-case | B | 3 | 3 | 227.8k (203.9–237.3k) | 8 | 120.6 | $0.2328 |
| cursor / grok-4.7-high | petclinic-anchor-column-case | C | 3 | 3 | 337.1k (332.8–940.3k) | 12 | 155.6 | $0.4216 |
| cursor / grok-4.7-high | petclinic-anchor-column-case | F | 3 | 3 | 265.6k (240.3–268.4k) | 9 | 174.2 | $0.2899 |
| cursor / grok-4.7-high | petclinic-anchor-composed-route | A | 3 | 3 | 264.7k (217.0–282.5k) | 12 | 138.5 | $0.2887 |
| cursor / grok-4.7-high | petclinic-anchor-composed-route | B | 3 | 3 | 340.8k (262.5–383.6k) | 15 | 210.4 | $0.3949 |
| cursor / grok-4.7-high | petclinic-anchor-composed-route | C | 3 | 3 | 268.6k (243.0–1894.4k) | 12 | 163.4 | $0.2770 |
| cursor / grok-4.7-high | petclinic-anchor-composed-route | F | 3 | 3 | 224.6k (206.2–271.0k) | 11 | 182.9 | $0.2491 |
| cursor / grok-4.7-high | petclinic-anchor-relaxed-env | A | 3 | 3 | 329.4k (209.8–350.2k) | 14 | 147.7 | $0.3966 |
| cursor / grok-4.7-high | petclinic-anchor-relaxed-env | B | 3 | 1 | 182.0k (159.2–254.6k) | 9 | 200.8 | $0.2735 |
| cursor / grok-4.7-high | petclinic-anchor-relaxed-env | C | 3 | 2 | 341.9k (215.4–936.0k) | 14 | 212.2 | $0.3857 |
| cursor / grok-4.7-high | petclinic-anchor-relaxed-env | F | 3 | 3 | 183.5k (161.5–210.0k) | 9 | 194.9 | $0.1911 |
| cursor / grok-4.7-high | petclinic-rename-telephone | A | 3 | 3 | 307.9k (115.5–583.9k) | 11 | 190.0 | $0.3127 |
| cursor / grok-4.7-high | petclinic-rename-telephone | B | 3 | 3 | 575.7k (460.2–622.1k) | 16 | 257.1 | $0.6600 |
| cursor / grok-4.7-high | petclinic-rename-telephone | C | 3 | 3 | 722.1k (446.7–792.6k) | 22 | 214.1 | $0.5627 |
| cursor / grok-4.7-high | petclinic-rename-telephone | F | 3 | 3 | 63.5k (61.7–171.2k) | 3 | 55.9 | $0.0918 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-column-case | A | 3 | 3 | 93.1k (71.0–116.8k) | 8 | 55.8 | $0.0709 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-column-case | B | 3 | 0 | 260.8k (234.9–336.2k) | 10 | 152.6 | $0.1253 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-column-case | C | 3 | 0 | 329.3k (256.2–541.5k) | 11 | 128.3 | $0.1660 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-column-case | F | 3 | 3 | 72.5k (72.2–82.6k) | 7 | 91.8 | $0.0689 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-composed-route | A | 3 | 3 | 83.6k (82.5–94.0k) | 9 | 51.5 | $0.0646 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-composed-route | B | 3 | 0 | 442.3k (334.0–869.9k) | 19 | 246.1 | $0.2244 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-composed-route | C | 3 | 0 | 344.3k (327.5–695.7k) | 18 | 222.3 | $0.1910 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-composed-route | F | 3 | 3 | 71.0k (70.5–84.0k) | 8 | 126.4 | $0.0559 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-relaxed-env | A | 3 | 3 | 124.5k (105.7–144.2k) | 11 | 125.5 | $0.0834 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-relaxed-env | B | 3 | 0 | 216.1k (127.4–305.5k) | 14 | 171.1 | $0.1181 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-relaxed-env | C | 3 | 0 | 196.3k (152.3–365.2k) | 11 | 136.2 | $0.1205 |
| agy / gemini-4-hydrogen-medium | petclinic-anchor-relaxed-env | F | 3 | 3 | 101.1k (87.9–105.2k) | 9 | 124.8 | $0.0739 |
| agy / gemini-4-hydrogen-medium | petclinic-rename-telephone | A | 3 | 3 | 69.1k (45.2–100.1k) | 7 | 50.0 | $0.0582 |
| agy / gemini-4-hydrogen-medium | petclinic-rename-telephone | B | 3 | 3 | 289.6k (241.5–311.3k) | 13 | 190.2 | $0.1648 |
| agy / gemini-4-hydrogen-medium | petclinic-rename-telephone | C | 3 | 3 | 411.3k (331.4–448.9k) | 14 | 191.2 | $0.1578 |
| agy / gemini-4-hydrogen-medium | petclinic-rename-telephone | F | 3 | 3 | 132.0k (32.3–138.7k) | 9 | 118.1 | $0.0890 |
| muse / spark-1.3-contributor high | petclinic-anchor-column-case | A | 3 | 2 | 1322.0k (1208.7–1480.8k) | 34 | 141.2 | $0.0110 |
| muse / spark-1.3-contributor high | petclinic-anchor-column-case | B | 3 | 3 | 1936.6k (872.9–2524.6k) | 46 | 201.4 | $0.0156 |
| muse / spark-1.3-contributor high | petclinic-anchor-column-case | C | 3 | 3 | 1461.8k (1220.5–2322.5k) | 43 | 142.2 | $0.0114 |
| muse / spark-1.3-contributor high | petclinic-anchor-column-case | F | 3 | 3 | 1288.2k (1024.5–1772.9k) | 37 | 224.7 | $0.0107 |
| muse / spark-1.3-contributor high | petclinic-anchor-composed-route | A | 3 | 3 | 619.0k (495.1–623.3k) | 24 | 183.4 | $0.0060 |
| muse / spark-1.3-contributor high | petclinic-anchor-composed-route | B | 3 | 3 | 600.0k (394.3–1024.1k) | 22 | 191.2 | $0.0087 |
| muse / spark-1.3-contributor high | petclinic-anchor-composed-route | C | 3 | 3 | 714.9k (639.1–819.7k) | 23 | 142.5 | $0.0064 |
| muse / spark-1.3-contributor high | petclinic-anchor-composed-route | F | 3 | 3 | 613.4k (447.0–893.2k) | 25 | 148.9 | $0.0064 |
| muse / spark-1.3-contributor high | petclinic-anchor-relaxed-env | A | 3 | 3 | 920.1k (894.9–1242.7k) | 35 | 174.6 | $0.0080 |
| muse / spark-1.3-contributor high | petclinic-anchor-relaxed-env | B | 3 | 0 | 713.4k (444.1–1150.9k) | 26 | 181.4 | $0.0076 |
| muse / spark-1.3-contributor high | petclinic-anchor-relaxed-env | C | 3 | 0 | 563.6k (463.4–1046.3k) | 22 | 100.1 | $0.0066 |
| muse / spark-1.3-contributor high | petclinic-anchor-relaxed-env | F | 3 | 3 | 672.6k (553.6–829.3k) | 24 | 195.2 | $0.0077 |
| muse / spark-1.3-contributor high | petclinic-rename-telephone | A | 3 | 3 | 2947.1k (1754.2–2953.0k) | 60 | 312.4 | $0.0165 |
| muse / spark-1.3-contributor high | petclinic-rename-telephone | B | 3 | 2 | 2999.9k (2948.5–3391.5k) | 59 | 321.2 | $0.0201 |
| muse / spark-1.3-contributor high | petclinic-rename-telephone | C | 3 | 3 | 2679.8k (2455.7–2712.2k) | 58 | 221.8 | $0.0147 |
| muse / spark-1.3-contributor high | petclinic-rename-telephone | F | 3 | 3 | 2770.2k (1043.1–2884.3k) | 57 | 197.3 | $0.0149 |

## Totals on daybreak (all tiers so far)

| Arm | Runs | Green | Tokens (sum) | Est. cost (sum) |
|---|---:|---:|---:|---:|
| F | 120 | 119 | 30.32M | $10.61 |
| A | 250 | 241 | 42.43M | $18.17 |
| B | 160 | 132 | 48.49M | $19.54 |
| C | 250 | 225 | 76.12M | $40.34 |

# Second host: bocabox (Fedora), tier 0 only

The same 120 cells on bocabox, run before the bench moved. Not pooled with daybreak. The 55 tier-1 rows bocabox
wrote before the move (of 180) are kept raw in rows-tier1-bocabox-partial.jsonl and are not used in any table.

**Tier 0's B on bocabox is not 0.51.1:** the jk-before home updated itself to 0.52.0 two minutes in. 38 of its 42
rows ran 0.52.0; only Sonnet's callers, rename and rename-field ran 0.51.1. So its A-vs-B rows are run-to-run noise
between identical builds (README.md, Findings).

### Tier 0 on bocabox (smoke fixture, 1 run per cell)

| Config | Arm | Runs | Green | Errors | Median tokens | Turns | Wall s | jk s | Est. cost | CLI versions |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A | 4 | 4/4 (100%) | 0 | 20.5k | 3.5 | 5.7 | 0.8 | $0.0158 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | B | 4 | 4/4 (100%) | 0 | 19.8k | 3 | 5.3 | 0.2 | $0.0311 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | C | 4 | 4/4 (100%) | 0 | 33.6k | 8 | 11.7 | 0.2 | $0.0340 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | A | 4 | 4/4 (100%) | 0 | 24.0k | 4 | 7.6 | 0.8 | $0.0357 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | B | 4 | 4/4 (100%) | 0 | 27.1k | 4 | 7.6 | 0.6 | $0.0492 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | C | 4 | 4/4 (100%) | 0 | 47.5k | 11 | 18.7 | 0.3 | $0.0922 | 2.1.326 (Claude Code) |
| codex / gpt-6.1-terra low (default) | A | 4 | 4/4 (100%) | 0 | 76.3k | 3 | 26.1 | 0.8 | $0.0137 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | B | 4 | 4/4 (100%) | 0 | 76.3k | 3 | 27.8 | 0.7 | $0.0119 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | C | 4 | 4/4 (100%) | 0 | 127.4k | 5 | 44.6 | 0.4 | $0.0180 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | A | 4 | 4/4 (100%) | 0 | 83.8k | 3.5 | 15.6 | 1.1 | $0.0260 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | B | 4 | 4/4 (100%) | 0 | 75.4k | 3.5 | 13.9 | 1.0 | $0.0274 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | C | 4 | 4/4 (100%) | 0 | 131.2k | 7.5 | 23.9 | 0.4 | $0.0505 | codex-cli 0.166.0 |
| grok / grok-4.7 high 256k | A | 4 | 4/4 (100%) | 0 | 45.5k | 5 | 19.5 | 5.0 | $0.0600 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | B | 4 | 4/4 (100%) | 0 | 43.5k | 4.5 | 21.1 | 4.5 | $0.0532 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | C | 4 | 4/4 (100%) | 0 | 273.6k | 16 | 142.8 | 14.0 | $0.3128 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | A | 4 | 4/4 (100%) | 0 | 34.5k | 4.5 | 11.8 | 3.1 | $0.0310 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | B | 4 | 4/4 (100%) | 0 | 36.7k | 4.5 | 11.9 | 2.4 | $0.0299 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | C | 4 | 4/4 (100%) | 0 | 132.6k | 12 | 41.5 | 7.5 | $0.0928 | grok 1.0.77 (50ca579fa996) [stable] |
| cursor / composer-2.5 | A | 4 | 4/4 (100%) | 0 | 73.8k | 5 | 19.8 | 1.0 | $0.0202 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | B | 4 | 4/4 (100%) | 0 | 82.4k | 5.5 | 18.5 | 0.6 | $0.0217 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | C | 4 | 4/4 (100%) | 0 | 148.4k | 9 | 28.9 | 0.4 | $0.0416 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | A | 4 | 4/4 (100%) | 0 | 69.6k | 4 | 39.0 | 1.0 | $0.0782 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | B | 4 | 4/4 (100%) | 0 | 66.6k | 3.5 | 34.4 | 0.8 | $0.0821 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | C | 4 | 4/4 (100%) | 0 | 237.2k | 11.5 | 105.2 | 0.8 | $0.2753 | 2026.11.13-eacade1 |
| agy / gemini-4-hydrogen-medium | A | 4 | 4/4 (100%) | 0 | 25.4k | 4 | 29.0 | 3.1 | $0.0276 | 1.5.10 |
| agy / gemini-4-hydrogen-medium | B | 4 | 4/4 (100%) | 0 | 29.0k | 4.5 | 18.1 | 3.9 | $0.0305 | 1.5.10 |
| agy / gemini-4-hydrogen-medium | C | 4 | 4/4 (100%) | 0 | 166.1k | 15 | 85.2 | 8.9 | $0.1152 | 1.5.10 |
| muse / spark-1.3-contributor high | A | 4 | 4/4 (100%) | 0 | 114.8k | 5.5 | 45.5 | 0.9 | $0.0044 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | B | 4 | 4/4 (100%) | 0 | 182.6k | 8 | 71.5 | 1.1 | $0.0054 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | C | 4 | 4/4 (100%) | 0 | 517.0k | 21 | 121.5 | 0.6 | $0.0064 | Muse Code 1.4.23 (1.4.23-R8977.1) |

### Tier 0 on bocabox (smoke fixture, 1 run per cell): savings of A against B, A against C

Per config, summed over the tasks both arms measured (sum of per-task medians); positive means the first arm lower. Noise: with repeats, `overlap` means the arms' summed per-task ranges overlap, so the difference is inside run-to-run noise; with one run per cell there is no noise estimate.

| Config | Arm vs | Tasks | Green arm / other | Tokens arm → other | Δ tokens | Δ turns | Δ wall | Δ cost | Tokens noise |
|---|---|---:|---|---|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A vs B | 4 | 4/4 / 4/4 | 75.4k → 92.3k | +18% | +24% | +13% | +45% | 1 run |
| claude2 / sonnet-5-5 medium | A vs C | 4 | 4/4 / 4/4 | 75.4k → 134.9k | +44% | +63% | +56% | +60% | 1 run |
| claude2 / opus-5-5 medium | A vs B | 4 | 4/4 / 4/4 | 89.2k → 133.4k | +33% | +36% | +32% | +40% | 1 run |
| claude2 / opus-5-5 medium | A vs C | 4 | 4/4 / 4/4 | 89.2k → 192.7k | +54% | +68% | +62% | +66% | 1 run |
| codex / gpt-6.1-terra low (default) | A vs B | 4 | 4/4 / 4/4 | 349.5k → 331.3k | -6% | -7% | +8% | -12% | 1 run |
| codex / gpt-6.1-terra low (default) | A vs C | 4 | 4/4 / 4/4 | 349.5k → 524.6k | +33% | +32% | +19% | +30% | 1 run |
| codex / gpt-6.1-sol low (default) | A vs B | 4 | 4/4 / 4/4 | 320.4k → 304.4k | -5% | +0% | -9% | +8% | 1 run |
| codex / gpt-6.1-sol low (default) | A vs C | 4 | 4/4 / 4/4 | 320.4k → 533.5k | +40% | +48% | +35% | +51% | 1 run |
| grok / grok-4.7 high 256k | A vs B | 4 | 4/4 / 4/4 | 185.2k → 184.0k | -1% | -5% | -1% | -1% | 1 run |
| grok / grok-4.7 high 256k | A vs C | 4 | 4/4 / 4/4 | 185.2k → 1582.4k | +88% | +74% | +88% | +87% | 1 run |
| grok / grok-4.5 high | A vs B | 4 | 4/4 / 4/4 | 148.3k → 139.1k | -7% | -6% | -3% | -6% | 1 run |
| grok / grok-4.5 high | A vs C | 4 | 4/4 / 4/4 | 148.3k → 1108.9k | +87% | +72% | +84% | +83% | 1 run |
| cursor / composer-2.5 | A vs B | 4 | 4/4 / 4/4 | 319.2k → 301.0k | -6% | -5% | -10% | -8% | 1 run |
| cursor / composer-2.5 | A vs C | 4 | 4/4 / 4/4 | 319.2k → 664.4k | +52% | +46% | +22% | +51% | 1 run |
| cursor / grok-4.7-high | A vs B | 4 | 4/4 / 4/4 | 291.1k → 269.0k | -8% | -7% | -15% | -17% | 1 run |
| cursor / grok-4.7-high | A vs C | 4 | 4/4 / 4/4 | 291.1k → 992.7k | +71% | +65% | +64% | +70% | 1 run |
| agy / gemini-4-hydrogen-medium | A vs B | 4 | 4/4 / 4/4 | 95.9k → 112.3k | +15% | +12% | -22% | +16% | 1 run |
| agy / gemini-4-hydrogen-medium | A vs C | 4 | 4/4 / 4/4 | 95.9k → 642.9k | +85% | +73% | +66% | +77% | 1 run |
| muse / spark-1.3-contributor high | A vs B | 4 | 4/4 / 4/4 | 747.3k → 916.5k | +18% | +22% | +16% | +13% | 1 run |
| muse / spark-1.3-contributor high | A vs C | 4 | 4/4 / 4/4 | 747.3k → 1882.9k | +60% | +57% | +59% | +33% | 1 run |

### Tier 0 on bocabox (smoke fixture, 1 run per cell): per task

| Config | Task | Arm | Runs | Green | Median tokens (min–max) | Turns | Wall s | Est. cost |
|---|---|---|---:|---:|---|---:|---:|---:|
| claude2 / sonnet-5-5 medium | smoke-callers | A | 1 | 1 | 20.4k | 3 | 4.8 | $0.0143 |
| claude2 / sonnet-5-5 medium | smoke-callers | B | 1 | 1 | 19.8k | 3 | 4.4 | $0.0310 |
| claude2 / sonnet-5-5 medium | smoke-callers | C | 1 | 1 | 28.1k | 6 | 9.1 | $0.0264 |
| claude2 / sonnet-5-5 medium | smoke-rename | A | 1 | 1 | 12.8k | 2 | 3.5 | $0.0105 |
| claude2 / sonnet-5-5 medium | smoke-rename | B | 1 | 1 | 12.4k | 2 | 3.1 | $0.0101 |
| claude2 / sonnet-5-5 medium | smoke-rename | C | 1 | 1 | 21.6k | 7 | 8.9 | $0.0294 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | A | 1 | 1 | 21.6k | 4 | 6.6 | $0.0177 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | B | 1 | 1 | 19.8k | 3 | 6.3 | $0.0311 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | C | 1 | 1 | 46.2k | 13 | 16.5 | $0.0557 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | A | 1 | 1 | 20.6k | 4 | 6.8 | $0.0172 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | B | 1 | 1 | 40.3k | 9 | 11.1 | $0.0367 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | C | 1 | 1 | 39.0k | 9 | 14.3 | $0.0386 |
| claude2 / opus-5-5 medium | smoke-callers | A | 1 | 1 | 28.4k | 4 | 8.0 | $0.0383 |
| claude2 / opus-5-5 medium | smoke-callers | B | 1 | 1 | 27.8k | 4 | 8.0 | $0.0356 |
| claude2 / opus-5-5 medium | smoke-callers | C | 1 | 1 | 35.8k | 7 | 10.3 | $0.0543 |
| claude2 / opus-5-5 medium | smoke-rename | A | 1 | 1 | 12.8k | 2 | 3.1 | $0.0209 |
| claude2 / opus-5-5 medium | smoke-rename | B | 1 | 1 | 19.8k | 3 | 4.9 | $0.0628 |
| claude2 / opus-5-5 medium | smoke-rename | C | 1 | 1 | 62.0k | 13 | 18.7 | $0.1045 |
| claude2 / opus-5-5 medium | smoke-rename-class | A | 1 | 1 | 27.2k | 4 | 7.3 | $0.0331 |
| claude2 / opus-5-5 medium | smoke-rename-class | B | 1 | 1 | 26.4k | 4 | 7.2 | $0.0314 |
| claude2 / opus-5-5 medium | smoke-rename-class | C | 1 | 1 | 47.7k | 15 | 22.2 | $0.1496 |
| claude2 / opus-5-5 medium | smoke-rename-field | A | 1 | 1 | 20.9k | 4 | 7.9 | $0.0398 |
| claude2 / opus-5-5 medium | smoke-rename-field | B | 1 | 1 | 59.4k | 11 | 18.3 | $0.0912 |
| claude2 / opus-5-5 medium | smoke-rename-field | C | 1 | 1 | 47.2k | 9 | 18.7 | $0.0800 |
| codex / gpt-6.1-terra low (default) | smoke-callers | A | 1 | 1 | 86.4k | 3 | 40.9 | $0.0164 |
| codex / gpt-6.1-terra low (default) | smoke-callers | B | 1 | 1 | 86.5k | 3 | 39.6 | $0.0165 |
| codex / gpt-6.1-terra low (default) | smoke-callers | C | 1 | 1 | 133.3k | 5 | 62.4 | $0.0199 |
| codex / gpt-6.1-terra low (default) | smoke-rename | A | 1 | 1 | 66.1k | 3 | 9.7 | $0.0091 |
| codex / gpt-6.1-terra low (default) | smoke-rename | B | 1 | 1 | 47.1k | 2 | 16.0 | $0.0069 |
| codex / gpt-6.1-terra low (default) | smoke-rename | C | 1 | 1 | 121.6k | 5 | 27.0 | $0.0162 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | A | 1 | 1 | 66.0k | 3 | 11.3 | $0.0132 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | B | 1 | 1 | 66.0k | 3 | 9.2 | $0.0090 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | C | 1 | 1 | 154.0k | 7 | 29.8 | $0.0263 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | A | 1 | 1 | 131.0k | 6 | 83.6 | $0.0142 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | B | 1 | 1 | 131.7k | 6 | 93.0 | $0.0148 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | C | 1 | 1 | 115.7k | 5 | 59.4 | $0.0133 |
| codex / gpt-6.1-sol low (default) | smoke-callers | A | 1 | 1 | 86.7k | 3 | 12.1 | $0.0331 |
| codex / gpt-6.1-sol low (default) | smoke-callers | B | 1 | 1 | 87.1k | 4 | 13.1 | $0.0333 |
| codex / gpt-6.1-sol low (default) | smoke-callers | C | 1 | 1 | 116.4k | 5 | 14.3 | $0.0637 |
| codex / gpt-6.1-sol low (default) | smoke-rename | A | 1 | 1 | 66.1k | 3 | 14.9 | $0.0181 |
| codex / gpt-6.1-sol low (default) | smoke-rename | B | 1 | 1 | 67.9k | 3 | 14.7 | $0.0284 |
| codex / gpt-6.1-sol low (default) | smoke-rename | C | 1 | 1 | 93.5k | 7 | 26.8 | $0.0459 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | A | 1 | 1 | 81.6k | 5 | 16.4 | $0.0195 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | B | 1 | 1 | 66.5k | 3 | 11.5 | $0.0240 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | C | 1 | 1 | 146.0k | 8 | 20.9 | $0.0471 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | A | 1 | 1 | 85.9k | 4 | 17.0 | $0.0325 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | B | 1 | 1 | 82.9k | 5 | 16.3 | $0.0264 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | C | 1 | 1 | 177.6k | 9 | 30.8 | $0.0539 |
| grok / grok-4.7 high 256k | smoke-callers | A | 1 | 1 | 44.2k | 5 | 17.8 | $0.0607 |
| grok / grok-4.7 high 256k | smoke-callers | B | 1 | 1 | 33.8k | 4 | 10.4 | $0.0475 |
| grok / grok-4.7 high 256k | smoke-callers | C | 1 | 1 | 214.2k | 15 | 103.6 | $0.2352 |
| grok / grok-4.7 high 256k | smoke-rename | A | 1 | 1 | 44.0k | 5 | 14.2 | $0.0381 |
| grok / grok-4.7 high 256k | smoke-rename | B | 1 | 1 | 38.3k | 4 | 19.3 | $0.0406 |
| grok / grok-4.7 high 256k | smoke-rename | C | 1 | 1 | 226.1k | 15 | 96.5 | $0.2479 |
| grok / grok-4.7 high 256k | smoke-rename-class | A | 1 | 1 | 46.8k | 5 | 21.2 | $0.0661 |
| grok / grok-4.7 high 256k | smoke-rename-class | B | 1 | 1 | 63.2k | 6 | 27.5 | $0.0741 |
| grok / grok-4.7 high 256k | smoke-rename-class | C | 1 | 1 | 821.0k | 30 | 308.6 | $0.8343 |
| grok / grok-4.7 high 256k | smoke-rename-field | A | 1 | 1 | 50.2k | 5 | 27.5 | $0.0593 |
| grok / grok-4.7 high 256k | smoke-rename-field | B | 1 | 1 | 48.6k | 5 | 22.8 | $0.0588 |
| grok / grok-4.7 high 256k | smoke-rename-field | C | 1 | 1 | 321.0k | 17 | 182.0 | $0.3776 |
| grok / grok-4.5 high | smoke-callers | A | 1 | 1 | 28.8k | 4 | 9.0 | $0.0323 |
| grok / grok-4.5 high | smoke-callers | B | 1 | 1 | 40.3k | 5 | 12.6 | $0.0294 |
| grok / grok-4.5 high | smoke-callers | C | 1 | 1 | 135.9k | 13 | 40.5 | $0.0884 |
| grok / grok-4.5 high | smoke-rename | A | 1 | 1 | 22.6k | 3 | 7.5 | $0.0293 |
| grok / grok-4.5 high | smoke-rename | B | 1 | 1 | 22.7k | 3 | 7.8 | $0.0220 |
| grok / grok-4.5 high | smoke-rename | C | 1 | 1 | 129.3k | 10 | 41.4 | $0.0971 |
| grok / grok-4.5 high | smoke-rename-class | A | 1 | 1 | 56.8k | 6 | 15.9 | $0.0387 |
| grok / grok-4.5 high | smoke-rename-class | B | 1 | 1 | 42.9k | 5 | 11.2 | $0.0411 |
| grok / grok-4.5 high | smoke-rename-class | C | 1 | 1 | 726.1k | 30 | 170.3 | $0.5151 |
| grok / grok-4.5 high | smoke-rename-field | A | 1 | 1 | 40.1k | 5 | 14.5 | $0.0297 |
| grok / grok-4.5 high | smoke-rename-field | B | 1 | 1 | 33.2k | 4 | 13.8 | $0.0304 |
| grok / grok-4.5 high | smoke-rename-field | C | 1 | 1 | 117.6k | 11 | 41.6 | $0.0790 |
| cursor / composer-2.5 | smoke-callers | A | 1 | 1 | 46.3k | 3 | 17.5 | $0.0138 |
| cursor / composer-2.5 | smoke-callers | B | 1 | 1 | 46.2k | 3 | 14.5 | $0.0157 |
| cursor / composer-2.5 | smoke-callers | C | 1 | 1 | 140.3k | 9 | 25.6 | $0.0393 |
| cursor / composer-2.5 | smoke-rename | A | 1 | 1 | 88.4k | 6 | 21.5 | $0.0243 |
| cursor / composer-2.5 | smoke-rename | B | 1 | 1 | 90.0k | 6 | 19.6 | $0.0241 |
| cursor / composer-2.5 | smoke-rename | C | 1 | 1 | 126.3k | 8 | 32.1 | $0.0368 |
| cursor / composer-2.5 | smoke-rename-class | A | 1 | 1 | 59.2k | 4 | 18.0 | $0.0161 |
| cursor / composer-2.5 | smoke-rename-class | B | 1 | 1 | 78.1k | 5 | 17.3 | $0.0206 |
| cursor / composer-2.5 | smoke-rename-class | C | 1 | 1 | 156.5k | 9 | 22.5 | $0.0440 |
| cursor / composer-2.5 | smoke-rename-field | A | 1 | 1 | 125.3k | 8 | 31.2 | $0.0354 |
| cursor / composer-2.5 | smoke-rename-field | B | 1 | 1 | 86.6k | 6 | 28.7 | $0.0228 |
| cursor / composer-2.5 | smoke-rename-field | C | 1 | 1 | 241.3k | 13 | 32.4 | $0.0635 |
| cursor / grok-4.7-high | smoke-callers | A | 1 | 1 | 53.2k | 3 | 41.1 | $0.0740 |
| cursor / grok-4.7-high | smoke-callers | B | 1 | 1 | 52.4k | 3 | 29.8 | $0.0404 |
| cursor / grok-4.7-high | smoke-callers | C | 1 | 1 | 127.5k | 7 | 66.0 | $0.1595 |
| cursor / grok-4.7-high | smoke-rename | A | 1 | 1 | 68.5k | 4 | 36.9 | $0.0732 |
| cursor / grok-4.7-high | smoke-rename | B | 1 | 1 | 55.4k | 3 | 29.1 | $0.0840 |
| cursor / grok-4.7-high | smoke-rename | C | 1 | 1 | 241.2k | 12 | 85.3 | $0.3065 |
| cursor / grok-4.7-high | smoke-rename-class | A | 1 | 1 | 98.8k | 5 | 50.4 | $0.1195 |
| cursor / grok-4.7-high | smoke-rename-class | B | 1 | 1 | 77.9k | 4 | 39.7 | $0.0938 |
| cursor / grok-4.7-high | smoke-rename-class | C | 1 | 1 | 233.1k | 11 | 125.2 | $0.2440 |
| cursor / grok-4.7-high | smoke-rename-field | A | 1 | 1 | 70.6k | 4 | 30.3 | $0.0825 |
| cursor / grok-4.7-high | smoke-rename-field | B | 1 | 1 | 83.3k | 5 | 39.0 | $0.0802 |
| cursor / grok-4.7-high | smoke-rename-field | C | 1 | 1 | 391.0k | 16 | 167.1 | $0.4664 |
| agy / gemini-4-hydrogen-medium | smoke-callers | A | 1 | 1 | 24.5k | 4 | 29.6 | $0.0263 |
| agy / gemini-4-hydrogen-medium | smoke-callers | B | 1 | 1 | 24.7k | 4 | 13.2 | $0.0265 |
| agy / gemini-4-hydrogen-medium | smoke-callers | C | 1 | 1 | 237.9k | 16 | 97.7 | $0.1719 |
| agy / gemini-4-hydrogen-medium | smoke-rename | A | 1 | 1 | 18.6k | 3 | 28.3 | $0.0216 |
| agy / gemini-4-hydrogen-medium | smoke-rename | B | 1 | 1 | 19.0k | 3 | 12.5 | $0.0235 |
| agy / gemini-4-hydrogen-medium | smoke-rename | C | 1 | 1 | 72.8k | 9 | 36.7 | $0.0593 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | A | 1 | 1 | 26.5k | 4 | 15.4 | $0.0289 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | B | 1 | 1 | 33.4k | 5 | 36.2 | $0.0344 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | C | 1 | 1 | 171.8k | 15 | 92.6 | $0.1201 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | A | 1 | 1 | 26.3k | 4 | 30.1 | $0.0300 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | B | 1 | 1 | 35.1k | 5 | 23.1 | $0.0425 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | C | 1 | 1 | 160.3k | 15 | 77.9 | $0.1104 |
| muse / spark-1.3-contributor high | smoke-callers | A | 1 | 1 | 61.3k | 3 | 57.3 | $0.0043 |
| muse / spark-1.3-contributor high | smoke-callers | B | 1 | 1 | 82.1k | 4 | 72.8 | $0.0044 |
| muse / spark-1.3-contributor high | smoke-callers | C | 1 | 1 | 205.0k | 9 | 61.9 | $0.0038 |
| muse / spark-1.3-contributor high | smoke-rename | A | 1 | 1 | 145.9k | 7 | 33.6 | $0.0031 |
| muse / spark-1.3-contributor high | smoke-rename | B | 1 | 1 | 231.4k | 10 | 70.3 | $0.0065 |
| muse / spark-1.3-contributor high | smoke-rename | C | 1 | 1 | 487.8k | 21 | 73.3 | $0.0062 |
| muse / spark-1.3-contributor high | smoke-rename-class | A | 1 | 1 | 83.7k | 4 | 31.7 | $0.0045 |
| muse / spark-1.3-contributor high | smoke-rename-class | B | 1 | 1 | 133.8k | 6 | 40.3 | $0.0037 |
| muse / spark-1.3-contributor high | smoke-rename-class | C | 1 | 1 | 644.0k | 23 | 238.6 | $0.0107 |
| muse / spark-1.3-contributor high | smoke-rename-field | A | 1 | 1 | 456.4k | 18 | 98.0 | $0.0064 |
| muse / spark-1.3-contributor high | smoke-rename-field | B | 1 | 1 | 469.3k | 21 | 77.8 | $0.0064 |
| muse / spark-1.3-contributor high | smoke-rename-field | C | 1 | 1 | 546.1k | 21 | 169.7 | $0.0066 |

### Totals on bocabox (tier 0)

| Arm | Runs | Green | Tokens (sum) | Est. cost (sum) |
|---|---:|---:|---:|---:|
| A | 40 | 40 | 2.62M | $1.27 |
| B | 40 | 40 | 2.78M | $1.36 |
| C | 40 | 40 | 8.26M | $5.15 |
