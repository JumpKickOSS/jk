# Change tasks — graph-native-edits

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-ignorecase-symptom | jk | 3 | 3 | 27821 | 8 | 702 | 6 | 10 | 0.00 | - |
| claude-code | commons-lang-ignorecase-symptom | jk-nograph | 3 | 3 | 44292 | 12 | 1266 | 8 | 14 | 0.00 | - |
| claude-code | commons-lang-rename-indexof | jk | 3 | 3 | 39624 | 8 | 3565 | 19 | 23 | 0.33 | - |
| claude-code | commons-lang-rename-indexof | jk-nograph | 3 | 3 | 29783 | 6 | 3773 | 18 | 22 | 0.00 | - |
| claude-code | commons-lang-rename-range-contains | jk | 3 | 3 | 208945 | 22 | 13375 | 40 | 76 | 0.07 | - |
| claude-code | commons-lang-rename-range-contains | jk-nograph | 3 | 3 | 277260 | 24 | 13922 | 40 | 81 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | jk | 3 | 3 | 38582 | 8 | 2724 | 12 | 106 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | jk-nograph | 3 | 3 | 38324 | 8 | 2875 | 12 | 104 | 0.00 | - |
| claude-code | jk-rename-codegraph-between | jk | 3 | 3 | 86903 | 8 | 1105 | 8 | 54 | 0.50 | - |
| claude-code | jk-rename-codegraph-between | jk-nograph | 3 | 3 | 63247 | 6 | 861 | 6 | 55 | 0.00 | - |
| claude-code | petclinic-owner-email | jk | 3 | 3 | 200255 | 22 | 9068 | 38 | 72 | 0.00 | - |
| claude-code | petclinic-owner-email | jk-nograph | 3 | 3 | 275123 | 24 | 9866 | 38 | 79 | 0.00 | - |
| claude-code | petclinic-rename-addvisit | jk | 3 | 3 | 28929 | 8 | 772 | 7 | 17 | 0.50 | - |
| claude-code | petclinic-rename-addvisit | jk-nograph | 3 | 3 | 27039 | 8 | 725 | 6 | 17 | 0.00 | - |
