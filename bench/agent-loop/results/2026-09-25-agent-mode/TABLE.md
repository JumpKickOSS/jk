# Agent loop: turns, tokens and wall to green

Date: 2026-09-25 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: grok · 165 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `grok` · grok-4.7 · effort high · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 63 | 100% | 6 | 8 | 47,953 | 83,194 | 21.4s | 35.1s | $1.52 | 4 | 2 | 1,608 | 20,087 | 30,720 | $0.0242 | 34 exact · 24 equivalent · 5 collateral · 0 cheat |
| mvn | 39 | 39 | 100% | 7 | 10 | 66,460 | 118,769 | 29.5s | 81.7s | $1.15 | 5 | 4 | 2,167 | 19,969 | 42,112 | $0.0295 | 30 exact · 7 equivalent · 2 collateral · 0 cheat |
| gradle | 63 | 63 | 100% | 7 | 9 | 64,078 | 86,954 | 29.3s | 55.6s | $1.81 | 5 | 4 | 2,008 | 22,966 | 36,736 | $0.0287 | 46 exact · 11 equivalent · 6 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 6t · 12.7s · 45,263 tok | green · 6t · 16.1s · 43,531 tok | green · 8t · 35.3s · 64,018 tok |
| gs-accessing-data-jpa | failing-assertion | green · 8t · 33.8s · 83,194 tok | green · 7t · 42.0s · 66,460 tok | green · 8t · 25.9s · 64,904 tok |
| gs-accessing-data-jpa | missing-dependency | green · 6t · 15.8s · 46,952 tok | green · 8t · 36.0s · 81,657 tok | green · 8t · 37.7s · 79,870 tok |
| gs-accessing-data-r2dbc | compile-error | green · 6t · 15.6s · 47,812 tok | green · 7t · 18.8s · 52,411 tok | green · 6t · 22.1s · 44,902 tok |
| gs-accessing-data-r2dbc | failing-assertion | green · 8t · 72.0s · 104,817 tok | green · 7t · 41.6s · 68,367 tok | green · 7t · 88.5s · 78,234 tok |
| gs-accessing-data-r2dbc | missing-dependency | green · 7t · 45.8s · 75,004 tok | green · 9t · 49.3s · 98,941 tok | green · 7t · 55.6s · 78,833 tok |
| gs-accessing-data-r2dbc | missing-resource | green · 9t · 117.0s · 132,947 tok | green · 10t · 112.7s · 180,609 tok | green · 12t · 175.9s · 260,988 tok |
| gs-accessing-data-rest | compile-error | green · 7t · 18.3s · 61,481 tok | green · 6t · 17.3s · 42,169 tok | green · 6t · 23.2s · 43,769 tok |
| gs-accessing-data-rest | failing-assertion | green · 8t · 71.5s · 92,900 tok | green · 12t · 103.3s · 155,734 tok | green · 13t · 95.0s · 166,695 tok |
| gs-accessing-data-rest | missing-dependency | green · 6t · 18.5s · 47,329 tok | green · 7t · 53.8s · 69,326 tok | green · 8t · 28.8s · 70,819 tok |
| gs-actuator-service | compile-error | green · 6t · 16.3s · 43,400 tok | green · 6t · 17.7s · 42,956 tok | green · 6t · 21.4s · 45,547 tok |
| gs-actuator-service | failing-assertion | green · 7t · 22.9s · 72,369 tok | green · 8t · 27.9s · 67,563 tok | green · 7t · 30.3s · 60,595 tok |
| gs-actuator-service | missing-dependency | green · 7t · 26.4s · 63,460 tok | green · 8t · 46.8s · 72,271 tok | green · 8t · 39.0s · 70,823 tok |
| gs-batch-processing | compile-error | green · 6t · 13.1s · 46,688 tok | green · 7t · 17.5s · 48,502 tok | green · 7t · 15.9s · 50,209 tok |
| gs-batch-processing | failing-assertion | green · 7t · 28.9s · 78,406 tok | green · 7t · 29.4s · 59,849 tok | green · 8t · 24.2s · 67,532 tok |
| gs-batch-processing | missing-dependency | green · 5t · 18.6s · 41,614 tok | green · 15t · 130.3s · 277,040 tok | green · 9t · 95.4s · 122,028 tok |
| gs-batch-processing | missing-resource | green · 8t · 33.5s · 79,122 tok | green · 9t · 29.5s · 109,960 tok | green · 8t · 31.0s · 100,365 tok |
| gs-consuming-rest | compile-error | green · 6t · 12.3s · 46,928 tok | green · 7t · 24.5s · 50,942 tok | green · 7t · 14.5s · 43,338 tok |
| gs-consuming-rest | missing-dependency | green · 5t · 12.7s · 37,665 tok | green · 7t · 32.7s · 65,089 tok | green · 7t · 42.9s · 68,427 tok |
| gs-graphql-server | compile-error | green · 6t · 12.7s · 45,877 tok | · | green · 7t · 16.6s · 49,968 tok |
| gs-graphql-server | failing-assertion | green · 8t · 27.5s · 75,145 tok | · | green · 7t · 35.3s · 54,637 tok |
| gs-graphql-server | missing-dependency | green · 5t · 12.8s · 36,812 tok | · | green · 7t · 30.2s · 59,751 tok |
| gs-graphql-server | missing-resource | green · 7t · 30.6s · 60,087 tok | · | green · 8t · 41.9s · 75,477 tok |
| gs-handling-form-submission | compile-error | green · 6t · 16.4s · 43,261 tok | · | green · 6t · 13.8s · 41,957 tok |
| gs-handling-form-submission | failing-assertion | green · 7t · 35.1s · 71,479 tok | · | green · 7t · 27.4s · 62,053 tok |
| gs-handling-form-submission | missing-dependency | green · 5t · 15.2s · 36,456 tok | · | green · 8t · 33.9s · 66,899 tok |
| gs-handling-form-submission | missing-resource | green · 7t · 26.7s · 84,712 tok | · | green · 8t · 27.3s · 70,145 tok |
| gs-reactive-rest-service | compile-error | green · 6t · 11.9s · 43,504 tok | green · 7t · 18.5s · 50,568 tok | green · 6t · 17.2s · 40,270 tok |
| gs-reactive-rest-service | failing-assertion | green · 8t · 27.5s · 73,285 tok | green · 6t · 20.7s · 43,490 tok | green · 7t · 20.8s · 52,664 tok |
| gs-rest-hateoas | compile-error | green · 6t · 11.8s · 45,210 tok | green · 6t · 15.0s · 39,886 tok | green · 6t · 19.2s · 39,868 tok |
| gs-rest-hateoas | failing-assertion | green · 7t · 29.0s · 61,586 tok | green · 10t · 82.4s · 118,769 tok | green · 9t · 52.9s · 86,015 tok |
| gs-rest-hateoas | missing-dependency | green · 5t · 16.5s · 39,795 tok | green · 8t · 27.7s · 73,562 tok | green · 8t · 29.2s · 76,254 tok |
| gs-rest-service | compile-error | green · 6t · 10.9s · 43,024 tok | green · 6t · 14.9s · 42,347 tok | green · 6t · 14.4s · 40,844 tok |
| gs-rest-service | failing-assertion | green · 8t · 64.7s · 74,659 tok | green · 8t · 33.8s · 68,342 tok | green · 8t · 45.6s · 69,598 tok |
| gs-rest-service | missing-dependency | green · 5t · 14.0s · 39,887 tok | green · 9t · 40.5s · 87,340 tok | green · 8t · 25.4s · 67,591 tok |
| gs-rest-service | version-conflict | green · 6t · 28.4s · 51,786 tok | green · 7t · 30.9s · 60,751 tok | green · 7t · 23.9s · 58,690 tok |
| gs-scheduling-tasks | compile-error | green · 6t · 22.7s · 46,112 tok | · | green · 6t · 22.4s · 42,194 tok |
| gs-scheduling-tasks | failing-assertion | green · 7t · 22.7s · 72,729 tok | · | green · 7t · 32.8s · 61,787 tok |
| gs-scheduling-tasks | missing-dependency | green · 7t · 30.8s · 61,852 tok | · | green · 8t · 42.1s · 77,590 tok |
| gs-securing-web | compile-error | green · 6t · 14.0s · 46,061 tok | · | green · 7t · 16.0s · 44,974 tok |
| gs-securing-web | failing-assertion | green · 9t · 62.4s · 99,457 tok | · | green · 8t · 39.6s · 74,381 tok |
| gs-securing-web | missing-dependency | green · 5t · 12.8s · 36,943 tok | · | green · 8t · 34.5s · 71,057 tok |
| gs-securing-web | missing-resource | green · 7t · 27.9s · 59,169 tok | · | green · 7t · 25.3s · 60,992 tok |
| gs-serving-web-content | compile-error | green · 6t · 13.4s · 41,852 tok | · | green · 6t · 29.9s · 39,751 tok |
| gs-serving-web-content | failing-assertion | green · 8t · 31.2s · 78,249 tok | · | green · 8t · 25.6s · 67,750 tok |
| gs-serving-web-content | missing-resource | green · 7t · 23.8s · 58,567 tok | · | green · 7t · 21.5s · 61,512 tok |
| gs-spring-boot | compile-error | green · 6t · 10.8s · 43,513 tok | green · 6t · 18.1s · 45,132 tok | green · 7t · 16.5s · 49,932 tok |
| gs-spring-boot | failing-assertion | green · 7t · 31.2s · 60,616 tok | green · 9t · 81.7s · 89,172 tok | green · 9t · 56.2s · 84,825 tok |
| gs-spring-boot | missing-dependency | green · 6t · 20.7s · 46,802 tok | green · 8t · 29.5s · 67,267 tok | green · 10t · 43.3s · 119,638 tok |
| gs-spring-boot | version-conflict | green · 6t · 21.4s · 47,953 tok | green · 8t · 26.6s · 67,540 tok | green · 7t · 33.2s · 64,078 tok |
| gs-testing-web | compile-error | green · 6t · 13.6s · 40,963 tok | green · 7t · 22.0s · 50,186 tok | green · 6t · 16.1s · 39,672 tok |
| gs-testing-web | failing-assertion | green · 7t · 26.2s · 58,895 tok | green · 7t · 42.2s · 62,121 tok | green · 7t · 36.2s · 60,572 tok |
| gs-uploading-files | compile-error | green · 6t · 11.4s · 41,549 tok | green · 6t · 16.7s · 39,165 tok | green · 7t · 17.5s · 50,446 tok |
| gs-uploading-files | failing-assertion | green · 8t · 25.9s · 71,602 tok | green · 10t · 73.1s · 123,146 tok | green · 9t · 45.8s · 87,649 tok |
| gs-uploading-files | missing-dependency | green · 5t · 16.6s · 40,449 tok | green · 7t · 60.4s · 68,900 tok | green · 8t · 29.5s · 67,707 tok |
| gs-uploading-files | missing-resource | green · 8t · 33.5s · 86,466 tok | · | green · 7t · 40.7s · 75,219 tok |
| gs-validating-form-input | compile-error | green · 6t · 11.3s · 43,442 tok | green · 6t · 15.7s · 42,220 tok | green · 7t · 14.4s · 49,538 tok |
| gs-validating-form-input | missing-dependency | green · 5t · 12.6s · 37,018 tok | green · 8t · 22.1s · 64,262 tok | green · 8t · 24.3s · 60,889 tok |
| gs-validating-form-input | missing-resource | green · 7t · 32.7s · 67,555 tok | · | green · 7t · 43.2s · 76,240 tok |
| junit-starter-gradle | compile-error | green · 6t · 16.8s · 43,326 tok | · | green · 6t · 11.7s · 37,700 tok |
| junit-starter-gradle | failing-assertion | green · 6t · 22.1s · 57,214 tok | · | green · 7t · 22.4s · 52,704 tok |
| junit-starter-gradle | missing-dependency | green · 5t · 16.7s · 43,454 tok | · | green · 8t · 29.3s · 69,152 tok |
| junit-starter-gradle | version-conflict | green · 7t · 28.8s · 67,812 tok | · | green · 9t · 70.7s · 86,954 tok |
