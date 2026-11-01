# Change tasks — 2026-10-31-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | cl-callers-csu-indexof | jk | 5 | 5 | 203531 | 129035 | 7443 | 14 | 85 | 0.10 | 1.0 |
| agy | cl-impact-dispatch-appendto | jk | 3 | 3 | 371019 | 151089 | 11961 | 22 | 111 | 0.06 | 1.0 |
| agy | cl-impact-ispackage | jk | 3 | 3 | 249578 | 133368 | 7136 | 15 | 69 | 0.11 | 1.0 |
| agy | cl-subtypes-daterule | jk | 3 | 3 | 1328166 | 150153 | 19034 | 32 | 207 | 0.00 | 1.0 |
| agy | jk-callers-projectinfo-name | jk | 3 | 3 | 759521 | 153780 | 9368 | 29 | 117 | 0.04 | 1.0 |
| agy | jk-callers-sidecar-name | jk | 3 | 3 | 1986893 | 190730 | 17005 | 47 | 195 | 0.04 | 1.0 |
| agy | jk-impact-dispatch-javahome | jk | 3 | 3 | 1497098 | 155008 | 21476 | 39 | 220 | 0.14 | 1.0 |
| agy | jk-impact-lockstale | jk | 3 | 3 | 659697 | 110546 | 12846 | 22 | 121 | 0.11 | 1.0 |
| claude-code | cl-callers-csu-indexof | jk | 9 | 9 | 19959 | 6 | 291 | 3 | 5 | 1.00 | 1.0 |
| claude-code | cl-impact-dispatch-appendto | jk | 9 | 9 | 20891 | 6 | 388 | 3 | 6 | 1.00 | 1.0 |
| claude-code | cl-impact-ispackage | jk | 9 | 9 | 22265 | 6 | 559 | 3 | 6 | 1.00 | 1.0 |
| claude-code | cl-subtypes-daterule | jk | 9 | 9 | 30246 | 8 | 706 | 4 | 8 | 0.00 | 1.0 |
| claude-code | commons-lang-ignorecase-symptom | jk | 9 | 9 | 37403 | 10 | 855 | 7 | 15 | 0.00 | - |
| claude-code | commons-lang-rename-indexof | jk | 9 | 9 | 18179 | 6 | 123 | 3 | 8 | 1.00 | - |
| claude-code | commons-lang-rename-indexof | jk-nograph | 6 | 6 | 44642 | 8 | 3787 | 18 | 23 | 0.00 | - |
| claude-code | commons-lang-rename-range-contains | jk | 9 | 9 | 18602 | 6 | 115 | 3 | 16 | 1.00 | - |
| claude-code | commons-lang-rename-range-contains | jk-nograph | 6 | 6 | 284367 | 24 | 12218 | 40 | 78 | 0.00 | - |
| claude-code | commons-lang-tostringstyle-name | jk | 10 | 10 | 49104 | 11 | 2982 | 13 | 114 | 0.00 | - |
| claude-code | jk-callers-projectinfo-name | jk | 9 | 9 | 46847 | 6 | 416 | 3 | 8 | 1.00 | 1.0 |
| claude-code | jk-callers-sidecar-name | jk | 9 | 9 | 46069 | 6 | 344 | 3 | 7 | 1.00 | 1.0 |
| claude-code | jk-impact-dispatch-javahome | jk | 9 | 9 | 77499 | 8 | 982 | 4 | 12 | 1.00 | 1.0 |
| claude-code | jk-impact-lockstale | jk | 9 | 9 | 48948 | 6 | 560 | 3 | 9 | 1.00 | 1.0 |
| claude-code | jk-rename-codegraph-between | jk | 9 | 9 | 29289 | 4 | 119 | 2 | 5 | 1.00 | - |
| claude-code | jk-rename-codegraph-between | jk-nograph | 6 | 6 | 104115 | 10 | 1450 | 8 | 81 | 0.00 | - |
| claude-code | petclinic-owner-email | jk | 12 | 12 | 218717 | 24 | 8776 | 34 | 73 | 0.00 | - |
| claude-code | petclinic-rename-addvisit | jk | 10 | 10 | 17925 | 6 | 121 | 3 | 16 | 1.00 | - |
| claude-code | petclinic-rename-addvisit | jk-nograph | 6 | 6 | 27940 | 8 | 800 | 7 | 21 | 0.00 | - |
| cursor | cl-callers-csu-indexof | jk | 13 | 13 | 48060 | 8064 | 516 | 3 | 18 | 1.00 | 1.0 |
| cursor | cl-impact-dispatch-appendto | jk | 6 | 6 | 57910 | 4122 | 870 | 3 | 19 | 1.00 | 1.0 |
| cursor | cl-impact-ispackage | jk | 13 | 13 | 49735 | 8502 | 819 | 3 | 19 | 1.00 | 1.0 |
| cursor | cl-subtypes-daterule | jk | 6 | 6 | 74462 | 5540 | 1083 | 4 | 20 | 0.00 | 1.0 |
| cursor | commons-lang-rename-indexof | jk | 3 | 3 | 65393 | 9644 | 837 | 4 | 19 | 0.40 | - |
| cursor | commons-lang-rename-indexof | jk-nograph | 3 | 3 | 1479260 | 43731 | 8457 | 40 | 368 | 0.00 | - |
| cursor | commons-lang-rename-range-contains | jk | 3 | 3 | 132870 | 12749 | 924 | 7 | 23 | 0.29 | - |
| cursor | commons-lang-rename-range-contains | jk-nograph | 3 | 3 | 1042831 | 62272 | 5117 | 24 | 45 | 0.00 | - |
| cursor | jk-callers-projectinfo-name | jk | 6 | 6 | 86896 | 6954 | 604 | 3 | 23 | 1.00 | 1.0 |
| cursor | jk-callers-sidecar-name | jk | 6 | 6 | 97306 | 7399 | 824 | 4 | 24 | 0.67 | 1.0 |
| cursor | jk-impact-dispatch-javahome | jk | 6 | 6 | 138380 | 8506 | 1460 | 4 | 30 | 1.00 | 1.0 |
| cursor | jk-impact-lockstale | jk | 6 | 6 | 89054 | 7522 | 854 | 3 | 25 | 1.00 | 1.0 |
| cursor | jk-rename-codegraph-between | jk | 3 | 3 | 529428 | 33389 | 3079 | 16 | 292 | 0.15 | - |
| cursor | jk-rename-codegraph-between | jk-nograph | 3 | 3 | 275947 | 22992 | 1947 | 20 | 249 | 0.00 | - |
| cursor | petclinic-rename-addvisit | jk | 3 | 3 | 58960 | 7788 | 516 | 4 | 27 | 1.00 | - |
| cursor | petclinic-rename-addvisit | jk-nograph | 3 | 3 | 92333 | 9934 | 1386 | 6 | 30 | 0.00 | - |
| grok | cl-callers-csu-indexof | jk | 4 | 4 | 48656 | 10480 | 1574 | 5 | 23 | 0.25 | 1.0 |
| grok | cl-impact-dispatch-appendto | jk | 3 | 3 | 104390 | 57740 | 8122 | 7 | 110 | 0.25 | 1.0 |
| grok | cl-impact-ispackage | jk | 4 | 4 | 40496 | 14280 | 2254 | 5 | 29 | 0.75 | 1.0 |
| grok | cl-subtypes-daterule | jk | 3 | 3 | 164409 | 46252 | 3834 | 10 | 50 | 0.00 | 1.0 |
| grok | jk-callers-projectinfo-name | jk | 3 | 3 | 30073 | 7109 | 1598 | 4 | 23 | 1.00 | 1.0 |
| grok | jk-callers-sidecar-name | jk | 3 | 3 | 94039 | 23120 | 5044 | 7 | 76 | 0.12 | 1.0 |
| grok | jk-impact-dispatch-javahome | jk | 3 | 3 | 218326 | 52254 | 11192 | 10 | 148 | 0.25 | 1.0 |
| grok | jk-impact-lockstale | jk | 3 | 3 | 96452 | 26121 | 4539 | 7 | 74 | 0.20 | 1.0 |
| muse | cl-callers-csu-indexof | jk | 6 | 6 | 84775 | 29144 | 4304 | 4 | 30 | 0.50 | 1.0 |
| muse | cl-impact-dispatch-appendto | jk | 3 | 3 | 306788 | 60142 | 11773 | 10 | 76 | 0.14 | 1.0 |
| muse | cl-impact-ispackage | jk | 4 | 4 | 101978 | 40132 | 5872 | 4 | 37 | 0.67 | 1.0 |
| muse | cl-subtypes-daterule | jk | 3 | 3 | 378632 | 66192 | 5819 | 14 | 47 | 0.00 | 1.0 |
| muse | jk-callers-projectinfo-name | jk | 3 | 3 | 308389 | 75434 | 11123 | 10 | 81 | 0.05 | 1.0 |
| muse | jk-callers-sidecar-name | jk | 3 | 3 | 170714 | 64691 | 8703 | 6 | 63 | 0.17 | 1.0 |
| muse | jk-impact-dispatch-javahome | jk | 3 | 3 | 130586 | 57773 | 8986 | 5 | 76 | 0.40 | 1.0 |
| muse | jk-impact-lockstale | jk | 3 | 3 | 75059 | 46746 | 10280 | 3 | 39 | 1.00 | 1.0 |
