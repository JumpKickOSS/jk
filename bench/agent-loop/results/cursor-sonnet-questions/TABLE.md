# Change tasks — cursor-sonnet-questions

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| cursor | cl-callers-csu-indexof | jk | 3 | 3 | 64560 | 10 | 443 | 3 | 20 | 1.00 | 1.0 |
| cursor | cl-callers-csu-indexof | jk-nograph | 3 | 3 | 137806 | 16 | 1485 | 6 | 25 | 0.25 | 1.0 |
| cursor | cl-callers-csu-indexof | jk-noline | 3 | 3 | 64393 | 10 | 446 | 3 | 18 | 1.00 | 1.0 |
| cursor | cl-impact-dispatch-appendto | jk | 3 | 3 | 84934 | 12 | 1150 | 4 | 22 | 1.00 | 1.0 |
| cursor | cl-impact-dispatch-appendto | jk-nograph | 3 | 2 | 251222 | 22 | 4622 | 9 | 48 | 0.10 | 0.917 |
| cursor | cl-impact-dispatch-appendto | jk-noline | 3 | 3 | 125356 | 16 | 2089 | 6 | 31 | 0.80 | 1.0 |
| cursor | jk-callers-projectinfo-name | jk | 3 | 3 | 99920 | 10 | 556 | 3 | 18 | 1.00 | 1.0 |
| cursor | jk-callers-projectinfo-name | jk-nograph | 3 | 3 | 570000 | 28 | 6362 | 12 | 54 | 0.04 | 0.963 |
| cursor | jk-callers-projectinfo-name | jk-noline | 3 | 3 | 99753 | 10 | 559 | 3 | 19 | 1.00 | 1.0 |
| cursor | jk-impact-lockstale | jk | 3 | 3 | 102093 | 10 | 700 | 3 | 19 | 1.00 | 1.0 |
| cursor | jk-impact-lockstale | jk-nograph | 3 | 3 | 857794 | 38 | 9111 | 17 | 84 | 0.04 | 0.984 |
| cursor | jk-impact-lockstale | jk-noline | 3 | 3 | 102242 | 10 | 861 | 3 | 20 | 1.00 | 1.0 |
