# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 134 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-sonnet-5 · effort high · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| mvn | 55 | 53 | 96% | 6 | 9 | 46,023 | 77,068 | 17.1s | 45.9s | $2.49 | 5 | 2 | 650 | 12 | 39,556 | $0.0405 | 36 exact · 18 equivalent · 1 collateral · 0 cheat |
| gradle | 79 | 77 | 97% | 6 | 10 | 44,312 | 78,273 | 16.4s | 28.6s | $3.45 | 6 | 2 | 568 | 12 | 39,034 | $0.0389 | 44 exact · 26 equivalent · 9 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | · | green · 5t · 14.2s · 32,819 tok | · |
| commons-cli | failing-assertion | · | green · 7t · 22.5s · 60,410 tok | · |
| commons-cli | missing-dependency | · | green · 6t · 18.0s · 59,783 tok | · |
| gs-accessing-data-jpa | compile-error | · | green · 7t · 18.1s · 48,635 tok | green · 5t · 12.5s · 34,475 tok |
| gs-accessing-data-jpa | failing-assertion | · | green · 7t · 19.0s · 53,573 tok | green · 6t · 22.8s · 44,740 tok |
| gs-accessing-data-jpa | missing-dependency | · | **red** · 17t · 171.6s · 198,768 tok | green · 6t · 17.5s · 57,920 tok |
| gs-accessing-data-r2dbc | compile-error | · | green · 5t · 11.1s · 35,276 tok | green · 5t · 13.0s · 35,326 tok |
| gs-accessing-data-r2dbc | failing-assertion | · | green · 9t · 41.2s · 76,297 tok | green · 8t · 18.1s · 62,317 tok |
| gs-accessing-data-r2dbc | missing-dependency | · | green · 5t · 13.9s · 46,300 tok | green · 6t · 15.7s · 60,546 tok |
| gs-accessing-data-r2dbc | missing-resource | · | green · 14t · 43.7s · 360,323 tok | green · 10t · 22.8s · 67,813 tok |
| gs-accessing-data-rest | compile-error | · | green · 5t · 14.2s · 32,337 tok | green · 5t · 12.4s · 32,523 tok |
| gs-accessing-data-rest | failing-assertion | · | green · 6t · 27.9s · 52,387 tok | green · 7t · 17.4s · 52,392 tok |
| gs-accessing-data-rest | missing-dependency | · | green · 5t · 16.0s · 37,541 tok | green · 5t · 14.7s · 36,567 tok |
| gs-actuator-service | compile-error | · | green · 5t · 14.4s · 32,150 tok | green · 5t · 12.7s · 32,144 tok |
| gs-actuator-service | failing-assertion | · | green · 7t · 21.2s · 56,221 tok | green · 11t · 57.5s · 109,560 tok |
| gs-actuator-service | missing-dependency | · | green · 6t · 16.8s · 43,985 tok | green · 6t · 14.9s · 43,147 tok |
| gs-batch-processing | compile-error | · | green · 5t · 16.1s · 32,207 tok | green · 5t · 11.7s · 32,207 tok |
| gs-batch-processing | failing-assertion | · | green · 13t · 40.5s · 80,415 tok | green · 11t · 28.6s · 78,273 tok |
| gs-batch-processing | missing-dependency | · | green · 5t · 14.7s · 48,653 tok | green · 14t · 65.7s · 223,633 tok |
| gs-batch-processing | missing-resource | · | green · 9t · 28.3s · 77,068 tok | green · 6t · 13.1s · 39,095 tok |
| gs-consuming-rest | compile-error | · | green · 5t · 12.1s · 33,168 tok | green · 5t · 14.4s · 33,162 tok |
| gs-consuming-rest | missing-dependency | · | green · 6t · 17.1s · 49,733 tok | green · 6t · 16.7s · 52,104 tok |
| gs-graphql-server | compile-error | · | · | green · 5t · 10.7s · 32,168 tok |
| gs-graphql-server | failing-assertion | · | · | green · 7t · 23.2s · 49,908 tok |
| gs-graphql-server | missing-dependency | · | · | green · 6t · 13.7s · 43,060 tok |
| gs-graphql-server | missing-resource | · | · | green · 11t · 19.4s · 65,577 tok |
| gs-handling-form-submission | compile-error | · | · | green · 5t · 12.7s · 32,570 tok |
| gs-handling-form-submission | failing-assertion | · | · | green · 5t · 14.2s · 34,313 tok |
| gs-handling-form-submission | missing-dependency | · | · | green · 6t · 11.0s · 43,088 tok |
| gs-handling-form-submission | missing-resource | · | · | green · 9t · 13.9s · 51,475 tok |
| gs-multi-module | compile-error | · | green · 5t · 12.5s · 32,608 tok | green · 5t · 15.4s · 32,331 tok |
| gs-multi-module | failing-assertion | · | green · 7t · 17.5s · 52,034 tok | green · 10t · 26.6s · 85,102 tok |
| gs-multi-module | missing-dependency | · | green · 6t · 22.8s · 37,580 tok | green · 8t · 17.2s · 45,659 tok |
| gs-multi-module | missing-resource | · | · | green · 15t · 23.8s · 87,395 tok |
| gs-multi-module | version-conflict | · | green · 8t · 14.8s · 46,894 tok | green · 7t · 13.8s · 43,353 tok |
| gs-reactive-rest-service | compile-error | · | green · 7t · 17.2s · 47,121 tok | green · 5t · 20.5s · 32,955 tok |
| gs-reactive-rest-service | failing-assertion | · | green · 6t · 15.8s · 43,713 tok | green · 6t · 15.4s · 42,223 tok |
| gs-rest-hateoas | compile-error | · | green · 5t · 19.8s · 32,228 tok | green · 5t · 14.6s · 32,204 tok |
| gs-rest-hateoas | failing-assertion | · | green · 6t · 15.5s · 42,865 tok | green · 6t · 19.2s · 43,058 tok |
| gs-rest-hateoas | missing-dependency | · | green · 6t · 18.1s · 50,283 tok | green · 6t · 15.6s · 53,236 tok |
| gs-rest-service | compile-error | · | green · 5t · 9.9s · 31,992 tok | green · 5t · 12.8s · 32,019 tok |
| gs-rest-service | failing-assertion | · | green · 7t · 25.4s · 52,413 tok | green · 9t · 25.9s · 71,196 tok |
| gs-rest-service | missing-dependency | · | green · 5t · 16.9s · 37,328 tok | green · 6t · 21.2s · 44,884 tok |
| gs-rest-service | version-conflict | · | green · 6t · 11.2s · 44,613 tok | green · 6t · 16.8s · 43,405 tok |
| gs-scheduling-tasks | compile-error | · | · | green · 5t · 17.4s · 32,569 tok |
| gs-scheduling-tasks | failing-assertion | · | · | green · 6t · 18.0s · 42,932 tok |
| gs-scheduling-tasks | missing-dependency | · | · | green · 6t · 22.6s · 48,262 tok |
| gs-securing-web | compile-error | · | · | green · 5t · 14.2s · 32,059 tok |
| gs-securing-web | failing-assertion | · | · | green · 10t · 50.4s · 101,797 tok |
| gs-securing-web | missing-dependency | · | · | green · 6t · 20.0s · 42,846 tok |
| gs-securing-web | missing-resource | · | · | green · 9t · 22.2s · 58,221 tok |
| gs-serving-web-content | compile-error | · | · | green · 6t · 13.7s · 39,057 tok |
| gs-serving-web-content | failing-assertion | · | · | green · 9t · 27.2s · 72,790 tok |
| gs-serving-web-content | missing-resource | · | · | green · 10t · 18.9s · 65,318 tok |
| gs-spring-boot | compile-error | · | green · 7t · 19.7s · 46,588 tok | green · 5t · 11.6s · 32,840 tok |
| gs-spring-boot | failing-assertion | · | green · 12t · 45.9s · 86,636 tok | green · 8t · 55.7s · 65,314 tok |
| gs-spring-boot | missing-dependency | · | green · 6t · 16.5s · 42,325 tok | green · 6t · 12.7s · 41,411 tok |
| gs-spring-boot | version-conflict | · | green · 5t · 18.8s · 41,195 tok | green · 6t · 12.9s · 47,739 tok |
| gs-testing-web | compile-error | · | green · 7t · 18.5s · 46,023 tok | green · 5t · 12.2s · 32,167 tok |
| gs-testing-web | failing-assertion | · | green · 5t · 12.7s · 34,266 tok | green · 5t · 11.6s · 34,250 tok |
| gs-uploading-files | compile-error | · | green · 7t · 14.5s · 47,173 tok | green · 5t · 16.3s · 32,978 tok |
| gs-uploading-files | failing-assertion | · | green · 8t · 26.5s · 73,785 tok | green · 7t · 30.4s · 58,609 tok |
| gs-uploading-files | missing-dependency | · | green · 6t · 14.4s · 43,244 tok | green · 6t · 16.4s · 42,409 tok |
| gs-uploading-files | missing-resource | · | · | green · 9t · 17.1s · 59,398 tok |
| gs-validating-form-input | compile-error | · | green · 5t · 15.8s · 32,236 tok | green · 5t · 11.8s · 32,437 tok |
| gs-validating-form-input | missing-dependency | · | green · 6t · 12.5s · 44,513 tok | green · 6t · 13.9s · 44,312 tok |
| gs-validating-form-input | missing-resource | · | · | green · 11t · 16.5s · 85,646 tok |
| junit-starter-gradle | compile-error | · | · | green · 5t · 8.8s · 31,975 tok |
| junit-starter-gradle | failing-assertion | · | · | green · 6t · 11.0s · 40,947 tok |
| junit-starter-gradle | missing-dependency | · | · | green · 6t · 15.3s · 46,006 tok |
| junit-starter-gradle | version-conflict | · | · | green · 10t · 16.2s · 67,178 tok |
| junit-starter-gradle-kotlin | compile-error | · | · | green · 5t · 12.5s · 32,472 tok |
| junit-starter-gradle-kotlin | failing-assertion | · | · | green · 7t · 16.4s · 51,166 tok |
| junit-starter-gradle-kotlin | missing-dependency | · | · | green · 6t · 10.6s · 45,351 tok |
| junit-starter-maven | compile-error | · | green · 5t · 9.1s · 32,098 tok | · |
| junit-starter-maven | failing-assertion | · | green · 5t · 11.8s · 33,221 tok | · |
| junit-starter-maven-kotlin | compile-error | · | green · 5t · 11.8s · 32,740 tok | · |
| junit-starter-maven-kotlin | failing-assertion | · | green · 7t · 26.7s · 54,705 tok | · |
| junit-starter-maven-kotlin | missing-dependency | · | green · 5t · 11.7s · 37,899 tok | · |
| spring-petclinic | compile-error | · | green · 5t · 48.2s · 32,636 tok | · |
| spring-petclinic | failing-assertion | · | green · 8t · 67.4s · 60,525 tok | · |
| spring-petclinic | missing-dependency | · | green · 5t · 48.8s · 55,044 tok | · |
| spring-petclinic | missing-resource | · | **red** · 17t · 91.4s · 319,538 tok | · |
| spring-petclinic-kotlin | compile-error | · | · | green · 5t · 20.8s · 64,721 tok |
| spring-petclinic-kotlin | failing-assertion | · | · | green · 5t · 17.5s · 65,396 tok |
| spring-petclinic-kotlin | missing-dependency | · | · | green · 5t · 21.8s · 70,193 tok |
| spring-petclinic-kotlin | missing-resource | · | · | **red** · 17t · 75.1s · 381,343 tok |
| tut-spring-boot-kotlin | compile-error | · | · | green · 5t · 19.9s · 33,382 tok |
| tut-spring-boot-kotlin | failing-assertion | · | · | green · 9t · 29.9s · 69,973 tok |
| tut-spring-boot-kotlin | missing-dependency | · | · | green · 6t · 18.6s · 43,748 tok |
| tut-spring-boot-kotlin | missing-resource | · | · | **red** · 17t · 76.5s · 346,786 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-accessing-data-jpa | missing-dependency | mvn | red | — | agent stopped (error_max_turns) |
| spring-petclinic | missing-resource | mvn | red | — | agent stopped (error_max_turns) |
| spring-petclinic-kotlin | missing-resource | gradle | red | — | agent stopped (error_max_turns) |
| tut-spring-boot-kotlin | missing-resource | gradle | red | — | agent stopped (error_max_turns) |
