# Change tasks — claude-line-ab

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | commons-lang-ignorecase-symptom | jk | 4 | 4 | 41731 | 10 | 1170 | 8 | 14 | 0.00 | - |
| claude-code | commons-lang-ignorecase-symptom | jk-noline | 4 | 4 | 42123 | 11 | 1318 | 8 | 16 | 0.00 | - |
| claude-code | commons-lang-rename-indexof | jk | 4 | 4 | 43648 | 8 | 3606 | 18 | 24 | 0.50 | - |
| claude-code | commons-lang-rename-indexof | jk-noline | 4 | 4 | 39142 | 7 | 3632 | 18 | 24 | 0.42 | - |
| claude-code | jk-callers-projectinfo-name | jk | 4 | 4 | 54535 | 7 | 533 | 4 | 10 | 1.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk-noline | 4 | 4 | 46070 | 6 | 419 | 3 | 9 | 1.00 | 1.0 |
