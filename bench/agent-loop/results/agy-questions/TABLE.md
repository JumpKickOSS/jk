# Change tasks — agy-questions

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | cl-callers-csu-indexof | jk | 3 | 3 | 455475 | 114926 | 7884 | 23 | 112 | 0.06 | 1.0 |
| agy | cl-callers-csu-indexof | jk-nograph | 3 | 3 | 1336479 | 137892 | 17584 | 35 | 184 | 0.00 | 1.0 |
| agy | cl-impact-dispatch-appendto | jk | 3 | 3 | 849856 | 186653 | 17784 | 29 | 182 | 0.21 | 1.0 |
| agy | cl-impact-dispatch-appendto | jk-nograph | 3 | 0 | 3685553 | 264383 | 29719 | 60 | 338 | 0.00 | 0.0 |
| agy | jk-callers-projectinfo-name | jk | 3 | 3 | 1520797 | 186020 | 13101 | 39 | 218 | 0.03 | 1.0 |
| agy | jk-callers-projectinfo-name | jk-nograph | 3 | 0 | 3793690 | 221519 | 15765 | 60 | 249 | 0.00 | 0.0 |
| agy | jk-impact-lockstale | jk | 3 | 0 | 2960409 | 233376 | 15168 | 60 | 284 | 0.53 | 0.0 |
| agy | jk-impact-lockstale | jk-nograph | 3 | 0 | 3604566 | 218016 | 17286 | 60 | 280 | 0.00 | 0.0 |
