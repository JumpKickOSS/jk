# Change tasks — 2026-11-04-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | commons-lang-rename-indexof | jk-agyrules | 1 | 1 | 82559 | 79621 | 2938 | 8 | 57 | 0.33 | - |
| agy | commons-lang-rename-range-contains | jk-agyrules | 1 | 1 | 54934 | 52036 | 2898 | 6 | 42 | 1.00 | - |
| agy | jk-rename-codegraph-between | jk-agyrules | 1 | 1 | 80891 | 78820 | 2071 | 6 | 71 | 1.00 | - |
| agy | petclinic-rename-addvisit | jk-agyrules | 1 | 1 | 42775 | 41238 | 1537 | 6 | 35 | 1.00 | - |
| claude-code | jk-rename-codegraph-between | jk | 1 | 1 | 43951 | 6 | 154 | 3 | 21 | 1.00 | - |
| cursor | jk-rename-codegraph-between | jk-cursorrules | 1 | 1 | 240713 | 15244 | 1661 | 12 | 163 | 0.25 | - |
| grok | commons-lang-rename-indexof | jk | 1 | 1 | 160218 | 32018 | 4168 | 11 | 68 | 0.29 | - |
| grok | commons-lang-rename-range-contains | jk | 1 | 1 | 68938 | 12570 | 1584 | 8 | 141 | 1.00 | - |
| grok | jk-rename-codegraph-between | jk | 1 | 1 | 64509 | 23027 | 778 | 5 | 58 | 1.00 | - |
| grok | petclinic-rename-addvisit | jk | 1 | 1 | 35912 | 16695 | 785 | 5 | 19 | 1.00 | - |
| muse | commons-lang-rename-indexof | jk | 1 | 1 | 146150 | 30571 | 4821 | 7 | 109 | 1.00 | - |
| muse | commons-lang-rename-range-contains | jk | 1 | 1 | 147506 | 52052 | 2857 | 7 | 117 | 1.00 | - |
| muse | jk-rename-codegraph-between | jk | 1 | 1 | 126324 | 33126 | 3146 | 5 | 36 | 1.00 | - |
