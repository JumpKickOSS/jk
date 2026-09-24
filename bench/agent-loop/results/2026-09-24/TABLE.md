# Agent loop: turns, tokens and wall to green

Date: 2026-09-24 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: scripted · 165 (scenario × tool) runs on this host

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. Turns are the agent's fix-and-rerun cycles (API turns for the LLM drivers); tokens are the API's input + output including cache reads and writes; wall is the agent's time only. A row is green only when the harness's own rerun after the agent stopped is green too. Median and p90 are over this host's runs of the tool, red runs at their budget. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `scripted` · budget 8 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost |
|---|---|---|---|---|---|---|---|---|---|---|
| jk | 63 | 62 | 98% | 1 | 1 | 0 | 0 | 0.3s | 3.3s | — |
| mvn | 39 | 39 | 100% | 1 | 1 | 0 | 0 | 6.1s | 7.8s | — |
| gradle | 63 | 63 | 100% | 1 | 1 | 0 | 0 | 4.4s | 6.0s | — |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| gs-accessing-data-jpa | compile-error | green · 1t · 0.3s | green · 1t · 6.7s | green · 1t · 5.2s |
| gs-accessing-data-jpa | failing-assertion | green · 1t · 3.8s | green · 1t · 6.5s | green · 1t · 4.8s |
| gs-accessing-data-jpa | missing-dependency | green · 1t · 3.3s | green · 1t · 6.7s | green · 1t · 5.3s |
| gs-accessing-data-r2dbc | compile-error | green · 1t · 0.4s | green · 1t · 5.7s | green · 1t · 4.2s |
| gs-accessing-data-r2dbc | failing-assertion | green · 1t · 0.3s | green · 1t · 5.3s | green · 1t · 3.7s |
| gs-accessing-data-r2dbc | missing-dependency | green · 1t · 2.4s | green · 1t · 5.5s | green · 1t · 4.1s |
| gs-accessing-data-r2dbc | missing-resource | green · 1t · 0.3s | green · 1t · 4.4s | green · 1t · 3.4s |
| gs-accessing-data-rest | compile-error | green · 1t · 0.4s | green · 1t · 7.1s | green · 1t · 5.7s |
| gs-accessing-data-rest | failing-assertion | green · 1t · 0.3s | green · 1t · 7.8s | green · 1t · 6.0s |
| gs-accessing-data-rest | missing-dependency | green · 1t · 4.5s | green · 1t · 8.0s | green · 1t · 6.3s |
| gs-actuator-service | compile-error | green · 1t · 0.4s | green · 1t · 6.2s | green · 1t · 5.1s |
| gs-actuator-service | failing-assertion | green · 1t · 0.3s | green · 1t · 6.5s | green · 1t · 4.8s |
| gs-actuator-service | missing-dependency | green · 1t · 3.2s | green · 1t · 6.5s | green · 1t · 5.0s |
| gs-batch-processing | compile-error | green · 1t · 0.4s | green · 1t · 5.0s | green · 1t · 3.6s |
| gs-batch-processing | failing-assertion | green · 1t · 0.3s | green · 1t · 4.8s | green · 1t · 3.4s |
| gs-batch-processing | missing-dependency | green · 1t · 2.3s | green · 1t · 4.8s | green · 1t · 3.4s |
| gs-batch-processing | missing-resource | green · 1t · 0.2s | green · 1t · 4.3s | green · 1t · 3.3s |
| gs-consuming-rest | compile-error | green · 1t · 0.3s | green · 1t · 4.8s | green · 1t · 3.6s |
| gs-consuming-rest | missing-dependency | green · 1t · 1.9s | green · 1t · 5.0s | green · 1t · 3.7s |
| gs-graphql-server | compile-error | green · 1t · 0.3s | · | green · 1t · 4.4s |
| gs-graphql-server | failing-assertion | green · 1t · 0.3s | · | green · 1t · 4.9s |
| gs-graphql-server | missing-dependency | green · 1t · 2.5s | · | green · 1t · 5.0s |
| gs-graphql-server | missing-resource | green · 1t · 0.2s | · | green · 1t · 3.8s |
| gs-handling-form-submission | compile-error | green · 1t · 0.6s | · | green · 1t · 4.0s |
| gs-handling-form-submission | failing-assertion | green · 1t · 0.3s | · | green · 1t · 3.3s |
| gs-handling-form-submission | missing-dependency | green · 1t · 2.4s | · | green · 1t · 3.8s |
| gs-handling-form-submission | missing-resource | green · 1t · 0.3s | · | green · 1t · 3.5s |
| gs-reactive-rest-service | compile-error | green · 1t · 0.4s | green · 1t · 8.2s | green · 1t · 6.7s |
| gs-reactive-rest-service | failing-assertion | green · 1t · 0.2s | green · 1t · 7.8s | green · 1t · 6.0s |
| gs-rest-hateoas | compile-error | green · 1t · 0.4s | green · 1t · 6.1s | green · 1t · 4.7s |
| gs-rest-hateoas | failing-assertion | green · 1t · 0.3s | green · 1t · 6.2s | green · 1t · 4.7s |
| gs-rest-hateoas | missing-dependency | green · 1t · 3.6s | green · 1t · 6.4s | green · 1t · 4.8s |
| gs-rest-service | compile-error | green · 1t · 0.6s | green · 1t · 5.3s | green · 1t · 4.0s |
| gs-rest-service | failing-assertion | green · 1t · 0.3s | green · 1t · 5.1s | green · 1t · 3.6s |
| gs-rest-service | missing-dependency | green · 1t · 2.4s | green · 1t · 5.2s | green · 1t · 3.8s |
| gs-rest-service | version-conflict | green · 1t · 0.4s | green · 1t · 4.4s | green · 1t · 3.6s |
| gs-scheduling-tasks | compile-error | green · 1t · 0.4s | · | green · 1t · 8.7s |
| gs-scheduling-tasks | failing-assertion | green · 1t · 0.2s | · | green · 1t · 8.3s |
| gs-scheduling-tasks | missing-dependency | green · 1t · 7.2s | · | green · 1t · 8.9s |
| gs-securing-web | compile-error | green · 1t · 0.3s | · | green · 1t · 4.6s |
| gs-securing-web | failing-assertion | green · 1t · 0.2s | · | green · 1t · 4.3s |
| gs-securing-web | missing-dependency | green · 1t · 2.9s | · | green · 1t · 4.3s |
| gs-securing-web | missing-resource | green · 1t · 0.3s | · | green · 1t · 4.7s |
| gs-serving-web-content | compile-error | green · 1t · 0.4s | · | green · 1t · 4.0s |
| gs-serving-web-content | failing-assertion | green · 1t · 0.3s | · | green · 1t · 3.4s |
| gs-serving-web-content | missing-resource | green · 1t · 0.3s | · | green · 1t · 3.7s |
| gs-spring-boot | compile-error | green · 1t · 0.3s | green · 1t · 6.1s | green · 1t · 5.0s |
| gs-spring-boot | failing-assertion | green · 1t · 0.2s | green · 1t · 6.2s | green · 1t · 4.7s |
| gs-spring-boot | missing-dependency | green · 1t · 3.4s | green · 1t · 6.1s | green · 1t · 4.9s |
| gs-spring-boot | version-conflict | green · 1t · 0.3s | green · 1t · 5.6s | green · 1t · 4.7s |
| gs-testing-web | compile-error | green · 1t · 0.4s | green · 1t · 6.8s | green · 1t · 6.0s |
| gs-testing-web | failing-assertion | green · 1t · 0.2s | green · 1t · 7.9s | green · 1t · 6.0s |
| gs-uploading-files | compile-error | green · 1t · 0.3s | green · 1t · 8.2s | green · 1t · 7.0s |
| gs-uploading-files | failing-assertion | green · 1t · 0.2s | green · 1t · 7.2s | green · 1t · 5.8s |
| gs-uploading-files | missing-dependency | green · 1t · 3.6s | green · 1t · 7.4s | green · 1t · 6.0s |
| gs-uploading-files | missing-resource | green · 1t · 0.3s | · | green · 1t · 5.6s |
| gs-validating-form-input | compile-error | green · 1t · 0.3s | green · 1t · 5.8s | green · 1t · 3.8s |
| gs-validating-form-input | missing-dependency | green · 1t · 3.3s | green · 1t · 5.3s | green · 1t · 3.9s |
| gs-validating-form-input | missing-resource | green · 1t · 0.3s | · | green · 1t · 3.6s |
| junit-starter-gradle | compile-error | green · 1t · 0.2s | · | green · 1t · 1.7s |
| junit-starter-gradle | failing-assertion | green · 1t · 0.2s | · | green · 1t · 1.8s |
| junit-starter-gradle | missing-dependency | green · 1t · 1.0s | · | green · 1t · 1.8s |
| junit-starter-gradle | version-conflict | **red** · 2t · 0.1s | · | green · 1t · 1.7s |

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
| junit-starter-gradle | version-conflict | jk | red | results-heuristic | results name nothing the oracle can act on (version-conflict: engine:### lock
```
`org.junit.jupiter:junit-jupiter` is declared without a vers) |
