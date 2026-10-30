# Change tasks — graph-native-questions

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| claude-code | cl-callers-csu-indexof | jk | 5 | 5 | 19164 | 6 | 303 | 3 | 6 | 1.00 | 1.0 |
| claude-code | cl-callers-csu-indexof | jk-nograph | 5 | 5 | 68350 | 10 | 1554 | 6 | 15 | 0.00 | 1.0 |
| claude-code | cl-impact-dispatch-appendto | jk | 5 | 5 | 20134 | 6 | 388 | 3 | 5 | 1.00 | 1.0 |
| claude-code | cl-impact-dispatch-appendto | jk-nograph | 5 | 5 | 167532 | 18 | 4330 | 13 | 34 | 0.00 | 0.957 |
| claude-code | cl-impact-ispackage | jk | 5 | 5 | 21542 | 6 | 575 | 3 | 6 | 1.00 | 1.0 |
| claude-code | cl-impact-ispackage | jk-nograph | 5 | 5 | 205113 | 24 | 5970 | 19 | 46 | 0.00 | 1.0 |
| claude-code | cl-subtypes-daterule | jk | 5 | 5 | 28718 | 8 | 746 | 4 | 8 | 0.00 | 1.0 |
| claude-code | cl-subtypes-daterule | jk-nograph | 5 | 5 | 27844 | 8 | 789 | 4 | 8 | 0.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk | 5 | 5 | 46016 | 6 | 416 | 3 | 10 | 1.00 | 1.0 |
| claude-code | jk-callers-projectinfo-name | jk-nograph | 5 | 5 | 372788 | 24 | 6235 | 25 | 45 | 0.00 | 0.963 |
| claude-code | jk-callers-sidecar-name | jk | 5 | 5 | 45328 | 6 | 389 | 3 | 7 | 1.00 | 1.0 |
| claude-code | jk-callers-sidecar-name | jk-nograph | 5 | 5 | 144826 | 14 | 1967 | 9 | 19 | 0.00 | 1.0 |
| claude-code | jk-impact-dispatch-javahome | jk | 5 | 5 | 76885 | 8 | 1060 | 4 | 12 | 1.00 | 0.923 |
| claude-code | jk-impact-dispatch-javahome | jk-nograph | 5 | 2 | 704546 | 34 | 12161 | 31 | 86 | 0.00 | 0.872 |
| claude-code | jk-impact-lockstale | jk | 5 | 5 | 48089 | 6 | 560 | 3 | 8 | 1.00 | 1.0 |
| claude-code | jk-impact-lockstale | jk-nograph | 5 | 2 | 385038 | 24 | 5661 | 21 | 37 | 0.00 | 0.868 |
