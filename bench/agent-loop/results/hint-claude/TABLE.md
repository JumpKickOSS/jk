# Change tasks — hint-claude

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk | 3 | 3 | 19465 | 6 | 303 | 3 | 5 | 1.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk | 3 | 3 | 62985 | 8 | 656 | 4 | 9 | 1.00 | 1.0 |
