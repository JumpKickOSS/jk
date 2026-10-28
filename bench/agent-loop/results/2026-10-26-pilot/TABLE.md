# Change tasks — 2026-10-26-pilot

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-ignorecase-symptom | jk | 1 | 1 | 35003 | 8 | 860 | 6 | 16 | 0.00 |
| claude-code | commons-lang-ignorecase-symptom | jk-graph-first | 1 | 1 | 49518 | 10 | 691 | 9 | 13 | 1.00 |
| claude-code | commons-lang-ignorecase-symptom | jk-hooks | 1 | 1 | 35491 | 8 | 684 | 5 | 9 | 0.00 |
| claude-code | commons-lang-ignorecase-symptom | jk-hooks-lean | 1 | 1 | 32294 | 8 | 709 | 5 | 15 | 0.00 |
| claude-code | commons-lang-ignorecase-symptom | jk-hooks-replace | 1 | 1 | 35287 | 8 | 706 | 5 | 10 | 0.00 |
| claude-code | commons-lang-ignorecase-symptom | jk-nograph | 1 | 1 | 35270 | 8 | 1087 | 7 | 12 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk | 1 | 1 | 94287 | 10 | 4380 | 22 | 42 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk-graph-first | 1 | 1 | 46463 | 8 | 3580 | 19 | 22 | 0.50 |
| claude-code | commons-lang-rename-indexof | jk-hooks | 1 | 1 | 74619 | 10 | 3965 | 19 | 27 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk-hooks-lean | 1 | 1 | 81038 | 12 | 4439 | 22 | 36 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk-hooks-replace | 1 | 1 | 144874 | 16 | 5285 | 31 | 122 | 0.67 |
| claude-code | commons-lang-rename-indexof | jk-nograph | 1 | 1 | 32827 | 6 | 3781 | 18 | 118 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk | 1 | 1 | 321834 | 26 | 13806 | 42 | 82 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk-graph-first | 1 | 1 | 316154 | 22 | 13747 | 35 | 89 | 0.18 |
| claude-code | commons-lang-rename-range-contains | jk-hooks | 1 | 1 | 200463 | 18 | 10582 | 49 | 65 | 0.12 |
| claude-code | commons-lang-rename-range-contains | jk-hooks-lean | 1 | 1 | 314502 | 24 | 14258 | 37 | 85 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk-hooks-replace | 1 | 1 | 395209 | 32 | 13886 | 42 | 86 | 0.08 |
| claude-code | commons-lang-rename-range-contains | jk-nograph | 1 | 1 | 143026 | 20 | 8612 | 41 | 143 | 0.00 |
| claude-code | commons-lang-tostringstyle-name | jk | 1 | 1 | 62573 | 12 | 3013 | 13 | 111 | 0.00 |
| claude-code | commons-lang-tostringstyle-name | jk-graph-first | 1 | 1 | 54594 | 10 | 1458 | 7 | 111 | 0.50 |
| claude-code | commons-lang-tostringstyle-name | jk-hooks | 1 | 1 | 64330 | 10 | 3075 | 13 | 110 | 0.00 |
| claude-code | commons-lang-tostringstyle-name | jk-hooks-lean | 1 | 1 | 41834 | 8 | 2633 | 12 | 114 | 0.00 |
| claude-code | commons-lang-tostringstyle-name | jk-hooks-replace | 1 | 1 | 65459 | 10 | 3097 | 13 | 112 | 0.00 |
| claude-code | commons-lang-tostringstyle-name | jk-nograph | 1 | 1 | 96754 | 14 | 3049 | 14 | 134 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk | 1 | 1 | 118287 | 10 | 1047 | 8 | 95 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk-graph-first | 1 | 1 | 94641 | 10 | 1174 | 8 | 15 | 0.50 |
| claude-code | jk-rename-codegraph-between | jk-hooks | 1 | 1 | 67753 | 6 | 1155 | 6 | 85 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk-hooks-lean | 1 | 1 | 90715 | 8 | 1029 | 7 | 38 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk-hooks-replace | 1 | 1 | 104434 | 12 | 1649 | 11 | 97 | 0.17 |
| claude-code | jk-rename-codegraph-between | jk-nograph | 1 | 1 | 90122 | 8 | 1039 | 7 | 69 | 0.00 |
