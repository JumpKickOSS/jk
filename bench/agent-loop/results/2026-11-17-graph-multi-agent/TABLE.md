# Graph bench across agents: banked table (2026-11-17, bocabox)

Arms: **A** `jk` (0.52.0 graph), **B** `jk-before` (0.51.1), **C** `jk-nograph` (no graph tools; see README.md).
Medians over runs that reported tokens; for the arm rows, all of the tier's tasks pooled. Est. cost is the API
list price of reported tokens (an API-equivalent estimate for subscription CLIs; README.md says how cache warmth
moves it). Savings: positive means A lower. A row with "1 run" has no noise estimate.

Status: tier 0 complete (120 runs). Tier 1 and the sliver follow.

## Tier 0 (smoke fixture, 1 run per cell)

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

### Tier 0 (smoke fixture, 1 run per cell): savings of A against B and C

Per config, summed over the tasks both arms measured (sum of per-task medians); positive means A lower. Noise: with repeats, `overlap` means the arms' summed per-task ranges overlap, so the difference is inside run-to-run noise; with one run per cell there is no noise estimate.

| Config | vs | Tasks | Green A / other | Tokens A → other | Δ tokens | Δ turns | Δ wall | Δ cost | Tokens noise |
|---|---|---:|---|---|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | B | 4 | 4/4 / 4/4 | 75.4k → 92.3k | +18% | +24% | +13% | +45% | 1 run |
| claude2 / sonnet-5-5 medium | C | 4 | 4/4 / 4/4 | 75.4k → 134.9k | +44% | +63% | +56% | +60% | 1 run |
| claude2 / opus-5-5 medium | B | 4 | 4/4 / 4/4 | 89.2k → 133.4k | +33% | +36% | +32% | +40% | 1 run |
| claude2 / opus-5-5 medium | C | 4 | 4/4 / 4/4 | 89.2k → 192.7k | +54% | +68% | +62% | +66% | 1 run |
| codex / gpt-6.1-terra low (default) | B | 4 | 4/4 / 4/4 | 349.5k → 331.3k | -6% | -7% | +8% | -12% | 1 run |
| codex / gpt-6.1-terra low (default) | C | 4 | 4/4 / 4/4 | 349.5k → 524.6k | +33% | +32% | +19% | +30% | 1 run |
| codex / gpt-6.1-sol low (default) | B | 4 | 4/4 / 4/4 | 320.4k → 304.4k | -5% | +0% | -9% | +8% | 1 run |
| codex / gpt-6.1-sol low (default) | C | 4 | 4/4 / 4/4 | 320.4k → 533.5k | +40% | +48% | +35% | +51% | 1 run |
| grok / grok-4.7 high 256k | B | 4 | 4/4 / 4/4 | 185.2k → 184.0k | -1% | -5% | -1% | -1% | 1 run |
| grok / grok-4.7 high 256k | C | 4 | 4/4 / 4/4 | 185.2k → 1582.4k | +88% | +74% | +88% | +87% | 1 run |
| grok / grok-4.5 high | B | 4 | 4/4 / 4/4 | 148.3k → 139.1k | -7% | -6% | -3% | -6% | 1 run |
| grok / grok-4.5 high | C | 4 | 4/4 / 4/4 | 148.3k → 1108.9k | +87% | +72% | +84% | +83% | 1 run |
| cursor / composer-2.5 | B | 4 | 4/4 / 4/4 | 319.2k → 301.0k | -6% | -5% | -10% | -8% | 1 run |
| cursor / composer-2.5 | C | 4 | 4/4 / 4/4 | 319.2k → 664.4k | +52% | +46% | +22% | +51% | 1 run |
| cursor / grok-4.7-high | B | 4 | 4/4 / 4/4 | 291.1k → 269.0k | -8% | -7% | -15% | -17% | 1 run |
| cursor / grok-4.7-high | C | 4 | 4/4 / 4/4 | 291.1k → 992.7k | +71% | +65% | +64% | +70% | 1 run |
| agy / gemini-4-hydrogen-medium | B | 4 | 4/4 / 4/4 | 95.9k → 112.3k | +15% | +12% | -22% | +16% | 1 run |
| agy / gemini-4-hydrogen-medium | C | 4 | 4/4 / 4/4 | 95.9k → 642.9k | +85% | +73% | +66% | +77% | 1 run |
| muse / spark-1.3-contributor high | B | 4 | 4/4 / 4/4 | 747.3k → 916.5k | +18% | +22% | +16% | +13% | 1 run |
| muse / spark-1.3-contributor high | C | 4 | 4/4 / 4/4 | 747.3k → 1882.9k | +60% | +57% | +59% | +33% | 1 run |

### Tier 0 (smoke fixture, 1 run per cell): per task

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

## Totals (all tiers so far)

| Arm | Runs | Green | Tokens (sum) | Est. cost (sum) |
|---|---:|---:|---:|---:|
| A | 40 | 40 | 2.62M | $1.27 |
| B | 40 | 40 | 2.78M | $1.36 |
| C | 40 | 40 | 8.26M | $5.15 |
