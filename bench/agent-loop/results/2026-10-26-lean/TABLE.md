# Change tasks — 2026-10-26-lean

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-rename-indexof | jk | 3 | 3 | 46082 | 8 | 3658 | 18 | 26 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk-hooks | 3 | 3 | 92183 | 12 | 4186 | 20 | 28 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk-hooks-replace | 3 | 3 | 101871 | 14 | 4358 | 21 | 31 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk | 3 | 3 | 317766 | 26 | 14752 | 40 | 105 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk-hooks | 3 | 3 | 309140 | 26 | 14847 | 48 | 99 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk-hooks-replace | 3 | 3 | 657864 | 58 | 16401 | 58 | 136 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk | 3 | 3 | 90912 | 8 | 1008 | 7 | 56 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk-hooks | 3 | 3 | 91868 | 8 | 1525 | 10 | 76 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk-hooks-replace | 3 | 3 | 176346 | 18 | 1720 | 12 | 110 | 0.00 |
