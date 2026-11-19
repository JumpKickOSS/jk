# Stage P': anchor push off (O) vs silent (S) on arm M2 = origin/main 750848aea + the JK-HVWV7 fix

Built by `python3 pushnoise.py --runs runs-stageP2-M2.jsonl O=rows-stageP2-M2-O.jsonl S=rows-stageP2-M2-S.jsonl`, `python3 ptask.py runs-stageP2-M2.jsonl` and `python3 pconv.py runs-stageP2-M2.jsonl`.

## Per config, arm and task kind

| Config | Arm | Kind | Runs | Green | Median tokens | Turns | Wall s | jk s | Reads+searches/run | Runs pushed | Pushed files fixed / named | Reads of pushed files/run | Of them on files left alone/run |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|
| claude2 / sonnet-5-5 medium | O | trap | 8 | 7/8 | 45.2k | 11.0 | 45.5 | 31.4 | 4.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / sonnet-5-5 medium | S | trap | 8 | 8/8 | 54.8k | 12.5 | 47.3 | 32.3 | 5.00 | 4/8 | 4 / 4 | 0.12 | 0.00 |
| claude2 / sonnet-5-5 medium | O | decoy | 8 | 8/8 | 29.8k | 5.0 | 31.9 | 15.4 | 2.50 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / sonnet-5-5 medium | S | decoy | 8 | 8/8 | 29.0k | 5.0 | 38.1 | 30.3 | 2.25 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | O | trap | 8 | 8/8 | 61.8k | 10.5 | 39.0 | 15.6 | 5.00 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | S | trap | 8 | 7/8 | 62.6k | 11.5 | 36.5 | 16.6 | 5.50 | 2/8 | 1 / 5 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | O | decoy | 8 | 8/8 | 66.9k | 9.5 | 51.5 | 30.9 | 4.12 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | S | decoy | 8 | 8/8 | 71.3k | 11.5 | 57.5 | 31.4 | 5.00 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | O | trap | 8 | 6/8 | 192.2k | 7.0 | 66.1 | 32.9 | 3.12 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | S | trap | 8 | 8/8 | 214.4k | 7.0 | 64.0 | 17.2 | 2.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | O | decoy | 8 | 8/8 | 121.2k | 5.0 | 51.3 | 31.0 | 2.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | S | decoy | 8 | 8/8 | 157.6k | 7.0 | 60.0 | 31.2 | 3.12 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | O | trap | 8 | 5/8 | 137.4k | 5.5 | 55.2 | 36.1 | 1.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | S | trap | 8 | 7/8 | 169.0k | 8.0 | 41.2 | 17.9 | 1.62 | 4/8 | 3 / 7 | 0.25 | 0.00 |
| codex / gpt-6.1-sol low (default) | O | decoy | 8 | 8/8 | 109.1k | 6.0 | 55.8 | 31.4 | 1.50 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | S | decoy | 8 | 8/8 | 77.3k | 5.0 | 52.2 | 30.4 | 1.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.7 high 256k | O | trap | 4 | 3/4 | 622.3k | 20.0 | 271.6 | 18.7 | 33.75 | 0/4 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.7 high 256k | S | trap | 4 | 3/4 | 246.0k | 18.5 | 310.4 | 13.3 | 41.25 | 1/4 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.7 high 256k | O | decoy | 4 | 4/4 | 303.4k | 11.5 | 156.3 | 5.5 | 14.75 | 0/4 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.7 high 256k | S | decoy | 4 | 4/4 | 317.5k | 13.5 | 177.9 | 4.8 | 17.50 | 0/4 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.5 high | O | trap | 8 | 8/8 | 192.6k | 12.0 | 86.5 | 16.7 | 14.75 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.5 high | S | trap | 8 | 8/8 | 162.6k | 10.5 | 69.2 | 18.6 | 11.25 | 3/8 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.5 high | O | decoy | 8 | 8/8 | 96.3k | 7.0 | 70.8 | 6.6 | 7.75 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| grok / grok-4.5 high | S | decoy | 8 | 8/8 | 92.5k | 7.5 | 52.6 | 6.0 | 7.12 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | O | trap | 8 | 5/8 | 302.4k | 13.5 | 59.2 | 5.2 | 28.00 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | S | trap | 8 | 6/8 | 457.3k | 19.0 | 82.3 | 34.2 | 29.25 | 5/8 | 3 / 11 | 0.00 | 0.00 |
| cursor / composer-2.5 | O | decoy | 8 | 8/8 | 169.4k | 9.0 | 63.5 | 38.2 | 17.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | S | decoy | 8 | 8/8 | 161.7k | 8.0 | 77.1 | 40.9 | 11.88 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | O | trap | 8 | 8/8 | 144.1k | 11.5 | 83.4 | 39.0 | 6.50 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | S | trap | 8 | 8/8 | 144.5k | 11.5 | 70.5 | 30.9 | 6.88 | 4/8 | 5 / 5 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | O | decoy | 8 | 8/8 | 128.2k | 10.0 | 99.9 | 52.8 | 5.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | S | decoy | 8 | 8/8 | 123.5k | 10.0 | 93.1 | 53.0 | 4.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | O | trap | 8 | 6/8 | 1199.3k | 33.0 | 163.3 | 49.1 | 25.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | S | trap | 8 | 7/8 | 1506.4k | 39.0 | 230.8 | 50.8 | 27.38 | 5/8 | 5 / 6 | 0.38 | 0.00 |
| muse / spark-1.3-contributor high | O | decoy | 8 | 8/8 | 381.0k | 13.5 | 96.4 | 45.3 | 11.00 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | S | decoy | 8 | 8/8 | 457.1k | 16.0 | 96.2 | 41.8 | 9.25 | 0/8 | 0 / 0 | 0.00 | 0.00 |

| Config | Traps green O → S | Trap tokens O → S (sum of per-task medians) | Decoys green O → S | Decoy reads/run O → S | Decoy turns O → S (sum of per-task medians) | Noise reads/run in S (traps, decoys) |
|---|---|---|---|---|---|---|
| claude2 / sonnet-5-5 medium | 7/8 → 8/8 | 229.0k → 259.3k | 8/8 → 8/8 | 2.50 → 2.25 | 27 → 25.5 | 0.00, 0.00 |
| claude2 / opus-5-5 medium | 8/8 → 7/8 | 267.1k → 304.8k | 8/8 → 8/8 | 4.12 → 5.00 | 39.5 → 43.5 | 0.00, 0.00 |
| codex / gpt-6.1-terra low (default) | 6/8 → 8/8 | 1185.9k → 838.3k | 8/8 → 8/8 | 2.62 → 3.12 | 24.5 → 25.5 | 0.00, 0.00 |
| codex / gpt-6.1-sol low (default) | 5/8 → 7/8 | 572.6k → 712.4k | 8/8 → 8/8 | 1.50 → 1.62 | 23 → 22.5 | 0.00, 0.00 |
| grok / grok-4.7 high 256k | 3/4 → 3/4 | 3267.2k → 1327.3k | 4/4 → 4/4 | 14.75 → 17.50 | 43 → 49 | 0.00, 0.00 |
| grok / grok-4.5 high | 8/8 → 8/8 | 1041.2k → 753.6k | 8/8 → 8/8 | 7.75 → 7.12 | 27 → 27.5 | 0.00, 0.00 |
| cursor / composer-2.5 | 5/8 → 6/8 | 2270.4k → 1995.4k | 8/8 → 8/8 | 17.38 → 11.88 | 48 → 39 | 0.00, 0.00 |
| agy / gemini-4-hydrogen-medium | 8/8 → 8/8 | 599.6k → 539.5k | 8/8 → 8/8 | 5.38 → 4.62 | 37.5 → 34.5 | 0.00, 0.00 |
| muse / spark-1.3-contributor high | 6/8 → 7/8 | 5181.4k → 6044.4k | 8/8 → 8/8 | 11.00 → 9.25 | 67.5 → 65.5 | 0.00, 0.00 |

## Per task, all configs

| Task | O green | S green |
|---|---:|---:|
| petclinic-push-column-h2 | 12/17 | 12/17 |
| petclinic-push-config-key | 16/17 | 17/17 |
| petclinic-push-decoy-getpet | 17/17 | 17/17 |
| petclinic-push-decoy-limit-value | 17/17 | 17/17 |
| petclinic-push-decoy-not-found | 17/17 | 17/17 |
| petclinic-push-json-property | 11/17 | 16/17 |
| petclinic-push-ts-client | 17/17 | 17/17 |
| petclinic-push-ts-client-decoy | 17/17 | 17/17 |

Runs where S showed a push:

| Config | Task | Run | Green | Pushed files fixed / named | Reads of pushed files |
|---|---|---:|---|---|---:|
| claude2 / sonnet-5-5 medium | petclinic-push-column-h2 | 1 | green | 1 / 1 | 0 |
| claude2 / sonnet-5-5 medium | petclinic-push-config-key | 1 | green | 1 / 1 | 0 |
| claude2 / sonnet-5-5 medium | petclinic-push-json-property | 1 | green | 1 / 1 | 1 |
| claude2 / opus-5-5 medium | petclinic-push-column-h2 | 1 | claimed | 0 / 4 | 0 |
| codex / gpt-6.1-sol low (default) | petclinic-push-column-h2 | 1 | green | 1 / 1 | 0 |
| codex / gpt-6.1-sol low (default) | petclinic-push-json-property | 1 | green | 1 / 1 | 1 |
| grok / grok-4.7 high 256k | petclinic-push-column-h2 | 1 | red | 0 / 0 | 0 |
| cursor / composer-2.5 | petclinic-push-column-h2 | 1 | claimed | 0 / 4 | 0 |
| cursor / composer-2.5 | petclinic-push-json-property | 1 | green | 1 / 1 | 0 |
| agy / gemini-4-hydrogen-medium | petclinic-push-column-h2 | 1 | green | 1 / 1 | 0 |
| agy / gemini-4-hydrogen-medium | petclinic-push-json-property | 1 | green | 1 / 1 | 0 |
| muse / spark-1.3-contributor high | petclinic-push-column-h2 | 1 | green | 1 / 1 | 0 |
| muse / spark-1.3-contributor high | petclinic-push-config-key | 1 | green | 1 / 1 | 1 |
| grok / grok-4.5 high | petclinic-push-column-h2 | 1 | green | 0 / 0 | 0 |
| claude2 / sonnet-5-5 medium | petclinic-push-column-h2 | 2 | green | 1 / 1 | 0 |
| claude2 / opus-5-5 medium | petclinic-push-column-h2 | 2 | green | 1 / 1 | 0 |
| codex / gpt-6.1-sol low (default) | petclinic-push-column-h2 | 2 | claimed | 0 / 4 | 0 |
| codex / gpt-6.1-sol low (default) | petclinic-push-json-property | 2 | green | 1 / 1 | 1 |
| cursor / composer-2.5 | petclinic-push-column-h2 | 2 | claimed | 0 / 4 | 0 |
| cursor / composer-2.5 | petclinic-push-config-key | 2 | green | 1 / 1 | 0 |
| cursor / composer-2.5 | petclinic-push-json-property | 2 | green | 1 / 1 | 0 |
| agy / gemini-4-hydrogen-medium | petclinic-push-column-h2 | 2 | green | 1 / 1 | 0 |
| agy / gemini-4-hydrogen-medium | petclinic-push-ts-client | 2 | green | 2 / 2 | 0 |
| muse / spark-1.3-contributor high | petclinic-push-column-h2 | 2 | green | 1 / 1 | 1 |
| muse / spark-1.3-contributor high | petclinic-push-config-key | 2 | green | 2 / 2 | 1 |
| muse / spark-1.3-contributor high | petclinic-push-json-property | 2 | claimed | 0 / 1 | 0 |
| grok / grok-4.5 high | petclinic-push-column-h2 | 2 | green | 0 / 0 | 0 |
| grok / grok-4.5 high | petclinic-push-json-property | 2 | green | 0 / 0 | 0 |

O runs on the same config and task:

- claude2 / sonnet-5-5 medium petclinic-push-column-h2: O ['green', 'green']
- claude2 / sonnet-5-5 medium petclinic-push-config-key: O ['green', 'green']
- claude2 / sonnet-5-5 medium petclinic-push-json-property: O ['green', 'claimed']
- claude2 / opus-5-5 medium petclinic-push-column-h2: O ['green', 'green']
- codex / gpt-6.1-sol low (default) petclinic-push-column-h2: O ['green', 'claimed']
- codex / gpt-6.1-sol low (default) petclinic-push-json-property: O ['claimed', 'claimed']
- grok / grok-4.7 high 256k petclinic-push-column-h2: O ['claimed']
- cursor / composer-2.5 petclinic-push-column-h2: O ['claimed', 'claimed']
- cursor / composer-2.5 petclinic-push-json-property: O ['green', 'claimed']
- agy / gemini-4-hydrogen-medium petclinic-push-column-h2: O ['green', 'green']
- agy / gemini-4-hydrogen-medium petclinic-push-json-property: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-column-h2: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-config-key: O ['green', 'green']
- grok / grok-4.5 high petclinic-push-column-h2: O ['green', 'green']
- claude2 / sonnet-5-5 medium petclinic-push-column-h2: O ['green', 'green']
- claude2 / opus-5-5 medium petclinic-push-column-h2: O ['green', 'green']
- codex / gpt-6.1-sol low (default) petclinic-push-column-h2: O ['green', 'claimed']
- codex / gpt-6.1-sol low (default) petclinic-push-json-property: O ['claimed', 'claimed']
- cursor / composer-2.5 petclinic-push-column-h2: O ['claimed', 'claimed']
- cursor / composer-2.5 petclinic-push-config-key: O ['green', 'green']
- cursor / composer-2.5 petclinic-push-json-property: O ['green', 'claimed']
- agy / gemini-4-hydrogen-medium petclinic-push-column-h2: O ['green', 'green']
- agy / gemini-4-hydrogen-medium petclinic-push-ts-client: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-column-h2: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-config-key: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-json-property: O ['claimed', 'claimed']
- grok / grok-4.5 high petclinic-push-column-h2: O ['green', 'green']
- grok / grok-4.5 high petclinic-push-json-property: O ['green', 'green']

## Paired by config, task and repeat (traps), both stages

### Stage P (M)
| Config | Conversions, pushed | Conversions, not pushed | Losses, pushed | Losses, not pushed |
|---|---:|---:|---:|---:|
| agy / gemini-4-hydrogen-medium | 0 | 0 | 0 | 0 |
| claude2 / opus-5-5 medium | 0 | 0 | 0 | 0 |
| claude2 / sonnet-5-5 medium | 0 | 0 | 0 | 0 |
| codex / gpt-6.1-sol low (default) | 0 | 0 | 0 | 0 |
| codex / gpt-6.1-terra low (default) | 0 | 0 | 0 | 3 |
| cursor / composer-2.5 | 0 | 0 | 0 | 1 |
| cursor / grok-4.7-high | 0 | 1 | 0 | 0 |
| muse / spark-1.3-contributor high | 2 | 0 | 0 | 0 |
| all | 2 | 1 | 0 | 4 |
- all traps: O 49/60, S 48/60, one-sided Fisher p(S >= observed) = 0.678
- petclinic-push-column-h2: O 11/15, S 10/15, one-sided Fisher p(S >= observed) = 0.787
- petclinic-push-config-key: O 15/15, S 13/15, one-sided Fisher p(S >= observed) = 1.000
- petclinic-push-json-property: O 8/15, S 10/15, one-sided Fisher p(S >= observed) = 0.355
- petclinic-push-ts-client: O 15/15, S 15/15, one-sided Fisher p(S >= observed) = 1.000
### Stage P' (M2)
| Config | Conversions, pushed | Conversions, not pushed | Losses, pushed | Losses, not pushed |
|---|---:|---:|---:|---:|
| agy / gemini-4-hydrogen-medium | 0 | 0 | 0 | 0 |
| claude2 / opus-5-5 medium | 0 | 0 | 1 | 0 |
| claude2 / sonnet-5-5 medium | 0 | 1 | 0 | 0 |
| codex / gpt-6.1-sol low (default) | 2 | 0 | 0 | 0 |
| codex / gpt-6.1-terra low (default) | 0 | 2 | 0 | 0 |
| cursor / composer-2.5 | 1 | 0 | 0 | 0 |
| grok / grok-4.5 high | 0 | 0 | 0 | 0 |
| grok / grok-4.7 high 256k | 0 | 0 | 0 | 0 |
| muse / spark-1.3-contributor high | 0 | 1 | 0 | 0 |
| all | 3 | 4 | 1 | 0 |
- all traps: O 56/68, S 62/68, one-sided Fisher p(S >= observed) = 0.102
- petclinic-push-column-h2: O 12/17, S 12/17, one-sided Fisher p(S >= observed) = 0.646
- petclinic-push-config-key: O 16/17, S 17/17, one-sided Fisher p(S >= observed) = 0.500
- petclinic-push-json-property: O 11/17, S 16/17, one-sided Fisher p(S >= observed) = 0.043
- petclinic-push-ts-client: O 17/17, S 17/17, one-sided Fisher p(S >= observed) = 1.000
