# Change tasks — 2026-10-26-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-rename-indexof | jk | 1 | 1 | 59464 | 10 | 3764 | 19 | 111 | 0.00 | - |
| claude-code | commons-lang-rename-indexof | jk-nograph | 1 | 1 | 44179 | 8 | 3557 | 18 | 109 | 0.00 | - |
| claude-code | commons-lang-rename-indexof | mvn | 1 | 0 | 43388 | 8 | 3607 | 18 | 218 | 0.00 | - |
| claude-code | commons-lang-rename-indexof | mvn-graphify | 1 | 0 | 77186 | 12 | 4158 | 21 | 229 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | jk | 1 | 1 | 35453 | 8 | 1499 | 6 | 101 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | jk-nograph | 1 | 1 | 43675 | 8 | 2625 | 12 | 112 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | mvn | 1 | 0 | 71258 | 14 | 3815 | 15 | 231 | 0.00 | - |
| claude-code | petclinic-owner-email | jk | 1 | 1 | 371106 | 32 | 9663 | 40 | 86 | 0.00 | - |
| claude-code | petclinic-owner-email | jk-nograph | 1 | 1 | 150580 | 20 | 8277 | 30 | 104 | 0.00 | - |
| claude-code | petclinic-owner-email | mvn | 1 | 0 | 235957 | 26 | 9715 | 52 | 98 | 0.00 | - |
| claude-code | petclinic-owner-email | mvn-graphify | 1 | 0 | 315789 | 30 | 10068 | 39 | 98 | 0.00 | - |
| claude-code | petclinic-rename-addvisit | jk | 1 | 1 | 22050 | 6 | 726 | 6 | 16 | 0.00 | - |
| claude-code | petclinic-rename-addvisit | jk-nograph | 1 | 1 | 26883 | 8 | 725 | 6 | 22 | 0.00 | - |
| claude-code | petclinic-rename-addvisit | mvn | 1 | 0 | 18303 | 6 | 712 | 6 | 25 | 0.00 | - |
| claude-code | petclinic-rename-addvisit | mvn-graphify | 1 | 0 | 26417 | 6 | 726 | 6 | 26 | 0.00 | - |
