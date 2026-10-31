# Change tasks — fix-claude

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk | 3 | 3 | 19491 | 6 | 303 | 3 | 5 | 1.00 | 1.0 |
| claude-code | cl-impact-dispatch-appendto | jk | 3 | 3 | 20387 | 6 | 388 | 3 | 5 | 1.00 | 1.0 |
| claude-code | jk-impact-dispatch-javahome | jk | 3 | 3 | 76150 | 8 | 964 | 4 | 10 | 1.00 | 0.99 |
| claude-code | jk-impact-lockstale | jk | 3 | 3 | 48444 | 6 | 560 | 3 | 7 | 1.00 | 1.0 |
