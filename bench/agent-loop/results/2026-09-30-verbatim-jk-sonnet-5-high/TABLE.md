# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 91 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-sonnet-5 · effort high · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 91 | 88 | 97% | 5 | 12 | 34,603 | 89,486 | 10.0s | 28.3s | $3.19 | 4 | 1 | 532 | 10 | 30,912 | $0.0319 | 42 exact · 40 equivalent · 8 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 7.9s · 32,188 tok | · | · |
| commons-cli | failing-assertion | green · 11t · 62.9s · 133,182 tok | · | · |
| commons-cli | missing-dependency | green · 5t · 10.1s · 35,377 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 5t · 7.4s · 33,781 tok | · | · |
| gs-accessing-data-jpa | failing-assertion | green · 5t · 7.2s · 33,775 tok | · | · |
| gs-accessing-data-jpa | missing-dependency | green · 4t · 6.3s · 25,755 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 7.7s · 34,603 tok | · | · |
| gs-accessing-data-r2dbc | failing-assertion | green · 7t · 21.7s · 54,569 tok | · | · |
| gs-accessing-data-r2dbc | missing-dependency | green · 4t · 5.9s · 25,992 tok | · | · |
| gs-accessing-data-r2dbc | missing-resource | green · 12t · 28.2s · 89,486 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 5t · 7.1s · 31,847 tok | · | · |
| gs-accessing-data-rest | failing-assertion | green · 5t · 9.6s · 38,831 tok | · | · |
| gs-accessing-data-rest | missing-dependency | green · 4t · 5.2s · 24,377 tok | · | · |
| gs-actuator-service | compile-error | green · 5t · 9.6s · 31,700 tok | · | · |
| gs-actuator-service | failing-assertion | green · 7t · 34.4s · 54,271 tok | · | · |
| gs-actuator-service | missing-dependency | green · 5t · 11.0s · 32,079 tok | · | · |
| gs-batch-processing | compile-error | green · 5t · 10.1s · 31,788 tok | · | · |
| gs-batch-processing | failing-assertion | green · 6t · 16.0s · 44,772 tok | · | · |
| gs-batch-processing | missing-dependency | green · 4t · 4.8s · 25,733 tok | · | · |
| gs-batch-processing | missing-resource | green · 10t · 11.9s · 60,781 tok | · | · |
| gs-consuming-rest | compile-error | green · 5t · 7.7s · 32,741 tok | · | · |
| gs-consuming-rest | missing-dependency | green · 4t · 4.7s · 25,718 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 10.0s · 31,548 tok | · | · |
| gs-graphql-server | failing-assertion | green · 7t · 17.5s · 49,142 tok | · | · |
| gs-graphql-server | missing-dependency | green · 4t · 5.1s · 24,356 tok | · | · |
| gs-graphql-server | missing-resource | green · 14t · 24.4s · 59,247 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 8.2s · 31,936 tok | · | · |
| gs-handling-form-submission | failing-assertion | green · 7t · 15.8s · 50,440 tok | · | · |
| gs-handling-form-submission | missing-dependency | green · 4t · 5.3s · 24,384 tok | · | · |
| gs-handling-form-submission | missing-resource | green · 9t · 14.3s · 59,595 tok | · | · |
| gs-multi-module | compile-error | green · 5t · 10.3s · 31,636 tok | · | · |
| gs-multi-module | failing-assertion | green · 12t · 28.3s · 93,851 tok | · | · |
| gs-multi-module | missing-dependency | **red** · 17t · 37.0s · 140,249 tok | · | · |
| gs-multi-module | missing-resource | green · 12t · 21.3s · 59,808 tok | · | · |
| gs-multi-module | version-conflict | green · 10t · 13.2s · 50,208 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 7t · 9.6s · 46,193 tok | · | · |
| gs-reactive-rest-service | failing-assertion | green · 9t · 16.0s · 66,268 tok | · | · |
| gs-rest-hateoas | compile-error | green · 5t · 7.5s · 31,577 tok | · | · |
| gs-rest-hateoas | failing-assertion | green · 6t · 13.1s · 43,161 tok | · | · |
| gs-rest-hateoas | missing-dependency | green · 4t · 5.2s · 25,551 tok | · | · |
| gs-rest-service | compile-error | green · 7t · 10.7s · 45,519 tok | · | · |
| gs-rest-service | failing-assertion | green · 7t · 12.8s · 47,574 tok | · | · |
| gs-rest-service | missing-dependency | green · 6t · 12.4s · 41,769 tok | · | · |
| gs-rest-service | version-conflict | green · 7t · 8.9s · 39,144 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 5t · 9.3s · 31,922 tok | · | · |
| gs-scheduling-tasks | failing-assertion | green · 5t · 7.7s · 33,246 tok | · | · |
| gs-scheduling-tasks | missing-dependency | green · 4t · 7.8s · 25,470 tok | · | · |
| gs-securing-web | compile-error | green · 5t · 10.4s · 31,642 tok | · | · |
| gs-securing-web | failing-assertion | green · 13t · 35.7s · 99,387 tok | · | · |
| gs-securing-web | missing-dependency | green · 5t · 12.9s · 32,609 tok | · | · |
| gs-securing-web | missing-resource | green · 9t · 16.5s · 48,419 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 7.8s · 31,764 tok | · | · |
| gs-serving-web-content | failing-assertion | green · 8t · 14.9s · 57,665 tok | · | · |
| gs-serving-web-content | missing-resource | green · 7t · 10.0s · 41,026 tok | · | · |
| gs-spring-boot | compile-error | green · 5t · 6.9s · 32,161 tok | · | · |
| gs-spring-boot | failing-assertion | green · 14t · 70.0s · 116,002 tok | · | · |
| gs-spring-boot | missing-dependency | green · 6t · 13.0s · 38,287 tok | · | · |
| gs-spring-boot | version-conflict | green · 6t · 9.6s · 31,664 tok | · | · |
| gs-testing-web | compile-error | green · 5t · 6.5s · 31,446 tok | · | · |
| gs-testing-web | failing-assertion | green · 5t · 7.1s · 32,826 tok | · | · |
| gs-uploading-files | compile-error | green · 5t · 10.5s · 32,272 tok | · | · |
| gs-uploading-files | failing-assertion | green · 12t · 51.6s · 139,024 tok | · | · |
| gs-uploading-files | missing-dependency | green · 6t · 14.2s · 40,267 tok | · | · |
| gs-uploading-files | missing-resource | green · 8t · 13.6s · 43,498 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 7.4s · 31,792 tok | · | · |
| gs-validating-form-input | missing-dependency | green · 4t · 4.9s · 24,658 tok | · | · |
| gs-validating-form-input | missing-resource | green · 9t · 13.4s · 55,013 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 6.5s · 31,488 tok | · | · |
| junit-starter-gradle | failing-assertion | green · 5t · 11.8s · 33,932 tok | · | · |
| junit-starter-gradle | missing-dependency | green · 4t · 5.3s · 25,206 tok | · | · |
| junit-starter-gradle | version-conflict | green · 4t · 8.9s · 24,103 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 9.2s · 31,907 tok | · | · |
| junit-starter-gradle-kotlin | failing-assertion | green · 7t · 11.3s · 50,385 tok | · | · |
| junit-starter-gradle-kotlin | missing-dependency | green · 4t · 7.4s · 25,641 tok | · | · |
| junit-starter-maven | compile-error | green · 5t · 7.2s · 31,484 tok | · | · |
| junit-starter-maven | failing-assertion | green · 5t · 9.9s · 32,445 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 8.1s · 31,903 tok | · | · |
| junit-starter-maven-kotlin | failing-assertion | green · 7t · 11.2s · 49,862 tok | · | · |
| junit-starter-maven-kotlin | missing-dependency | green · 6t · 15.6s · 38,934 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 9.2s · 31,716 tok | · | · |
| spring-petclinic | failing-assertion | green · 10t · 24.8s · 88,173 tok | · | · |
| spring-petclinic | missing-dependency | green · 4t · 8.8s · 24,593 tok | · | · |
| spring-petclinic | missing-resource | **red** · 17t · 77.1s · 221,716 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 8.8s · 63,666 tok | · | · |
| spring-petclinic-kotlin | failing-assertion | green · 5t · 9.1s · 63,118 tok | · | · |
| spring-petclinic-kotlin | missing-dependency | green · 4t · 8.4s · 49,510 tok | · | · |
| spring-petclinic-kotlin | missing-resource | green · 17t · 80.5s · 307,294 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 6t · 8.3s · 40,317 tok | · | · |
| tut-spring-boot-kotlin | failing-assertion | green · 8t · 13.0s · 60,899 tok | · | · |
| tut-spring-boot-kotlin | missing-dependency | green · 4t · 11.5s · 24,999 tok | · | · |
| tut-spring-boot-kotlin | missing-resource | **red** · 17t · 72.2s · 194,653 tok | · | · |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-multi-module | missing-dependency | jk | red | — | agent stopped (error_max_turns) |
| spring-petclinic | missing-resource | jk | red | — | agent stopped (error_max_turns) |
| spring-petclinic-kotlin | missing-resource | jk | green | — | agent stopped (error_max_turns) |
| tut-spring-boot-kotlin | missing-resource | jk | red | — | agent stopped (error_max_turns) |
