# Change tasks — hint-cursor-sonnet

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| cursor | cl-callers-csu-indexof | jk | 3 | 3 | 64632 | 10 | 443 | 3 | 16 | 1.00 | 1.0 |
| cursor | jk-callers-projectinfo-name | jk | 3 | 3 | 100008 | 10 | 556 | 3 | 20 | 1.00 | 1.0 |
