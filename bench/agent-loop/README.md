# Agent-loop scenario corpus

The fixed inputs behind the metric in [docs/user/why.md](../../docs/user/why.md#making-the-north-star-true):
median agent turns, tokens and wall time to green on a set of "agent breaks the build"
scenarios, jk against Maven and Gradle. This directory holds the corpus (public repositories
pinned by commit, each with injected failures), the one command that replays a scenario, the
comparator wrappers, and the harness that drives an agent over all of it and writes the table.

```bash
bench/agent-loop/scenario --list                                             # the repo × failure matrix
bench/agent-loop/scenario --tool jk --repo gs-rest-service --failure compile-error --out /tmp/s1
bench/agent-loop/scenario --tool mvn --repo gs-rest-service --failure missing-dependency --out /tmp/s2
bench/agent-loop/scenario --verify --jobs 2 --report bench/agent-loop/VERIFY.md
bench/agent-loop/harness --dry-run                                           # the (scenario × tool) matrix
bench/agent-loop/harness --driver scripted                                   # the oracle over the whole matrix
bench/agent-loop/harness --driver claude-code --only gs-rest-service:missing-dependency
```

Clones, green baselines and verify sandboxes live under `$AGENT_LOOP_HOME` (default
`/home/bsant/src/scratch/agent-loop`), never in this tree.

## What a scenario is

`scenario --tool T --repo X --failure Y --out DIR` copies the tool's **green baseline** of repo X
into DIR and applies failure Y as an uncommitted working-tree edit. A baseline is the pinned commit
built and tested green once with that tool:

| Tool | Baseline contents | Run |
|---|---|---|
| `jk` | `jk import <build file>`, the original Maven/Gradle files removed, `jk.toml` + `jk-lock.toml` committed | `jk test` |
| `gradle` | the repo's own `build.gradle` / wrapper | `wrappers/gradle-results test` |
| `mvn` | the repo's own `pom.xml` / wrapper | `wrappers/mvn-results test` |

Every tool starts from the same source tree and the same commit; `git diff` in the sandbox shows
exactly the injection, and `git checkout -- . && git clean -fd` reverts it.

## Failure classes

| Class | Edit | Expected signal |
|---|---|---|
| `compile-error` | a `;` removed from one statement in a main source file | a compile diagnostic at that `file:line` |
| `failing-assertion` | one expected value in one test changed | that test class fails, nothing else |
| `missing-dependency` | the dependency the code needs removed from the tool's build file | compile errors in the sources that used it |
| `version-conflict` | a transitive pinned to an incompatible version (Gradle: `strictly`) | a dependency-resolution failure, or the named test class where the tool resolves and fails later |
| `missing-resource` | a file the tests load deleted | the test classes that load it fail |

Two classes from the design are in the tool but have no rows in this corpus: `wrong-java-release`,
because jk refuses `java` below 17 and no accepted repo uses a post-17 language feature, so a
release below the code's needs cannot be expressed for all three tools; and
`broken-annotation-processor`, because no repo that imports cleanly into jk uses Lombok, MapStruct
or another processor (the two MapStruct examples are in [CANDIDATES.md](CANDIDATES.md) with
their import failures). Both injectors exist so a repo that imports later can use them.

## The corpus

Nineteen repositories, [`scenarios.toml`](scenarios.toml), all Gradle single-module Java
projects: eighteen Spring guides (`complete/` of each) and the JUnit 5 Gradle starter. Thirteen of
the guides also ship a `pom.xml`, so they have a Maven baseline too and the Maven wrapper is
exercised on real projects. Every repo compiles and tests green under `jk gradle`, under `jk mvn`
where listed, and under jk 0.13.7 after `jk import` of its `build.gradle` with no hand edits.

The selection is one repository short of twenty and narrower than the design asked for (a mix of
Maven and Gradle, single- and multi-module, Kotlin). That is the honest result of the filter, not a
choice: fifty-two candidates were tried and thirty-three are rejected in
[CANDIDATES.md](CANDIDATES.md) with the exact reason. Almost every Maven project with a parent or
an imported BOM imports into jk with `version = "unresolved"` and cannot resolve; every Gradle file
with a `$property` version imports it literally; JUnit 4 suites do not run. Those rejects are the
import work the corpus waits on, and the matrix grows as they land — add the repo table, its
failures, run `scenario --baseline` and `--verify`.

Baseline walls (tests included, warm caches) are in each `BASELINE.json` under
`$AGENT_LOOP_HOME/baselines/<tool>/<repo>/`; the guides take 3–25 s per tool, the whole corpus a
few minutes.

## Verification

`scenario --verify` injects every (repo × failure) into a fresh copy of each tool's baseline, runs
the tool, and requires the resulting `target/jk-results.md` to come from a non-zero exit, to name
the injected failure (the edited `file:line` for a compile failure, the failing test class for a
test failure, a resolution message for a version conflict) and to name nothing else. It then
reverts the tree, checks it clean, and after the last failure runs the tool once more, which must
be green. The table is [VERIFY.md](VERIFY.md), dated and stamped with the jk version; a red row
there is either a scenario to fix or a defect in the tool that produced the results file, and the
row says which. A targeted re-run (`--repo`, `--failure`, `--tool`) replaces its own rows in an
existing report and leaves the rest of the table in place.

One row is red on purpose: `junit-starter-gradle` × `version-conflict` under jk. An exact
`junit-jupiter-api = 5.0.0` pin beside `junit-jupiter 6.1.3` resolves without complaint under jk
and under Gradle; the JUnit engine then fails to start. Gradle's console says so and the wrapper
carries it into the results file; jk's results file says `run-tests: 1 test failure` and names no
test, no exception and no message, so an agent gets nothing to act on. That is a jk defect the
row keeps visible until it is fixed.

## The wrappers: the null hypothesis

`wrappers/mvn-results` and `wrappers/gradle-results` run the tool through `jk mvn` / `jk gradle`
(so provisioning is uniform), keep the console verbatim in `target/<tool>.log`, and write
`target/jk-results.md` in **the same shape jk writes**: outcome headline, why-lines, `Tests:` and
`Diagnostics:` counts, `## Files`, `## Failures` with `file:line:col` and the compiler's snippet,
`## Tests` with the per-package table and each failing test's message and stack clipped to 24
lines, `## Failed steps`, `## Warnings`, `## Modules`. Compiler errors come from the console
(`[ERROR] /File.java:[12,5] …` for Maven; `/File.java:12: error: …` and Kotlin's
`e: file:///File.kt:12:5 …` for Gradle); tests come from the JUnit XML under
`target/surefire-reports`, `target/failsafe-reports` and `build/test-results`. `target/jk-diagnostics.json`
carries the parsed structure. `wrappers/results-mcp --dir <project> --tool mvn|gradle` is a stdio
JSON-RPC MCP server exposing `run`, `results` and `diagnostics`: the wrapper's rerun and the two
readers an agent reaches for first with jk (`jk_run`, `jk_results`, `jk_diagnostics`).

These wrappers are built **deliberately well**. They are the benchmark's null hypothesis: the
cheapest thing Maven or Gradle could ship tomorrow — a results file and two MCP tools over the
output they already produce. If jk does not beat an agent equipped with them by a wide margin, the
wrapper is the product. A weak comparator would make the number meaningless, so a wrapper defect
found on any scenario is fixed here, not left as an advantage. What the wrapper cannot do without
changing the tool — a repair hint, a token budget, a cache that makes the rerun cheap, a lockfile
the agent can edit — is exactly what the measurement is for.

## The harness: the loop, measured

`harness` runs the metric. For each (repo × failure × tool) it materialises the sandbox with
`scenario`, runs the tool once so the results file is red, starts the tool's MCP server (jk: the
shared engine's, every call scoped to the sandbox with `dir`; Maven and Gradle:
`wrappers/results-mcp`), and hands an agent one fixed system prompt: the build is red, make it
green, use only these tools, stop when the results say OK. The agent loops until green or the
budget ends (`--max-turns 8`, `--max-minutes 10` by default). The harness then reruns the tool
itself; a row is **green** only when that rerun is green too, **claimed** when the agent said
green and the rerun disagrees, **red** when the budget ran out. Every row records turns, input and
output tokens (cache reads and writes counted), the agent's wall, the outcome, the fix sources,
and a finding when the run had something to say; the transcript sits beside the sandbox under
`$AGENT_LOOP_HOME/harness/<date>/<driver>/<tool>/<repo>.<failure>/`.

| Driver | What drives the loop | Cost |
|---|---|---|
| `scripted` | a deterministic oracle: classify the failure from the results file, apply that class's known fix, rerun through MCP | none |
| `claude-code` | `claude -p` in the sandbox with the tool's MCP server, file tools only (no shell, no git), `--max-turns` as the budget | the API's |
| `api` | a Messages-API tool-use loop (`claude-sonnet-5` default): MCP tools bridged through the harness, file tools confined to the sandbox; needs `ANTHROPIC_API_KEY` and the `anthropic` SDK | the API's |

The oracle is the plumbing proof and the results-file audit in one. It never reads the injection
to decide what is wrong; the results file has to say. A compile locus with `';' expected` gets its
semicolon; `package X does not exist` maps the package to the artifact and declares it in the
tool's build file (Boot 3 and Boot 4 starter names both known); a resolve message or a
`NoSuchMethodError` under a failing test removes the exact pin it names; a template or document
named in a test's message is restored from the tree; an assertion message with an
expected/actual pair edits the failing test's line. Where the file names the failure but not the
edit, the oracle falls back to the scenario's inverse and records the gap as a finding
(`fix_sources` says `results`, `results-heuristic`, `git-status` or `scenario`); where the file
names nothing actionable it stops red and says so. Every finding is a fact about what the tool
told the agent, and the table's Findings section lists them per run.

Rows go to `results/<date>/rows.jsonl` and the rendered `results/<date>/TABLE.md` (median and p90
per tool for turns, tokens and wall, the green rate, one line per scenario, the findings). A
re-run of `--only` replaces its own rows and keeps the rest.

`bench/jar-size/` is the neighbouring bench; this one measures the loop, that one the artefact.
