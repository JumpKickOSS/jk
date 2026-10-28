# Change tasks — 2026-10-27-grok

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| grok | commons-lang-rename-indexof | jk | 3 | 3 | 426667 | 74400 | 7134 | 15 | 95 | 0.00 |
| grok | commons-lang-rename-indexof | jk-graph | 3 | 3 | 408823 | 69784 | 7657 | 15 | 99 | 0.03 |
| grok | commons-lang-rename-indexof | jk-graphify | 3 | 3 | 498999 | 50716 | 7378 | 15 | 96 | 0.04 |
| grok | commons-lang-rename-indexof | mvn | 3 | 3 | 357625 | 38893 | 5912 | 13 | 255 | 0.00 |
| grok | commons-lang-rename-indexof | mvn-graphify | 3 | 3 | 462186 | 48323 | 7940 | 15 | 107 | 0.04 |
| grok | commons-lang-rename-range-contains | jk | 3 | 3 | 1496619 | 92798 | 11045 | 23 | 146 | 0.00 |
| grok | commons-lang-rename-range-contains | jk-graph | 3 | 3 | 1015027 | 93448 | 10889 | 21 | 139 | 0.06 |
| grok | commons-lang-rename-range-contains | jk-graphify | 3 | 3 | 1232806 | 108449 | 10245 | 23 | 131 | 0.04 |
| grok | commons-lang-rename-range-contains | mvn | 3 | 3 | 1273262 | 91051 | 10083 | 22 | 156 | 0.00 |
| grok | commons-lang-rename-range-contains | mvn-graphify | 3 | 3 | 1000778 | 91680 | 11618 | 22 | 156 | 0.00 |
| grok | jk-rename-codegraph-between | jk | 3 | 3 | 279081 | 24188 | 4781 | 13 | 89 | 0.00 |
| grok | jk-rename-codegraph-between | jk-graph | 3 | 3 | 244262 | 31686 | 3337 | 10 | 73 | 0.00 |
| grok | jk-rename-codegraph-between | jk-graphify | 3 | 3 | 332857 | 63356 | 3569 | 11 | 72 | 0.05 |
