# Change tasks — 2026-11-04-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | commons-lang-rename-indexof | jk-agyrules | 2 | 2 | 56014 | 53373 | 2640 | 6 | 42 | 0.67 | - |
| agy | commons-lang-rename-range-contains | jk-agyrules | 2 | 2 | 38328 | 35968 | 2360 | 4 | 33 | 1.00 | - |
| agy | jk-rename-codegraph-between | jk-agyrules | 2 | 2 | 59590 | 57806 | 1784 | 4 | 47 | 1.00 | - |
| agy | petclinic-rename-addvisit | jk-agyrules | 2 | 2 | 31624 | 30160 | 1464 | 4 | 29 | 1.00 | - |
| claude-code | commons-lang-rename-indexof | jk | 1 | 1 | 13167 | 4 | 92 | 2 | 12 | 1.00 | - |
| claude-code | commons-lang-rename-range-contains | jk | 1 | 1 | 12529 | 4 | 84 | 2 | 19 | 1.00 | - |
| claude-code | jk-rename-codegraph-between | jk | 2 | 2 | 36533 | 5 | 125 | 2 | 18 | 1.00 | - |
| claude-code | petclinic-rename-addvisit | jk | 1 | 1 | 12066 | 4 | 90 | 2 | 15 | 1.00 | - |
| cursor | commons-lang-rename-indexof | jk-cursorrules | 1 | 1 | 92103 | 9476 | 1123 | 6 | 113 | 1.00 | - |
| cursor | commons-lang-rename-range-contains | jk-cursorrules | 1 | 1 | 35697 | 8130 | 463 | 2 | 20 | 1.00 | - |
| cursor | jk-rename-codegraph-between | jk-cursorrules | 2 | 2 | 146280 | 14230 | 1090 | 7 | 95 | 0.62 | - |
| cursor | petclinic-rename-addvisit | jk-cursorrules | 1 | 1 | 34980 | 8492 | 280 | 2 | 21 | 1.00 | - |
| grok | commons-lang-rename-indexof | jk | 2 | 2 | 228393 | 44790 | 7667 | 12 | 116 | 0.23 | - |
| grok | commons-lang-rename-range-contains | jk | 2 | 2 | 49384 | 11917 | 1500 | 6 | 84 | 1.00 | - |
| grok | jk-rename-codegraph-between | jk | 2 | 2 | 51480 | 22590 | 858 | 4 | 41 | 1.00 | - |
| grok | petclinic-rename-addvisit | jk | 2 | 2 | 28278 | 11100 | 730 | 4 | 21 | 1.00 | - |
| muse | commons-lang-rename-indexof | jk | 2 | 2 | 152570 | 41435 | 6074 | 7 | 118 | 1.00 | - |
| muse | commons-lang-rename-range-contains | jk | 2 | 2 | 176152 | 46270 | 5124 | 8 | 125 | 1.00 | - |
| muse | jk-rename-codegraph-between | jk | 2 | 2 | 92007 | 42910 | 4071 | 4 | 42 | 1.00 | - |
| muse | petclinic-rename-addvisit | jk | 1 | 1 | 45689 | 29066 | 3966 | 2 | 40 | 1.00 | - |
