# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: grok · 165 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `grok` · grok-4.7 · effort high · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 63 | 100% | 6 | 8 | 46,261 | 83,888 | 19.2s | 48.8s | $1.38 | 4 | 2 | 1,475 | 15,295 | 33,280 | $0.0219 | 30 exact · 25 equivalent · 8 collateral · 0 cheat |
| mvn | 39 | 39 | 100% | 8 | 10 | 69,099 | 114,788 | 31.9s | 60.5s | $1.01 | 6 | 4 | 2,593 | 16,030 | 50,688 | $0.0258 | 29 exact · 8 equivalent · 2 collateral · 0 cheat |
| gradle | 63 | 63 | 100% | 7 | 9 | 62,285 | 96,457 | 28.0s | 54.3s | $1.58 | 5 | 4 | 2,212 | 16,576 | 43,008 | $0.0250 | 46 exact · 11 equivalent · 6 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 6t · 11.3s · 37,433 tok | green · 6t · 16.0s · 41,292 tok | green · 6t · 15.6s · 42,922 tok |
| gs-accessing-data-jpa | failing-assertion | green · 7t · 24.2s · 75,957 tok | green · 8t · 35.0s · 69,734 tok | green · 7t · 33.6s · 61,240 tok |
| gs-accessing-data-jpa | missing-dependency | green · 5t · 11.6s · 37,160 tok | green · 7t · 33.8s · 70,103 tok | green · 8t · 40.4s · 84,531 tok |
| gs-accessing-data-r2dbc | compile-error | green · 8t · 21.8s · 73,490 tok | green · 6t · 16.7s · 40,024 tok | green · 7t · 16.4s · 51,029 tok |
| gs-accessing-data-r2dbc | failing-assertion | green · 8t · 52.9s · 91,064 tok | green · 7t · 33.1s · 60,605 tok | green · 9t · 65.9s · 96,457 tok |
| gs-accessing-data-r2dbc | missing-dependency | green · 5t · 15.5s · 38,848 tok | green · 8t · 60.5s · 78,508 tok | green · 8t · 79.8s · 98,926 tok |
| gs-accessing-data-r2dbc | missing-resource | green · 8t · 48.8s · 82,276 tok | green · 10t · 152.2s · 190,740 tok | green · 10t · 159.0s · 196,171 tok |
| gs-accessing-data-rest | compile-error | green · 6t · 12.2s · 45,744 tok | green · 6t · 16.3s · 42,666 tok | green · 6t · 15.7s · 41,624 tok |
| gs-accessing-data-rest | failing-assertion | green · 9t · 78.1s · 113,894 tok | green · 8t · 56.9s · 82,007 tok | green · 8t · 35.9s · 68,706 tok |
| gs-accessing-data-rest | missing-dependency | green · 5t · 11.2s · 35,927 tok | green · 12t · 55.0s · 171,597 tok | green · 7t · 38.8s · 66,042 tok |
| gs-actuator-service | compile-error | green · 6t · 13.1s · 41,592 tok | green · 7t · 16.7s · 47,314 tok | green · 7t · 17.6s · 50,258 tok |
| gs-actuator-service | failing-assertion | green · 7t · 25.6s · 60,830 tok | green · 6t · 22.0s · 47,400 tok | green · 7t · 28.3s · 59,347 tok |
| gs-actuator-service | missing-dependency | green · 7t · 26.7s · 60,659 tok | green · 10t · 42.7s · 124,912 tok | green · 8t · 33.4s · 69,024 tok |
| gs-batch-processing | compile-error | green · 6t · 15.0s · 46,261 tok | green · 6t · 14.1s · 41,970 tok | green · 7t · 15.9s · 52,549 tok |
| gs-batch-processing | failing-assertion | green · 7t · 27.2s · 63,060 tok | green · 8t · 31.9s · 71,552 tok | green · 8t · 31.8s · 70,835 tok |
| gs-batch-processing | missing-dependency | green · 5t · 13.2s · 36,979 tok | green · 9t · 75.2s · 114,788 tok | green · 8t · 82.4s · 104,387 tok |
| gs-batch-processing | missing-resource | green · 7t · 28.3s · 60,922 tok | green · 7t · 25.1s · 83,039 tok | green · 7t · 23.8s · 82,330 tok |
| gs-consuming-rest | compile-error | green · 7t · 17.4s · 55,366 tok | green · 6t · 17.6s · 42,582 tok | green · 7t · 15.8s · 49,821 tok |
| gs-consuming-rest | missing-dependency | green · 5t · 11.4s · 34,648 tok | green · 10t · 64.8s · 145,206 tok | green · 14t · 83.0s · 230,950 tok |
| gs-graphql-server | compile-error | green · 6t · 19.6s · 44,719 tok | · | green · 7t · 17.7s · 50,696 tok |
| gs-graphql-server | failing-assertion | green · 8t · 22.9s · 73,932 tok | · | green · 8t · 23.6s · 63,323 tok |
| gs-graphql-server | missing-dependency | green · 5t · 10.0s · 35,645 tok | · | green · 7t · 22.2s · 53,972 tok |
| gs-graphql-server | missing-resource | green · 8t · 31.7s · 80,053 tok | · | green · 9t · 37.7s · 80,042 tok |
| gs-handling-form-submission | compile-error | green · 6t · 14.0s · 42,794 tok | · | green · 6t · 14.4s · 39,708 tok |
| gs-handling-form-submission | failing-assertion | green · 8t · 29.8s · 80,436 tok | · | green · 7t · 22.2s · 58,524 tok |
| gs-handling-form-submission | missing-dependency | green · 5t · 12.0s · 36,124 tok | · | green · 7t · 21.9s · 56,659 tok |
| gs-handling-form-submission | missing-resource | green · 7t · 28.6s · 80,174 tok | · | green · 7t · 24.3s · 62,476 tok |
| gs-reactive-rest-service | compile-error | green · 6t · 13.1s · 40,830 tok | green · 7t · 20.4s · 50,571 tok | green · 6t · 17.5s · 39,812 tok |
| gs-reactive-rest-service | failing-assertion | green · 8t · 27.6s · 73,251 tok | green · 8t · 47.8s · 72,738 tok | green · 6t · 23.0s · 42,353 tok |
| gs-rest-hateoas | compile-error | green · 6t · 14.0s · 43,373 tok | green · 7t · 16.5s · 43,804 tok | green · 7t · 15.4s · 49,300 tok |
| gs-rest-hateoas | failing-assertion | green · 9t · 34.1s · 80,301 tok | green · 9t · 48.9s · 80,933 tok | green · 9t · 54.3s · 85,894 tok |
| gs-rest-hateoas | missing-dependency | green · 13t · 87.4s · 205,598 tok | green · 8t · 40.5s · 70,530 tok | green · 8t · 31.2s · 76,926 tok |
| gs-rest-service | compile-error | green · 6t · 13.3s · 45,425 tok | green · 6t · 14.3s · 38,335 tok | green · 6t · 14.1s · 41,685 tok |
| gs-rest-service | failing-assertion | green · 9t · 42.0s · 79,822 tok | green · 8t · 29.8s · 66,302 tok | green · 8t · 28.0s · 66,811 tok |
| gs-rest-service | missing-dependency | green · 5t · 13.2s · 37,292 tok | green · 9t · 39.9s · 79,690 tok | green · 7t · 25.7s · 57,588 tok |
| gs-rest-service | version-conflict | green · 5t · 18.1s · 39,738 tok | green · 8t · 30.2s · 68,984 tok | green · 7t · 31.4s · 61,068 tok |
| gs-scheduling-tasks | compile-error | green · 6t · 16.4s · 41,487 tok | · | green · 7t · 26.8s · 49,702 tok |
| gs-scheduling-tasks | failing-assertion | green · 8t · 28.4s · 71,734 tok | · | green · 7t · 41.2s · 62,285 tok |
| gs-scheduling-tasks | missing-dependency | green · 7t · 19.3s · 58,199 tok | · | green · 10t · 51.2s · 105,479 tok |
| gs-securing-web | compile-error | green · 6t · 12.4s · 44,783 tok | · | green · 6t · 18.8s · 40,042 tok |
| gs-securing-web | failing-assertion | green · 9t · 55.2s · 100,771 tok | · | green · 8t · 30.0s · 70,427 tok |
| gs-securing-web | missing-dependency | green · 6t · 19.2s · 41,746 tok | · | green · 7t · 36.7s · 62,346 tok |
| gs-securing-web | missing-resource | green · 7t · 29.4s · 83,888 tok | · | green · 7t · 28.7s · 65,055 tok |
| gs-serving-web-content | compile-error | green · 6t · 20.7s · 44,559 tok | · | green · 6t · 15.8s · 40,581 tok |
| gs-serving-web-content | failing-assertion | green · 7t · 27.6s · 64,950 tok | · | green · 7t · 33.6s · 59,105 tok |
| gs-serving-web-content | missing-resource | green · 7t · 22.7s · 59,695 tok | · | green · 7t · 24.9s · 64,338 tok |
| gs-spring-boot | compile-error | green · 6t · 11.1s · 43,149 tok | green · 7t · 16.9s · 50,497 tok | green · 6t · 15.0s · 37,816 tok |
| gs-spring-boot | failing-assertion | green · 8t · 62.5s · 85,122 tok | green · 8t · 47.3s · 75,963 tok | green · 14t · 121.2s · 236,653 tok |
| gs-spring-boot | missing-dependency | green · 5t · 15.1s · 39,332 tok | green · 9t · 56.5s · 82,526 tok | green · 8t · 37.2s · 69,955 tok |
| gs-spring-boot | version-conflict | green · 5t · 17.4s · 37,063 tok | green · 8t · 26.4s · 69,099 tok | green · 7t · 30.7s · 65,027 tok |
| gs-testing-web | compile-error | green · 6t · 13.8s · 45,393 tok | green · 7t · 16.8s · 47,730 tok | green · 6t · 15.3s · 39,192 tok |
| gs-testing-web | failing-assertion | green · 7t · 32.5s · 58,044 tok | green · 7t · 31.4s · 54,650 tok | green · 7t · 33.7s · 60,781 tok |
| gs-uploading-files | compile-error | green · 6t · 11.0s · 38,667 tok | green · 6t · 18.0s · 40,424 tok | green · 6t · 16.0s · 40,626 tok |
| gs-uploading-files | failing-assertion | green · 8t · 24.2s · 70,183 tok | green · 10t · 61.0s · 111,872 tok | green · 9t · 43.2s · 79,277 tok |
| gs-uploading-files | missing-dependency | green · 5t · 12.8s · 36,492 tok | green · 9t · 42.3s · 99,171 tok | green · 7t · 30.1s · 66,035 tok |
| gs-uploading-files | missing-resource | green · 9t · 50.8s · 103,954 tok | · | green · 8t · 31.4s · 75,837 tok |
| gs-validating-form-input | compile-error | green · 6t · 12.7s · 41,332 tok | green · 7t · 19.9s · 53,354 tok | green · 7t · 15.8s · 50,731 tok |
| gs-validating-form-input | missing-dependency | green · 5t · 11.4s · 35,229 tok | green · 8t · 32.5s · 68,526 tok | green · 8t · 20.4s · 64,989 tok |
| gs-validating-form-input | missing-resource | green · 7t · 32.4s · 67,200 tok | · | green · 7t · 28.4s · 71,817 tok |
| junit-starter-gradle | compile-error | green · 6t · 13.5s · 41,162 tok | · | green · 7t · 13.5s · 47,423 tok |
| junit-starter-gradle | failing-assertion | green · 7t · 21.7s · 55,685 tok | · | green · 8t · 22.5s · 58,424 tok |
| junit-starter-gradle | missing-dependency | green · 5t · 13.0s · 35,040 tok | · | green · 8t · 23.3s · 61,438 tok |
| junit-starter-gradle | version-conflict | green · 6t · 18.9s · 46,394 tok | · | green · 8t · 53.8s · 75,463 tok |
