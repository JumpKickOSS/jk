# Change tasks — grok-questions

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| grok | cl-callers-csu-indexof | jk | 3 | 3 | 32697 | 8089 | 1032 | 5 | 15 | 0.50 | 1.0 |
| grok | cl-callers-csu-indexof | jk-nograph | 3 | 3 | 467238 | 85894 | 10656 | 22 | 149 | 0.07 | 1.0 |
| grok | cl-callers-csu-indexof | jk-noline | 3 | 3 | 31532 | 6536 | 932 | 5 | 13 | 0.33 | 1.0 |
| grok | cl-impact-dispatch-appendto | jk | 3 | 3 | 312197 | 38739 | 10200 | 13 | 125 | 0.14 | 1.0 |
| grok | cl-impact-dispatch-appendto | jk-nograph | 3 | 3 | 1993511 | 150343 | 38848 | 35 | 522 | 0.04 | 0.917 |
| grok | cl-impact-dispatch-appendto | jk-noline | 3 | 3 | 372977 | 48977 | 13269 | 15 | 175 | 0.23 | 1.0 |
| grok | jk-callers-projectinfo-name | jk | 3 | 3 | 77538 | 23806 | 2404 | 7 | 34 | 0.17 | 1.0 |
| grok | jk-callers-projectinfo-name | jk-nograph | 3 | 3 | 3423517 | 171763 | 24179 | 48 | 308 | 0.03 | 1.0 |
| grok | jk-callers-projectinfo-name | jk-noline | 3 | 3 | 38274 | 13553 | 1590 | 6 | 25 | 0.50 | 1.0 |
| grok | jk-impact-lockstale | jk | 3 | 3 | 40180 | 14376 | 3163 | 5 | 45 | 1.00 | 1.0 |
| grok | jk-impact-lockstale | jk-nograph | 3 | 3 | 4333292 | 214537 | 37397 | 50 | 528 | 0.01 | 0.968 |
| grok | jk-impact-lockstale | jk-noline | 3 | 3 | 77723 | 39693 | 4366 | 6 | 60 | 0.27 | 1.0 |
