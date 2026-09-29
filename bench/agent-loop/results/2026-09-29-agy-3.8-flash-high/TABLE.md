# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: agy · 24 (scenario × tool) runs on this host · subset agent-subset

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `agy` · gemini-3.8-flash-high · effort high · subset agent-subset · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 8 | 7 | 88% | 12.5 | 16 | 132,351 | 172,781 | 41.6s | 74.4s | — | 9 | 4.5 | 3,419 | 126,544 | 0 | — | 4 exact · 3 equivalent · 0 collateral · 0 cheat |
| mvn | 8 | 6 | 75% | 13 | 16 | 148,982 | 240,340 | 39.3s | 44.8s | — | 9.5 | 8 | 2,831 | 124,352 | 0 | — | 5 exact · 1 equivalent · 0 collateral · 0 cheat |
| gradle | 8 | 5 | 62% | 11 | 16 | 117,143 | 228,260 | 40.5s | 51.7s | — | 9 | 6 | 2,523 | 114,831 | 0 | — | 6 exact · 0 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | failing-assertion | green · 11t · 28.1s · 115,022 tok | green · 12t · 38.0s · 133,101 tok | green · 11t · 36.5s · 114,588 tok |
| gs-accessing-data-r2dbc | missing-resource | green · 16t · 50.1s · 214,102 tok | **red** · 16t · 44.8s · 444,055 tok | **red** · 16t · 57.1s · 227,700 tok |
| gs-actuator-service | failing-assertion | green · 9t · 37.9s · 96,473 tok | green · 11t · 39.8s · 116,126 tok | green · 10t · 47.3s · 102,479 tok |
| gs-batch-processing | missing-resource | green · 14t · 74.4s · 168,780 tok | **red** · 16t · 36.0s · 225,284 tok | **red** · 16t · 39.8s · 256,969 tok |
| gs-consuming-rest | missing-dependency | green · 11t · 27.7s · 105,511 tok | green · 16t · 47.0s · 240,340 tok | **red** · 16t · 51.7s · 228,260 tok |
| gs-rest-service | version-conflict | **red** · 16t · 83.5s · 172,781 tok | green · 8t · 38.8s · 76,275 tok | green · 11t · 41.2s · 118,050 tok |
| gs-spring-boot | version-conflict | green · 14t · 45.2s · 149,681 tok | green · 14t · 44.1s · 164,864 tok | green · 11t · 33.1s · 116,236 tok |
| gs-testing-web | compile-error | green · 6t · 21.5s · 51,904 tok | green · 8t · 23.8s · 69,718 tok | green · 5t · 18.7s · 41,707 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-accessing-data-r2dbc | missing-resource | mvn | red | — | turn budget exhausted (16) |
| gs-accessing-data-r2dbc | missing-resource | gradle | red | — | turn budget exhausted (16) |
| gs-batch-processing | missing-resource | mvn | red | — | turn budget exhausted (16) |
| gs-batch-processing | missing-resource | gradle | red | — | turn budget exhausted (16) |
| gs-consuming-rest | missing-dependency | gradle | red | — | turn budget exhausted (16); the tree is green although the agent did not report it |
| gs-rest-service | version-conflict | jk | red | — | turn budget exhausted (16) |
