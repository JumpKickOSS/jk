# Change tasks — graph-nav-rerun

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | jk-callers-projectinfo-name | jk | 2 | 1 | 323697 | 18 | 4634 | 19 | 33 | 0.00 | 0.9045 |
| claude-code | jk-callers-projectinfo-name | jk-graph | 2 | 2 | 193011 | 14 | 3408 | 13 | 23 | 0.28 | 0.9645 |
| claude-code | jk-impact-lockstale | jk | 2 | 2 | 362050 | 24 | 4410 | 16 | 46 | 0.00 | 0.979 |
| claude-code | jk-impact-lockstale | jk-graph | 2 | 2 | 383606 | 26 | 5923 | 24 | 41 | 0.05 | 0.9445 |
