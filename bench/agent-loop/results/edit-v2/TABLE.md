# Change tasks — edit-v2

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-ignorecase-symptom | jk | 3 | 3 | 43850 | 12 | 1390 | 9 | 14 | 0.00 | - |
| claude-code | commons-lang-ignorecase-symptom | jk-nograph | 3 | 3 | 37370 | 10 | 957 | 7 | 11 | 0.00 | - |
| claude-code | commons-lang-rename-indexof | jk | 3 | 3 | 35889 | 8 | 3454 | 19 | 28 | 0.33 | - |
| claude-code | commons-lang-rename-indexof | jk-nograph | 3 | 3 | 75334 | 10 | 3683 | 19 | 22 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | jk | 3 | 3 | 47580 | 10 | 2955 | 14 | 106 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | jk-nograph | 3 | 3 | 59210 | 12 | 3572 | 15 | 110 | 0.00 | - |
| claude-code | petclinic-owner-email | jk | 3 | 3 | 159186 | 20 | 9027 | 38 | 69 | 0.00 | - |
| claude-code | petclinic-owner-email | jk-nograph | 3 | 3 | 162002 | 22 | 8194 | 28 | 69 | 0.00 | - |
| claude-code | petclinic-rename-addvisit | jk | 3 | 3 | 26879 | 8 | 772 | 7 | 18 | 0.50 | - |
| claude-code | petclinic-rename-addvisit | jk-nograph | 3 | 3 | 18146 | 6 | 724 | 6 | 16 | 0.00 | - |
