# Change tasks — graph-nav-v2

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk | 5 | 5 | 17736 | 6 | 303 | 3 | 8 | 1.00 | 1.0 |
| claude-code | cl-callers-csu-indexof | jk-nograph | 5 | 5 | 75026 | 14 | 2152 | 9 | 29 | 0.00 | 1.0 |
| claude-code | cl-impact-ispackage | jk | 5 | 5 | 20072 | 6 | 559 | 3 | 8 | 1.00 | 1.0 |
| claude-code | cl-impact-ispackage | jk-nograph | 5 | 5 | 234215 | 24 | 6006 | 19 | 65 | 0.00 | 1.0 |
| claude-code | cl-subtypes-daterule | jk | 5 | 5 | 28280 | 8 | 806 | 5 | 14 | 0.00 | 1.0 |
| claude-code | cl-subtypes-daterule | jk-nograph | 5 | 5 | 26101 | 8 | 762 | 4 | 13 | 0.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk | 5 | 5 | 61377 | 8 | 650 | 4 | 16 | 1.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk-nograph | 5 | 5 | 401277 | 22 | 5770 | 23 | 49 | 0.00 | 0.963 |
| claude-code | jk-callers-sidecar-name | jk | 5 | 5 | 44476 | 6 | 407 | 3 | 12 | 1.00 | 1.0 |
| claude-code | jk-callers-sidecar-name | jk-nograph | 5 | 5 | 143830 | 14 | 2048 | 11 | 33 | 0.00 | 1.0 |
| claude-code | jk-impact-lockstale | jk | 5 | 5 | 46523 | 6 | 524 | 3 | 7 | 1.00 | 1.0 |
| claude-code | jk-impact-lockstale | jk-nograph | 5 | 5 | 311015 | 22 | 4400 | 14 | 51 | 0.00 | 1.0 |
