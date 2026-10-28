# Change tasks — 2026-10-28-lean

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-rename-indexof | jk | 3 | 3 | 42334 | 8 | 594 | 5 | 99 | 0.00 |
| claude-code | commons-lang-rename-indexof | mvn | 3 | 3 | 32847 | 8 | 762 | 5 | 202 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk | 3 | 3 | 79747 | 14 | 1276 | 9 | 27 | 0.00 |
| claude-code | commons-lang-rename-range-contains | mvn | 3 | 3 | 128934 | 18 | 1829 | 13 | 246 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk | 3 | 3 | 118939 | 10 | 1133 | 8 | 36 | 0.00 |
