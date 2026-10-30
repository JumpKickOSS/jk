# Change tasks — agy-agentsline

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | cl-impact-dispatch-appendto | jk-agentsline | 3 | 3 | 647921 | 148325 | 25162 | 23 | 234 | 0.07 | 1.0 |
| agy | jk-callers-projectinfo-name | jk-agentsline | 3 | 3 | 727989 | 151411 | 10872 | 29 | 174 | 0.04 | 1.0 |
| agy | jk-impact-lockstale | jk-agentsline | 3 | 3 | 125545 | 83087 | 7111 | 7 | 64 | 0.20 | 1.0 |
