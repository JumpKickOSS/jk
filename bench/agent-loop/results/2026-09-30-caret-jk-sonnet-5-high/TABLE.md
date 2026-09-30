# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 27 (scenario × tool) runs on this host · subset compile-errors

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-sonnet-5 · effort high · subset compile-errors · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 27 | 100% | 5 | 6 | 31,947 | 38,117 | 8.9s | 12.2s | $0.59 | 3 | 1 | 475 | 10 | 29,202 | $0.0218 | 26 exact · 1 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 9.4s · 31,945 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 5t · 7.5s · 33,548 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 9.7s · 34,591 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 5t · 8.7s · 31,738 tok | · | · |
| gs-actuator-service | compile-error | green · 6t · 13.9s · 38,072 tok | · | · |
| gs-batch-processing | compile-error | green · 5t · 8.9s · 31,801 tok | · | · |
| gs-consuming-rest | compile-error | green · 5t · 12.2s · 32,685 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 8.1s · 31,542 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 11.1s · 31,864 tok | · | · |
| gs-multi-module | compile-error | green · 9t · 15.1s · 61,833 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 5t · 7.9s · 32,138 tok | · | · |
| gs-rest-hateoas | compile-error | green · 6t · 9.6s · 38,117 tok | · | · |
| gs-rest-service | compile-error | green · 7t · 17.0s · 45,250 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 5t · 8.9s · 31,969 tok | · | · |
| gs-securing-web | compile-error | green · 5t · 9.9s · 31,468 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 12.2s · 31,752 tok | · | · |
| gs-spring-boot | compile-error | green · 5t · 7.8s · 32,149 tok | · | · |
| gs-testing-web | compile-error | green · 5t · 9.8s · 31,650 tok | · | · |
| gs-uploading-files | compile-error | green · 5t · 7.5s · 32,260 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 9.4s · 31,934 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 8.7s · 31,472 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 8.6s · 31,947 tok | · | · |
| junit-starter-maven | compile-error | green · 5t · 7.5s · 31,468 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 8.8s · 31,883 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 7.9s · 31,457 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 7.7s · 63,626 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 5t · 6.6s · 32,783 tok | · | · |
