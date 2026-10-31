# Change tasks — fix-muse

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| muse | cl-impact-dispatch-appendto | jk | 3 | 3 | 475518 | 85336 | 14174 | 15 | 83 | 0.33 | 1.0 |
