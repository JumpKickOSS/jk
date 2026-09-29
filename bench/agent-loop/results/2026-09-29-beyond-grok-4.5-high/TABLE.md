# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: grok · 57 (scenario × tool) runs on this host · subset beyond-guides

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `grok` · grok-4.5 · effort high · subset beyond-guides · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 25 | 93% | 6 | 9 | 45,883 | 88,570 | 23.2s | 64.3s | $0.50 | 4 | 2 | 1,247 | 11,736 | 30,976 | $0.0201 | 14 exact · 9 equivalent · 2 collateral · 0 cheat |
| mvn | 15 | 12 | 80% | 8 | 16 | 60,306 | 285,965 | 53.4s | 158.9s | $0.57 | 5 | 3 | 2,806 | 19,243 | 42,880 | $0.0348 | 7 exact · 4 equivalent · 4 collateral · 0 cheat |
| gradle | 15 | 14 | 93% | 7 | 9 | 60,924 | 120,659 | 34.2s | 600.2s | $0.30 | 5 | 4 | 1,379 | 16,386 | 37,504 | $0.0212 | 9 exact · 4 equivalent · 1 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 6t · 10.4s · 36,644 tok | green · 6t · 18.0s · 36,036 tok | · |
| commons-cli | failing-assertion | green · 7t · 33.8s · 71,165 tok | green · 7t · 35.3s · 59,220 tok | · |
| commons-cli | missing-dependency | green · 5t · 14.5s · 35,049 tok | green · 12t · 61.3s · 192,783 tok | · |
| gs-multi-module | compile-error | green · 6t · 12.7s · 38,975 tok | green · 6t · 16.1s · 36,088 tok | green · 6t · 15.6s · 36,035 tok |
| gs-multi-module | failing-assertion | green · 7t · 28.0s · 57,217 tok | green · 8t · 50.4s · 66,933 tok | green · 7t · 44.5s · 60,924 tok |
| gs-multi-module | missing-resource | green · 8t · 30.7s · 72,337 tok | · | green · 8t · 29.9s · 69,330 tok |
| gs-multi-module | version-conflict | green · 9t · 31.2s · 82,267 tok | green · 8t · 25.6s · 60,306 tok | green · 7t · 24.2s · 52,228 tok |
| junit-starter-gradle-kotlin | compile-error | green · 6t · 11.5s · 37,135 tok | · | green · 6t · 15.1s · 35,693 tok |
| junit-starter-gradle-kotlin | failing-assertion | green · 7t · 28.2s · 56,328 tok | · | green · 8t · 27.9s · 65,351 tok |
| junit-starter-gradle-kotlin | missing-dependency | green · 6t · 17.8s · 45,883 tok | · | green · 7t · 19.0s · 55,791 tok |
| junit-starter-maven | compile-error | green · 6t · 36.7s · 38,845 tok | green · 7t · 12.6s · 41,939 tok | · |
| junit-starter-maven | failing-assertion | green · 7t · 23.2s · 53,266 tok | green · 8t · 19.9s · 53,451 tok | · |
| junit-starter-maven-kotlin | compile-error | green · 6t · 13.8s · 37,201 tok | **red** · 16t · 87.6s · 159,277 tok | · |
| junit-starter-maven-kotlin | failing-assertion | green · 7t · 30.5s · 52,982 tok | **red** · 16t · 99.0s · 180,165 tok | · |
| junit-starter-maven-kotlin | missing-dependency | green · 6t · 13.9s · 41,515 tok | **red** · 16t · 104.3s · 189,114 tok | · |
| spring-petclinic | compile-error | green · 6t · 12.8s · 39,142 tok | green · 7t · 53.4s · 42,768 tok | · |
| spring-petclinic | failing-assertion | green · 7t · 17.6s · 53,120 tok | green · 7t · 59.0s · 51,156 tok | · |
| spring-petclinic | missing-dependency | green · 5t · 11.6s · 32,961 tok | green · 15t · 271.5s · 442,908 tok | · |
| spring-petclinic | missing-resource | green · 13t · 135.5s · 335,160 tok | green · 12t · 158.9s · 285,965 tok | · |
| spring-petclinic-kotlin | compile-error | green · 6t · 13.3s · 37,974 tok | · | green · 6t · 33.9s · 37,103 tok |
| spring-petclinic-kotlin | failing-assertion | green · 7t · 22.3s · 49,627 tok | · | green · 9t · 58.0s · 108,229 tok |
| spring-petclinic-kotlin | missing-dependency | green · 9t · 64.3s · 108,110 tok | · | green · 9t · 61.4s · 107,286 tok |
| spring-petclinic-kotlin | missing-resource | green · 9t · 41.9s · 250,223 tok | · | green · 9t · 50.3s · 140,452 tok |
| tut-spring-boot-kotlin | compile-error | green · 6t · 14.1s · 38,200 tok | · | green · 5t · 600.2s |
| tut-spring-boot-kotlin | failing-assertion | **red** · 0t · 600.1s | · | **red** · 0t · 600.3s |
| tut-spring-boot-kotlin | missing-dependency | **red** · 2t · 600.2s | · | green · 7t · 90.2s · 64,249 tok |
| tut-spring-boot-kotlin | missing-resource | green · 7t · 43.6s · 88,570 tok | · | green · 8t · 34.2s · 120,659 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| junit-starter-maven-kotlin | compile-error | mvn | red | — | agent stopped (error_max_turns) |
| junit-starter-maven-kotlin | failing-assertion | mvn | red | — | agent stopped (error_max_turns) |
| junit-starter-maven-kotlin | missing-dependency | mvn | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| tut-spring-boot-kotlin | compile-error | gradle | green | — | time budget exhausted (600s) |
| tut-spring-boot-kotlin | failing-assertion | jk | red | — | time budget exhausted (600s) |
| tut-spring-boot-kotlin | failing-assertion | gradle | red | — | time budget exhausted (600s) |
| tut-spring-boot-kotlin | missing-dependency | jk | red | — | time budget exhausted (600s) |
