# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 91 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 91 | 91 | 100% | 5 | 8 | 21,850 | 34,065 | 10.8s | 14.8s | $3.77 | 4 | 1 | 477 | 8 | 19,148 | $0.0414 | 55 exact · 35 equivalent · 1 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 7.2s · 21,013 tok | · | · |
| commons-cli | failing-assertion | green · 6t · 12.6s · 25,983 tok | · | · |
| commons-cli | missing-dependency | green · 4t · 7.9s · 21,647 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 5t · 8.6s · 21,193 tok | · | · |
| gs-accessing-data-jpa | failing-assertion | green · 7t · 12.1s · 30,628 tok | · | · |
| gs-accessing-data-jpa | missing-dependency | green · 5t · 10.1s · 22,557 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 7.4s · 21,339 tok | · | · |
| gs-accessing-data-r2dbc | failing-assertion | green · 7t · 10.8s · 30,966 tok | · | · |
| gs-accessing-data-r2dbc | missing-dependency | green · 4t · 7.3s · 16,385 tok | · | · |
| gs-accessing-data-r2dbc | missing-resource | green · 9t · 14.3s · 36,777 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 7t · 12.3s · 32,628 tok | · | · |
| gs-accessing-data-rest | failing-assertion | green · 8t · 17.9s · 39,013 tok | · | · |
| gs-accessing-data-rest | missing-dependency | green · 4t · 10.8s · 15,343 tok | · | · |
| gs-actuator-service | compile-error | green · 5t · 8.1s · 20,994 tok | · | · |
| gs-actuator-service | failing-assertion | green · 5t · 10.2s · 23,255 tok | · | · |
| gs-actuator-service | missing-dependency | green · 5t · 9.3s · 21,099 tok | · | · |
| gs-batch-processing | compile-error | green · 5t · 8.9s · 21,026 tok | · | · |
| gs-batch-processing | failing-assertion | green · 5t · 10.6s · 23,456 tok | · | · |
| gs-batch-processing | missing-dependency | green · 5t · 8.8s · 28,418 tok | · | · |
| gs-batch-processing | missing-resource | green · 7t · 11.7s · 32,213 tok | · | · |
| gs-consuming-rest | compile-error | green · 6t · 10.6s · 21,850 tok | · | · |
| gs-consuming-rest | missing-dependency | green · 5t · 12.5s · 22,417 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 7.1s · 20,878 tok | · | · |
| gs-graphql-server | failing-assertion | green · 7t · 14.7s · 29,424 tok | · | · |
| gs-graphql-server | missing-dependency | green · 4t · 8.0s · 15,330 tok | · | · |
| gs-graphql-server | missing-resource | green · 8t · 11.2s · 28,243 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 7.3s · 21,119 tok | · | · |
| gs-handling-form-submission | failing-assertion | green · 8t · 13.0s · 31,644 tok | · | · |
| gs-handling-form-submission | missing-dependency | green · 4t · 8.3s · 15,375 tok | · | · |
| gs-handling-form-submission | missing-resource | green · 8t · 12.9s · 31,027 tok | · | · |
| gs-multi-module | compile-error | green · 5t · 8.6s · 21,051 tok | · | · |
| gs-multi-module | failing-assertion | green · 11t · 24.2s · 45,176 tok | · | · |
| gs-multi-module | missing-dependency | green · 7t · 12.7s · 28,265 tok | · | · |
| gs-multi-module | missing-resource | green · 8t · 11.7s · 28,940 tok | · | · |
| gs-multi-module | version-conflict | green · 8t · 15.2s · 28,570 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 5t · 8.6s · 21,043 tok | · | · |
| gs-reactive-rest-service | failing-assertion | green · 6t · 12.0s · 21,913 tok | · | · |
| gs-rest-hateoas | compile-error | green · 5t · 11.3s · 20,926 tok | · | · |
| gs-rest-hateoas | failing-assertion | green · 6t · 12.0s · 23,667 tok | · | · |
| gs-rest-hateoas | missing-dependency | green · 4t · 8.9s · 16,086 tok | · | · |
| gs-rest-service | compile-error | green · 5t · 7.7s · 20,941 tok | · | · |
| gs-rest-service | failing-assertion | green · 6t · 13.1s · 23,123 tok | · | · |
| gs-rest-service | missing-dependency | green · 5t · 10.4s · 21,689 tok | · | · |
| gs-rest-service | version-conflict | green · 6t · 13.2s · 26,790 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 6t · 8.4s · 21,583 tok | · | · |
| gs-scheduling-tasks | failing-assertion | green · 5t · 11.4s · 22,512 tok | · | · |
| gs-scheduling-tasks | missing-dependency | green · 4t · 11.7s · 16,036 tok | · | · |
| gs-securing-web | compile-error | green · 5t · 9.4s · 21,150 tok | · | · |
| gs-securing-web | failing-assertion | green · 6t · 15.7s · 32,558 tok | · | · |
| gs-securing-web | missing-dependency | green · 5t · 10.9s · 21,632 tok | · | · |
| gs-securing-web | missing-resource | green · 6t · 11.7s · 27,488 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 8.6s · 21,040 tok | · | · |
| gs-serving-web-content | failing-assertion | green · 6t · 11.1s · 23,886 tok | · | · |
| gs-serving-web-content | missing-resource | green · 7t · 13.1s · 31,552 tok | · | · |
| gs-spring-boot | compile-error | green · 4t · 6.4s · 20,517 tok | · | · |
| gs-spring-boot | failing-assertion | green · 6t · 11.2s · 21,736 tok | · | · |
| gs-spring-boot | missing-dependency | green · 5t · 10.8s · 21,348 tok | · | · |
| gs-spring-boot | version-conflict | green · 6t · 11.0s · 26,609 tok | · | · |
| gs-testing-web | compile-error | green · 4t · 6.2s · 20,540 tok | · | · |
| gs-testing-web | failing-assertion | green · 7t · 13.4s · 35,219 tok | · | · |
| gs-uploading-files | compile-error | green · 5t · 8.5s · 21,045 tok | · | · |
| gs-uploading-files | failing-assertion | green · 6t · 14.0s · 25,323 tok | · | · |
| gs-uploading-files | missing-dependency | green · 5t · 10.4s · 21,626 tok | · | · |
| gs-uploading-files | missing-resource | green · 7t · 12.1s · 32,743 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 7.0s · 21,059 tok | · | · |
| gs-validating-form-input | missing-dependency | green · 4t · 9.2s · 15,488 tok | · | · |
| gs-validating-form-input | missing-resource | green · 8t · 13.3s · 33,324 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 6.8s · 20,978 tok | · | · |
| junit-starter-gradle | failing-assertion | green · 5t · 12.5s · 21,662 tok | · | · |
| junit-starter-gradle | missing-dependency | green · 4t · 6.4s · 15,839 tok | · | · |
| junit-starter-gradle | version-conflict | green · 6t · 9.2s · 21,767 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 6.6s · 21,283 tok | · | · |
| junit-starter-gradle-kotlin | failing-assertion | green · 5t · 8.9s · 22,672 tok | · | · |
| junit-starter-gradle-kotlin | missing-dependency | green · 4t · 6.8s · 16,034 tok | · | · |
| junit-starter-maven | compile-error | green · 4t · 10.1s · 20,429 tok | · | · |
| junit-starter-maven | failing-assertion | green · 5t · 12.1s · 21,915 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 7.1s · 21,281 tok | · | · |
| junit-starter-maven-kotlin | failing-assertion | green · 5t · 7.4s · 22,481 tok | · | · |
| junit-starter-maven-kotlin | missing-dependency | green · 5t · 7.1s · 21,619 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 7.5s · 20,825 tok | · | · |
| spring-petclinic | failing-assertion | green · 6t · 14.8s · 24,359 tok | · | · |
| spring-petclinic | missing-dependency | green · 4t · 31.7s · 15,419 tok | · | · |
| spring-petclinic | missing-resource | green · 8t · 50.2s · 37,899 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 9.7s · 45,263 tok | · | · |
| spring-petclinic-kotlin | failing-assertion | green · 7t · 11.5s · 71,348 tok | · | · |
| spring-petclinic-kotlin | missing-dependency | green · 4t · 13.9s · 34,065 tok | · | · |
| spring-petclinic-kotlin | missing-resource | green · 10t · 25.0s · 91,449 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 5t · 8.8s · 21,843 tok | · | · |
| tut-spring-boot-kotlin | failing-assertion | green · 8t · 19.9s · 39,704 tok | · | · |
| tut-spring-boot-kotlin | missing-dependency | green · 4t · 12.9s · 21,193 tok | · | · |
| tut-spring-boot-kotlin | missing-resource | green · 8t · 22.7s · 32,742 tok | · | · |
