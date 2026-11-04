# Change tasks — 2026-11-04-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | cl-callers-csu-indexof | jk-agyrules | 1 | 1 | 373588 | 85505 | 51995 | 10 | 448 | 0.33 | 1.0 |
| agy | cl-impact-ispackage | jk-agyrules | 1 | 1 | 67609 | 60182 | 7427 | 6 | 66 | 1.00 | 1.0 |
| agy | commons-lang-rename-indexof | jk-agyrules | 3 | 3 | 44509 | 41994 | 2515 | 4 | 34 | 0.50 | - |
| agy | commons-lang-rename-range-contains | jk-agyrules | 3 | 3 | 54934 | 52036 | 2898 | 6 | 42 | 1.00 | - |
| agy | jk-callers-projectinfo-name | jk-agyrules | 1 | 1 | 81646 | 59047 | 5931 | 5 | 67 | 1.00 | 1.0 |
| agy | jk-rename-codegraph-between | jk-agyrules | 2 | 2 | 59590 | 57806 | 1784 | 4 | 47 | 1.00 | - |
| agy | petclinic-callers-addpet | jk-agyrules | 1 | 1 | 41037 | 38648 | 2389 | 5 | 29 | 1.00 | 1.0 |
| agy | petclinic-rename-addvisit | jk-agyrules | 2 | 2 | 31624 | 30160 | 1464 | 4 | 29 | 1.00 | - |
| claude-code | cl-callers-csu-indexof | jk | 1 | 1 | 20434 | 6 | 304 | 3 | 6 | 1.00 | 1.0 |
| claude-code | cl-impact-ispackage | jk | 1 | 1 | 22704 | 6 | 560 | 3 | 6 | 1.00 | 1.0 |
| claude-code | commons-lang-rename-indexof | jk | 2 | 2 | 12754 | 4 | 92 | 2 | 15 | 1.00 | - |
| claude-code | commons-lang-rename-range-contains | jk | 2 | 2 | 12529 | 4 | 84 | 2 | 15 | 1.00 | - |
| claude-code | jk-callers-projectinfo-name | jk | 1 | 1 | 47298 | 6 | 423 | 3 | 11 | 1.00 | 1.0 |
| claude-code | jk-rename-codegraph-between | jk | 2 | 2 | 36533 | 5 | 125 | 2 | 18 | 1.00 | - |
| claude-code | petclinic-callers-addpet | jk | 1 | 1 | 19429 | 6 | 255 | 3 | 16 | 1.00 | 1.0 |
| claude-code | petclinic-rename-addvisit | jk | 1 | 1 | 12066 | 4 | 90 | 2 | 15 | 1.00 | - |
| cursor | cl-callers-csu-indexof | jk-cursorrules | 1 | 1 | 48772 | 12368 | 596 | 3 | 17 | 1.00 | 1.0 |
| cursor | cl-impact-ispackage | jk-cursorrules | 1 | 1 | 50825 | 9068 | 1181 | 3 | 18 | 1.00 | 1.0 |
| cursor | commons-lang-rename-indexof | jk-cursorrules | 2 | 2 | 63886 | 8868 | 842 | 4 | 67 | 1.00 | - |
| cursor | commons-lang-rename-range-contains | jk-cursorrules | 2 | 2 | 36763 | 12694 | 452 | 2 | 67 | 1.00 | - |
| cursor | jk-callers-projectinfo-name | jk-cursorrules | 1 | 1 | 71885 | 14182 | 903 | 3 | 28 | 1.00 | 1.0 |
| cursor | jk-rename-codegraph-between | jk-cursorrules | 2 | 2 | 146280 | 14230 | 1090 | 7 | 95 | 0.62 | - |
| cursor | petclinic-callers-addpet | jk-cursorrules | 1 | 1 | 47958 | 8184 | 414 | 3 | 16 | 1.00 | 1.0 |
| cursor | petclinic-rename-addvisit | jk-cursorrules | 1 | 1 | 34980 | 8492 | 280 | 2 | 21 | 1.00 | - |
| grok | cl-callers-csu-indexof | jk | 1 | 1 | 28399 | 6022 | 617 | 4 | 13 | 1.00 | 1.0 |
| grok | cl-impact-ispackage | jk | 1 | 1 | 31105 | 13602 | 1503 | 4 | 19 | 1.00 | 1.0 |
| grok | commons-lang-rename-indexof | jk | 3 | 3 | 160218 | 32018 | 4168 | 11 | 68 | 0.29 | - |
| grok | commons-lang-rename-range-contains | jk | 3 | 3 | 37540 | 11695 | 1415 | 5 | 29 | 1.00 | - |
| grok | jk-callers-projectinfo-name | jk | 1 | 1 | 53352 | 12500 | 1044 | 4 | 49 | 1.00 | 1.0 |
| grok | jk-rename-codegraph-between | jk | 2 | 2 | 51480 | 22590 | 858 | 4 | 41 | 1.00 | - |
| grok | petclinic-callers-addpet | jk | 1 | 1 | 27450 | 9722 | 448 | 4 | 9 | 1.00 | 1.0 |
| grok | petclinic-rename-addvisit | jk | 2 | 2 | 28278 | 11100 | 730 | 4 | 21 | 1.00 | - |
| muse | cl-callers-csu-indexof | jk | 1 | 1 | 65548 | 27911 | 3619 | 3 | 18 | 1.00 | 1.0 |
| muse | cl-impact-ispackage | jk | 1 | 1 | 69434 | 28231 | 6673 | 3 | 50 | 1.00 | 1.0 |
| muse | commons-lang-rename-indexof | jk | 3 | 3 | 146150 | 30571 | 4970 | 7 | 109 | 1.00 | - |
| muse | commons-lang-rename-range-contains | jk | 3 | 3 | 163901 | 40487 | 7391 | 7 | 117 | 1.00 | - |
| muse | jk-callers-projectinfo-name | jk | 1 | 1 | 85709 | 34228 | 5815 | 3 | 51 | 1.00 | 1.0 |
| muse | jk-rename-codegraph-between | jk | 2 | 2 | 92007 | 42910 | 4071 | 4 | 42 | 1.00 | - |
| muse | petclinic-callers-addpet | jk | 1 | 1 | 63449 | 42889 | 3167 | 3 | 23 | 1.00 | 1.0 |
| muse | petclinic-rename-addvisit | jk | 1 | 1 | 45689 | 29066 | 3966 | 2 | 40 | 1.00 | - |
