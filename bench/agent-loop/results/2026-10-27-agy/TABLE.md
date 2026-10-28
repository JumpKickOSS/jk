# Change tasks — 2026-10-27-agy

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | commons-lang-rename-indexof | jk | 3 | 2 | 3556287 | 240165 | 26349 | 60 | 409 | 0.00 |
| agy | commons-lang-rename-indexof | jk-graph | 3 | 2 | 2890968 | 282879 | 20722 | 60 | 314 | 0.05 |
| agy | commons-lang-rename-indexof | jk-graphify | 3 | 0 | 2838583 | 307244 | 20783 | 60 | 314 | 0.12 |
| agy | commons-lang-rename-indexof | mvn | 3 | 3 | 3299939 | 227785 | 24785 | 60 | 537 | 0.00 |
| agy | commons-lang-rename-indexof | mvn-graphify | 3 | 2 | 2906961 | 237951 | 18645 | 60 | 490 | 0.00 |
| agy | commons-lang-rename-range-contains | jk | 3 | 0 | 3348524 | 254743 | 25597 | 60 | 312 | 0.00 |
| agy | commons-lang-rename-range-contains | jk-graph | 3 | 0 | 3079220 | 291588 | 21092 | 60 | 300 | 0.10 |
| agy | commons-lang-rename-range-contains | jk-graphify | 3 | 0 | 3153383 | 302390 | 13148 | 60 | 296 | 0.07 |
| agy | commons-lang-rename-range-contains | mvn | 3 | 1 | 4113207 | 259143 | 31490 | 60 | 524 | 0.00 |
| agy | commons-lang-rename-range-contains | mvn-graphify | 3 | 0 | 3112214 | 251758 | 15482 | 60 | 452 | 0.07 |
| agy | jk-rename-codegraph-between | jk | 3 | 1 | 3110826 | 271670 | 12677 | 60 | 396 | 0.00 |
| agy | jk-rename-codegraph-between | jk-graph | 3 | 0 | 3298212 | 201393 | 14396 | 60 | 474 | 0.00 |
| agy | jk-rename-codegraph-between | jk-graphify | 3 | 2 | 3700473 | 253424 | 16715 | 60 | 468 | 0.00 |
