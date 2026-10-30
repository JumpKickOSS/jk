# Change tasks — claude-overloads

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk | 3 | 3 | 19377 | 6 | 303 | 3 | 13 | 1.00 | 1.0 |
| claude-code | jk-impact-dispatch-javahome | jk | 3 | 3 | 94601 | 10 | 1042 | 5 | 16 | 1.00 | 0.99 |
| claude-code | jk-impact-lockstale | jk | 3 | 3 | 48418 | 6 | 560 | 3 | 14 | 1.00 | 1.0 |
