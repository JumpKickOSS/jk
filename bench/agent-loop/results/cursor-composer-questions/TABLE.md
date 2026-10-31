# Change tasks — cursor-composer-questions

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| cursor | cl-callers-csu-indexof | jk | 3 | 3 | 47289 | 8023 | 514 | 3 | 15 | 1.00 | 1.0 |
| cursor | cl-callers-csu-indexof | jk-nograph | 3 | 3 | 147172 | 15574 | 2318 | 8 | 23 | 0.00 | 1.0 |
| cursor | cl-callers-csu-indexof | jk-noline | 3 | 3 | 47658 | 8034 | 720 | 3 | 17 | 1.00 | 1.0 |
| cursor | cl-impact-dispatch-appendto | jk | 3 | 1 | 344661 | 15552 | 9333 | 17 | 59 | 0.59 | 0.533 |
| cursor | cl-impact-dispatch-appendto | jk-nograph | 3 | 1 | 1130913 | 58512 | 9401 | 26 | 119 | 0.00 | 0.842 |
| cursor | cl-impact-dispatch-appendto | jk-noline | 3 | 1 | 1029978 | 43846 | 14610 | 26 | 86 | 0.38 | 0.533 |
| cursor | jk-callers-projectinfo-name | jk | 3 | 3 | 92569 | 15160 | 1313 | 4 | 31 | 0.20 | 1.0 |
| cursor | jk-callers-projectinfo-name | jk-nograph | 3 | 3 | 1541843 | 68158 | 9076 | 28 | 69 | 0.00 | 1.0 |
| cursor | jk-callers-projectinfo-name | jk-noline | 3 | 3 | 70513 | 15024 | 981 | 3 | 20 | 1.00 | 1.0 |
| cursor | jk-impact-lockstale | jk | 3 | 3 | 72815 | 14283 | 1504 | 3 | 21 | 1.00 | 1.0 |
| cursor | jk-impact-lockstale | jk-nograph | 3 | 3 | 1027882 | 49689 | 8470 | 22 | 59 | 0.00 | 0.984 |
| cursor | jk-impact-lockstale | jk-noline | 3 | 3 | 177067 | 20848 | 3139 | 7 | 29 | 0.21 | 1.0 |
