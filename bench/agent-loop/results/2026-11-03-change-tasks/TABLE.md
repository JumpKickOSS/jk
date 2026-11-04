# Change tasks — 2026-11-03-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| agy | cl-callers-csu-indexof | jk-agyrules | 1 | 1 | 265059 | 99432 | 19281 | 14 | 194 | 0.11 | 1.0 |
| agy | gs-uploading-rename-loadasresource | jk-agyrules | 1 | 1 | 47446 | 46165 | 1281 | 6 | 25 | 1.00 | - |
| agy | jk-rename-codegraph-between | jk-agyrules | 1 | 1 | 104000 | 101556 | 2444 | 7 | 63 | 0.50 | - |
| agy | petclinic-callers-addpet | jk-agyrules | 1 | 1 | 41990 | 40202 | 1788 | 5 | 24 | 1.00 | 1.0 |
| agy | petclinic-rename-addvisit | jk-agyrules | 1 | 1 | 64564 | 63070 | 1494 | 8 | 86 | 1.00 | - |
| claude-code | cl-callers-csu-indexof | jk | 4 | 4 | 20293 | 6 | 292 | 3 | 6 | 1.00 | 1.0 |
| claude-code | cli-rename-strip-hyphens | jk | 1 | 1 | 18464 | 6 | 132 | 3 | 7 | 1.00 | - |
| claude-code | commons-lang-rename-indexof | jk | 1 | 1 | 20306 | 6 | 123 | 3 | 94 | 1.00 | - |
| claude-code | commons-lang-rename-range-contains | jk | 4 | 4 | 19775 | 6 | 115 | 3 | 52 | 1.00 | - |
| claude-code | gs-uploading-rename-loadasresource | jk | 4 | 4 | 18400 | 6 | 122 | 3 | 7 | 1.00 | - |
| claude-code | gs-uploading-rename-loadasresource | jk+shipped | 3 | 3 | 18688 | 6 | 122 | 3 | 6 | 1.00 | - |
| claude-code | jk-callers-projectinfo-name | jk | 3 | 3 | 64066 | 8 | 631 | 4 | 10 | 1.00 | 1.0 |
| claude-code | jk-rename-codegraph-between | jk | 4 | 4 | 29465 | 4 | 109 | 2 | 7 | 1.00 | - |
| claude-code | petclinic-callers-addpet | jk | 4 | 4 | 19273 | 6 | 238 | 3 | 4 | 1.00 | 1.0 |
| claude-code | petclinic-callers-addpet | jk+shipped | 3 | 3 | 19549 | 6 | 259 | 3 | 14 | 1.00 | 1.0 |
| claude-code | petclinic-rename-addvisit | jk | 4 | 4 | 18198 | 6 | 121 | 3 | 49 | 1.00 | - |
| claude-code | petclinic-rename-addvisit | jk+shipped | 3 | 3 | 18477 | 6 | 121 | 3 | 58 | 1.00 | - |
| cursor | cl-callers-csu-indexof | jk-cursorrules | 2 | 2 | 48932 | 8396 | 648 | 3 | 17 | 1.00 | 1.0 |
| cursor | cli-rename-strip-hyphens | jk-cursorrules | 1 | 1 | 71560 | 7977 | 447 | 5 | 20 | 1.00 | - |
| cursor | commons-lang-rename-indexof | jk-cursorrules | 1 | 1 | 87166 | 12076 | 1394 | 5 | 25 | 0.33 | - |
| cursor | commons-lang-rename-range-contains | jk-cursorrules | 2 | 2 | 124671 | 10254 | 1680 | 8 | 116 | 0.23 | - |
| cursor | gs-uploading-rename-loadasresource | jk-cursorrules | 2 | 2 | 72802 | 8256 | 434 | 5 | 17 | 0.75 | - |
| cursor | jk-callers-projectinfo-name | jk-cursorrules | 1 | 1 | 72005 | 14187 | 986 | 3 | 37 | 1.00 | 1.0 |
| cursor | jk-rename-codegraph-between | jk-cursorrules | 2 | 2 | 185704 | 15339 | 1308 | 9 | 132 | 0.33 | - |
| cursor | petclinic-callers-addpet | jk-cursorrules | 3 | 3 | 47998 | 8158 | 434 | 3 | 18 | 1.00 | 1.0 |
| cursor | petclinic-rename-addvisit | jk-cursorrules | 4 | 4 | 77680 | 10050 | 546 | 6 | 63 | 1.00 | - |
| grok | cl-callers-csu-indexof | jk | 1 | 1 | 28423 | 6014 | 649 | 4 | 11 | 1.00 | 1.0 |
| grok | gs-uploading-rename-loadasresource | jk | 1 | 1 | 35300 | 5653 | 591 | 5 | 10 | 1.00 | - |
| grok | petclinic-callers-addpet | jk | 1 | 1 | 27843 | 5638 | 573 | 4 | 8 | 1.00 | 1.0 |
| grok | petclinic-rename-addvisit | jk | 1 | 1 | 35011 | 5514 | 569 | 5 | 52 | 1.00 | - |
| muse | cl-callers-csu-indexof | jk | 1 | 1 | 67037 | 44368 | 5020 | 3 | 34 | 1.00 | 1.0 |
| muse | gs-uploading-rename-loadasresource | jk | 1 | 1 | 97924 | 27939 | 2461 | 5 | 22 | 1.00 | - |
| muse | jk-rename-codegraph-between | jk | 1 | 1 | 103832 | 31596 | 4313 | 4 | 70 | 1.00 | - |
| muse | petclinic-callers-addpet | jk | 1 | 1 | 64237 | 26104 | 4371 | 3 | 26 | 1.00 | 1.0 |
| muse | petclinic-rename-addvisit | jk | 1 | 1 | 78438 | 42228 | 2064 | 4 | 70 | 1.00 | - |
