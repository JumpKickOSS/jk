# Count (Sc) and kinds (Sk) on M2 against P' off (O) and silent (S): codex sol, cursor composer, muse, traps only

O and S rows are P''s rows of these three configs and four traps; Sc and Sk ran after P' (2026-11-19 00:05-02:03), not interleaved with them. Built by `python3 pushnoise.py O=... S=... Sc=rows-stageP2k-M2-Sc.jsonl Sk=rows-stageP2k-M2-Sk.jsonl`.

| Config | Arm | Kind | Runs | Green | Median tokens | Turns | Wall s | jk s | Reads+searches/run | Runs pushed | Pushed files fixed / named | Reads of pushed files/run | Of them on files left alone/run |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|
| codex / gpt-6.1-sol low (default) | O | trap | 8 | 5/8 | 137.4k | 5.5 | 55.2 | 36.1 | 1.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| codex / gpt-6.1-sol low (default) | S | trap | 8 | 7/8 | 169.0k | 8.0 | 41.2 | 17.9 | 1.62 | 4/8 | 3 / 7 | 0.25 | 0.00 |
| codex / gpt-6.1-sol low (default) | Sc | trap | 8 | 8/8 | 152.2k | 9.0 | 48.2 | 21.9 | 1.88 | 4/8 | 4 / 4 | 0.25 | 0.00 |
| codex / gpt-6.1-sol low (default) | Sk | trap | 8 | 8/8 | 149.4k | 8.5 | 49.3 | 26.5 | 1.62 | 4/8 | 4 / 4 | 0.25 | 0.00 |
| cursor / composer-2.5 | O | trap | 8 | 5/8 | 302.4k | 13.5 | 59.2 | 5.2 | 28.00 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| cursor / composer-2.5 | S | trap | 8 | 6/8 | 457.3k | 19.0 | 82.3 | 34.2 | 29.25 | 5/8 | 3 / 11 | 0.00 | 0.00 |
| cursor / composer-2.5 | Sc | trap | 8 | 6/8 | 517.6k | 20.0 | 104.8 | 48.8 | 31.88 | 3/8 | 1 / 9 | 0.00 | 0.00 |
| cursor / composer-2.5 | Sk | trap | 8 | 6/8 | 380.5k | 17.5 | 95.5 | 45.8 | 26.12 | 2/8 | 0 / 7 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | O | trap | 8 | 6/8 | 1199.3k | 33.0 | 163.3 | 49.1 | 25.62 | 0/8 | 0 / 0 | 0.00 | 0.00 |
| muse / spark-1.3-contributor high | S | trap | 8 | 7/8 | 1506.4k | 39.0 | 230.8 | 50.8 | 27.38 | 5/8 | 5 / 6 | 0.38 | 0.00 |
| muse / spark-1.3-contributor high | Sc | trap | 8 | 8/8 | 1232.7k | 36.5 | 212.4 | 48.2 | 26.12 | 5/8 | 5 / 9 | 0.38 | 0.25 |
| muse / spark-1.3-contributor high | Sk | trap | 8 | 8/8 | 1054.9k | 31.5 | 219.1 | 51.8 | 23.88 | 6/8 | 6 / 10 | 0.88 | 0.50 |

