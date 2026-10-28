# Change tasks — 2026-10-28-lean2

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-rename-indexof | jk | 3 | 3 | 34839 | 8 | 805 | 5 | 104 | 0.00 |
| claude-code | commons-lang-rename-indexof | mvn | 3 | 3 | 32801 | 8 | 783 | 5 | 203 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk | 3 | 2 | 68822 | 12 | 1147 | 7 | 26 | 0.00 |
| claude-code | commons-lang-rename-range-contains | mvn | 3 | 3 | 48840 | 10 | 1346 | 7 | 209 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk | 3 | 3 | 110330 | 10 | 880 | 7 | 80 | 0.00 |
