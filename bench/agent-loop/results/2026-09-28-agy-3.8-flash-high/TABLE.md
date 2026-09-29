# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: agy · 24 (scenario × tool) runs on this host · subset agent-subset

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `agy` · gemini-3.8-flash-high · effort high · subset agent-subset · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 8 | 5 | 62% | 14 | 16 | 153,964 | 195,027 | 55.5s | 72.8s | — | 10 | 6 | 3,554 | 131,983 | 0 | — | 4 exact · 3 equivalent · 0 collateral · 0 cheat |
| mvn | 8 | 5 | 62% | 11 | 16 | 114,302 | 277,701 | 43.5s | 62.6s | — | 8 | 5.5 | 2,748 | 111,560 | 4,049 | — | 5 exact · 0 equivalent · 0 collateral · 0 cheat |
| gradle | 8 | 6 | 75% | 10 | 16 | 101,534 | 309,097 | 54.3s | 65.0s | — | 7.5 | 5 | 2,342 | 99,192 | 2,025 | — | 6 exact · 0 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | failing-assertion | green · 12t · 50.9s · 138,613 tok | green · 12t · 45.3s · 125,516 tok | green · 10t · 55.6s · 102,285 tok |
| gs-accessing-data-r2dbc | missing-resource | **red** · 16t · 72.5s · 246,911 tok | **red** · 16t · 62.3s · 323,144 tok | **red** · 16t · 53.1s · 309,097 tok |
| gs-actuator-service | failing-assertion | green · 10t · 36.5s · 100,741 tok | green · 10t · 41.7s · 100,589 tok | green · 10t · 41.5s · 100,783 tok |
| gs-batch-processing | missing-resource | **red** · 16t · 72.8s · 195,027 tok | **red** · 16t · 69.9s · 277,701 tok | **red** · 16t · 73.2s · 339,087 tok |
| gs-consuming-rest | missing-dependency | green · 9t · 31.2s · 86,329 tok | **red** · 16t · 41.7s · 240,551 tok | green · 14t · 56.5s · 225,205 tok |
| gs-rest-service | version-conflict | green · 16t · 60.1s · 178,576 tok | green · 9t · 37.7s · 92,659 tok | green · 8t · 44.0s · 75,218 tok |
| gs-spring-boot | version-conflict | **red** · 16t · 78.7s · 169,315 tok | green · 10t · 62.6s · 103,089 tok | green · 9t · 65.0s · 92,678 tok |
| gs-testing-web | compile-error | green · 6t · 23.4s · 51,585 tok | green · 7t · 33.1s · 60,330 tok | green · 7t · 28.0s · 60,225 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-accessing-data-r2dbc | missing-resource | jk | red | — | turn budget exhausted (16) |
| gs-accessing-data-r2dbc | missing-resource | mvn | red | — | turn budget exhausted (16) |
| gs-accessing-data-r2dbc | missing-resource | gradle | red | — | turn budget exhausted (16) |
| gs-batch-processing | missing-resource | jk | red | — | turn budget exhausted (16); the tree is green although the agent did not report it |
| gs-batch-processing | missing-resource | mvn | red | — | turn budget exhausted (16) |
| gs-batch-processing | missing-resource | gradle | red | — | turn budget exhausted (16) |
| gs-consuming-rest | missing-dependency | mvn | red | — | turn budget exhausted (16) |
| gs-spring-boot | version-conflict | jk | red | — | turn budget exhausted (16); the tree is green although the agent did not report it |
