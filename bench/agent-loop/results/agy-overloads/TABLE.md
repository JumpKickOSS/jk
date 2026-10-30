# Change tasks — agy-overloads

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | jk-impact-dispatch-javahome | jk | 3 | 3 | 849271 | 161028 | 14090 | 28 | 172 | 0.08 | 0.99 |
| agy | jk-impact-lockstale | jk | 3 | 3 | 401380 | 104883 | 9417 | 18 | 108 | 0.12 | 1.0 |
