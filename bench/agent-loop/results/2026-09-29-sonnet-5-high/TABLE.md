# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: claude-code · 165 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `claude-code` · claude-sonnet-5 · effort high · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 63 | 100% | 5 | 10 | 34,075 | 65,097 | 11.6s | 21.3s | $2.01 | 4 | 1 | 706 | 10 | 30,661 | $0.0319 | 30 exact · 27 equivalent · 6 collateral · 0 cheat |
| mvn | 39 | 38 | 97% | 6 | 11 | 44,357 | 87,277 | 14.7s | 33.2s | $1.77 | 6 | 2 | 693 | 12 | 39,021 | $0.0413 | 24 exact · 13 equivalent · 2 collateral · 0 cheat |
| gradle | 63 | 63 | 100% | 6 | 8 | 42,953 | 63,226 | 14.2s | 23.6s | $2.14 | 6 | 2 | 566 | 12 | 38,467 | $0.0340 | 36 exact · 20 equivalent · 7 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 5t · 7.8s · 34,899 tok | green · 7t · 22.3s · 48,257 tok | green · 5t · 19.5s · 34,191 tok |
| gs-accessing-data-jpa | failing-assertion | green · 10t · 12.5s · 53,273 tok | green · 7t · 17.9s · 44,399 tok | green · 6t · 17.4s · 42,418 tok |
| gs-accessing-data-jpa | missing-dependency | green · 4t · 5.9s · 26,992 tok | green · 11t · 33.2s · 125,856 tok | green · 6t · 13.9s · 58,027 tok |
| gs-accessing-data-r2dbc | compile-error | green · 5t · 10.5s · 35,895 tok | green · 5t · 12.4s · 34,986 tok | green · 5t · 10.8s · 34,936 tok |
| gs-accessing-data-r2dbc | failing-assertion | green · 11t · 17.5s · 61,891 tok | green · 6t · 14.4s · 49,879 tok | green · 7t · 21.0s · 60,885 tok |
| gs-accessing-data-r2dbc | missing-dependency | green · 4t · 13.9s · 27,291 tok | green · 5t · 14.7s · 46,137 tok | green · 6t · 13.5s · 60,443 tok |
| gs-accessing-data-r2dbc | missing-resource | green · 14t · 46.2s · 81,920 tok | green · 20t · 127.8s · 261,306 tok | green · 13t · 29.4s · 96,602 tok |
| gs-accessing-data-rest | compile-error | green · 5t · 8.1s · 32,862 tok | green · 5t · 12.7s · 32,103 tok | green · 5t · 13.0s · 32,133 tok |
| gs-accessing-data-rest | failing-assertion | green · 8t · 21.2s · 54,800 tok | green · 6t · 18.7s · 42,450 tok | green · 7t · 16.6s · 48,823 tok |
| gs-accessing-data-rest | missing-dependency | green · 4t · 7.8s · 25,623 tok | green · 5t · 16.4s · 37,225 tok | green · 5t · 16.3s · 36,240 tok |
| gs-actuator-service | compile-error | green · 5t · 8.9s · 32,835 tok | green · 5t · 12.3s · 31,916 tok | green · 5t · 12.4s · 32,072 tok |
| gs-actuator-service | failing-assertion | green · 9t · 24.3s · 69,745 tok | green · 9t · 18.5s · 54,686 tok | green · 8t · 31.2s · 68,006 tok |
| gs-actuator-service | missing-dependency | green · 6t · 10.1s · 41,026 tok | green · 5t · 14.1s · 36,581 tok | green · 6t · 14.1s · 42,903 tok |
| gs-batch-processing | compile-error | green · 5t · 12.2s · 32,727 tok | green · 5t · 11.2s · 31,973 tok | green · 5t · 10.5s · 32,129 tok |
| gs-batch-processing | failing-assertion | green · 7t · 21.3s · 55,631 tok | **red** · 17t · 123.5s · 253,406 tok | green · 6t · 26.1s · 45,659 tok |
| gs-batch-processing | missing-dependency | green · 4t · 9.0s · 26,913 tok | green · 12t · 37.5s · 170,731 tok | green · 6t · 16.6s · 65,932 tok |
| gs-batch-processing | missing-resource | green · 6t · 11.6s · 43,923 tok | green · 8t · 13.3s · 49,985 tok | green · 7t · 16.7s · 47,122 tok |
| gs-consuming-rest | compile-error | green · 5t · 7.9s · 33,753 tok | green · 5t · 10.3s · 32,934 tok | green · 5t · 11.1s · 32,928 tok |
| gs-consuming-rest | missing-dependency | green · 4t · 9.7s · 26,955 tok | green · 6t · 13.0s · 49,419 tok | green · 6t · 11.4s · 51,835 tok |
| gs-graphql-server | compile-error | green · 5t · 8.1s · 32,915 tok | · | green · 5t · 11.1s · 32,090 tok |
| gs-graphql-server | failing-assertion | green · 9t · 13.3s · 65,097 tok | · | green · 8t · 17.6s · 57,458 tok |
| gs-graphql-server | missing-dependency | green · 4t · 6.1s · 25,657 tok | · | green · 6t · 16.0s · 42,953 tok |
| gs-graphql-server | missing-resource | green · 13t · 14.3s · 60,738 tok | · | green · 11t · 16.0s · 65,649 tok |
| gs-handling-form-submission | compile-error | green · 5t · 8.4s · 33,075 tok | · | green · 5t · 10.2s · 32,336 tok |
| gs-handling-form-submission | failing-assertion | green · 9t · 18.9s · 60,108 tok | · | green · 6t · 11.7s · 42,818 tok |
| gs-handling-form-submission | missing-dependency | green · 4t · 12.3s · 25,714 tok | · | green · 6t · 12.8s · 42,878 tok |
| gs-handling-form-submission | missing-resource | green · 9t · 13.9s · 54,257 tok | · | green · 9t · 14.3s · 49,115 tok |
| gs-reactive-rest-service | compile-error | green · 5t · 7.5s · 33,277 tok | green · 5t · 13.5s · 32,631 tok | green · 5t · 13.6s · 32,565 tok |
| gs-reactive-rest-service | failing-assertion | green · 8t · 13.4s · 57,437 tok | green · 6t · 17.2s · 42,183 tok | green · 5t · 14.0s · 33,829 tok |
| gs-rest-hateoas | compile-error | green · 5t · 11.6s · 32,967 tok | green · 5t · 13.4s · 32,150 tok | green · 5t · 11.2s · 31,970 tok |
| gs-rest-hateoas | failing-assertion | green · 6t · 19.7s · 43,365 tok | green · 6t · 31.2s · 43,163 tok | green · 7t · 19.5s · 53,909 tok |
| gs-rest-hateoas | missing-dependency | green · 4t · 11.0s · 26,801 tok | green · 6t · 14.1s · 50,048 tok | green · 6t · 14.1s · 53,008 tok |
| gs-rest-service | compile-error | green · 5t · 8.1s · 32,590 tok | green · 5t · 12.7s · 31,758 tok | green · 5t · 11.0s · 31,941 tok |
| gs-rest-service | failing-assertion | green · 9t · 31.8s · 71,275 tok | green · 7t · 23.7s · 53,585 tok | green · 7t · 19.4s · 51,740 tok |
| gs-rest-service | missing-dependency | green · 5t · 13.2s · 34,338 tok | green · 5t · 11.4s · 37,085 tok | green · 5t · 12.2s · 37,156 tok |
| gs-rest-service | version-conflict | green · 4t · 6.7s · 25,449 tok | green · 6t · 12.0s · 44,357 tok | green · 6t · 12.4s · 43,271 tok |
| gs-scheduling-tasks | compile-error | green · 5t · 11.4s · 33,245 tok | · | green · 5t · 15.7s · 32,335 tok |
| gs-scheduling-tasks | failing-assertion | green · 7t · 12.0s · 49,146 tok | · | green · 8t · 17.6s · 44,192 tok |
| gs-scheduling-tasks | missing-dependency | green · 4t · 8.6s · 26,781 tok | · | green · 6t · 16.6s · 47,986 tok |
| gs-securing-web | compile-error | green · 5t · 9.2s · 32,765 tok | · | green · 5t · 11.9s · 32,031 tok |
| gs-securing-web | failing-assertion | green · 15t · 81.0s · 154,283 tok | · | green · 10t · 24.5s · 63,226 tok |
| gs-securing-web | missing-dependency | green · 5t · 11.2s · 34,075 tok | · | green · 6t · 12.9s · 42,524 tok |
| gs-securing-web | missing-resource | green · 8t · 21.3s · 43,911 tok | · | green · 8t · 14.2s · 47,962 tok |
| gs-serving-web-content | compile-error | green · 5t · 7.8s · 32,839 tok | · | green · 5t · 23.6s · 32,018 tok |
| gs-serving-web-content | failing-assertion | green · 11t · 19.5s · 68,549 tok | · | green · 7t · 17.2s · 51,830 tok |
| gs-serving-web-content | missing-resource | green · 10t · 15.2s · 56,913 tok | · | green · 8t · 26.2s · 62,271 tok |
| gs-spring-boot | compile-error | green · 5t · 7.5s · 33,386 tok | green · 5t · 11.7s · 32,435 tok | green · 5t · 18.4s · 32,606 tok |
| gs-spring-boot | failing-assertion | green · 10t · 28.3s · 66,470 tok | green · 11t · 39.1s · 87,277 tok | green · 8t · 41.2s · 61,276 tok |
| gs-spring-boot | missing-dependency | green · 6t · 13.3s · 40,664 tok | green · 6t · 15.6s · 42,042 tok | green · 6t · 16.8s · 41,250 tok |
| gs-spring-boot | version-conflict | green · 6t · 13.1s · 33,734 tok | green · 5t · 19.8s · 40,834 tok | green · 6t · 13.9s · 47,517 tok |
| gs-testing-web | compile-error | green · 5t · 12.1s · 32,795 tok | green · 5t · 12.0s · 31,765 tok | green · 5t · 12.5s · 31,933 tok |
| gs-testing-web | failing-assertion | green · 6t · 12.8s · 42,199 tok | green · 8t · 24.4s · 63,507 tok | green · 6t · 15.5s · 43,990 tok |
| gs-uploading-files | compile-error | green · 5t · 7.5s · 33,407 tok | green · 7t · 17.5s · 46,776 tok | green · 5t · 19.8s · 32,694 tok |
| gs-uploading-files | failing-assertion | green · 9t · 18.7s · 59,395 tok | green · 7t · 26.3s · 60,912 tok | green · 5t · 21.4s · 37,327 tok |
| gs-uploading-files | missing-dependency | green · 5t · 8.3s · 34,105 tok | green · 8t · 16.2s · 44,954 tok | green · 6t · 13.9s · 42,171 tok |
| gs-uploading-files | missing-resource | green · 9t · 16.3s · 63,262 tok | · | green · 8t · 14.1s · 49,600 tok |
| gs-validating-form-input | compile-error | green · 5t · 8.0s · 32,939 tok | green · 5t · 11.0s · 32,008 tok | green · 5t · 11.5s · 32,253 tok |
| gs-validating-form-input | missing-dependency | green · 4t · 6.1s · 25,850 tok | green · 6t · 13.7s · 45,388 tok | green · 6t · 11.9s · 44,022 tok |
| gs-validating-form-input | missing-resource | green · 9t · 13.3s · 53,271 tok | · | green · 10t · 16.1s · 80,002 tok |
| junit-starter-gradle | compile-error | green · 5t · 8.3s · 32,705 tok | · | green · 5t · 8.2s · 31,741 tok |
| junit-starter-gradle | failing-assertion | green · 7t · 13.1s · 50,163 tok | · | green · 6t · 12.0s · 42,314 tok |
| junit-starter-gradle | missing-dependency | green · 4t · 7.2s · 26,367 tok | · | green · 6t · 12.3s · 45,856 tok |
| junit-starter-gradle | version-conflict | green · 4t · 5.8s · 25,365 tok | · | green · 9t · 16.7s · 65,872 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-batch-processing | failing-assertion | mvn | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
