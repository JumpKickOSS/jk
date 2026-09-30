# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 27 (scenario × tool) runs on this host · subset compile-errors

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · subset compile-errors · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 27 | 100% | 5 | 5 | 21,049 | 21,284 | 7.9s | 8.7s | $0.83 | 3 | 1 | 431 | 8 | 18,587 | $0.0307 | 27 exact · 0 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 6.8s · 21,019 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 5t · 8.6s · 21,202 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 7.0s · 21,486 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 5t · 8.7s · 21,009 tok | · | · |
| gs-actuator-service | compile-error | green · 5t · 6.9s · 21,003 tok | · | · |
| gs-batch-processing | compile-error | green · 5t · 8.2s · 21,035 tok | · | · |
| gs-consuming-rest | compile-error | green · 5t · 8.5s · 21,089 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 6.7s · 21,010 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 7.2s · 21,128 tok | · | · |
| gs-multi-module | compile-error | green · 5t · 6.9s · 21,063 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 5t · 16.0s · 21,052 tok | · | · |
| gs-rest-hateoas | compile-error | green · 5t · 8.3s · 21,030 tok | · | · |
| gs-rest-service | compile-error | green · 5t · 10.0s · 20,951 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 5t · 8.3s · 21,039 tok | · | · |
| gs-securing-web | compile-error | green · 5t · 7.6s · 21,135 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 8.3s · 21,049 tok | · | · |
| gs-spring-boot | compile-error | green · 5t · 6.6s · 20,950 tok | · | · |
| gs-testing-web | compile-error | green · 5t · 9.7s · 20,934 tok | · | · |
| gs-uploading-files | compile-error | green · 5t · 8.6s · 21,008 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 6.5s · 21,068 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 6.4s · 20,984 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 6.9s · 21,283 tok | · | · |
| junit-starter-maven | compile-error | green · 5t · 6.4s · 20,982 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 8.0s · 21,284 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 7.2s · 21,202 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 8.1s · 45,254 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 5t · 7.9s · 21,846 tok | · | · |
