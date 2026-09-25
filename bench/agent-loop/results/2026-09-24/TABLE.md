# Agent loop: turns, tokens and wall to green

Date: 2026-09-24 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: grok, scripted · 330 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `grok` · grok-4.7 · effort high · budget 8 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 41 | 65% | 8 | 8 | 87,814 | 148,476 | 28.2s | 53.5s | $2.45 | 5 | 4 | 2,496 | 29,724 | 54,016 | $0.0296 | 44 exact · 13 equivalent · 5 collateral · 0 cheat |
| mvn | 39 | 35 | 90% | 7 | 8 | 66,094 | 85,630 | 31.4s | 65.0s | $1.10 | 5 | 5 | 2,413 | 26,042 | 34,176 | $0.0260 | 26 exact · 7 equivalent · 4 collateral · 0 cheat |
| gradle | 63 | 58 | 92% | 7 | 8 | 63,330 | 83,472 | 29.2s | 59.2s | $1.67 | 5 | 5 | 2,397 | 24,172 | 36,224 | $0.0253 | 43 exact · 12 equivalent · 5 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 7t · 17.0s · 63,930 tok | green · 7t · 17.0s · 51,550 tok | green · 7t · 16.7s · 50,731 tok |
| gs-accessing-data-jpa | failing-assertion | green · 7t · 23.0s · 67,673 tok | **red** · 8t · 78.4s · 74,042 tok | green · 7t · 41.2s · 61,971 tok |
| gs-accessing-data-jpa | missing-dependency | **red** · 8t · 37.8s · 148,476 tok | green · 8t · 38.8s · 78,176 tok | green · 7t · 30.6s · 68,189 tok |
| gs-accessing-data-r2dbc | compile-error | green · 7t · 23.0s · 70,087 tok | green · 7t · 18.7s · 52,401 tok | green · 7t · 16.4s · 51,055 tok |
| gs-accessing-data-r2dbc | failing-assertion | green · 8t · 32.4s · 86,536 tok | green · 8t · 65.0s · 85,803 tok | green · 7t · 38.8s · 66,243 tok |
| gs-accessing-data-r2dbc | missing-dependency | **red** · 8t · 44.1s · 153,244 tok | green · 8t · 63.4s · 92,337 tok | green · 8t · 61.1s · 119,501 tok |
| gs-accessing-data-r2dbc | missing-resource | **red** · 8t · 112.2s · 124,306 tok | **red** · 8t · 90.3s · 107,729 tok | **red** · 8t · 115.9s · 129,189 tok |
| gs-accessing-data-rest | compile-error | green · 7t · 19.4s · 66,667 tok | green · 6t · 16.8s · 42,536 tok | green · 6t · 15.7s · 41,862 tok |
| gs-accessing-data-rest | failing-assertion | green · 8t · 29.1s · 90,362 tok | green · 8t · 50.1s · 75,483 tok | **red** · 8t · 65.1s · 86,442 tok |
| gs-accessing-data-rest | missing-dependency | **red** · 8t · 66.5s · 147,769 tok | green · 8t · 31.4s · 67,013 tok | green · 8t · 46.3s · 73,503 tok |
| gs-actuator-service | compile-error | green · 7t · 13.8s · 64,051 tok | green · 7t · 17.3s · 50,153 tok | green · 6t · 15.0s · 41,604 tok |
| gs-actuator-service | failing-assertion | green · 7t · 20.2s · 75,322 tok | green · 7t · 32.7s · 60,654 tok | green · 8t · 27.8s · 66,378 tok |
| gs-actuator-service | missing-dependency | **red** · 8t · 43.5s · 132,535 tok | green · 8t · 36.7s · 69,041 tok | green · 7t · 36.6s · 62,495 tok |
| gs-batch-processing | compile-error | green · 7t · 13.6s · 62,563 tok | green · 6t · 15.3s · 42,870 tok | green · 7t · 15.2s · 51,754 tok |
| gs-batch-processing | failing-assertion | green · 7t · 25.1s · 73,920 tok | green · 8t · 25.4s · 68,921 tok | green · 6t · 24.9s · 49,128 tok |
| gs-batch-processing | missing-dependency | **red** · 8t · 53.5s · 150,562 tok | **red** · 8t · 37.2s · 99,356 tok | green · 7t · 33.7s · 75,575 tok |
| gs-batch-processing | missing-resource | green · 8t · 25.8s · 95,565 tok | green · 7t · 24.4s · 83,046 tok | green · 8t · 50.4s · 108,879 tok |
| gs-consuming-rest | compile-error | green · 7t · 15.5s · 66,028 tok | green · 6t · 14.7s · 43,337 tok | green · 6t · 14.2s · 42,143 tok |
| gs-consuming-rest | missing-dependency | **red** · 8t · 32.4s · 140,792 tok | green · 6t · 78.6s · 75,864 tok | green · 8t · 55.5s · 84,967 tok |
| gs-graphql-server | compile-error | green · 7t · 15.3s · 64,106 tok | · | green · 6t · 17.7s · 39,671 tok |
| gs-graphql-server | failing-assertion | green · 8t · 24.8s · 76,456 tok | · | green · 8t · 46.7s · 67,729 tok |
| gs-graphql-server | missing-dependency | **red** · 8t · 41.5s · 135,723 tok | · | green · 7t · 29.2s · 59,227 tok |
| gs-graphql-server | missing-resource | **red** · 8t · 32.8s · 89,932 tok | · | green · 8t · 47.5s · 81,621 tok |
| gs-handling-form-submission | compile-error | green · 7t · 14.0s · 66,120 tok | · | green · 7t · 13.8s · 45,340 tok |
| gs-handling-form-submission | failing-assertion | green · 8t · 46.9s · 85,553 tok | · | green · 7t · 26.2s · 60,533 tok |
| gs-handling-form-submission | missing-dependency | **red** · 8t · 59.4s · 143,396 tok | · | **red** · 8t · 48.7s · 71,622 tok |
| gs-handling-form-submission | missing-resource | green · 8t · 31.5s · 94,724 tok | · | green · 7t · 21.8s · 61,914 tok |
| gs-reactive-rest-service | compile-error | green · 7t · 14.5s · 66,836 tok | green · 6t · 19.9s · 44,831 tok | green · 6t · 20.7s · 43,550 tok |
| gs-reactive-rest-service | failing-assertion | green · 7t · 19.2s · 64,311 tok | green · 7t · 18.5s · 48,864 tok | green · 6t · 17.9s · 43,382 tok |
| gs-rest-hateoas | compile-error | green · 7t · 13.7s · 62,824 tok | green · 6t · 18.9s · 45,199 tok | green · 6t · 14.0s · 41,788 tok |
| gs-rest-hateoas | failing-assertion | green · 8t · 31.5s · 88,773 tok | green · 8t · 47.5s · 74,621 tok | **red** · 8t · 67.3s · 81,675 tok |
| gs-rest-hateoas | missing-dependency | **red** · 8t · 41.1s · 160,970 tok | green · 8t · 27.9s · 74,504 tok | green · 7t · 32.0s · 70,562 tok |
| gs-rest-service | compile-error | green · 7t · 16.1s · 63,370 tok | green · 6t · 15.4s · 42,096 tok | green · 6t · 14.4s · 41,689 tok |
| gs-rest-service | failing-assertion | **red** · 8t · 69.4s · 97,028 tok | green · 8t · 33.8s · 65,768 tok | **red** · 8t · 73.1s · 73,311 tok |
| gs-rest-service | missing-dependency | **red** · 8t · 41.1s · 170,180 tok | green · 8t · 33.0s · 69,527 tok | green · 8t · 30.7s · 69,070 tok |
| gs-rest-service | version-conflict | **red** · 8t · 34.0s · 144,401 tok | green · 7t · 29.6s · 60,815 tok | green · 8t · 41.9s · 72,831 tok |
| gs-scheduling-tasks | compile-error | green · 7t · 17.1s · 64,430 tok | · | green · 6t · 19.2s · 40,241 tok |
| gs-scheduling-tasks | failing-assertion | green · 8t · 30.8s · 86,132 tok | · | green · 7t · 33.6s · 60,989 tok |
| gs-scheduling-tasks | missing-dependency | **red** · 8t · 29.9s · 138,617 tok | · | green · 8t · 38.6s · 75,473 tok |
| gs-securing-web | compile-error | green · 7t · 13.2s · 62,828 tok | · | green · 6t · 14.3s · 41,726 tok |
| gs-securing-web | failing-assertion | green · 7t · 20.3s · 71,452 tok | · | green · 8t · 59.2s · 83,472 tok |
| gs-securing-web | missing-dependency | **red** · 8t · 53.3s · 135,054 tok | · | green · 7t · 35.1s · 63,539 tok |
| gs-securing-web | missing-resource | green · 8t · 24.1s · 93,118 tok | · | green · 7t · 22.1s · 61,961 tok |
| gs-serving-web-content | compile-error | green · 7t · 14.0s · 64,109 tok | · | green · 6t · 13.6s · 41,697 tok |
| gs-serving-web-content | failing-assertion | green · 8t · 38.0s · 85,928 tok | · | green · 8t · 22.5s · 67,447 tok |
| gs-serving-web-content | missing-resource | green · 8t · 26.9s · 109,098 tok | · | green · 8t · 24.1s · 73,500 tok |
| gs-spring-boot | compile-error | green · 7t · 12.6s · 62,819 tok | green · 6t · 16.0s · 42,296 tok | green · 6t · 13.8s · 41,659 tok |
| gs-spring-boot | failing-assertion | green · 8t · 36.2s · 87,814 tok | green · 8t · 83.0s · 78,536 tok | green · 8t · 84.9s · 79,558 tok |
| gs-spring-boot | missing-dependency | **red** · 8t · 86.7s · 126,910 tok | **red** · 8t · 37.7s · 82,134 tok | green · 7t · 34.5s · 60,564 tok |
| gs-spring-boot | version-conflict | **red** · 8t · 46.6s · 118,403 tok | green · 7t · 33.1s · 63,920 tok | green · 8t · 34.5s · 74,947 tok |
| gs-testing-web | compile-error | green · 7t · 13.7s · 62,665 tok | green · 6t · 16.1s · 42,493 tok | green · 7t · 15.6s · 49,412 tok |
| gs-testing-web | failing-assertion | green · 7t · 22.8s · 70,310 tok | green · 8t · 31.6s · 66,094 tok | green · 8t · 27.4s · 63,330 tok |
| gs-uploading-files | compile-error | green · 7t · 13.3s · 64,325 tok | green · 7t · 17.8s · 50,819 tok | green · 6t · 15.1s · 41,944 tok |
| gs-uploading-files | failing-assertion | **red** · 8t · 26.5s · 89,279 tok | green · 8t · 39.6s · 73,297 tok | green · 8t · 55.9s · 78,064 tok |
| gs-uploading-files | missing-dependency | **red** · 8t · 52.2s · 135,774 tok | green · 8t · 36.8s · 85,630 tok | green · 8t · 35.6s · 75,028 tok |
| gs-uploading-files | missing-resource | green · 8t · 28.2s · 96,840 tok | · | green · 8t · 36.4s · 82,663 tok |
| gs-validating-form-input | compile-error | green · 7t · 13.3s · 60,284 tok | green · 6t · 15.2s · 42,398 tok | green · 7t · 15.8s · 50,655 tok |
| gs-validating-form-input | missing-dependency | **red** · 8t · 36.7s · 141,238 tok | green · 6t · 21.7s · 51,001 tok | green · 8t · 20.2s · 65,599 tok |
| gs-validating-form-input | missing-resource | green · 8t · 39.5s · 114,043 tok | · | green · 8t · 42.5s · 92,093 tok |
| junit-starter-gradle | compile-error | green · 7t · 13.4s · 62,197 tok | · | green · 7t · 12.7s · 49,368 tok |
| junit-starter-gradle | failing-assertion | green · 8t · 26.3s · 83,514 tok | · | green · 6t · 20.9s · 45,222 tok |
| junit-starter-gradle | missing-dependency | green · 8t · 29.3s · 156,478 tok | · | green · 7t · 22.7s · 60,736 tok |
| junit-starter-gradle | version-conflict | **red** · 8t · 68.7s · 151,446 tok | · | green · 8t · 37.7s · 68,593 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-accessing-data-jpa | failing-assertion | mvn | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-accessing-data-jpa | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-accessing-data-r2dbc | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-accessing-data-r2dbc | missing-resource | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-accessing-data-r2dbc | missing-resource | mvn | red | — | agent stopped (error_max_turns) |
| gs-accessing-data-r2dbc | missing-resource | gradle | red | — | agent stopped (error_max_turns) |
| gs-accessing-data-rest | failing-assertion | mvn | green | — | agent stopped (error_max_turns) |
| gs-accessing-data-rest | failing-assertion | gradle | red | — | agent stopped (error_max_turns) |
| gs-accessing-data-rest | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-actuator-service | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-actuator-service | missing-dependency | mvn | green | — | agent stopped (error_max_turns) |
| gs-batch-processing | failing-assertion | mvn | green | — | agent stopped (error_max_turns) |
| gs-batch-processing | missing-dependency | jk | red | — | agent stopped (error_max_turns) |
| gs-batch-processing | missing-dependency | mvn | red | — | agent stopped (error_max_turns) |
| gs-consuming-rest | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-graphql-server | failing-assertion | gradle | green | — | agent stopped (error_max_turns) |
| gs-graphql-server | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-graphql-server | missing-resource | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-handling-form-submission | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-handling-form-submission | missing-dependency | gradle | red | — | agent stopped (error_max_turns) |
| gs-rest-hateoas | failing-assertion | gradle | red | — | agent stopped (error_max_turns) |
| gs-rest-hateoas | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-rest-service | failing-assertion | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-rest-service | failing-assertion | gradle | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-rest-service | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-rest-service | version-conflict | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-scheduling-tasks | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-securing-web | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-spring-boot | failing-assertion | mvn | green | — | agent stopped (error_max_turns) |
| gs-spring-boot | failing-assertion | gradle | green | — | agent stopped (error_max_turns) |
| gs-spring-boot | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-spring-boot | missing-dependency | mvn | red | — | agent stopped (error_max_turns) |
| gs-spring-boot | version-conflict | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-uploading-files | failing-assertion | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-uploading-files | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| gs-validating-form-input | missing-dependency | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| junit-starter-gradle | version-conflict | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |

## `scripted` · budget 8 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 62 | 98% | 1 | 1 | 0 | 0 | 0.3s | 0.4s | — | 1 | 1 | 0 | 0 | 0 | $0 | 45 exact · 18 equivalent · 0 collateral · 0 cheat |
| mvn | 39 | 39 | 100% | 1 | 1 | 0 | 0 | 6.0s | 7.5s | — | 1 | 1 | 0 | 0 | 0 | $0 | 30 exact · 9 equivalent · 0 collateral · 0 cheat |
| gradle | 63 | 63 | 100% | 1 | 1 | 0 | 0 | 4.5s | 6.1s | — | 1 | 1 | 0 | 0 | 0 | $0 | 53 exact · 10 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 1t · 2.0s | green · 1t · 11.5s | green · 1t · 7.0s |
| gs-accessing-data-jpa | failing-assertion | green · 1t · 0.4s | green · 1t · 6.2s | green · 1t · 4.7s |
| gs-accessing-data-jpa | missing-dependency | green · 1t · 1.0s | green · 1t · 6.3s | green · 1t · 5.0s |
| gs-accessing-data-r2dbc | compile-error | green · 1t · 0.3s | green · 1t · 5.6s | green · 1t · 4.2s |
| gs-accessing-data-r2dbc | failing-assertion | green · 1t · 0.3s | green · 1t · 5.1s | green · 1t · 3.6s |
| gs-accessing-data-r2dbc | missing-dependency | green · 1t · 0.3s | green · 1t · 5.4s | green · 1t · 3.8s |
| gs-accessing-data-r2dbc | missing-resource | green · 1t · 0.3s | green · 1t · 4.5s | green · 1t · 3.4s |
| gs-accessing-data-rest | compile-error | green · 1t · 0.3s | green · 1t · 7.2s | green · 1t · 6.0s |
| gs-accessing-data-rest | failing-assertion | green · 1t · 0.4s | green · 1t · 7.7s | green · 1t · 5.9s |
| gs-accessing-data-rest | missing-dependency | green · 1t · 0.3s | green · 1t · 7.6s | green · 1t · 6.2s |
| gs-actuator-service | compile-error | green · 1t · 0.3s | green · 1t · 6.6s | green · 1t · 5.1s |
| gs-actuator-service | failing-assertion | green · 1t · 0.2s | green · 1t · 6.2s | green · 1t · 4.8s |
| gs-actuator-service | missing-dependency | green · 1t · 0.3s | green · 1t · 6.4s | green · 1t · 4.7s |
| gs-batch-processing | compile-error | green · 1t · 0.3s | green · 1t · 5.0s | green · 1t · 3.6s |
| gs-batch-processing | failing-assertion | green · 1t · 0.3s | green · 1t · 4.7s | green · 1t · 3.3s |
| gs-batch-processing | missing-dependency | green · 1t · 0.3s | green · 1t · 4.8s | green · 1t · 3.4s |
| gs-batch-processing | missing-resource | green · 1t · 0.3s | green · 1t · 4.2s | green · 1t · 4.0s |
| gs-consuming-rest | compile-error | green · 1t · 0.4s | green · 1t · 4.9s | green · 1t · 3.6s |
| gs-consuming-rest | missing-dependency | green · 1t · 0.3s | green · 1t · 4.9s | green · 1t · 3.6s |
| gs-graphql-server | compile-error | green · 1t · 0.3s | · | green · 1t · 4.5s |
| gs-graphql-server | failing-assertion | green · 1t · 0.2s | · | green · 1t · 4.5s |
| gs-graphql-server | missing-dependency | green · 1t · 0.3s | · | green · 1t · 4.2s |
| gs-graphql-server | missing-resource | green · 1t · 0.3s | · | green · 1t · 4.5s |
| gs-handling-form-submission | compile-error | green · 1t · 0.6s | · | green · 1t · 3.6s |
| gs-handling-form-submission | failing-assertion | green · 1t · 0.2s | · | green · 1t · 3.4s |
| gs-handling-form-submission | missing-dependency | green · 1t · 0.3s | · | green · 1t · 3.4s |
| gs-handling-form-submission | missing-resource | green · 1t · 0.3s | · | green · 1t · 3.3s |
| gs-reactive-rest-service | compile-error | green · 1t · 0.4s | green · 1t · 7.8s | green · 1t · 6.4s |
| gs-reactive-rest-service | failing-assertion | green · 1t · 0.2s | green · 1t · 7.5s | green · 1t · 5.9s |
| gs-rest-hateoas | compile-error | green · 1t · 0.2s | green · 1t · 6.0s | green · 1t · 4.6s |
| gs-rest-hateoas | failing-assertion | green · 1t · 0.2s | green · 1t · 6.0s | green · 1t · 4.5s |
| gs-rest-hateoas | missing-dependency | green · 1t · 0.6s | green · 1t · 6.0s | green · 1t · 4.5s |
| gs-rest-service | compile-error | green · 1t · 0.4s | green · 1t · 5.2s | green · 1t · 4.0s |
| gs-rest-service | failing-assertion | green · 1t · 0.3s | green · 1t · 5.0s | green · 1t · 3.6s |
| gs-rest-service | missing-dependency | green · 1t · 0.3s | green · 1t · 5.2s | green · 1t · 3.9s |
| gs-rest-service | version-conflict | green · 1t · 0.3s | green · 1t · 4.5s | green · 1t · 3.6s |
| gs-scheduling-tasks | compile-error | green · 1t · 0.3s | · | green · 1t · 8.8s |
| gs-scheduling-tasks | failing-assertion | green · 1t · 0.3s | · | green · 1t · 8.4s |
| gs-scheduling-tasks | missing-dependency | green · 1t · 0.4s | · | green · 1t · 9.5s |
| gs-securing-web | compile-error | green · 1t · 0.4s | · | green · 1t · 4.4s |
| gs-securing-web | failing-assertion | green · 1t · 0.4s | · | green · 1t · 4.6s |
| gs-securing-web | missing-dependency | green · 1t · 0.5s | · | green · 1t · 4.4s |
| gs-securing-web | missing-resource | green · 1t · 0.4s | · | green · 1t · 4.5s |
| gs-serving-web-content | compile-error | green · 1t · 0.4s | · | green · 1t · 4.4s |
| gs-serving-web-content | failing-assertion | green · 1t · 0.3s | · | green · 1t · 3.4s |
| gs-serving-web-content | missing-resource | green · 1t · 0.3s | · | green · 1t · 3.4s |
| gs-spring-boot | compile-error | green · 1t · 0.3s | green · 1t · 6.5s | green · 1t · 5.2s |
| gs-spring-boot | failing-assertion | green · 1t · 0.2s | green · 1t · 6.4s | green · 1t · 4.9s |
| gs-spring-boot | missing-dependency | green · 1t · 0.3s | green · 1t · 6.2s | green · 1t · 4.8s |
| gs-spring-boot | version-conflict | green · 1t · 0.3s | green · 1t · 5.6s | green · 1t · 5.4s |
| gs-testing-web | compile-error | green · 1t · 0.3s | green · 1t · 6.5s | green · 1t · 5.6s |
| gs-testing-web | failing-assertion | green · 1t · 0.2s | green · 1t · 7.3s | green · 1t · 5.5s |
| gs-uploading-files | compile-error | green · 1t · 0.3s | green · 1t · 7.1s | green · 1t · 6.1s |
| gs-uploading-files | failing-assertion | green · 1t · 0.2s | green · 1t · 6.6s | green · 1t · 5.2s |
| gs-uploading-files | missing-dependency | green · 1t · 0.3s | green · 1t · 6.6s | green · 1t · 5.4s |
| gs-uploading-files | missing-resource | green · 1t · 0.2s | · | green · 1t · 5.5s |
| gs-validating-form-input | compile-error | green · 1t · 0.4s | green · 1t · 5.8s | green · 1t · 3.8s |
| gs-validating-form-input | missing-dependency | green · 1t · 0.3s | green · 1t · 5.3s | green · 1t · 4.0s |
| gs-validating-form-input | missing-resource | green · 1t · 0.2s | · | green · 1t · 3.7s |
| junit-starter-gradle | compile-error | green · 1t · 0.2s | · | green · 1t · 1.9s |
| junit-starter-gradle | failing-assertion | green · 1t · 0.2s | · | green · 1t · 1.8s |
| junit-starter-gradle | missing-dependency | green · 1t · 0.3s | · | green · 1t · 1.8s |
| junit-starter-gradle | version-conflict | **red** · 2t · 0.1s | · | green · 1t · 1.8s |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-accessing-data-r2dbc | missing-resource | jk | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-accessing-data-r2dbc | missing-resource | mvn | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-accessing-data-r2dbc | missing-resource | gradle | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-accessing-data-rest | failing-assertion | jk | green | scenario | com.example.accessingdatarest.AccessingDataRestApplicationTests#shouldReturnRepositoryIndex() failed with a message that carries no expected/actual pair |
| gs-accessing-data-rest | failing-assertion | mvn | green | scenario | com.example.accessingdatarest.AccessingDataRestApplicationTests#shouldReturnRepositoryIndex failed with a message that carries no expected/actual pair |
| gs-accessing-data-rest | failing-assertion | gradle | green | scenario | com.example.accessingdatarest.AccessingDataRestApplicationTests#shouldReturnRepositoryIndex() failed with a message that carries no expected/actual pair |
| gs-batch-processing | missing-resource | jk | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-batch-processing | missing-resource | mvn | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-batch-processing | missing-resource | gradle | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| junit-starter-gradle | version-conflict | jk | red | results-heuristic | results name nothing the oracle can act on (version-conflict: engine:### lock
```
`org.junit.jupiter:junit-jupiter` is declared without a vers) |
