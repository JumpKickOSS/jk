# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 27 (scenario × tool) runs on this host · subset compile-errors

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · subset compile-errors · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 27 | 100% | 5 | 5 | 21,137 | 21,450 | 8.2s | 9.1s | $0.86 | 3 | 1 | 437 | 8 | 18,655 | $0.0319 | 27 exact · 0 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 8.5s · 21,076 tok | · | · |
| gs-accessing-data-jpa | compile-error | green · 5t · 7.0s · 21,304 tok | · | · |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 7.2s · 21,450 tok | · | · |
| gs-accessing-data-rest | compile-error | green · 5t · 11.8s · 21,087 tok | · | · |
| gs-actuator-service | compile-error | green · 5t · 9.1s · 20,980 tok | · | · |
| gs-batch-processing | compile-error | green · 5t · 6.5s · 21,137 tok | · | · |
| gs-consuming-rest | compile-error | green · 5t · 8.2s · 21,191 tok | · | · |
| gs-graphql-server | compile-error | green · 5t · 6.6s · 21,112 tok | · | · |
| gs-handling-form-submission | compile-error | green · 5t · 8.8s · 21,230 tok | · | · |
| gs-multi-module | compile-error | green · 5t · 8.6s · 21,156 tok | · | · |
| gs-reactive-rest-service | compile-error | green · 6t · 10.2s · 21,843 tok | · | · |
| gs-rest-hateoas | compile-error | green · 5t · 7.5s · 15,898 tok | · | · |
| gs-rest-service | compile-error | green · 4t · 8.6s · 20,656 tok | · | · |
| gs-scheduling-tasks | compile-error | green · 5t · 9.5s · 21,436 tok | · | · |
| gs-securing-web | compile-error | green · 5t · 8.6s · 21,135 tok | · | · |
| gs-serving-web-content | compile-error | green · 5t · 8.2s · 21,151 tok | · | · |
| gs-spring-boot | compile-error | green · 5t · 6.6s · 21,052 tok | · | · |
| gs-testing-web | compile-error | green · 5t · 8.3s · 21,036 tok | · | · |
| gs-uploading-files | compile-error | green · 4t · 7.5s · 20,764 tok | · | · |
| gs-validating-form-input | compile-error | green · 5t · 8.5s · 21,170 tok | · | · |
| junit-starter-gradle | compile-error | green · 5t · 7.8s · 21,053 tok | · | · |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 6.9s · 21,319 tok | · | · |
| junit-starter-maven | compile-error | green · 5t · 7.1s · 21,051 tok | · | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 6.4s · 21,317 tok | · | · |
| spring-petclinic | compile-error | green · 5t · 8.5s · 20,907 tok | · | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 7.7s · 45,295 tok | · | · |
| tut-spring-boot-kotlin | compile-error | green · 5t · 8.5s · 21,879 tok | · | · |
