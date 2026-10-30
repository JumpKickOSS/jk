# Change tasks — agy-closure

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | cl-impact-dispatch-appendto | jk | 3 | 3 | 1037396 | 240121 | 19088 | 40 | 208 | 0.28 | 1.0 |
| agy | jk-impact-lockstale | jk | 3 | 1 | 2841901 | 222548 | 18593 | 60 | 297 | 0.37 | 0.0 |
