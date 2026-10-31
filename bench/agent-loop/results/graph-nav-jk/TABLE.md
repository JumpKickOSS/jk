# Change tasks — graph-nav-jk

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk | 5 | 5 | 70814 | 12 | 1786 | 8 | 14 | 0.00 | 1.0 |
| claude-code | cl-impact-ispackage | jk | 5 | 5 | 197738 | 22 | 5240 | 16 | 37 | 0.00 | 1.0 |
| claude-code | cl-subtypes-daterule | jk | 5 | 5 | 28629 | 8 | 835 | 4 | 10 | 0.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk | 3 | 3 | 290262 | 18 | 5499 | 23 | 33 | 0.00 | 0.963 |
| claude-code | jk-callers-sidecar-name | jk | 5 | 5 | 177378 | 14 | 2209 | 12 | 18 | 0.00 | 1.0 |
| claude-code | jk-impact-lockstale | jk | 4 | 4 | 308994 | 22 | 4765 | 16 | 32 | 0.00 | 0.958 |
