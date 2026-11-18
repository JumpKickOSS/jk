# Stage P: anchor push off (O) vs silent (S) on arm M = origin/main 750848aea

Built by `python3 pushnoise.py --runs runs-stageP-M.jsonl O=rows-stageP-M-O.jsonl S=rows-stageP-M-S.jsonl` and `python3 ptask.py runs-stageP-M.jsonl` (transcripts stay on the bench host).

## Per config, arm and task kind

| Config | Arm | Kind | Runs | Green | Median tokens | Turns | Wall s | jk s | Reads+searches/run | Runs pushed | Pushed files fixed / named | Reads of pushed files/run | Of them on files left alone/run |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|
| claude2 / sonnet-5-5 medium | O | trap | 8 | 6/8 | 49.2k | 9.0 | 63.5 | 46.5 | 3.50 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / sonnet-5-5 medium | S | trap | 8 | 6/8 | 50.2k | 10.0 | 64.2 | 45.2 | 3.88 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / sonnet-5-5 medium | O | decoy | 8 | 8/8 | 30.6k | 5.5 | 58.9 | 44.7 | 2.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / sonnet-5-5 medium | S | decoy | 8 | 8/8 | 29.7k | 5.0 | 61.5 | 45.2 | 2.25 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | O | trap | 8 | 8/8 | 63.5k | 11.0 | 61.6 | 25.4 | 4.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | S | trap | 8 | 8/8 | 64.1k | 11.0 | 57.3 | 25.8 | 5.38 | 1/8 | 1 / 1 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | O | decoy | 8 | 8/8 | 69.0k | 11.0 | 71.8 | 44.8 | 4.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| claude2 / opus-5-5 medium | S | decoy | 8 | 8/8 | 69.7k | 10.0 | 73.6 | 45.5 | 4.50 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | O | trap | 8 | 7/8 | 285.0k | 9.0 | 114.3 | 52.0 | 2.88 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | S | trap | 8 | 4/8 | 397.7k | 11.5 | 125.4 | 50.0 | 3.88 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | O | decoy | 8 | 8/8 | 160.7k | 5.5 | 69.7 | 5.4 | 2.12 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-terra low (default) | S | decoy | 8 | 8/8 | 133.1k | 5.5 | 66.2 | 45.1 | 2.88 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | O | trap | 8 | 6/8 | 160.5k | 6.0 | 51.4 | 26.2 | 1.50 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | S | trap | 8 | 6/8 | 156.7k | 6.0 | 49.7 | 26.2 | 1.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | O | decoy | 8 | 8/8 | 142.1k | 5.5 | 68.0 | 46.0 | 1.75 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | S | decoy | 8 | 8/8 | 136.4k | 5.0 | 65.9 | 45.0 | 1.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | O | trap | 8 | 6/8 | 342.7k | 16.0 | 111.5 | 50.3 | 25.75 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | S | trap | 8 | 5/8 | 423.7k | 16.0 | 92.2 | 52.0 | 23.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | O | decoy | 8 | 8/8 | 137.3k | 7.5 | 71.8 | 46.3 | 13.50 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | S | decoy | 8 | 8/8 | 178.3k | 9.5 | 85.7 | 47.1 | 16.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / grok-4.7-high | O | trap | 4 | 2/4 | 486.4k | 14.5 | 314.4 | 55.6 | 30.75 | 0/4 | 0 / 0 | 0.00 | 0.00 |
| cursor / grok-4.7-high | S | trap | 4 | 3/4 | 423.7k | 13.5 | 234.4 | 10.6 | 23.25 | 1/4 | 0 / 4 | 0.00 | 0.00 |
| cursor / grok-4.7-high | O | decoy | 4 | 4/4 | 186.1k | 7.5 | 144.0 | 53.7 | 15.25 | 0/4 | 0 / 0 | 0.00 | 0.00 |
| cursor / grok-4.7-high | S | decoy | 4 | 4/4 | 213.4k | 9.0 | 168.1 | 53.0 | 12.00 | 0/4 | 0 / 0 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | O | trap | 8 | 8/8 | 156.2k | 11.5 | 101.8 | 45.2 | 7.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | S | trap | 8 | 8/8 | 138.3k | 11.5 | 84.3 | 38.1 | 6.75 | 1/8 | 1 / 1 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | O | decoy | 8 | 8/8 | 141.9k | 11.0 | 119.5 | 59.0 | 6.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| agy / gemini-4-hydrogen-medium | S | decoy | 8 | 8/8 | 129.4k | 10.0 | 105.0 | 58.2 | 6.25 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | O | trap | 8 | 6/8 | 1339.5k | 36.0 | 221.1 | 53.6 | 25.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | S | trap | 8 | 8/8 | 1284.6k | 35.5 | 222.1 | 52.1 | 26.38 | 5/8 | 5 / 9 | 0.38 | 0.00 |
| muse / spark-1.3-contributor high | O | decoy | 8 | 8/8 | 408.9k | 14.5 | 99.1 | 47.2 | 9.00 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | S | decoy | 8 | 8/8 | 461.3k | 17.5 | 98.9 | 48.1 | 12.38 | 0/8 | 0 / 0 | 0.00 | 0.00 |

| Config | Traps green O → S | Trap tokens O → S (sum of per-task medians) | Decoys green O → S | Decoy reads/run O → S | Decoy turns O → S (sum of per-task medians) | Noise reads/run in S (traps, decoys) |
|---|---|---|---|---|---|---|
| claude2 / sonnet-5-5 medium | 6/8 → 6/8 | 220.4k → 255.6k | 8/8 → 8/8 | 2.38 → 2.25 | 27 → 24 | 0.00, 0.00 |
| claude2 / opus-5-5 medium | 8/8 → 8/8 | 244.1k → 277.8k | 8/8 → 8/8 | 4.62 → 4.50 | 42 → 40 | 0.00, 0.00 |
| codex / gpt-6.1-terra low (default) | 7/8 → 4/8 | 1260.5k → 1521.5k | 8/8 → 8/8 | 2.12 → 2.88 | 21.5 → 23.5 | 0.00, 0.00 |
| codex / gpt-6.1-sol low (default) | 6/8 → 6/8 | 654.1k → 604.2k | 8/8 → 8/8 | 1.75 → 1.62 | 24 → 21 | 0.00, 0.00 |
| cursor / composer-2.5 | 6/8 → 5/8 | 1510.7k → 1669.7k | 8/8 → 8/8 | 13.50 → 16.38 | 36 → 46.5 | 0.00, 0.00 |
| cursor / grok-4.7-high | 2/4 → 3/4 | 3131.5k → 1932.6k | 4/4 → 4/4 | 15.25 → 12.00 | 44 → 36 | 0.00, 0.00 |
| agy / gemini-4-hydrogen-medium | 8/8 → 8/8 | 541.4k → 557.2k | 8/8 → 8/8 | 6.38 → 6.25 | 42.5 → 39 | 0.00, 0.00 |
| muse / spark-1.3-contributor high | 6/8 → 8/8 | 5948.3k → 5609.6k | 8/8 → 8/8 | 9.00 → 12.38 | 61.5 → 75.5 | 0.00, 0.00 |

## Per task, all configs

| Task | O green | S green |
|---|---:|---:|
| petclinic-push-column-h2 | 11/15 | 10/15 |
| petclinic-push-config-key | 15/15 | 13/15 |
| petclinic-push-decoy-getpet | 15/15 | 15/15 |
| petclinic-push-decoy-limit-value | 15/15 | 15/15 |
| petclinic-push-decoy-not-found | 15/15 | 15/15 |
| petclinic-push-json-property | 8/15 | 10/15 |
| petclinic-push-ts-client | 15/15 | 15/15 |
| petclinic-push-ts-client-decoy | 15/15 | 15/15 |

Runs where S showed a push:

| Config | Task | Run | Green | Pushed files fixed / named | Reads of pushed files |
|---|---|---:|---|---|---:|
| claude2 / opus-5-5 medium | petclinic-push-column-h2 | 1 | green | 1 / 1 | 0 |
| muse / spark-1.3-contributor high | petclinic-push-config-key | 1 | green | 1 / 3 | 1 |
| muse / spark-1.3-contributor high | petclinic-push-json-property | 1 | green | 1 / 1 | 1 |
| cursor / grok-4.7-high | petclinic-push-column-h2 | 1 | claimed | 0 / 4 | 0 |
| agy / gemini-4-hydrogen-medium | petclinic-push-column-h2 | 2 | green | 1 / 1 | 0 |
| muse / spark-1.3-contributor high | petclinic-push-column-h2 | 2 | green | 1 / 1 | 0 |
| muse / spark-1.3-contributor high | petclinic-push-config-key | 2 | green | 1 / 3 | 0 |
| muse / spark-1.3-contributor high | petclinic-push-json-property | 2 | green | 1 / 1 | 1 |

O runs on the same config and task:

- claude2 / opus-5-5 medium petclinic-push-column-h2: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-config-key: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-json-property: O ['claimed', 'claimed']
- cursor / grok-4.7-high petclinic-push-column-h2: O ['claimed']
- agy / gemini-4-hydrogen-medium petclinic-push-column-h2: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-column-h2: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-config-key: O ['green', 'green']
- muse / spark-1.3-contributor high petclinic-push-json-property: O ['claimed', 'claimed']
