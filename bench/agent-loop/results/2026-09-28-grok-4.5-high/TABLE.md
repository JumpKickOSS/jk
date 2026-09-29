# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: grok · 165 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `grok` · grok-4.5 · effort high · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 63 | 100% | 6 | 7 | 39,467 | 55,097 | 14.2s | 27.5s | $0.91 | 4 | 2 | 859 | 11,503 | 29,312 | $0.0144 | 31 exact · 24 equivalent · 8 collateral · 0 cheat |
| mvn | 39 | 39 | 100% | 7 | 8 | 46,091 | 70,842 | 21.8s | 30.8s | $0.61 | 5 | 3 | 1,156 | 12,464 | 33,408 | $0.0156 | 29 exact · 6 equivalent · 4 collateral · 0 cheat |
| gradle | 63 | 63 | 100% | 7 | 9 | 51,724 | 73,708 | 21.9s | 37.0s | $1.02 | 5 | 3 | 1,234 | 13,322 | 34,944 | $0.0162 | 45 exact · 11 equivalent · 7 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 6t · 12.6s · 38,147 tok | green · 6t · 14.2s · 35,035 tok | green · 7t · 16.2s · 43,510 tok |
| gs-accessing-data-jpa | failing-assertion | green · 7t · 22.5s · 61,139 tok | green · 6t · 18.7s · 39,954 tok | green · 7t · 24.2s · 48,984 tok |
| gs-accessing-data-jpa | missing-dependency | green · 5t · 12.9s · 33,130 tok | green · 6t · 28.4s · 47,601 tok | green · 7t · 18.2s · 57,790 tok |
| gs-accessing-data-r2dbc | compile-error | green · 6t · 11.6s · 40,694 tok | green · 6t · 15.8s · 35,933 tok | green · 6t · 12.5s · 37,326 tok |
| gs-accessing-data-r2dbc | failing-assertion | green · 7t · 21.8s · 54,321 tok | green · 7t · 30.1s · 55,817 tok | green · 7t · 28.3s · 56,880 tok |
| gs-accessing-data-r2dbc | missing-dependency | green · 6t · 13.5s · 41,090 tok | green · 6t · 16.5s · 47,149 tok | green · 7t · 27.8s · 62,638 tok |
| gs-accessing-data-r2dbc | missing-resource | green · 10t · 39.8s · 152,560 tok | green · 12t · 55.6s · 188,586 tok | green · 9t · 46.7s · 106,218 tok |
| gs-accessing-data-rest | compile-error | green · 6t · 12.0s · 38,005 tok | green · 6t · 16.1s · 33,715 tok | green · 6t · 16.2s · 35,869 tok |
| gs-accessing-data-rest | failing-assertion | green · 7t · 23.7s · 55,271 tok | green · 8t · 31.2s · 63,090 tok | green · 8t · 28.3s · 62,599 tok |
| gs-accessing-data-rest | missing-dependency | green · 5t · 11.0s · 30,414 tok | green · 7t · 22.9s · 43,809 tok | green · 7t · 26.7s · 50,848 tok |
| gs-actuator-service | compile-error | green · 6t · 11.8s · 35,173 tok | green · 6t · 15.5s · 33,750 tok | green · 7t · 16.1s · 40,009 tok |
| gs-actuator-service | failing-assertion | green · 7t · 18.1s · 52,308 tok | green · 7t · 23.2s · 51,082 tok | green · 8t · 27.7s · 56,046 tok |
| gs-actuator-service | missing-dependency | green · 6t · 16.1s · 38,007 tok | green · 8t · 30.8s · 84,331 tok | green · 7t · 22.8s · 51,526 tok |
| gs-batch-processing | compile-error | green · 6t · 13.7s · 40,287 tok | green · 6t · 12.7s · 31,503 tok | green · 6t · 13.6s · 35,600 tok |
| gs-batch-processing | failing-assertion | green · 7t · 20.0s · 54,142 tok | green · 7t · 22.6s · 46,091 tok | green · 8t · 24.0s · 56,863 tok |
| gs-batch-processing | missing-dependency | green · 5t · 12.3s · 34,733 tok | green · 7t · 27.0s · 61,265 tok | green · 7t · 22.5s · 65,438 tok |
| gs-batch-processing | missing-resource | green · 7t · 20.0s · 54,806 tok | green · 7t · 21.8s · 71,784 tok | green · 8t · 33.1s · 95,249 tok |
| gs-consuming-rest | compile-error | green · 6t · 12.4s · 37,983 tok | green · 6t · 15.0s · 36,563 tok | green · 7t · 14.5s · 38,363 tok |
| gs-consuming-rest | missing-dependency | green · 6t · 14.5s · 38,939 tok | green · 7t · 25.6s · 101,096 tok | green · 9t · 30.1s · 69,389 tok |
| gs-graphql-server | compile-error | green · 6t · 12.9s · 39,001 tok | · | green · 6t · 15.0s · 33,656 tok |
| gs-graphql-server | failing-assertion | green · 7t · 22.7s · 51,423 tok | · | green · 6t · 17.9s · 39,370 tok |
| gs-graphql-server | missing-dependency | green · 5t · 12.5s · 33,359 tok | · | green · 8t · 35.3s · 56,715 tok |
| gs-graphql-server | missing-resource | green · 7t · 25.1s · 54,341 tok | · | green · 7t · 18.9s · 52,579 tok |
| gs-handling-form-submission | compile-error | green · 6t · 11.4s · 37,743 tok | · | green · 7t · 12.8s · 42,127 tok |
| gs-handling-form-submission | failing-assertion | green · 7t · 22.0s · 54,294 tok | · | green · 6t · 18.3s · 42,504 tok |
| gs-handling-form-submission | missing-dependency | green · 5t · 17.5s · 33,712 tok | · | green · 7t · 17.1s · 48,191 tok |
| gs-handling-form-submission | missing-resource | green · 7t · 52.2s · 54,829 tok | · | green · 8t · 21.9s · 64,724 tok |
| gs-reactive-rest-service | compile-error | green · 6t · 11.2s · 39,467 tok | green · 7t · 17.9s · 39,265 tok | green · 6t · 15.7s · 31,779 tok |
| gs-reactive-rest-service | failing-assertion | green · 7t · 21.4s · 51,204 tok | green · 7t · 20.2s · 43,207 tok | green · 6t · 20.3s · 39,373 tok |
| gs-rest-hateoas | compile-error | green · 6t · 11.2s · 36,838 tok | green · 6t · 15.2s · 36,090 tok | green · 7t · 15.1s · 42,134 tok |
| gs-rest-hateoas | failing-assertion | green · 10t · 57.9s · 95,036 tok | green · 8t · 49.3s · 70,842 tok | green · 9t · 55.8s · 78,694 tok |
| gs-rest-hateoas | missing-dependency | green · 5t · 11.0s · 30,992 tok | green · 7t · 24.6s · 57,953 tok | green · 7t · 21.8s · 57,573 tok |
| gs-rest-service | compile-error | green · 6t · 11.7s · 36,472 tok | green · 6t · 14.9s · 35,657 tok | green · 6t · 14.1s · 35,512 tok |
| gs-rest-service | failing-assertion | green · 7t · 26.9s · 51,748 tok | green · 7t · 42.8s · 51,320 tok | green · 9t · 58.0s · 74,438 tok |
| gs-rest-service | missing-dependency | green · 5t · 10.5s · 29,796 tok | green · 7t · 24.2s · 52,054 tok | green · 7t · 18.5s · 51,745 tok |
| gs-rest-service | version-conflict | green · 7t · 20.5s · 49,515 tok | green · 8t · 21.0s · 58,339 tok | green · 8t · 24.3s · 59,083 tok |
| gs-scheduling-tasks | compile-error | green · 6t · 12.4s · 39,107 tok | · | green · 6t · 19.5s · 33,636 tok |
| gs-scheduling-tasks | failing-assertion | green · 7t · 20.4s · 54,530 tok | · | green · 6t · 25.2s · 41,716 tok |
| gs-scheduling-tasks | missing-dependency | green · 6t · 18.2s · 40,615 tok | · | green · 8t · 27.9s · 62,208 tok |
| gs-securing-web | compile-error | green · 6t · 12.0s · 37,246 tok | · | green · 6t · 16.6s · 33,822 tok |
| gs-securing-web | failing-assertion | green · 7t · 33.9s · 60,145 tok | · | green · 9t · 43.0s · 81,600 tok |
| gs-securing-web | missing-dependency | green · 5t · 12.1s · 34,240 tok | · | green · 7t · 27.6s · 53,551 tok |
| gs-securing-web | missing-resource | green · 7t · 20.6s · 52,601 tok | · | green · 7t · 21.9s · 64,750 tok |
| gs-serving-web-content | compile-error | green · 6t · 14.2s · 37,488 tok | · | green · 6t · 13.6s · 35,736 tok |
| gs-serving-web-content | failing-assertion | green · 7t · 21.3s · 49,317 tok | · | green · 8t · 22.4s · 57,937 tok |
| gs-serving-web-content | missing-resource | green · 7t · 22.1s · 55,097 tok | · | green · 7t · 20.4s · 56,982 tok |
| gs-spring-boot | compile-error | green · 6t · 11.5s · 36,789 tok | green · 7t · 17.5s · 42,588 tok | green · 6t · 14.1s · 33,957 tok |
| gs-spring-boot | failing-assertion | green · 7t · 67.6s · 53,334 tok | green · 7t · 26.4s · 50,461 tok | green · 7t · 43.8s · 55,265 tok |
| gs-spring-boot | missing-dependency | green · 5t · 12.9s · 33,652 tok | green · 6t · 28.1s · 38,871 tok | green · 8t · 34.9s · 60,409 tok |
| gs-spring-boot | version-conflict | green · 5t · 16.6s · 33,766 tok | green · 7t · 17.3s · 53,359 tok | green · 7t · 23.8s · 52,922 tok |
| gs-testing-web | compile-error | green · 6t · 11.6s · 36,740 tok | green · 6t · 15.5s · 33,578 tok | green · 6t · 15.6s · 35,657 tok |
| gs-testing-web | failing-assertion | green · 7t · 29.6s · 54,581 tok | green · 6t · 30.2s · 43,631 tok | green · 6t · 37.9s · 41,976 tok |
| gs-uploading-files | compile-error | green · 6t · 11.0s · 37,842 tok | green · 7t · 17.0s · 42,376 tok | green · 6t · 15.7s · 36,181 tok |
| gs-uploading-files | failing-assertion | green · 7t · 21.8s · 53,548 tok | green · 7t · 30.6s · 53,912 tok | green · 7t · 37.0s · 59,896 tok |
| gs-uploading-files | missing-dependency | green · 5t · 9.8s · 30,217 tok | green · 7t · 22.7s · 52,784 tok | green · 7t · 23.0s · 51,724 tok |
| gs-uploading-files | missing-resource | green · 8t · 27.5s · 71,004 tok | · | green · 9t · 27.4s · 82,422 tok |
| gs-validating-form-input | compile-error | green · 6t · 11.9s · 37,200 tok | green · 6t · 14.2s · 31,777 tok | green · 6t · 13.6s · 33,750 tok |
| gs-validating-form-input | missing-dependency | green · 5t · 10.9s · 33,868 tok | green · 6t · 17.4s · 41,793 tok | green · 7t · 16.9s · 49,725 tok |
| gs-validating-form-input | missing-resource | green · 7t · 23.1s · 55,034 tok | · | green · 8t · 24.3s · 73,708 tok |
| junit-starter-gradle | compile-error | green · 6t · 11.7s · 36,571 tok | · | green · 7t · 12.4s · 39,980 tok |
| junit-starter-gradle | failing-assertion | green · 7t · 19.6s · 50,036 tok | · | green · 7t · 20.5s · 46,985 tok |
| junit-starter-gradle | missing-dependency | green · 5t · 11.6s · 33,867 tok | · | green · 7t · 16.4s · 51,332 tok |
| junit-starter-gradle | version-conflict | green · 6t · 14.1s · 40,064 tok | · | green · 9t · 26.4s · 66,189 tok |
