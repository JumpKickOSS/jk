# Change tasks — 2026-11-04-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | commons-lang-rename-indexof | jk-agyrules | 1 | 1 | 82559 | 79621 | 2938 | 8 | 57 | 0.33 | - |
| agy | commons-lang-rename-range-contains | jk-agyrules | 1 | 1 | 54934 | 52036 | 2898 | 6 | 42 | 1.00 | - |
| agy | jk-rename-codegraph-between | jk-agyrules | 1 | 1 | 80891 | 78820 | 2071 | 6 | 71 | 1.00 | - |
| agy | petclinic-rename-addvisit | jk-agyrules | 1 | 1 | 42775 | 41238 | 1537 | 6 | 35 | 1.00 | - |
| claude-code | commons-lang-rename-indexof | jk | 1 | 1 | 13167 | 4 | 92 | 2 | 12 | 1.00 | - |
| claude-code | commons-lang-rename-range-contains | jk | 1 | 1 | 12529 | 4 | 84 | 2 | 19 | 1.00 | - |
| claude-code | jk-rename-codegraph-between | jk | 2 | 2 | 36533 | 5 | 125 | 2 | 18 | 1.00 | - |
| claude-code | petclinic-rename-addvisit | jk | 1 | 1 | 12066 | 4 | 90 | 2 | 15 | 1.00 | - |
| cursor | commons-lang-rename-indexof | jk-cursorrules | 1 | 1 | 92103 | 9476 | 1123 | 6 | 113 | 1.00 | - |
| cursor | commons-lang-rename-range-contains | jk-cursorrules | 1 | 1 | 35697 | 8130 | 463 | 2 | 20 | 1.00 | - |
| cursor | jk-rename-codegraph-between | jk-cursorrules | 2 | 2 | 146280 | 14230 | 1090 | 7 | 95 | 0.62 | - |
| cursor | petclinic-rename-addvisit | jk-cursorrules | 1 | 1 | 34980 | 8492 | 280 | 2 | 21 | 1.00 | - |
| grok | commons-lang-rename-indexof | jk | 1 | 1 | 160218 | 32018 | 4168 | 11 | 68 | 0.29 | - |
| grok | commons-lang-rename-range-contains | jk | 1 | 1 | 68938 | 12570 | 1584 | 8 | 141 | 1.00 | - |
| grok | jk-rename-codegraph-between | jk | 1 | 1 | 64509 | 23027 | 778 | 5 | 58 | 1.00 | - |
| grok | petclinic-rename-addvisit | jk | 1 | 1 | 35912 | 16695 | 785 | 5 | 19 | 1.00 | - |
| muse | commons-lang-rename-indexof | jk | 1 | 1 | 146150 | 30571 | 4821 | 7 | 109 | 1.00 | - |
| muse | commons-lang-rename-range-contains | jk | 1 | 1 | 147506 | 52052 | 2857 | 7 | 117 | 1.00 | - |
| muse | jk-rename-codegraph-between | jk | 1 | 1 | 126324 | 33126 | 3146 | 5 | 36 | 1.00 | - |
