# Change tasks — graph-nav-pilot

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk | 1 | 1 | 68085 | 10 | 1531 | 7 | 17 | 0.00 | 1.0 |
| claude-code | cl-callers-csu-indexof | jk-graph | 1 | 1 | 67420 | 14 | 1901 | 8 | 21 | 0.17 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk | 1 | 1 | 287482 | 18 | 4183 | 18 | 46 | 0.00 | 0.923 |
| claude-code | jk-callers-projectinfo-name | jk-graph | 1 | 1 | 92929 | 10 | 1171 | 6 | 10 | 0.25 | 1.0 |
