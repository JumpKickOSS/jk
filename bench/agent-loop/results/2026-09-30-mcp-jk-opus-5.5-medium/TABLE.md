# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 91 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 91 | 91 | 100% | 5 | 8 | 21,811 | 36,512 | 11.5s | 20.8s | $3.97 | 4 | 1 | 481 | 8 | 19,139 | $0.0436 | 54 exact · 36 equivalent · 1 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 8.1s · 21,167 tok | · | · |
| commons-cli | failing-assertion | green · 7t · 16.9s · 31,344 tok | · | · |
| commons-cli | missing-dependency | green · 5t · 11.1s · 23,439 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 5t · 9.3s · 21,164 tok | · | · |
| gs-accessing-data-jpa | failing-assertion | green · 8t · 16.2s · 30,272 tok | · | · |
| gs-accessing-data-jpa | missing-dependency | green · 4t · 10.2s · 16,215 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 7.6s · 21,448 tok | · | · |
| gs-accessing-data-r2dbc | failing-assertion | green · 8t · 13.0s · 30,787 tok | · | · |
| gs-accessing-data-r2dbc | missing-dependency | green · 5t · 10.7s · 17,424 tok | · | · |
| gs-accessing-data-r2dbc | missing-resource | green · 9t · 16.3s · 36,435 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 5t · 13.4s · 21,074 tok | · | · |
| gs-accessing-data-rest | failing-assertion | green · 7t · 15.1s · 32,302 tok | · | · |
| gs-accessing-data-rest | missing-dependency | green · 4t · 17.3s · 15,295 tok | · | · |
| gs-actuator-service | compile-error | green · 5t · 8.3s · 20,965 tok | · | · |
| gs-actuator-service | failing-assertion | green · 7t · 14.9s · 29,694 tok | · | · |
| gs-actuator-service | missing-dependency | green · 5t · 9.7s · 21,064 tok | · | · |
| gs-batch-processing | compile-error | green · 6t · 10.2s · 21,168 tok | · | · |
| gs-batch-processing | failing-assertion | green · 6t · 14.2s · 28,696 tok | · | · |
| gs-batch-processing | missing-dependency | green · 4t · 8.1s · 16,140 tok | · | · |
| gs-batch-processing | missing-resource | green · 6t · 9.5s · 22,795 tok | · | · |
| gs-consuming-rest | compile-error | green · 6t · 9.3s · 21,534 tok | · | · |
| gs-consuming-rest | missing-dependency | green · 4t · 7.8s · 16,130 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 7.8s · 20,873 tok | · | · |
| gs-graphql-server | failing-assertion | green · 8t · 13.1s · 29,699 tok | · | · |
| gs-graphql-server | missing-dependency | green · 4t · 8.3s · 15,257 tok | · | · |
| gs-graphql-server | missing-resource | green · 7t · 12.3s · 28,768 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 7.9s · 21,090 tok | · | · |
| gs-handling-form-submission | failing-assertion | green · 8t · 15.4s · 37,715 tok | · | · |
| gs-handling-form-submission | missing-dependency | green · 4t · 8.7s · 15,281 tok | · | · |
| gs-handling-form-submission | missing-resource | green · 8t · 15.2s · 31,044 tok | · | · |
| gs-multi-module | compile-error | green · 5t · 9.8s · 21,022 tok | · | · |
| gs-multi-module | failing-assertion | green · 9t · 21.5s · 36,512 tok | · | · |
| gs-multi-module | missing-dependency | green · 7t · 14.3s · 28,315 tok | · | · |
| gs-multi-module | missing-resource | green · 8t · 20.8s · 28,841 tok | · | · |
| gs-multi-module | version-conflict | green · 8t · 12.3s · 28,424 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 6t · 10.2s · 21,348 tok | · | · |
| gs-reactive-rest-service | failing-assertion | green · 7t · 15.5s · 29,522 tok | · | · |
| gs-rest-hateoas | compile-error | green · 5t · 9.3s · 20,992 tok | · | · |
| gs-rest-hateoas | failing-assertion | green · 8t · 15.6s · 31,961 tok | · | · |
| gs-rest-hateoas | missing-dependency | green · 4t · 8.9s · 16,052 tok | · | · |
| gs-rest-service | compile-error | green · 5t · 9.5s · 20,913 tok | · | · |
| gs-rest-service | failing-assertion | green · 7t · 15.4s · 28,954 tok | · | · |
| gs-rest-service | missing-dependency | green · 4t · 8.4s · 15,653 tok | · | · |
| gs-rest-service | version-conflict | green · 6t · 16.6s · 26,892 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 5t · 10.1s · 21,276 tok | · | · |
| gs-scheduling-tasks | failing-assertion | green · 7t · 13.8s · 29,494 tok | · | · |
| gs-scheduling-tasks | missing-dependency | green · 4t · 12.5s · 16,002 tok | · | · |
| gs-securing-web | compile-error | green · 4t · 8.2s · 20,530 tok | · | · |
| gs-securing-web | failing-assertion | green · 7t · 16.5s · 31,298 tok | · | · |
| gs-securing-web | missing-dependency | green · 4t · 22.4s · 15,447 tok | · | · |
| gs-securing-web | missing-resource | green · 7t · 21.8s · 30,140 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 9.6s · 20,882 tok | · | · |
| gs-serving-web-content | failing-assertion | green · 8t · 14.2s · 30,048 tok | · | · |
| gs-serving-web-content | missing-resource | green · 7t · 14.1s · 30,204 tok | · | · |
| gs-spring-boot | compile-error | green · 5t · 7.3s · 20,915 tok | · | · |
| gs-spring-boot | failing-assertion | green · 8t · 15.0s · 29,893 tok | · | · |
| gs-spring-boot | missing-dependency | green · 5t · 10.1s · 21,114 tok | · | · |
| gs-spring-boot | version-conflict | green · 6t · 14.3s · 26,504 tok | · | · |
| gs-testing-web | compile-error | green · 5t · 7.3s · 20,896 tok | · | · |
| gs-testing-web | failing-assertion | green · 7t · 14.8s · 28,918 tok | · | · |
| gs-uploading-files | compile-error | green · 5t · 9.6s · 21,016 tok | · | · |
| gs-uploading-files | failing-assertion | green · 9t · 22.9s · 48,962 tok | · | · |
| gs-uploading-files | missing-dependency | green · 5t · 11.5s · 21,585 tok | · | · |
| gs-uploading-files | missing-resource | green · 6t · 12.5s · 27,732 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 9.1s · 21,030 tok | · | · |
| gs-validating-form-input | missing-dependency | green · 4t · 8.2s · 15,414 tok | · | · |
| gs-validating-form-input | missing-resource | green · 8t · 17.3s · 40,436 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 9.0s · 20,949 tok | · | · |
| junit-starter-gradle | failing-assertion | green · 6t · 12.1s · 27,352 tok | · | · |
| junit-starter-gradle | missing-dependency | green · 4t · 7.0s · 15,805 tok | · | · |
| junit-starter-gradle | version-conflict | green · 6t · 8.9s · 21,652 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 7.2s · 21,248 tok | · | · |
| junit-starter-gradle-kotlin | failing-assertion | green · 6t · 10.7s · 27,830 tok | · | · |
| junit-starter-gradle-kotlin | missing-dependency | green · 4t · 6.4s · 16,009 tok | · | · |
| junit-starter-maven | compile-error | green · 5t · 7.0s · 20,947 tok | · | · |
| junit-starter-maven | failing-assertion | green · 6t · 24.7s · 27,609 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 7.1s · 21,249 tok | · | · |
| junit-starter-maven-kotlin | failing-assertion | green · 7t · 10.7s · 28,895 tok | · | · |
| junit-starter-maven-kotlin | missing-dependency | green · 5t · 9.4s · 27,526 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 7.6s · 20,796 tok | · | · |
| spring-petclinic | failing-assertion | green · 7t · 15.9s · 29,675 tok | · | · |
| spring-petclinic | missing-dependency | green · 4t · 31.5s · 15,389 tok | · | · |
| spring-petclinic | missing-resource | green · 10t · 26.2s · 59,154 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 9.2s · 45,235 tok | · | · |
| spring-petclinic-kotlin | failing-assertion | green · 8t · 18.2s · 74,657 tok | · | · |
| spring-petclinic-kotlin | missing-dependency | green · 4t · 14.5s · 33,966 tok | · | · |
| spring-petclinic-kotlin | missing-resource | green · 9t · 20.1s · 82,277 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 5t · 8.3s · 21,811 tok | · | · |
| tut-spring-boot-kotlin | failing-assertion | green · 12t · 22.1s · 62,817 tok | · | · |
| tut-spring-boot-kotlin | missing-dependency | green · 4t · 13.2s · 20,993 tok | · | · |
| tut-spring-boot-kotlin | missing-resource | green · 15t · 45.6s · 88,560 tok | · | · |
