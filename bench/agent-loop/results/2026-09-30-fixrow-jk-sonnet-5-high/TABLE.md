# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 27 (scenario × tool) runs on this host · subset compile-errors

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-sonnet-5 · effort high · subset compile-errors · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 27 | 100% | 5 | 5 | 31,958 | 33,840 | 9.6s | 12.1s | $0.60 | 4 | 1 | 468 | 10 | 29,176 | $0.0221 | 26 exact · 1 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 12.3s · 31,965 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 5t · 10.2s · 33,840 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 6t · 12.6s · 41,309 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 5t · 9.3s · 31,971 tok | · | · |
| gs-actuator-service | compile-error | green · 5t · 7.8s · 31,668 tok | · | · |
| gs-batch-processing | compile-error | green · 5t · 7.6s · 31,801 tok | · | · |
| gs-consuming-rest | compile-error | green · 5t · 8.1s · 32,665 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 8.7s · 31,678 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 11.7s · 31,894 tok | · | · |
| gs-multi-module | compile-error | green · 5t · 9.1s · 31,812 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 5t · 9.9s · 32,230 tok | · | · |
| gs-rest-hateoas | compile-error | green · 5t · 6.9s · 31,755 tok | · | · |
| gs-rest-service | compile-error | green · 7t · 11.2s · 45,752 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 5t · 9.6s · 32,213 tok | · | · |
| gs-securing-web | compile-error | green · 5t · 10.5s · 31,766 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 11.3s · 31,958 tok | · | · |
| gs-spring-boot | compile-error | green · 5t · 7.7s · 32,285 tok | · | · |
| gs-testing-web | compile-error | green · 5t · 11.0s · 31,635 tok | · | · |
| gs-uploading-files | compile-error | green · 5t · 8.9s · 32,476 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 9.8s · 31,916 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 7.2s · 31,289 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 12.1s · 31,931 tok | · | · |
| junit-starter-maven | compile-error | green · 5t · 8.5s · 31,620 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 8.2s · 31,977 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 12.1s · 31,549 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 13.4s · 63,695 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 5t · 9.5s · 32,887 tok | · | · |
