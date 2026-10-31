# Change tasks — hint-muse

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| muse | cl-impact-dispatch-appendto | jk | 3 | 3 | 1682948 | 123371 | 31385 | 36 | 258 | 0.77 | 1.0 |
| muse | jk-impact-lockstale | jk | 3 | 3 | 160811 | 62050 | 6779 | 7 | 43 | 0.50 | 1.0 |
