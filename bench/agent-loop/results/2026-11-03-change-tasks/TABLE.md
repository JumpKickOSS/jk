# Change tasks — 2026-11-03-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | gs-uploading-rename-loadasresource | jk+shipped | 3 | 3 | 18688 | 6 | 122 | 3 | 6 | 1.00 | - |
| claude-code | petclinic-callers-addpet | jk+shipped | 3 | 3 | 19549 | 6 | 259 | 3 | 14 | 1.00 | 1.0 |
| claude-code | petclinic-rename-addvisit | jk+shipped | 3 | 3 | 18477 | 6 | 121 | 3 | 58 | 1.00 | - |
