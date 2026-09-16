# Agent loop: turns, tokens and wall to green

Date: 2026-09-16 · jk 0.13.7 · drivers: api, claude-code, scripted · 169 (scenario × tool) runs

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. Turns are the agent's fix-and-rerun cycles (API turns for the LLM drivers); tokens are the API's input + output including cache reads and writes; wall is the agent's time only. A row is green only when the harness's own rerun after the agent stopped is green too. Median and p90 are over every run of the tool, red runs at their budget.

## `api` · claude-sonnet-5 · budget 8 turns / 10.0 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost |
|---|---|---|---|---|---|---|---|---|---|---|
| jk | 1 | 1 | 100% | 6 | 6 | 49,047 | 49,047 | 33.6s | 33.6s | $0.10 |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-rest-service | compile-error | green · 6t · 33.6s · 49,047 tok | · | · |

## `claude-code` · claude-sonnet-5 · budget 8 turns / 10.0 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost |
|---|---|---|---|---|---|---|---|---|---|---|
| jk | 1 | 1 | 100% | 9 | 9 | 45,982 | 45,982 | 29.3s | 29.3s | $0.04 |
| mvn | 1 | 1 | 100% | 6 | 6 | 23,623 | 23,623 | 26.0s | 26.0s | $0.02 |
| gradle | 1 | 1 | 100% | 6 | 6 | 23,807 | 23,807 | 14.2s | 14.2s | $0.02 |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-rest-service | missing-dependency | green · 9t · 29.3s · 45,982 tok | green · 6t · 26.0s · 23,623 tok | green · 6t · 14.2s · 23,807 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-rest-service | missing-dependency | jk | green | — | agent stopped (error_max_turns) |

## `scripted` · budget 8 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost |
|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 62 | 98% | 1 | 1 | 0 | 0 | 3.7s | 6.1s | — |
| mvn | 39 | 39 | 100% | 1 | 1 | 0 | 0 | 4.5s | 9.7s | — |
| gradle | 63 | 63 | 100% | 1 | 1 | 0 | 0 | 3.5s | 7.4s | — |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 1t · 4.3s | green · 1t · 8.5s | green · 1t · 7.5s |
| gs-accessing-data-jpa | failing-assertion | green · 1t · 4.1s | green · 1t · 4.6s | green · 1t · 3.5s |
| gs-accessing-data-jpa | missing-dependency | green · 1t · 4.0s | green · 1t · 4.6s | green · 1t · 3.7s |
| gs-accessing-data-r2dbc | compile-error | green · 1t · 3.2s | green · 1t · 4.8s | green · 1t · 4.4s |
| gs-accessing-data-r2dbc | failing-assertion | green · 1t · 0.6s | green · 1t · 4.5s | green · 1t · 3.4s |
| gs-accessing-data-r2dbc | missing-dependency | green · 1t · 5.1s | green · 1t · 4.9s | green · 1t · 4.2s |
| gs-accessing-data-r2dbc | missing-resource | green · 1t · 2.6s | green · 1t · 4.3s | green · 1t · 3.6s |
| gs-accessing-data-rest | compile-error | green · 1t · 5.7s | green · 1t · 5.9s | green · 1t · 5.1s |
| gs-accessing-data-rest | failing-assertion | green · 1t · 4.5s | green · 1t · 5.1s | green · 1t · 4.1s |
| gs-accessing-data-rest | missing-dependency | green · 1t · 5.3s | green · 1t · 5.5s | green · 1t · 4.7s |
| gs-actuator-service | compile-error | green · 1t · 5.0s | green · 1t · 4.2s | green · 1t · 3.6s |
| gs-actuator-service | failing-assertion | green · 1t · 4.8s | green · 1t · 4.0s | green · 1t · 3.2s |
| gs-actuator-service | missing-dependency | green · 1t · 4.8s | green · 1t · 7.4s | green · 1t · 6.3s |
| gs-batch-processing | compile-error | green · 1t · 3.3s | green · 1t · 4.2s | green · 1t · 3.5s |
| gs-batch-processing | failing-assertion | green · 1t · 2.7s | green · 1t · 3.0s | green · 1t · 2.0s |
| gs-batch-processing | missing-dependency | green · 1t · 2.5s | green · 1t · 3.0s | green · 1t · 2.1s |
| gs-batch-processing | missing-resource | green · 1t · 2.0s | green · 1t · 2.7s | green · 1t · 2.0s |
| gs-consuming-rest | compile-error | green · 1t · 2.8s | green · 1t · 3.7s | green · 1t · 4.3s |
| gs-consuming-rest | missing-dependency | green · 1t · 5.0s | green · 1t · 4.0s | green · 1t · 2.3s |
| gs-graphql-server | compile-error | green · 1t · 3.2s | · | green · 1t · 3.1s |
| gs-graphql-server | failing-assertion | green · 1t · 3.0s | · | green · 1t · 2.7s |
| gs-graphql-server | missing-dependency | green · 1t · 3.1s | · | green · 1t · 2.8s |
| gs-graphql-server | missing-resource | green · 1t · 2.4s | · | green · 1t · 2.4s |
| gs-handling-form-submission | compile-error | green · 1t · 2.8s | · | green · 1t · 2.5s |
| gs-handling-form-submission | failing-assertion | green · 1t · 2.7s | · | green · 1t · 2.2s |
| gs-handling-form-submission | missing-dependency | green · 1t · 2.7s | · | green · 1t · 2.2s |
| gs-handling-form-submission | missing-resource | green · 1t · 2.4s | · | green · 1t · 2.9s |
| gs-reactive-rest-service | compile-error | green · 1t · 6.1s | green · 1t · 10.2s | green · 1t · 6.9s |
| gs-reactive-rest-service | failing-assertion | green · 1t · 5.2s | green · 1t · 6.2s | green · 1t · 5.0s |
| gs-rest-hateoas | compile-error | green · 1t · 13.1s | green · 1t · 14.6s | green · 1t · 13.1s |
| gs-rest-hateoas | failing-assertion | green · 1t · 13.3s | green · 1t · 14.0s | green · 1t · 5.7s |
| gs-rest-hateoas | missing-dependency | green · 1t · 10.4s | green · 1t · 11.2s | green · 1t · 9.1s |
| gs-rest-service | compile-error | green · 1t · 3.7s | green · 1t · 4.5s | green · 1t · 3.3s |
| gs-rest-service | failing-assertion | green · 1t · 2.7s | green · 1t · 3.2s | green · 1t · 2.3s |
| gs-rest-service | missing-dependency | green · 1t · 3.2s | green · 1t · 3.7s | green · 1t · 2.7s |
| gs-rest-service | version-conflict | green · 1t · 2.8s | green · 1t · 3.4s | green · 1t · 3.0s |
| gs-scheduling-tasks | compile-error | green · 1t · 10.8s | · | green · 1t · 9.0s |
| gs-scheduling-tasks | failing-assertion | green · 1t · 9.1s | · | green · 1t · 8.2s |
| gs-scheduling-tasks | missing-dependency | green · 1t · 7.7s | · | green · 1t · 7.4s |
| gs-securing-web | compile-error | green · 1t · 3.5s | · | green · 1t · 3.2s |
| gs-securing-web | failing-assertion | green · 1t · 3.4s | · | green · 1t · 2.7s |
| gs-securing-web | missing-dependency | green · 1t · 3.4s | · | green · 1t · 2.8s |
| gs-securing-web | missing-resource | green · 1t · 2.5s | · | green · 1t · 2.6s |
| gs-serving-web-content | compile-error | green · 1t · 2.8s | · | green · 1t · 2.5s |
| gs-serving-web-content | failing-assertion | green · 1t · 3.0s | · | green · 1t · 2.3s |
| gs-serving-web-content | missing-resource | green · 1t · 2.2s | · | green · 1t · 2.8s |
| gs-spring-boot | compile-error | green · 1t · 4.6s | green · 1t · 4.5s | green · 1t · 3.6s |
| gs-spring-boot | failing-assertion | green · 1t · 4.2s | green · 1t · 4.3s | green · 1t · 3.2s |
| gs-spring-boot | missing-dependency | green · 1t · 4.2s | green · 1t · 4.5s | green · 1t · 3.5s |
| gs-spring-boot | version-conflict | green · 1t · 4.1s | green · 1t · 4.0s | green · 1t · 3.6s |
| gs-testing-web | compile-error | green · 1t · 4.8s | green · 1t · 6.0s | green · 1t · 5.3s |
| gs-testing-web | failing-assertion | green · 1t · 5.3s | green · 1t · 5.6s | green · 1t · 4.5s |
| gs-uploading-files | compile-error | green · 1t · 5.2s | green · 1t · 5.1s | green · 1t · 4.4s |
| gs-uploading-files | failing-assertion | green · 1t · 4.5s | green · 1t · 4.3s | green · 1t · 3.8s |
| gs-uploading-files | missing-dependency | green · 1t · 4.7s | green · 1t · 5.0s | green · 1t · 4.2s |
| gs-uploading-files | missing-resource | green · 1t · 3.8s | · | green · 1t · 3.5s |
| gs-validating-form-input | compile-error | green · 1t · 3.0s | green · 1t · 3.7s | green · 1t · 2.7s |
| gs-validating-form-input | missing-dependency | green · 1t · 4.0s | green · 1t · 9.7s | green · 1t · 7.5s |
| gs-validating-form-input | missing-resource | green · 1t · 3.9s | · | green · 1t · 4.6s |
| junit-starter-gradle | compile-error | green · 1t · 1.4s | · | green · 1t · 1.0s |
| junit-starter-gradle | failing-assertion | green · 1t · 1.2s | · | green · 1t · 1.2s |
| junit-starter-gradle | missing-dependency | green · 1t · 1.3s | · | green · 1t · 0.9s |
| junit-starter-gradle | version-conflict | **red** · 1t · 0.1s | · | green · 1t · 0.9s |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| gs-accessing-data-r2dbc | missing-resource | jk | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-accessing-data-r2dbc | missing-resource | mvn | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-accessing-data-r2dbc | missing-resource | gradle | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-accessing-data-rest | failing-assertion | jk | green | scenario | com.example.accessingdatarest.AccessingDataRestApplicationTests#shouldReturnRepositoryIndex() failed with a message that carries no expected/actual pair |
| gs-accessing-data-rest | failing-assertion | mvn | green | scenario | com.example.accessingdatarest.AccessingDataRestApplicationTests#shouldReturnRepositoryIndex failed with a message that carries no expected/actual pair |
| gs-accessing-data-rest | failing-assertion | gradle | green | scenario | com.example.accessingdatarest.AccessingDataRestApplicationTests#shouldReturnRepositoryIndex() failed with a message that carries no expected/actual pair |
| gs-batch-processing | missing-resource | jk | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-batch-processing | missing-resource | mvn | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| gs-batch-processing | missing-resource | gradle | green | git-status | the failing test's message names no resource; the deleted file came from git status |
| junit-starter-gradle | version-conflict | jk | red | — | results name nothing the oracle can act on (unnamed: unnamed:`run-tests`: 1 test failure) |
