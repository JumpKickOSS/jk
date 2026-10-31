# Change tasks — hint-cursor-composer

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| cursor | cl-impact-dispatch-appendto | jk | 3 | 0 | 468309 | 31804 | 11302 | 19 | 85 | 0.50 | 0.533 |
| cursor | jk-impact-lockstale | jk | 3 | 3 | 72360 | 14301 | 1328 | 3 | 24 | 1.00 | 1.0 |
