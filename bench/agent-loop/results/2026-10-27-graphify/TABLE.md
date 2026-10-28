# Change tasks — 2026-10-27-graphify

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-rename-indexof | jk | 3 | 3 | 43907 | 8 | 579 | 5 | 20 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk-graphify | 3 | 3 | 73570 | 10 | 813 | 6 | 16 | 0.00 |
| claude-code | commons-lang-rename-indexof | jk-hooks | 3 | 3 | 38012 | 6 | 520 | 4 | 10 | 0.00 |
| claude-code | commons-lang-rename-indexof | mvn | 3 | 3 | 32697 | 8 | 788 | 5 | 206 | 0.00 |
| claude-code | commons-lang-rename-indexof | mvn-graphify | 3 | 3 | 49857 | 8 | 739 | 5 | 206 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk | 3 | 3 | 111316 | 18 | 1823 | 12 | 35 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk-graphify | 3 | 3 | 145064 | 18 | 2067 | 11 | 38 | 0.00 |
| claude-code | commons-lang-rename-range-contains | jk-hooks | 3 | 3 | 100532 | 16 | 1370 | 11 | 22 | 0.00 |
| claude-code | commons-lang-rename-range-contains | mvn | 3 | 3 | 67354 | 12 | 1334 | 8 | 228 | 0.00 |
| claude-code | commons-lang-rename-range-contains | mvn-graphify | 3 | 3 | 84869 | 12 | 1563 | 7 | 216 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk | 3 | 3 | 93199 | 10 | 710 | 6 | 41 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk-graphify | 3 | 3 | 105650 | 8 | 752 | 5 | 40 | 0.00 |
| claude-code | jk-rename-codegraph-between | jk-hooks | 3 | 3 | 116639 | 10 | 669 | 6 | 65 | 0.00 |
