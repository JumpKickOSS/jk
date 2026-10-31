# Change tasks — muse-questions

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| muse | cl-callers-csu-indexof | jk | 3 | 3 | 64557 | 42473 | 3537 | 3 | 21 | 1.00 | 1.0 |
| muse | cl-callers-csu-indexof | jk-nograph | 3 | 3 | 864280 | 98773 | 10532 | 17 | 85 | 0.00 | 1.0 |
| muse | cl-impact-dispatch-appendto | jk | 3 | 3 | 2050257 | 130620 | 35842 | 44 | 198 | 0.74 | 1.0 |
| muse | cl-impact-dispatch-appendto | jk-nograph | 3 | 3 | 2629227 | 164323 | 34214 | 37 | 252 | 0.00 | 0.909 |
| muse | jk-callers-projectinfo-name | jk | 3 | 3 | 106708 | 47157 | 4844 | 5 | 28 | 0.33 | 1.0 |
| muse | jk-callers-projectinfo-name | jk-nograph | 3 | 3 | 2534843 | 176789 | 25565 | 27 | 87 | 0.00 | 1.0 |
| muse | jk-impact-lockstale | jk | 3 | 3 | 203007 | 52810 | 10106 | 8 | 49 | 0.33 | 1.0 |
| muse | jk-impact-lockstale | jk-nograph | 3 | 3 | 5147567 | 229961 | 46223 | 52 | 307 | 0.00 | 1.0 |
