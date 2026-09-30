# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 91 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-sonnet-5 · effort high · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 91 | 87 | 96% | 6 | 11 | 39,133 | 75,709 | 10.6s | 31.2s | $3.43 | 4.5 | 1 | 558 | 12 | 36,189 | $0.0323 | 42 exact · 40 equivalent · 8 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 7.8s · 31,852 tok | · | · |
| commons-cli | failing-assertion | green · 11t · 63.2s · 129,006 tok | · | · |
| commons-cli | missing-dependency | green · 4t · 11.0s · 25,703 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 7t · 10.4s · 47,773 tok | · | · |
| gs-accessing-data-jpa | failing-assertion | green · 6t · 14.2s · 41,507 tok | · | · |
| gs-accessing-data-jpa | missing-dependency | green · 4t · 5.4s · 25,801 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 7.8s · 34,628 tok | · | · |
| gs-accessing-data-r2dbc | failing-assertion | green · 9t · 18.3s · 53,038 tok | · | · |
| gs-accessing-data-r2dbc | missing-dependency | green · 4t · 6.2s · 26,106 tok | · | · |
| gs-accessing-data-r2dbc | missing-resource | **red** · 17t · 70.9s · 186,544 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 7t · 9.5s · 45,652 tok | · | · |
| gs-accessing-data-rest | failing-assertion | green · 7t · 26.1s · 56,606 tok | · | · |
| gs-accessing-data-rest | missing-dependency | green · 4t · 6.8s · 24,427 tok | · | · |
| gs-actuator-service | compile-error | green · 5t · 10.6s · 31,723 tok | · | · |
| gs-actuator-service | failing-assertion | green · 7t · 29.4s · 60,062 tok | · | · |
| gs-actuator-service | missing-dependency | green · 6t · 7.9s · 38,584 tok | · | · |
| gs-batch-processing | compile-error | green · 5t · 7.8s · 31,705 tok | · | · |
| gs-batch-processing | failing-assertion | green · 8t · 21.4s · 60,693 tok | · | · |
| gs-batch-processing | missing-dependency | green · 4t · 5.6s · 25,774 tok | · | · |
| gs-batch-processing | missing-resource | green · 10t · 13.2s · 56,372 tok | · | · |
| gs-consuming-rest | compile-error | green · 5t · 7.7s · 32,856 tok | · | · |
| gs-consuming-rest | missing-dependency | green · 4t · 8.4s · 25,823 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 11.3s · 31,733 tok | · | · |
| gs-graphql-server | failing-assertion | green · 9t · 18.1s · 56,166 tok | · | · |
| gs-graphql-server | missing-dependency | green · 4t · 5.5s · 24,444 tok | · | · |
| gs-graphql-server | missing-resource | green · 9t · 10.5s · 48,680 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 8.1s · 31,995 tok | · | · |
| gs-handling-form-submission | failing-assertion | green · 9t · 12.1s · 49,619 tok | · | · |
| gs-handling-form-submission | missing-dependency | green · 4t · 4.6s · 24,346 tok | · | · |
| gs-handling-form-submission | missing-resource | green · 9t · 17.4s · 44,160 tok | · | · |
| gs-multi-module | compile-error | green · 5t · 7.8s · 31,755 tok | · | · |
| gs-multi-module | failing-assertion | green · 12t · 27.2s · 69,068 tok | · | · |
| gs-multi-module | missing-dependency | **red** · 17t · 54.7s · 141,713 tok | · | · |
| gs-multi-module | missing-resource | green · 15t · 28.0s · 86,413 tok | · | · |
| gs-multi-module | version-conflict | green · 9t · 17.5s · 42,815 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 7t · 13.1s · 46,299 tok | · | · |
| gs-reactive-rest-service | failing-assertion | green · 9t · 20.7s · 64,466 tok | · | · |
| gs-rest-hateoas | compile-error | green · 5t · 14.1s · 31,696 tok | · | · |
| gs-rest-hateoas | failing-assertion | green · 9t · 35.8s · 75,709 tok | · | · |
| gs-rest-hateoas | missing-dependency | green · 4t · 5.5s · 25,636 tok | · | · |
| gs-rest-service | compile-error | green · 7t · 9.9s · 45,231 tok | · | · |
| gs-rest-service | failing-assertion | green · 6t · 16.7s · 41,123 tok | · | · |
| gs-rest-service | missing-dependency | green · 5t · 8.6s · 32,897 tok | · | · |
| gs-rest-service | version-conflict | green · 6t · 10.8s · 31,559 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 5t · 15.4s · 32,341 tok | · | · |
| gs-scheduling-tasks | failing-assertion | green · 7t · 16.0s · 46,726 tok | · | · |
| gs-scheduling-tasks | missing-dependency | green · 4t · 6.9s · 25,423 tok | · | · |
| gs-securing-web | compile-error | green · 5t · 8.1s · 31,761 tok | · | · |
| gs-securing-web | failing-assertion | green · 17t · 132.3s · 136,725 tok | · | · |
| gs-securing-web | missing-dependency | green · 6t · 10.5s · 39,133 tok | · | · |
| gs-securing-web | missing-resource | green · 8t · 10.1s · 41,043 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 7.6s · 31,812 tok | · | · |
| gs-serving-web-content | failing-assertion | green · 10t · 20.2s · 58,337 tok | · | · |
| gs-serving-web-content | missing-resource | green · 8t · 13.9s · 40,737 tok | · | · |
| gs-spring-boot | compile-error | green · 5t · 7.7s · 32,436 tok | · | · |
| gs-spring-boot | failing-assertion | green · 11t · 29.7s · 74,284 tok | · | · |
| gs-spring-boot | missing-dependency | green · 6t · 13.6s · 38,330 tok | · | · |
| gs-spring-boot | version-conflict | green · 6t · 10.4s · 31,405 tok | · | · |
| gs-testing-web | compile-error | green · 5t · 7.9s · 31,565 tok | · | · |
| gs-testing-web | failing-assertion | green · 7t · 11.7s · 40,020 tok | · | · |
| gs-uploading-files | compile-error | green · 5t · 10.4s · 32,385 tok | · | · |
| gs-uploading-files | failing-assertion | green · 5t · 12.0s · 35,112 tok | · | · |
| gs-uploading-files | missing-dependency | green · 6t · 8.1s · 39,328 tok | · | · |
| gs-uploading-files | missing-resource | green · 8t · 20.3s · 43,498 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 9.8s · 31,755 tok | · | · |
| gs-validating-form-input | missing-dependency | green · 4t · 5.6s · 24,680 tok | · | · |
| gs-validating-form-input | missing-resource | green · 9t · 16.6s · 51,620 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 6.3s · 31,453 tok | · | · |
| junit-starter-gradle | failing-assertion | green · 6t · 10.5s · 39,903 tok | · | · |
| junit-starter-gradle | missing-dependency | green · 4t · 6.5s · 25,248 tok | · | · |
| junit-starter-gradle | version-conflict | green · 4t · 6.0s · 24,143 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 8.0s · 32,018 tok | · | · |
| junit-starter-gradle-kotlin | failing-assertion | green · 9t · 13.7s · 57,244 tok | · | · |
| junit-starter-gradle-kotlin | missing-dependency | green · 4t · 9.8s · 25,391 tok | · | · |
| junit-starter-maven | compile-error | green · 5t · 7.1s · 31,603 tok | · | · |
| junit-starter-maven | failing-assertion | green · 6t · 10.6s · 40,359 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 11.5s · 32,088 tok | · | · |
| junit-starter-maven-kotlin | failing-assertion | green · 7t · 15.2s · 52,680 tok | · | · |
| junit-starter-maven-kotlin | missing-dependency | green · 4t · 9.4s · 24,742 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 8.1s · 31,831 tok | · | · |
| spring-petclinic | failing-assertion | green · 7t · 44.3s · 54,136 tok | · | · |
| spring-petclinic | missing-dependency | green · 4t · 6.5s · 24,692 tok | · | · |
| spring-petclinic | missing-resource | **red** · 17t · 187.4s · 239,995 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 9.4s · 63,710 tok | · | · |
| spring-petclinic-kotlin | failing-assertion | green · 9t · 30.6s · 123,982 tok | · | · |
| spring-petclinic-kotlin | missing-dependency | green · 4t · 10.9s · 49,622 tok | · | · |
| spring-petclinic-kotlin | missing-resource | green · 17t · 90.2s · 339,830 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 5t · 6.7s · 32,764 tok | · | · |
| tut-spring-boot-kotlin | failing-assertion | green · 10t · 31.2s · 75,633 tok | · | · |
| tut-spring-boot-kotlin | missing-dependency | green · 4t · 16.0s · 24,951 tok | · | · |
| tut-spring-boot-kotlin | missing-resource | **red** · 17t · 105.4s · 194,080 tok | · | · |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-accessing-data-r2dbc | missing-resource | jk | red | — | agent stopped (error_max_turns) |
| gs-multi-module | missing-dependency | jk | red | — | agent stopped (error_max_turns) |
| spring-petclinic | missing-resource | jk | red | — | agent stopped (error_max_turns) |
| spring-petclinic-kotlin | missing-resource | jk | green | — | agent stopped (error_max_turns) |
| tut-spring-boot-kotlin | missing-resource | jk | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
