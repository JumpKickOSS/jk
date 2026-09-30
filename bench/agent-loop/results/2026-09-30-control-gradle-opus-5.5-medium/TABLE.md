# Agent loop: turns, tokens and wall to green

Date: 2026-09-30 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 23 (scenario × tool) runs on this host · subset compile-errors

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · subset compile-errors · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gradle | 23 | 23 | 100% | 5 | 5 | 21,382 | 21,823 | 12.1s | 18.7s | $0.81 | 3 | 2 | 379 | 8 | 18,432 | $0.0353 | 23 exact · 0 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | · | · | green · 5t · 13.2s · 21,770 tok |
| gs-accessing-data-r2dbc | compile-error | · | · | green · 4t · 10.0s · 21,382 tok |
| gs-accessing-data-rest | compile-error | · | · | green · 5t · 13.9s · 21,654 tok |
| gs-actuator-service | compile-error | · | · | green · 5t · 12.9s · 21,490 tok |
| gs-batch-processing | compile-error | · | · | green · 5t · 11.3s · 21,442 tok |
| gs-consuming-rest | compile-error | · | · | green · 4t · 10.3s · 21,114 tok |
| gs-graphql-server | compile-error | · | · | green · 5t · 12.2s · 21,503 tok |
| gs-handling-form-submission | compile-error | · | · | green · 4t · 10.9s · 21,248 tok |
| gs-multi-module | compile-error | · | · | green · 5t · 12.1s · 21,639 tok |
| gs-reactive-rest-service | compile-error | · | · | green · 4t · 13.6s · 21,264 tok |
| gs-rest-hateoas | compile-error | · | · | green · 4t · 11.1s · 21,133 tok |
| gs-rest-service | compile-error | · | · | green · 4t · 11.4s · 21,002 tok |
| gs-scheduling-tasks | compile-error | · | · | green · 5t · 16.9s · 21,823 tok |
| gs-securing-web | compile-error | · | · | green · 4t · 11.6s · 21,031 tok |
| gs-serving-web-content | compile-error | · | · | green · 4t · 10.7s · 21,144 tok |
| gs-spring-boot | compile-error | · | · | green · 4t · 12.1s · 20,951 tok |
| gs-testing-web | compile-error | · | · | green · 5t · 18.7s · 21,389 tok |
| gs-uploading-files | compile-error | · | · | green · 4t · 13.0s · 21,156 tok |
| gs-validating-form-input | compile-error | · | · | green · 4t · 10.5s · 21,157 tok |
| junit-starter-gradle | compile-error | · | · | green · 5t · 9.2s · 21,382 tok |
| junit-starter-gradle-kotlin | compile-error | · | · | green · 5t · 11.0s · 21,748 tok |
| spring-petclinic-kotlin | compile-error | · | · | green · 5t · 21.7s · 46,402 tok |
| tut-spring-boot-kotlin | compile-error | · | · | green · 5t · 21.0s · 22,322 tok |
