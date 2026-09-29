# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 57 (scenario × tool) runs on this host · subset beyond-guides

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-opus-5-5 · effort medium · subset beyond-guides · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 27 | 100% | 6 | 9 | 30,028 | 55,676 | 13.8s | 27.7s | $1.80 | 4.5 | 1 | 875 | 8 | 25,561 | $0.0668 | 17 exact · 9 equivalent · 1 collateral · 0 cheat |
| mvn | 15 | 15 | 100% | 6 | 8 | 25,595 | 53,143 | 18.7s | 58.9s | $0.98 | 5 | 2 | 553 | 8 | 20,322 | $0.0651 | 11 exact · 4 equivalent · 0 collateral · 0 cheat |
| gradle | 15 | 15 | 100% | 6 | 10 | 31,098 | 87,052 | 19.3s | 32.6s | $1.30 | 5 | 3 | 597 | 10 | 26,035 | $0.0869 | 12 exact · 3 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 5t · 9.7s · 22,175 tok | green · 5t · 18.7s · 21,591 tok | · |
| commons-cli | failing-assertion | green · 8t · 18.5s · 34,059 tok | green · 6t · 19.8s · 26,307 tok | · |
| commons-cli | missing-dependency | green · 5t · 10.7s · 24,628 tok | green · 7t · 28.7s · 53,143 tok | · |
| gs-multi-module | compile-error | green · 5t · 10.1s · 22,053 tok | green · 5t · 12.8s · 21,735 tok | green · 4t · 11.2s · 20,927 tok |
| gs-multi-module | failing-assertion | green · 9t · 24.1s · 39,289 tok | green · 8t · 20.5s · 37,589 tok | green · 9t · 22.0s · 39,331 tok |
| gs-multi-module | missing-resource | green · 7t · 10.3s · 29,265 tok | · | green · 7t · 19.3s · 29,828 tok |
| gs-multi-module | version-conflict | green · 8t · 13.8s · 30,303 tok | green · 6t · 14.4s · 25,595 tok | green · 7t · 13.9s · 30,067 tok |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 8.8s · 22,346 tok | · | green · 5t · 11.4s · 21,628 tok |
| junit-starter-gradle-kotlin | failing-assertion | green · 7t · 14.5s · 30,886 tok | · | green · 8t · 17.6s · 31,098 tok |
| junit-starter-gradle-kotlin | missing-dependency | green · 5t · 9.3s · 23,286 tok | · | green · 5t · 11.1s · 25,229 tok |
| junit-starter-maven | compile-error | green · 5t · 7.8s · 21,985 tok | green · 5t · 10.7s · 21,357 tok | · |
| junit-starter-maven | failing-assertion | green · 7t · 15.8s · 30,028 tok | green · 5t · 10.7s · 22,887 tok | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 8.8s · 22,337 tok | green · 5t · 12.1s · 21,833 tok | · |
| junit-starter-maven-kotlin | failing-assertion | green · 6t · 13.8s · 24,194 tok | green · 7t · 16.4s · 29,118 tok | · |
| junit-starter-maven-kotlin | missing-dependency | green · 5t · 11.0s · 22,909 tok | green · 5t · 14.3s · 25,260 tok | · |
| spring-petclinic | compile-error | green · 5t · 8.7s · 21,819 tok | green · 5t · 52.7s · 21,554 tok | · |
| spring-petclinic | failing-assertion | green · 7t · 14.2s · 33,448 tok | green · 6t · 56.3s · 25,980 tok | · |
| spring-petclinic | missing-dependency | green · 4t · 37.1s · 21,938 tok | green · 7t · 58.9s · 31,802 tok | · |
| spring-petclinic | missing-resource | green · 10t · 30.3s · 55,676 tok | green · 18t · 129.3s · 161,236 tok | · |
| spring-petclinic-kotlin | compile-error | green · 6t · 13.2s · 47,231 tok | · | green · 5t · 21.4s · 46,266 tok |
| spring-petclinic-kotlin | failing-assertion | green · 8t · 15.9s · 76,665 tok | · | green · 6t · 20.4s · 49,486 tok |
| spring-petclinic-kotlin | missing-dependency | green · 5t · 16.7s · 48,710 tok | · | green · 5t · 26.0s · 51,351 tok |
| spring-petclinic-kotlin | missing-resource | green · 8t · 27.7s · 68,628 tok | · | green · 9t · 32.6s · 87,052 tok |
| tut-spring-boot-kotlin | compile-error | green · 5t · 8.2s · 22,899 tok | · | green · 5t · 19.1s · 22,202 tok |
| tut-spring-boot-kotlin | failing-assertion | green · 10t · 23.5s · 44,379 tok | · | green · 10t · 24.8s · 40,810 tok |
| tut-spring-boot-kotlin | missing-dependency | green · 14t · 35.3s · 73,625 tok | · | green · 6t · 18.6s · 30,716 tok |
| tut-spring-boot-kotlin | missing-resource | green · 8t · 16.9s · 35,883 tok | · | green · 15t · 47.3s · 167,952 tok |
