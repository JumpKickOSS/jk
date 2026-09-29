# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 57 (scenario × tool) runs on this host · subset beyond-guides

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-sonnet-5 · effort high · subset beyond-guides · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 24 | 89% | 6 | 17 | 43,994 | 131,591 | 14.1s | 54.5s | $1.61 | 6 | 1 | 1,051 | 12 | 38,615 | $0.0406 | 14 exact · 11 equivalent · 2 collateral · 0 cheat |
| mvn | 15 | 14 | 93% | 5 | 8 | 41,595 | 70,733 | 16.7s | 53.3s | $0.75 | 5 | 2 | 672 | 10 | 37,136 | $0.0367 | 10 exact · 4 equivalent · 1 collateral · 0 cheat |
| gradle | 15 | 14 | 93% | 7 | 17 | 52,666 | 535,606 | 18.7s | 54.4s | $1.30 | 5 | 2 | 679 | 12 | 46,813 | $0.0701 | 7 exact · 6 equivalent · 2 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 7.7s · 33,031 tok | green · 5t · 16.7s · 32,549 tok | · |
| commons-cli | failing-assertion | green · 6t · 27.5s · 43,994 tok | green · 7t · 21.0s · 70,733 tok | · |
| commons-cli | missing-dependency | green · 5t · 9.6s · 36,711 tok | green · 5t · 15.3s · 62,820 tok | · |
| gs-multi-module | compile-error | green · 5t · 18.8s · 32,808 tok | green · 5t · 12.4s · 32,424 tok | green · 5t · 12.2s · 32,097 tok |
| gs-multi-module | failing-assertion | green · 10t · 16.1s · 58,379 tok | green · 8t · 19.8s · 53,485 tok | green · 7t · 16.8s · 52,302 tok |
| gs-multi-module | missing-resource | green · 18t · 20.1s · 93,084 tok | · | green · 13t · 17.7s · 63,782 tok |
| gs-multi-module | version-conflict | green · 8t · 14.1s · 42,986 tok | green · 8t · 16.9s · 46,637 tok | green · 7t · 13.8s · 43,020 tok |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 7.2s · 33,211 tok | · | green · 5t · 10.1s · 32,238 tok |
| junit-starter-gradle-kotlin | failing-assertion | green · 8t · 14.8s · 61,236 tok | · | green · 7t · 22.1s · 52,666 tok |
| junit-starter-gradle-kotlin | missing-dependency | green · 5t · 7.9s · 34,405 tok | · | green · 6t · 12.5s · 44,915 tok |
| junit-starter-maven | compile-error | green · 5t · 11.1s · 32,724 tok | green · 5t · 8.4s · 31,864 tok | · |
| junit-starter-maven | failing-assertion | green · 6t · 9.9s · 40,873 tok | green · 6t · 10.6s · 41,595 tok | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 7.5s · 33,209 tok | green · 5t · 11.0s · 32,506 tok | · |
| junit-starter-maven-kotlin | failing-assertion | green · 7t · 11.9s · 51,034 tok | green · 6t · 14.6s · 41,468 tok | · |
| junit-starter-maven-kotlin | missing-dependency | green · 5t · 9.9s · 33,759 tok | green · 5t · 12.9s · 37,589 tok | · |
| spring-petclinic | compile-error | green · 5t · 7.6s · 32,641 tok | green · 5t · 48.5s · 32,288 tok | · |
| spring-petclinic | failing-assertion | green · 8t · 54.5s · 70,736 tok | green · 6t · 53.3s · 46,437 tok | · |
| spring-petclinic | missing-dependency | green · 4t · 7.2s · 25,817 tok | green · 5t · 51.1s · 54,916 tok | · |
| spring-petclinic | missing-resource | **red** · 17t · 150.8s · 261,251 tok | **red** · 17t · 141.3s · 326,915 tok | · |
| spring-petclinic-kotlin | compile-error | green · 5t · 22.0s · 64,860 tok | · | green · 5t · 20.1s · 64,486 tok |
| spring-petclinic-kotlin | failing-assertion | green · 6t · 18.5s · 81,526 tok | · | green · 5t · 17.8s · 65,215 tok |
| spring-petclinic-kotlin | missing-dependency | green · 9t · 33.1s · 131,591 tok | · | green · 7t · 28.1s · 90,136 tok |
| spring-petclinic-kotlin | missing-resource | **red** · 17t · 69.8s · 324,088 tok | · | **red** · 17t · 54.4s · 565,314 tok |
| tut-spring-boot-kotlin | compile-error | green · 5t · 10.2s · 34,109 tok | · | green · 5t · 21.3s · 33,148 tok |
| tut-spring-boot-kotlin | failing-assertion | green · 8t · 13.4s · 65,535 tok | · | green · 8t · 18.7s · 72,560 tok |
| tut-spring-boot-kotlin | missing-dependency | **red** · 17t · 54.7s · 202,452 tok | · | green · 6t · 23.5s · 43,483 tok |
| tut-spring-boot-kotlin | missing-resource | green · 10t · 27.4s · 100,821 tok | · | green · 18t · 93.2s · 535,606 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| spring-petclinic | missing-resource | jk | red | — | agent stopped (error_max_turns) |
| spring-petclinic | missing-resource | mvn | red | — | agent stopped (error_max_turns) |
| spring-petclinic-kotlin | missing-resource | jk | red | — | agent stopped (error_max_turns) |
| spring-petclinic-kotlin | missing-resource | gradle | red | — | agent stopped (error_max_turns) |
| tut-spring-boot-kotlin | missing-dependency | jk | red | — | agent stopped (error_max_turns) |
