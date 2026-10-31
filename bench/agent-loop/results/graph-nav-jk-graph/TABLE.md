# Change tasks — graph-nav-jk-graph

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk-graph | 5 | 5 | 77451 | 10 | 1675 | 7 | 15 | 0.00 | 1.0 |
| claude-code | cl-impact-ispackage | jk-graph | 5 | 5 | 194704 | 22 | 5514 | 16 | 44 | 0.00 | 0.976 |
| claude-code | cl-subtypes-daterule | jk-graph | 5 | 5 | 22770 | 6 | 570 | 4 | 8 | 0.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk-graph | 3 | 3 | 166080 | 14 | 2135 | 10 | 20 | 0.20 | 0.929 |
| claude-code | jk-callers-sidecar-name | jk-graph | 5 | 5 | 122518 | 10 | 1768 | 9 | 16 | 0.14 | 1.0 |
| claude-code | jk-impact-lockstale | jk-graph | 3 | 3 | 365326 | 24 | 4918 | 21 | 34 | 0.18 | 0.958 |
