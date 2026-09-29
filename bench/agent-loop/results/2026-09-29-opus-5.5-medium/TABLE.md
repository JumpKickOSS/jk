# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 165 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 63 | 100% | 5 | 8 | 22,848 | 33,423 | 11.2s | 16.6s | $2.92 | 3 | 1 | 625 | 8 | 19,725 | $0.0464 | 39 exact · 23 equivalent · 1 collateral · 0 cheat |
| mvn | 39 | 39 | 100% | 5 | 7 | 24,877 | 34,292 | 14.3s | 20.2s | $1.97 | 3 | 2 | 456 | 8 | 20,331 | $0.0505 | 34 exact · 5 equivalent · 0 collateral · 0 cheat |
| gradle | 63 | 63 | 100% | 6 | 7 | 30,725 | 38,928 | 15.3s | 19.3s | $3.22 | 4 | 2 | 498 | 10 | 25,816 | $0.0511 | 54 exact · 9 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 5t · 8.3s · 22,206 tok | green · 5t · 14.3s · 21,675 tok | green · 4t · 14.3s · 21,182 tok |
| gs-accessing-data-jpa | failing-assertion | green · 8t · 19.2s · 31,788 tok | green · 8t · 20.2s · 31,804 tok | green · 7t · 17.6s · 30,926 tok |
| gs-accessing-data-jpa | missing-dependency | green · 4t · 9.8s · 17,200 tok | green · 5t · 14.0s · 30,251 tok | green · 6t · 13.5s · 41,926 tok |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 9.8s · 22,372 tok | green · 5t · 11.4s · 21,988 tok | green · 4t · 9.8s · 15,815 tok |
| gs-accessing-data-r2dbc | failing-assertion | green · 8t · 13.7s · 32,183 tok | green · 7t · 18.0s · 34,860 tok | green · 7t · 18.5s · 36,072 tok |
| gs-accessing-data-r2dbc | missing-dependency | green · 5t · 9.8s · 18,397 tok | green · 5t · 11.9s · 31,675 tok | green · 6t · 14.5s · 43,691 tok |
| gs-accessing-data-r2dbc | missing-resource | green · 9t · 15.6s · 33,926 tok | green · 8t · 13.1s · 38,080 tok | green · 8t · 44.4s · 48,208 tok |
| gs-accessing-data-rest | compile-error | green · 5t · 8.1s · 22,106 tok | green · 5t · 14.3s · 21,511 tok | green · 4t · 13.8s · 21,120 tok |
| gs-accessing-data-rest | failing-assertion | green · 8t · 21.0s · 40,598 tok | green · 7t · 21.1s · 34,780 tok | green · 7t · 22.0s · 34,765 tok |
| gs-accessing-data-rest | missing-dependency | green · 4t · 11.0s · 16,285 tok | green · 5t · 15.3s · 24,877 tok | green · 6t · 16.1s · 30,725 tok |
| gs-actuator-service | compile-error | green · 5t · 8.1s · 21,866 tok | green · 5t · 12.9s · 21,371 tok | green · 5t · 13.4s · 21,370 tok |
| gs-actuator-service | failing-assertion | green · 6t · 14.7s · 30,136 tok | green · 6t · 18.7s · 30,946 tok | green · 7t · 18.1s · 32,110 tok |
| gs-actuator-service | missing-dependency | green · 5t · 11.2s · 22,372 tok | green · 5t · 15.0s · 24,426 tok | green · 6t · 15.9s · 30,173 tok |
| gs-batch-processing | compile-error | green · 5t · 8.1s · 21,999 tok | green · 5t · 10.5s · 21,410 tok | green · 4t · 9.9s · 21,026 tok |
| gs-batch-processing | failing-assertion | green · 6t · 15.5s · 30,296 tok | green · 6t · 17.8s · 31,062 tok | green · 6t · 16.0s · 30,932 tok |
| gs-batch-processing | missing-dependency | green · 5t · 11.4s · 23,680 tok | green · 5t · 13.7s · 33,649 tok | green · 6t · 15.6s · 48,725 tok |
| gs-batch-processing | missing-resource | green · 7t · 10.6s · 33,434 tok | green · 8t · 18.9s · 38,054 tok | green · 8t · 15.2s · 37,844 tok |
| gs-consuming-rest | compile-error | green · 5t · 7.8s · 22,063 tok | green · 5t · 12.0s · 21,522 tok | green · 4t · 10.9s · 20,998 tok |
| gs-consuming-rest | missing-dependency | green · 5t · 10.5s · 23,535 tok | green · 5t · 12.0s · 28,144 tok | green · 6t · 15.8s · 37,643 tok |
| gs-graphql-server | compile-error | green · 5t · 8.2s · 21,974 tok | · | green · 4t · 11.7s · 20,980 tok |
| gs-graphql-server | failing-assertion | green · 8t · 13.8s · 31,216 tok | · | green · 7t · 22.9s · 37,745 tok |
| gs-graphql-server | missing-dependency | green · 4t · 8.2s · 16,262 tok | · | green · 6t · 14.1s · 30,018 tok |
| gs-graphql-server | missing-resource | green · 8t · 15.0s · 31,076 tok | · | green · 8t · 15.7s · 34,354 tok |
| gs-handling-form-submission | compile-error | green · 5t · 7.9s · 22,112 tok | · | green · 5t · 10.6s · 21,563 tok |
| gs-handling-form-submission | failing-assertion | green · 8t · 15.1s · 32,454 tok | · | green · 6t · 16.1s · 31,605 tok |
| gs-handling-form-submission | missing-dependency | green · 5t · 11.2s · 22,360 tok | · | green · 5t · 9.9s · 23,946 tok |
| gs-handling-form-submission | missing-resource | green · 8t · 19.1s · 32,437 tok | · | green · 8t · 15.4s · 36,852 tok |
| gs-reactive-rest-service | compile-error | green · 5t · 7.6s · 22,383 tok | green · 5t · 13.9s · 21,840 tok | green · 4t · 12.9s · 15,734 tok |
| gs-reactive-rest-service | failing-assertion | green · 7t · 17.8s · 30,660 tok | green · 7t · 19.8s · 31,504 tok | green · 7t · 17.9s · 31,417 tok |
| gs-rest-hateoas | compile-error | green · 5t · 8.0s · 22,014 tok | green · 5t · 13.1s · 21,433 tok | green · 4t · 11.7s · 21,017 tok |
| gs-rest-hateoas | failing-assertion | green · 8t · 17.4s · 33,423 tok | green · 8t · 20.5s · 34,292 tok | green · 7t · 18.9s · 33,116 tok |
| gs-rest-hateoas | missing-dependency | green · 4t · 10.2s · 17,067 tok | green · 5t · 13.2s · 28,031 tok | green · 6t · 14.7s · 38,166 tok |
| gs-rest-service | compile-error | green · 4t · 7.8s · 21,514 tok | green · 5t · 23.3s · 21,255 tok | green · 4t · 15.8s · 20,906 tok |
| gs-rest-service | failing-assertion | green · 7t · 15.2s · 30,393 tok | green · 7t · 16.8s · 30,666 tok | green · 7t · 18.2s · 30,890 tok |
| gs-rest-service | missing-dependency | green · 5t · 12.6s · 22,848 tok | green · 5t · 15.1s · 24,758 tok | green · 6t · 15.7s · 31,573 tok |
| gs-rest-service | version-conflict | green · 5t · 11.1s · 22,382 tok | green · 5t · 12.7s · 24,896 tok | green · 6t · 14.6s · 30,223 tok |
| gs-scheduling-tasks | compile-error | green · 5t · 9.6s · 22,172 tok | · | green · 5t · 15.4s · 21,703 tok |
| gs-scheduling-tasks | failing-assertion | green · 8t · 16.6s · 32,068 tok | · | green · 5t · 20.3s · 23,758 tok |
| gs-scheduling-tasks | missing-dependency | green · 4t · 15.2s · 22,854 tok | · | green · 6t · 18.3s · 34,262 tok |
| gs-securing-web | compile-error | green · 5t · 10.4s · 22,033 tok | · | green · 5t · 13.5s · 21,510 tok |
| gs-securing-web | failing-assertion | green · 6t · 16.5s · 32,538 tok | · | green · 7t · 19.3s · 33,289 tok |
| gs-securing-web | missing-dependency | green · 5t · 11.6s · 22,879 tok | · | green · 6t · 14.8s · 29,786 tok |
| gs-securing-web | missing-resource | green · 7t · 13.8s · 29,886 tok | · | green · 7t · 14.6s · 35,374 tok |
| gs-serving-web-content | compile-error | green · 5t · 7.7s · 22,023 tok | · | green · 4t · 11.0s · 21,028 tok |
| gs-serving-web-content | failing-assertion | green · 8t · 15.2s · 31,764 tok | · | green · 6t · 39.7s · 30,362 tok |
| gs-serving-web-content | missing-resource | green · 7t · 13.3s · 32,667 tok | · | green · 7t · 14.2s · 40,366 tok |
| gs-spring-boot | compile-error | green · 5t · 8.1s · 21,914 tok | green · 5t · 11.9s · 21,706 tok | green · 4t · 11.8s · 20,835 tok |
| gs-spring-boot | failing-assertion | green · 6t · 11.7s · 22,924 tok | green · 6t · 16.2s · 23,488 tok | green · 6t · 16.1s · 23,683 tok |
| gs-spring-boot | missing-dependency | green · 5t · 11.0s · 22,338 tok | green · 5t · 14.3s · 23,591 tok | green · 6t · 15.3s · 28,682 tok |
| gs-spring-boot | version-conflict | green · 5t · 8.3s · 22,326 tok | green · 5t · 14.7s · 27,620 tok | green · 6t · 16.0s · 33,607 tok |
| gs-testing-web | compile-error | green · 5t · 7.6s · 21,888 tok | green · 5t · 13.4s · 21,261 tok | green · 4t · 12.8s · 20,889 tok |
| gs-testing-web | failing-assertion | green · 7t · 15.4s · 30,478 tok | green · 7t · 18.2s · 30,785 tok | green · 7t · 19.3s · 30,978 tok |
| gs-uploading-files | compile-error | green · 5t · 7.9s · 21,972 tok | green · 5t · 14.9s · 21,893 tok | green · 5t · 15.3s · 21,904 tok |
| gs-uploading-files | failing-assertion | green · 7t · 16.8s · 34,972 tok | green · 7t · 24.3s · 31,334 tok | green · 7t · 19.2s · 34,604 tok |
| gs-uploading-files | missing-dependency | green · 5t · 12.5s · 22,880 tok | green · 5t · 15.7s · 24,315 tok | green · 6t · 14.1s · 29,549 tok |
| gs-uploading-files | missing-resource | green · 9t · 15.7s · 50,435 tok | · | green · 8t · 18.1s · 38,928 tok |
| gs-validating-form-input | compile-error | green · 5t · 8.1s · 22,042 tok | green · 5t · 12.4s · 21,436 tok | green · 5t · 15.5s · 21,465 tok |
| gs-validating-form-input | missing-dependency | green · 4t · 10.7s · 21,971 tok | green · 5t · 11.8s · 24,808 tok | green · 6t · 11.6s · 31,017 tok |
| gs-validating-form-input | missing-resource | green · 9t · 15.8s · 36,400 tok | · | green · 9t · 18.6s · 52,017 tok |
| junit-starter-gradle | compile-error | green · 5t · 10.2s · 21,961 tok | · | green · 4t · 8.8s · 20,725 tok |
| junit-starter-gradle | failing-assertion | green · 7t · 13.5s · 30,313 tok | · | green · 6t · 12.4s · 23,516 tok |
| junit-starter-gradle | missing-dependency | green · 4t · 7.8s · 22,603 tok | · | green · 6t · 8.9s · 32,403 tok |
| junit-starter-gradle | version-conflict | green · 7t · 12.3s · 29,563 tok | · | green · 6t · 9.7s · 27,077 tok |
