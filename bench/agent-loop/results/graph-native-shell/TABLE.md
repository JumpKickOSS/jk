# Change tasks — graph-native-shell

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk-nograph | 3 | 3 | 70050 | 10 | 985 | 5 | 12 | 0.00 | 1.0 |
| claude-code | cl-callers-csu-indexof | jk-nograph-noline | 3 | 3 | 54459 | 8 | 824 | 4 | 11 | 0.00 | 1.0 |
| claude-code | cl-impact-dispatch-appendto | jk-nograph | 3 | 2 | 136900 | 12 | 3111 | 8 | 26 | 0.00 | 0.957 |
| claude-code | cl-impact-dispatch-appendto | jk-nograph-noline | 3 | 3 | 192364 | 16 | 3901 | 11 | 31 | 0.00 | 0.957 |
| claude-code | jk-callers-projectinfo-name | jk-nograph | 3 | 3 | 191988 | 14 | 2919 | 7 | 25 | 0.00 | 0.963 |
| claude-code | jk-callers-projectinfo-name | jk-nograph-noline | 3 | 3 | 249620 | 18 | 3247 | 11 | 28 | 0.00 | 0.963 |
| claude-code | jk-impact-lockstale | jk-nograph | 3 | 3 | 569112 | 40 | 6992 | 21 | 56 | 0.00 | 1.0 |
| claude-code | jk-impact-lockstale | jk-nograph-noline | 3 | 3 | 703128 | 38 | 8189 | 22 | 71 | 0.00 | 0.968 |
