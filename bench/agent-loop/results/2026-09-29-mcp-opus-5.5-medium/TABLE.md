# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 134 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| mvn | 55 | 55 | 100% | 5 | 7 | 24,508 | 37,833 | 14.7s | 22.0s | $2.94 | 4 | 2 | 542 | 8 | 19,863 | $0.0534 | 45 exact · 10 equivalent · 0 collateral · 0 cheat |
| gradle | 79 | 79 | 100% | 6 | 8 | 30,657 | 40,572 | 15.2s | 20.6s | $4.46 | 4 | 2 | 557 | 10 | 25,944 | $0.0564 | 66 exact · 13 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | · | green · 5t · 18.6s · 21,741 tok | · |
| commons-cli | failing-assertion | · | green · 6t · 19.2s · 26,569 tok | · |
| commons-cli | missing-dependency | · | green · 5t · 21.8s · 32,226 tok | · |
| gs-accessing-data-jpa | compile-error | · | green · 5t · 14.5s · 21,825 tok | green · 4t · 13.3s · 21,327 tok |
| gs-accessing-data-jpa | failing-assertion | · | green · 7t · 18.8s · 31,008 tok | green · 7t · 20.0s · 31,114 tok |
| gs-accessing-data-jpa | missing-dependency | · | green · 5t · 13.7s · 30,401 tok | green · 5t · 12.1s · 24,324 tok |
| gs-accessing-data-r2dbc | compile-error | · | green · 5t · 12.9s · 22,194 tok | green · 4t · 9.6s · 15,920 tok |
| gs-accessing-data-r2dbc | failing-assertion | · | green · 7t · 17.9s · 35,128 tok | green · 7t · 18.4s · 35,283 tok |
| gs-accessing-data-r2dbc | missing-dependency | · | green · 5t · 13.4s · 31,825 tok | green · 6t · 14.3s · 43,916 tok |
| gs-accessing-data-r2dbc | missing-resource | · | green · 8t · 15.5s · 37,833 tok | green · 8t · 15.2s · 37,681 tok |
| gs-accessing-data-rest | compile-error | · | green · 5t · 14.7s · 21,661 tok | green · 4t · 12.4s · 15,821 tok |
| gs-accessing-data-rest | failing-assertion | · | green · 7t · 20.9s · 34,851 tok | green · 7t · 20.5s · 34,896 tok |
| gs-accessing-data-rest | missing-dependency | · | green · 5t · 15.5s · 25,155 tok | green · 6t · 16.5s · 30,889 tok |
| gs-actuator-service | compile-error | · | green · 5t · 13.9s · 21,521 tok | green · 5t · 13.1s · 21,520 tok |
| gs-actuator-service | failing-assertion | · | green · 6t · 19.5s · 31,093 tok | green · 7t · 18.2s · 32,160 tok |
| gs-actuator-service | missing-dependency | · | green · 5t · 14.2s · 24,508 tok | green · 6t · 14.6s · 30,274 tok |
| gs-batch-processing | compile-error | · | green · 5t · 12.0s · 21,560 tok | green · 4t · 10.1s · 21,125 tok |
| gs-batch-processing | failing-assertion | · | green · 6t · 13.4s · 30,983 tok | green · 6t · 17.7s · 31,413 tok |
| gs-batch-processing | missing-dependency | · | green · 6t · 16.1s · 48,760 tok | green · 6t · 15.0s · 48,931 tok |
| gs-batch-processing | missing-resource | · | green · 8t · 16.2s · 38,082 tok | green · 8t · 17.0s · 38,696 tok |
| gs-consuming-rest | compile-error | · | green · 5t · 10.6s · 21,672 tok | green · 5t · 10.5s · 21,662 tok |
| gs-consuming-rest | missing-dependency | · | green · 7t · 17.4s · 45,350 tok | green · 6t · 16.1s · 38,049 tok |
| gs-graphql-server | compile-error | · | · | green · 4t · 11.6s · 21,125 tok |
| gs-graphql-server | failing-assertion | · | · | green · 7t · 17.6s · 29,671 tok |
| gs-graphql-server | missing-dependency | · | · | green · 6t · 20.5s · 30,198 tok |
| gs-graphql-server | missing-resource | · | · | green · 7t · 14.9s · 34,158 tok |
| gs-handling-form-submission | compile-error | · | · | green · 4t · 10.3s · 21,277 tok |
| gs-handling-form-submission | failing-assertion | · | · | green · 5t · 15.1s · 25,423 tok |
| gs-handling-form-submission | missing-dependency | · | · | green · 6t · 12.8s · 30,252 tok |
| gs-handling-form-submission | missing-resource | · | · | green · 8t · 16.1s · 37,086 tok |
| gs-multi-module | compile-error | · | green · 5t · 13.7s · 21,888 tok | green · 4t · 12.7s · 21,072 tok |
| gs-multi-module | failing-assertion | · | green · 9t · 21.7s · 39,040 tok | green · 9t · 17.2s · 39,035 tok |
| gs-multi-module | missing-dependency | · | green · 5t · 12.2s · 24,012 tok | green · 6t · 13.4s · 30,313 tok |
| gs-multi-module | missing-resource | · | · | green · 8t · 16.8s · 31,024 tok |
| gs-multi-module | version-conflict | · | green · 6t · 15.4s · 25,938 tok | green · 7t · 15.2s · 30,221 tok |
| gs-reactive-rest-service | compile-error | · | green · 5t · 14.8s · 21,990 tok | green · 4t · 13.6s · 21,293 tok |
| gs-reactive-rest-service | failing-assertion | · | green · 6t · 22.0s · 23,783 tok | green · 7t · 18.6s · 31,651 tok |
| gs-rest-hateoas | compile-error | · | green · 5t · 11.2s · 21,583 tok | green · 4t · 11.4s · 21,162 tok |
| gs-rest-hateoas | failing-assertion | · | green · 8t · 18.5s · 34,441 tok | green · 8t · 18.5s · 34,450 tok |
| gs-rest-hateoas | missing-dependency | · | green · 5t · 11.5s · 21,268 tok | green · 6t · 13.8s · 38,346 tok |
| gs-rest-service | compile-error | · | green · 5t · 12.6s · 21,405 tok | green · 4t · 11.3s · 21,031 tok |
| gs-rest-service | failing-assertion | · | green · 5t · 17.3s · 24,023 tok | green · 7t · 19.5s · 31,178 tok |
| gs-rest-service | missing-dependency | · | green · 5t · 13.5s · 24,946 tok | green · 6t · 13.7s · 31,617 tok |
| gs-rest-service | version-conflict | · | green · 5t · 14.7s · 25,259 tok | green · 6t · 17.1s · 30,657 tok |
| gs-scheduling-tasks | compile-error | · | · | green · 4t · 15.7s · 21,368 tok |
| gs-scheduling-tasks | failing-assertion | · | · | green · 5t · 20.8s · 23,849 tok |
| gs-scheduling-tasks | missing-dependency | · | · | green · 6t · 19.4s · 34,575 tok |
| gs-securing-web | compile-error | · | · | green · 5t · 13.3s · 21,574 tok |
| gs-securing-web | failing-assertion | · | · | green · 7t · 20.6s · 33,518 tok |
| gs-securing-web | missing-dependency | · | · | green · 6t · 13.9s · 29,966 tok |
| gs-securing-web | missing-resource | · | · | green · 7t · 15.3s · 35,629 tok |
| gs-serving-web-content | compile-error | · | · | green · 4t · 10.6s · 21,173 tok |
| gs-serving-web-content | failing-assertion | · | · | green · 5t · 13.6s · 24,657 tok |
| gs-serving-web-content | missing-resource | · | · | green · 7t · 13.3s · 40,572 tok |
| gs-spring-boot | compile-error | · | green · 5t · 11.6s · 21,393 tok | green · 4t · 11.8s · 15,628 tok |
| gs-spring-boot | failing-assertion | · | green · 6t · 22.4s · 23,909 tok | green · 7t · 20.6s · 31,408 tok |
| gs-spring-boot | missing-dependency | · | green · 5t · 12.4s · 23,741 tok | green · 6t · 14.6s · 28,902 tok |
| gs-spring-boot | version-conflict | · | green · 5t · 11.9s · 27,753 tok | green · 6t · 15.9s · 33,868 tok |
| gs-testing-web | compile-error | · | green · 5t · 13.1s · 21,411 tok | green · 4t · 12.3s · 21,034 tok |
| gs-testing-web | failing-assertion | · | green · 7t · 19.4s · 31,845 tok | green · 7t · 19.3s · 31,324 tok |
| gs-uploading-files | compile-error | · | green · 6t · 12.9s · 22,625 tok | green · 5t · 13.2s · 22,054 tok |
| gs-uploading-files | failing-assertion | · | green · 7t · 20.0s · 34,902 tok | green · 7t · 21.5s · 35,020 tok |
| gs-uploading-files | missing-dependency | · | green · 5t · 13.5s · 24,246 tok | green · 5t · 17.1s · 23,769 tok |
| gs-uploading-files | missing-resource | · | · | green · 7t · 18.8s · 39,790 tok |
| gs-validating-form-input | compile-error | · | green · 6t · 11.9s · 22,208 tok | green · 5t · 11.4s · 21,615 tok |
| gs-validating-form-input | missing-dependency | · | green · 5t · 12.1s · 24,901 tok | green · 6t · 13.3s · 31,197 tok |
| gs-validating-form-input | missing-resource | · | · | green · 8t · 15.5s · 50,864 tok |
| junit-starter-gradle | compile-error | · | · | green · 5t · 9.4s · 21,412 tok |
| junit-starter-gradle | failing-assertion | · | · | green · 7t · 15.1s · 29,926 tok |
| junit-starter-gradle | missing-dependency | · | · | green · 6t · 10.4s · 32,611 tok |
| junit-starter-gradle | version-conflict | · | · | green · 7t · 16.5s · 28,743 tok |
| junit-starter-gradle-kotlin | compile-error | · | · | green · 5t · 10.8s · 21,778 tok |
| junit-starter-gradle-kotlin | failing-assertion | · | · | green · 7t · 17.2s · 29,374 tok |
| junit-starter-gradle-kotlin | missing-dependency | · | · | green · 5t · 10.2s · 25,379 tok |
| junit-starter-maven | compile-error | · | green · 5t · 8.6s · 21,507 tok | · |
| junit-starter-maven | failing-assertion | · | green · 5t · 14.0s · 23,365 tok | · |
| junit-starter-maven-kotlin | compile-error | · | green · 5t · 13.0s · 21,983 tok | · |
| junit-starter-maven-kotlin | failing-assertion | · | green · 6t · 16.4s · 22,609 tok | · |
| junit-starter-maven-kotlin | missing-dependency | · | green · 5t · 12.2s · 25,234 tok | · |
| spring-petclinic | compile-error | · | green · 5t · 49.0s · 21,700 tok | · |
| spring-petclinic | failing-assertion | · | green · 6t · 53.1s · 25,762 tok | · |
| spring-petclinic | missing-dependency | · | green · 6t · 51.1s · 24,542 tok | · |
| spring-petclinic | missing-resource | · | green · 11t · 69.1s · 69,531 tok | · |
| spring-petclinic-kotlin | compile-error | · | · | green · 5t · 21.7s · 46,485 tok |
| spring-petclinic-kotlin | failing-assertion | · | · | green · 7t · 20.5s · 62,531 tok |
| spring-petclinic-kotlin | missing-dependency | · | · | green · 6t · 24.2s · 67,148 tok |
| spring-petclinic-kotlin | missing-resource | · | · | green · 10t · 34.2s · 89,950 tok |
| tut-spring-boot-kotlin | compile-error | · | · | green · 5t · 17.6s · 22,352 tok |
| tut-spring-boot-kotlin | failing-assertion | · | · | green · 6t · 21.4s · 32,654 tok |
| tut-spring-boot-kotlin | missing-dependency | · | · | green · 6t · 17.8s · 30,882 tok |
| tut-spring-boot-kotlin | missing-resource | · | · | green · 8t · 26.7s · 74,897 tok |
