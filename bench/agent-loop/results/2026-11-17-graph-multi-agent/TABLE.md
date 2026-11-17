# Graph bench across agents: banked table (2026-11-17, daybreak WSL)

Arms: **A** `jk` (0.52.0 graph), **B** `jk-before` (0.51.1), **C** `jk-nograph` (no graph tools; see README.md).
Medians over runs that reported tokens; for the arm rows, all of the tier's tasks pooled. Est. cost is the API
list price of reported tokens (an API-equivalent estimate for subscription CLIs; README.md says how cache warmth
moves it). Savings: positive means A lower. A row with "1 run" has no noise estimate.

Every table names its host. **daybreak** (Windows 11 + WSL Ubuntu 26.04, 12 GB) is the measurement host; tier 0
was first run on **bocabox** (Fedora) and is kept below as a second host's table, never pooled with daybreak's.

Status: tier 0 complete on daybreak (120 runs, 3 grok-4.7 runs retried once after their time cap); tier 1 running.

## Tier 0 on daybreak (smoke fixture, 1 run per cell)

| Config | Arm | Runs | Green | Errors | Median tokens | Turns | Wall s | jk s | Est. cost | CLI versions |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | A | 4 | 4/4 (100%) | 0 | 23.9k | 3.5 | 11.3 | 1.0 | $0.0160 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | B | 4 | 4/4 (100%) | 0 | 20.4k | 3.5 | 9.5 | 0.6 | $0.0147 | 2.1.326 (Claude Code) |
| claude2 / sonnet-5-5 medium | C | 4 | 4/4 (100%) | 0 | 34.6k | 9.5 | 16.9 | 0.1 | $0.0379 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | A | 4 | 4/4 (100%) | 0 | 27.4k | 4 | 12.6 | 1.0 | $0.0343 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | B | 4 | 4/4 (100%) | 0 | 26.9k | 4 | 12.9 | 0.6 | $0.0371 | 2.1.326 (Claude Code) |
| claude2 / opus-5-5 medium | C | 4 | 4/4 (100%) | 0 | 43.2k | 10.5 | 23.1 | 0.2 | $0.0938 | 2.1.326 (Claude Code) |
| codex / gpt-6.1-terra low (default) | A | 4 | 4/4 (100%) | 0 | 72.8k | 3 | 12.8 | 0.8 | $0.0104 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | B | 4 | 4/4 (100%) | 0 | 72.9k | 3 | 12.9 | 1.1 | $0.0101 | codex-cli 0.166.0 |
| codex / gpt-6.1-terra low (default) | C | 4 | 4/4 (100%) | 0 | 112.3k | 5 | 20.9 | 0.0 | $0.0198 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | A | 4 | 4/4 (100%) | 0 | 80.9k | 3 | 15.0 | 0.9 | $0.0283 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | B | 4 | 4/4 (100%) | 0 | 86.3k | 3.5 | 15.6 | 1.1 | $0.0255 | codex-cli 0.166.0 |
| codex / gpt-6.1-sol low (default) | C | 4 | 4/4 (100%) | 0 | 158.1k | 8.5 | 26.4 | 0.3 | $0.0495 | codex-cli 0.166.0 |
| grok / grok-4.7 high 256k | A | 4 | 4/4 (100%) | 0 | 41.6k | 5 | 24.6 | 4.7 | $0.0491 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | B | 4 | 4/4 (100%) | 0 | 39.5k | 4.5 | 31.6 | 13.0 | $0.0443 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.7 high 256k | C | 4 | 4/4 (100%) | 0 | 325.1k | 19 | 148.4 | 11.6 | $0.3020 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | A | 4 | 4/4 (100%) | 0 | 32.9k | 4.5 | 11.9 | 2.8 | $0.0361 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | B | 4 | 4/4 (100%) | 0 | 32.9k | 4.5 | 10.6 | 2.7 | $0.0305 | grok 1.0.77 (50ca579fa996) [stable] |
| grok / grok-4.5 high | C | 4 | 4/4 (100%) | 0 | 199.9k | 16.5 | 61.9 | 11.4 | $0.1312 | grok 1.0.77 (50ca579fa996) [stable] |
| cursor / composer-2.5 | A | 4 | 4/4 (100%) | 0 | 62.1k | 4 | 32.8 | 0.8 | $0.0183 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | B | 4 | 4/4 (100%) | 0 | 54.0k | 3.5 | 30.0 | 0.7 | $0.0148 | 2026.11.13-eacade1 |
| cursor / composer-2.5 | C | 4 | 4/4 (100%) | 0 | 154.5k | 9 | 35.7 | 0.3 | $0.0413 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | A | 4 | 4/4 (100%) | 0 | 54.9k | 3 | 36.7 | 0.9 | $0.0609 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | B | 4 | 4/4 (100%) | 0 | 55.9k | 3 | 38.2 | 0.8 | $0.0904 | 2026.11.13-eacade1 |
| cursor / grok-4.7-high | C | 4 | 4/4 (100%) | 0 | 240.5k | 11.5 | 136.2 | 0.8 | $0.2717 | 2026.11.13-eacade1 |
| agy / gemini-4-hydrogen-medium | A | 4 | 4/4 (100%) | 0 | 25.8k | 4 | 19.4 | 3.0 | $0.0291 | 1.5.10, 1.5.9 |
| agy / gemini-4-hydrogen-medium | B | 4 | 4/4 (100%) | 0 | 25.5k | 4 | 19.0 | 3.6 | $0.0276 | 1.5.10 |
| agy / gemini-4-hydrogen-medium | C | 4 | 4/4 (100%) | 0 | 120.4k | 11.5 | 60.7 | 8.1 | $0.0908 | 1.5.10 |
| muse / spark-1.3-contributor high | A | 4 | 4/4 (100%) | 0 | 141.0k | 7 | 55.3 | 0.9 | $0.0046 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | B | 4 | 4/4 (100%) | 0 | 237.7k | 10.5 | 55.5 | 1.2 | $0.0053 | Muse Code 1.4.23 (1.4.23-R8977.1) |
| muse / spark-1.3-contributor high | C | 4 | 4/4 (100%) | 0 | 481.4k | 20 | 91.5 | 0.6 | $0.0049 | Muse Code 1.4.23 (1.4.23-R8977.1) |

### Tier 0 on daybreak (smoke fixture, 1 run per cell): savings of A against B and C

Per config, summed over the tasks both arms measured (sum of per-task medians); positive means A lower. Noise: with repeats, `overlap` means the arms' summed per-task ranges overlap, so the difference is inside run-to-run noise; with one run per cell there is no noise estimate.

| Config | vs | Tasks | Green A / other | Tokens A → other | Δ tokens | Δ turns | Δ wall | Δ cost | Tokens noise |
|---|---|---:|---|---|---:|---:|---:|---:|---|
| claude2 / sonnet-5-5 medium | B | 4 | 4/4 / 4/4 | 91.8k → 74.2k | -24% | +0% | -27% | -14% | 1 run |
| claude2 / sonnet-5-5 medium | C | 4 | 4/4 / 4/4 | 91.8k → 137.0k | +33% | +66% | +34% | +60% | 1 run |
| claude2 / opus-5-5 medium | B | 4 | 4/4 / 4/4 | 114.9k → 103.1k | -11% | -12% | -7% | -11% | 1 run |
| claude2 / opus-5-5 medium | C | 4 | 4/4 / 4/4 | 114.9k → 166.9k | +31% | +55% | +38% | +55% | 1 run |
| codex / gpt-6.1-terra low (default) | B | 4 | 4/4 / 4/4 | 276.5k → 292.5k | +5% | +8% | +9% | +3% | 1 run |
| codex / gpt-6.1-terra low (default) | C | 4 | 4/4 / 4/4 | 276.5k → 495.0k | +44% | +40% | +39% | +51% | 1 run |
| codex / gpt-6.1-sol low (default) | B | 4 | 4/4 / 4/4 | 312.9k → 331.0k | +5% | +7% | +5% | -5% | 1 run |
| codex / gpt-6.1-sol low (default) | C | 4 | 4/4 / 4/4 | 312.9k → 598.4k | +48% | +56% | +37% | +42% | 1 run |
| grok / grok-4.7 high 256k | B | 4 | 4/4 / 4/4 | 169.7k → 164.3k | -3% | -5% | +18% | -10% | 1 run |
| grok / grok-4.7 high 256k | C | 4 | 4/4 / 4/4 | 169.7k → 1794.9k | +91% | +76% | +43% | +87% | 1 run |
| grok / grok-4.5 high | B | 4 | 4/4 / 4/4 | 129.0k → 149.3k | +14% | +11% | +2% | +8% | 1 run |
| grok / grok-4.5 high | C | 4 | 4/4 / 4/4 | 129.0k → 1143.7k | +89% | +77% | +87% | +82% | 1 run |
| cursor / composer-2.5 | B | 4 | 4/4 / 4/4 | 261.1k → 220.5k | -18% | -21% | -5% | -30% | 1 run |
| cursor / composer-2.5 | C | 4 | 4/4 / 4/4 | 261.1k → 1126.7k | +77% | +68% | +29% | +72% | 1 run |
| cursor / grok-4.7-high | B | 4 | 4/4 / 4/4 | 233.0k → 236.4k | +1% | +0% | +13% | +26% | 1 run |
| cursor / grok-4.7-high | C | 4 | 4/4 / 4/4 | 233.0k → 1037.7k | +78% | +73% | +74% | +79% | 1 run |
| agy / gemini-4-hydrogen-medium | B | 4 | 4/4 / 4/4 | 105.8k → 126.5k | +16% | +16% | +18% | +7% | 1 run |
| agy / gemini-4-hydrogen-medium | C | 4 | 4/4 / 4/4 | 105.8k → 485.9k | +78% | +67% | +69% | +68% | 1 run |
| muse / spark-1.3-contributor high | B | 4 | 4/4 / 4/4 | 563.5k → 1122.5k | +50% | +42% | +36% | +10% | 1 run |
| muse / spark-1.3-contributor high | C | 4 | 4/4 / 4/4 | 563.5k → 1804.8k | +69% | +62% | +52% | +4% | 1 run |

### Tier 0 on daybreak (smoke fixture, 1 run per cell): per task

| Config | Task | Arm | Runs | Green | Median tokens (min–max) | Turns | Wall s | Est. cost |
|---|---|---|---:|---:|---|---:|---:|---:|
| claude2 / sonnet-5-5 medium | smoke-callers | A | 1 | 1 | 20.4k | 3 | 9.6 | $0.0142 |
| claude2 / sonnet-5-5 medium | smoke-callers | B | 1 | 1 | 20.4k | 3 | 5.2 | $0.0143 |
| claude2 / sonnet-5-5 medium | smoke-callers | C | 1 | 1 | 29.9k | 6 | 12.5 | $0.0283 |
| claude2 / sonnet-5-5 medium | smoke-rename | A | 1 | 1 | 12.8k | 2 | 8.6 | $0.0105 |
| claude2 / sonnet-5-5 medium | smoke-rename | B | 1 | 1 | 12.8k | 2 | 8.2 | $0.0105 |
| claude2 / sonnet-5-5 medium | smoke-rename | C | 1 | 1 | 22.6k | 9 | 15.5 | $0.0360 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | A | 1 | 1 | 31.2k | 4 | 13.0 | $0.0223 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | B | 1 | 1 | 20.4k | 4 | 11.9 | $0.0150 |
| claude2 / sonnet-5-5 medium | smoke-rename-class | C | 1 | 1 | 45.2k | 13 | 22.8 | $0.0573 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | A | 1 | 1 | 27.4k | 4 | 14.6 | $0.0178 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | B | 1 | 1 | 20.6k | 4 | 10.8 | $0.0172 |
| claude2 / sonnet-5-5 medium | smoke-rename-field | C | 1 | 1 | 39.2k | 10 | 18.4 | $0.0399 |
| claude2 / opus-5-5 medium | smoke-callers | A | 1 | 1 | 28.3k | 4 | 12.5 | $0.0377 |
| claude2 / opus-5-5 medium | smoke-callers | B | 1 | 1 | 28.4k | 4 | 12.8 | $0.0378 |
| claude2 / opus-5-5 medium | smoke-callers | C | 1 | 1 | 28.8k | 4 | 12.8 | $0.0472 |
| claude2 / opus-5-5 medium | smoke-rename | A | 1 | 1 | 12.8k | 2 | 9.1 | $0.0218 |
| claude2 / opus-5-5 medium | smoke-rename | B | 1 | 1 | 27.5k | 4 | 11.7 | $0.0365 |
| claude2 / opus-5-5 medium | smoke-rename | C | 1 | 1 | 51.7k | 12 | 24.3 | $0.1039 |
| claude2 / opus-5-5 medium | smoke-rename-class | A | 1 | 1 | 26.4k | 4 | 12.6 | $0.0310 |
| claude2 / opus-5-5 medium | smoke-rename-class | B | 1 | 1 | 26.4k | 4 | 13.0 | $0.0310 |
| claude2 / opus-5-5 medium | smoke-rename-class | C | 1 | 1 | 47.6k | 15 | 28.4 | $0.1188 |
| claude2 / opus-5-5 medium | smoke-rename-field | A | 1 | 1 | 47.3k | 8 | 20.3 | $0.0696 |
| claude2 / opus-5-5 medium | smoke-rename-field | B | 1 | 1 | 20.8k | 4 | 13.3 | $0.0394 |
| claude2 / opus-5-5 medium | smoke-rename-field | C | 1 | 1 | 38.7k | 9 | 22.0 | $0.0836 |
| codex / gpt-6.1-terra low (default) | smoke-callers | A | 1 | 1 | 84.8k | 3 | 14.3 | $0.0159 |
| codex / gpt-6.1-terra low (default) | smoke-callers | B | 1 | 1 | 84.7k | 3 | 11.4 | $0.0159 |
| codex / gpt-6.1-terra low (default) | smoke-callers | C | 1 | 1 | 130.8k | 5 | 16.0 | $0.0221 |
| codex / gpt-6.1-terra low (default) | smoke-rename | A | 1 | 1 | 46.0k | 2 | 9.1 | $0.0064 |
| codex / gpt-6.1-terra low (default) | smoke-rename | B | 1 | 1 | 64.4k | 3 | 13.8 | $0.0086 |
| codex / gpt-6.1-terra low (default) | smoke-rename | C | 1 | 1 | 77.6k | 4 | 22.7 | $0.0105 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | A | 1 | 1 | 64.3k | 3 | 14.1 | $0.0084 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | B | 1 | 1 | 61.9k | 3 | 12.1 | $0.0103 |
| codex / gpt-6.1-terra low (default) | smoke-rename-class | C | 1 | 1 | 192.8k | 6 | 20.7 | $0.0384 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | A | 1 | 1 | 81.4k | 4 | 11.4 | $0.0125 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | B | 1 | 1 | 81.4k | 4 | 16.6 | $0.0099 |
| codex / gpt-6.1-terra low (default) | smoke-rename-field | C | 1 | 1 | 93.7k | 5 | 21.0 | $0.0175 |
| codex / gpt-6.1-sol low (default) | smoke-callers | A | 1 | 1 | 85.0k | 3 | 15.5 | $0.0369 |
| codex / gpt-6.1-sol low (default) | smoke-callers | B | 1 | 1 | 88.8k | 3 | 13.4 | $0.0350 |
| codex / gpt-6.1-sol low (default) | smoke-callers | C | 1 | 1 | 110.4k | 5 | 16.4 | $0.0382 |
| codex / gpt-6.1-sol low (default) | smoke-rename | A | 1 | 1 | 66.1k | 3 | 14.5 | $0.0185 |
| codex / gpt-6.1-sol low (default) | smoke-rename | B | 1 | 1 | 68.4k | 3 | 15.8 | $0.0203 |
| codex / gpt-6.1-sol low (default) | smoke-rename | C | 1 | 1 | 168.9k | 10 | 29.8 | $0.0497 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | A | 1 | 1 | 81.1k | 3 | 13.9 | $0.0294 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | B | 1 | 1 | 90.1k | 5 | 15.3 | $0.0248 |
| codex / gpt-6.1-sol low (default) | smoke-rename-class | C | 1 | 1 | 171.9k | 9 | 27.9 | $0.0561 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | A | 1 | 1 | 80.7k | 5 | 18.7 | $0.0272 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | B | 1 | 1 | 83.7k | 4 | 21.2 | $0.0261 |
| codex / gpt-6.1-sol low (default) | smoke-rename-field | C | 1 | 1 | 147.2k | 8 | 24.9 | $0.0492 |
| grok / grok-4.7 high 256k | smoke-callers | A | 1 | 1 | 41.2k | 5 | 13.4 | $0.0330 |
| grok / grok-4.7 high 256k | smoke-callers | B | 1 | 1 | 32.5k | 4 | 11.3 | $0.0382 |
| grok / grok-4.7 high 256k | smoke-callers | C | 1 | 1 | 333.8k | 19 | 100.8 | $0.2860 |
| grok / grok-4.7 high 256k | smoke-rename | A | 1 | 1 | 40.7k | 5 | 302.6 | $0.0629 |
| grok / grok-4.7 high 256k | smoke-rename | B | 1 | 1 | 32.9k | 4 | 16.8 | $0.0395 |
| grok / grok-4.7 high 256k | smoke-rename | C | 1 | 1 | 248.4k | 17 | 109.5 | $0.3017 |
| grok / grok-4.7 high 256k | smoke-rename-class | A | 1 | 1 | 45.8k | 5 | 22.9 | $0.0704 |
| grok / grok-4.7 high 256k | smoke-rename-class | B | 1 | 1 | 46.0k | 5 | 46.4 | $0.0567 |
| grok / grok-4.7 high 256k | smoke-rename-class | C | 1 | 1 | 896.5k | 30 | 247.1 | $0.6569 |
| grok / grok-4.7 high 256k | smoke-rename-field | A | 1 | 1 | 41.9k | 5 | 26.4 | $0.0354 |
| grok / grok-4.7 high 256k | smoke-rename-field | B | 1 | 1 | 52.9k | 6 | 371.4 | $0.0491 |
| grok / grok-4.7 high 256k | smoke-rename-field | C | 1 | 1 | 316.3k | 19 | 187.4 | $0.3023 |
| grok / grok-4.5 high | smoke-callers | A | 1 | 1 | 25.7k | 4 | 7.9 | $0.0214 |
| grok / grok-4.5 high | smoke-callers | B | 1 | 1 | 26.0k | 4 | 8.1 | $0.0300 |
| grok / grok-4.5 high | smoke-callers | C | 1 | 1 | 204.4k | 18 | 59.8 | $0.1313 |
| grok / grok-4.5 high | smoke-rename | A | 1 | 1 | 22.0k | 3 | 9.8 | $0.0346 |
| grok / grok-4.5 high | smoke-rename | B | 1 | 1 | 23.0k | 3 | 9.2 | $0.0301 |
| grok / grok-4.5 high | smoke-rename | C | 1 | 1 | 133.4k | 12 | 41.2 | $0.0928 |
| grok / grok-4.5 high | smoke-rename-class | A | 1 | 1 | 40.0k | 5 | 13.9 | $0.0376 |
| grok / grok-4.5 high | smoke-rename-class | B | 1 | 1 | 39.8k | 5 | 12.0 | $0.0309 |
| grok / grok-4.5 high | smoke-rename-class | C | 1 | 1 | 610.5k | 30 | 209.6 | $0.4356 |
| grok / grok-4.5 high | smoke-rename-field | A | 1 | 1 | 41.3k | 5 | 17.1 | $0.0494 |
| grok / grok-4.5 high | smoke-rename-field | B | 1 | 1 | 60.4k | 7 | 20.6 | $0.0647 |
| grok / grok-4.5 high | smoke-rename-field | C | 1 | 1 | 195.4k | 15 | 64.0 | $0.1311 |
| cursor / composer-2.5 | smoke-callers | A | 1 | 1 | 46.0k | 3 | 18.0 | $0.0123 |
| cursor / composer-2.5 | smoke-callers | B | 1 | 1 | 46.2k | 3 | 38.6 | $0.0124 |
| cursor / composer-2.5 | smoke-callers | C | 1 | 1 | 136.0k | 9 | 37.1 | $0.0373 |
| cursor / composer-2.5 | smoke-rename | A | 1 | 1 | 59.6k | 4 | 32.8 | $0.0185 |
| cursor / composer-2.5 | smoke-rename | B | 1 | 1 | 34.0k | 2 | 21.7 | $0.0100 |
| cursor / composer-2.5 | smoke-rename | C | 1 | 1 | 117.6k | 8 | 24.3 | $0.0300 |
| cursor / composer-2.5 | smoke-rename-class | A | 1 | 1 | 64.5k | 4 | 32.9 | $0.0182 |
| cursor / composer-2.5 | smoke-rename-class | B | 1 | 1 | 78.4k | 5 | 20.0 | $0.0208 |
| cursor / composer-2.5 | smoke-rename-class | C | 1 | 1 | 173.0k | 9 | 34.3 | $0.0454 |
| cursor / composer-2.5 | smoke-rename-field | A | 1 | 1 | 91.0k | 6 | 40.8 | $0.0294 |
| cursor / composer-2.5 | smoke-rename-field | B | 1 | 1 | 61.8k | 4 | 38.3 | $0.0171 |
| cursor / composer-2.5 | smoke-rename-field | C | 1 | 1 | 700.0k | 27 | 79.2 | $0.1680 |
| cursor / grok-4.7-high | smoke-callers | A | 1 | 1 | 52.4k | 3 | 20.8 | $0.0519 |
| cursor / grok-4.7-high | smoke-callers | B | 1 | 1 | 52.5k | 3 | 24.7 | $0.0553 |
| cursor / grok-4.7-high | smoke-callers | C | 1 | 1 | 204.8k | 11 | 86.5 | $0.2390 |
| cursor / grok-4.7-high | smoke-rename | A | 1 | 1 | 70.8k | 4 | 38.4 | $0.0701 |
| cursor / grok-4.7-high | smoke-rename | B | 1 | 1 | 72.0k | 4 | 41.2 | $0.0941 |
| cursor / grok-4.7-high | smoke-rename | C | 1 | 1 | 268.8k | 12 | 153.6 | $0.3045 |
| cursor / grok-4.7-high | smoke-rename-class | A | 1 | 1 | 56.9k | 3 | 35.6 | $0.0654 |
| cursor / grok-4.7-high | smoke-rename-class | B | 1 | 1 | 56.9k | 3 | 51.1 | $0.0920 |
| cursor / grok-4.7-high | smoke-rename-class | C | 1 | 1 | 212.2k | 10 | 118.9 | $0.2175 |
| cursor / grok-4.7-high | smoke-rename-field | A | 1 | 1 | 52.9k | 3 | 37.7 | $0.0565 |
| cursor / grok-4.7-high | smoke-rename-field | B | 1 | 1 | 55.0k | 3 | 35.2 | $0.0889 |
| cursor / grok-4.7-high | smoke-rename-field | C | 1 | 1 | 351.9k | 15 | 158.2 | $0.4185 |
| agy / gemini-4-hydrogen-medium | smoke-callers | A | 1 | 1 | 24.3k | 4 | 18.7 | $0.0257 |
| agy / gemini-4-hydrogen-medium | smoke-callers | B | 1 | 1 | 24.2k | 4 | 17.8 | $0.0250 |
| agy / gemini-4-hydrogen-medium | smoke-callers | C | 1 | 1 | 122.3k | 11 | 46.6 | $0.0946 |
| agy / gemini-4-hydrogen-medium | smoke-rename | A | 1 | 1 | 19.4k | 3 | 15.7 | $0.0233 |
| agy / gemini-4-hydrogen-medium | smoke-rename | B | 1 | 1 | 18.4k | 3 | 16.7 | $0.0225 |
| agy / gemini-4-hydrogen-medium | smoke-rename | C | 1 | 1 | 87.3k | 11 | 42.6 | $0.0668 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | A | 1 | 1 | 34.7k | 5 | 25.9 | $0.0341 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | B | 1 | 1 | 57.2k | 8 | 43.7 | $0.0470 |
| agy / gemini-4-hydrogen-medium | smoke-rename-class | C | 1 | 1 | 118.5k | 12 | 99.5 | $0.0870 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | A | 1 | 1 | 27.4k | 4 | 20.2 | $0.0326 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | B | 1 | 1 | 26.8k | 4 | 20.1 | $0.0301 |
| agy / gemini-4-hydrogen-medium | smoke-rename-field | C | 1 | 1 | 157.7k | 14 | 74.7 | $0.1085 |
| muse / spark-1.3-contributor high | smoke-callers | A | 1 | 1 | 57.7k | 3 | 41.7 | $0.0041 |
| muse / spark-1.3-contributor high | smoke-callers | B | 1 | 1 | 57.1k | 3 | 36.2 | $0.0021 |
| muse / spark-1.3-contributor high | smoke-callers | C | 1 | 1 | 210.7k | 10 | 58.9 | $0.0032 |
| muse / spark-1.3-contributor high | smoke-rename | A | 1 | 1 | 96.5k | 5 | 24.7 | $0.0042 |
| muse / spark-1.3-contributor high | smoke-rename | B | 1 | 1 | 57.8k | 3 | 37.8 | $0.0041 |
| muse / spark-1.3-contributor high | smoke-rename | C | 1 | 1 | 631.2k | 24 | 92.6 | $0.0057 |
| muse / spark-1.3-contributor high | smoke-rename-class | A | 1 | 1 | 223.8k | 11 | 68.9 | $0.0050 |
| muse / spark-1.3-contributor high | smoke-rename-class | B | 1 | 1 | 590.0k | 24 | 199.6 | $0.0074 |
| muse / spark-1.3-contributor high | smoke-rename-class | C | 1 | 1 | 584.3k | 23 | 216.2 | $0.0061 |
| muse / spark-1.3-contributor high | smoke-rename-field | A | 1 | 1 | 185.5k | 9 | 86.7 | $0.0050 |
| muse / spark-1.3-contributor high | smoke-rename-field | B | 1 | 1 | 417.7k | 18 | 73.2 | $0.0066 |
| muse / spark-1.3-contributor high | smoke-rename-field | C | 1 | 1 | 378.6k | 17 | 90.4 | $0.0041 |

## Totals on daybreak (all tiers so far)

| Arm | Runs | Green | Tokens (sum) | Est. cost (sum) |
|---|---:|---:|---:|---:|
| A | 40 | 40 | 2.26M | $1.18 |
| B | 40 | 40 | 2.82M | $1.23 |
| C | 40 | 40 | 8.79M | $4.97 |

# Second host: bocabox (Fedora), tier 0 only

The same 120 cells on bocabox, run before the bench moved. Not pooled with daybreak. The 55 tier-1 rows bocabox
wrote before the move (of 180) are kept raw in rows-tier1-bocabox-partial.jsonl and are not used in any table.

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

### Tier 0 on bocabox (smoke fixture, 1 run per cell): savings of A against B and C

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
