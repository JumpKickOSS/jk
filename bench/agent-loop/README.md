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
`$JK_BENCH_HOME/agent-loop`; set `$JK_BENCH_HOME` for the scratch root — default in
[`../benchtools.py`](../benchtools.py) `bench_home()`),
never in this tree. Maven and Gradle are the versions in [`../tools.toml`](../tools.toml): the
harness provisions those distributions through jk and runs the binaries directly. A pin older
than the latest GA, or a latest-GA check that cannot reach the network, refuses the run unless
`--allow-stale-tools` or `--offline-tools` is passed, in which case every row records
`stale_tools` or `offline_tools`. A repo build script that cannot run on the pinned Gradle is
`incompatible-with-latest-gradle` plus the first error line; the repo wrapper is not a fallback.
`.jdk-version`, when present, is ensured and used; if that fails the run is
`jdk-unavailable: <spec>` and the tool is not started. Otherwise the environment's Java is the
one recorded (`java_home` and the first line of `java -version`). Every results row and the
table header carry a host block and a host id (sha256 of cpu model, logical CPUs, RAM GiB
rounded, and the OS id from `/etc/os-release`, first 12 hex; `JK_BENCH_HOST` overrides it).
Rows written with no host id are host `bocabox`. Medians compare only the current host; other
hosts are a separate section.

## What a scenario is

`scenario --tool T --repo X --failure Y --out DIR` copies the tool's **green baseline** of repo X
into DIR and applies failure Y as an uncommitted working-tree edit. A baseline is the pinned commit
built and tested green once with that tool:

| Tool | Baseline contents | Run |
|---|---|---|
| `jk` | `jk import <build file>`, every Maven and Gradle build file removed (both families, whatever was imported), `jk.toml` + `jk-lock.toml` committed | `jk test` |
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
the guides also ship a `pom.xml`, so they have a Maven baseline too. Every repo compiles and
tests green under the pinned Gradle, under the pinned Maven where listed, and under jk after
`jk import` of its `build.gradle` with no hand edits.

The selection is one repository short of twenty and narrower than the design asked for (a mix of
Maven and Gradle, single- and multi-module, Kotlin). That is the honest result of the filter, not a
choice: fifty-two candidates were tried and thirty-three are rejected in
[CANDIDATES.md](CANDIDATES.md) with the exact reason. Almost every Maven project with a parent or
an imported BOM imports into jk with `version = "unresolved"` and cannot resolve; every Gradle file
with a `$property` version imports it literally; JUnit 4 suites do not run. Those rejects are the
import work the corpus waits on, and the matrix grows as they land — add the repo table, its
failures, run `scenario --baseline` and `--verify`.

Each baseline keeps only its own tool's build: a jk tree has neither Maven's nor Gradle's files,
a Maven tree has no Gradle build and a Gradle tree no `pom.xml`, so no agent can read the answer
out of another tool's build file. Baseline walls (tests included, warm caches) are in
`$AGENT_LOOP_HOME/baselines/<tool>/<repo>.BASELINE.json`, beside the tree rather than in it, so a
sandbox never carries harness state; the guides take 3–25 s per tool, the whole corpus a few
minutes. The marker records `baseline_format`; a missing or different value rebuilds the baseline.
`scenario --baseline` stops Gradle's daemons after each Gradle baseline that actually ran the
build, before the next repo. A baseline that is already current does not start Gradle and does not
stop it.

## Verification

`scenario --verify` injects every (repo × failure) into a fresh copy of each tool's baseline, runs
the tool, and requires the resulting `target/jk-results.md` to come from a non-zero exit, to name
the injected failure (the edited `file:line` for a compile failure, the failing test class for a
test failure, a resolution message for a version conflict) and to name nothing else. It then
reverts the tree, checks it clean, and after the last failure runs the tool once more, which must
be green. The table is [VERIFY.md](VERIFY.md), dated and stamped with the jk version; a red row
there is either a scenario to fix or a defect in the tool that produced the results file, and the
row says which. A targeted re-run (`--repo`, `--failure`, `--tool`) replaces its own rows in an
existing report and leaves the rest of the table in place. With `--jobs`, jk and Maven sandboxes
still run in parallel. Gradle sandboxes run one at a time: `gradle --stop` stops every daemon for
the pinned installation, so one job must not stop while another job's Gradle build is in flight.
The daemons are stopped when that sandbox's last Gradle build (the green run after revert) finishes.

`junit-starter-gradle` × `version-conflict` resolves the exact `junit-jupiter-api = 5.0.0` pin
under both jk and Gradle, then fails when the JUnit engine starts. No test class can be named.
The scenario accepts a results file that says the discovery failed (`failed to discover tests`,
`discovery exited`, or the same resolve wording Gradle uses).

## The wrappers: the null hypothesis

`wrappers/mvn-results` and `wrappers/gradle-results` run the pinned `mvn` / `gradle` binaries
(not `./mvnw`, `./gradlew`, or `jk mvn` / `jk gradle`, which would follow the repo wrapper),
keep the console verbatim in `target/<tool>.log`, and write
`target/jk-results.md` in **the same shape jk writes**: outcome headline, why-lines, `Tests:` and
`Diagnostics:` counts, `## Files`, `## Failures` with `file:line:col` and the compiler's snippet,
`## Tests` with the per-package table and each failing test's message and stack clipped to 24
lines, `## Failed steps`, `## Warnings`, `## Modules`. Compiler errors come from the console
(`[ERROR] /File.java:[12,5] …` for Maven; `/File.java:12: error: …` and Kotlin's
`e: file:///File.kt:12:5 …` for Gradle); tests come from the JUnit XML under
`target/surefire-reports`, `target/failsafe-reports` and `build/test-results`. `target/jk-diagnostics.json`
carries the parsed structure. `wrappers/results-mcp --dir <project> --tool mvn|gradle` is a stdio
JSON-RPC MCP server exposing `run`, `results` and `diagnostics`: the wrapper's rerun and the two
readers an agent reaches for first with jk (`run`, `run`, `diagnostics`).

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
shared engine's, every call scoped to the sandbox with `dir` — the first such call binds the
connection, so there is no `bind` turn; Maven and Gradle: `wrappers/results-mcp`), and hands an
agent one fixed system prompt: the build is red, make it green, use only these tools, stop when the
results say OK. jk's default `tools/list` is `run`, `diagnostics`, `deps`, `why`, and `skill`; the wrapper's
is its three. The agent loops until green or the
budget ends (`--max-turns 16`, `--max-minutes 10` by default). The harness then reruns the tool
itself; a row is **green** only when that rerun is green too, **claimed** when the agent said
green and the rerun disagrees, **red** when the budget ran out. After that rerun a Gradle scenario
stops the pinned Gradle's daemons, so a matrix does not leave a daemon and compiler workers per
sandbox. The red run before the agent is what starts the daemon; the agent's builds keep it, and
the row records `gradle_daemon: "warm"`. jk and Maven runs are unchanged. Every row records turns,
input and output tokens (cache reads and writes counted; `output_tokens` and `reasoning_tokens`
for every LLM driver), the agent's wall, the outcome, the fix sources, the signal-quality fields
below, and a finding when the run had something to say; the transcript sits beside the sandbox under
`$AGENT_LOOP_HOME/harness/<date>/<driver>/<tool>/<repo>.<failure>/`.

| Driver | What drives the loop | Cost |
|---|---|---|
| `scripted` | a deterministic oracle: classify the failure from the results file, apply that class's known fix, rerun through MCP | none |
| `claude-code` | `claude -p` in the sandbox with the tool's MCP server, file tools only (no shell, no git), `--max-turns` as the budget | the API's |
| `grok` | `grok -p` in the sandbox with the tool's MCP server, file tools only (`read_file`, `search_replace`, `grep`, `list_dir`; no shell, no web, no subagents) under `--sandbox agent-loop`, `--max-turns` as the budget (`grok-4.7`, effort `high`) | the API's |
| `api` | a Messages-API tool-use loop (`claude-sonnet-5` default): MCP tools bridged through the harness, file tools confined to the sandbox; needs `ANTHROPIC_API_KEY` and the `anthropic` SDK | the API's |

Each grok run keeps its MCP config and sessions in a throwaway `GROK_HOME` that is deleted when the run ends (`GROK_MEMORY=0`, vendor compatibility off), with the login passed as a copy via `GROK_AUTH_PATH`; `--sandbox agent-loop` denies reads of the user's grok state, the local artifact cache (`~/.jk/store/repos`), sibling runs and the harness sources, so file tools stay on the project and nothing carries from one scenario to the next. The profile extends grok's `workspace` profile (read anywhere; write the project, `/tmp`, and `~/.grok`) and adds a `read_write` grant for the Gradle user home (`GRADLE_USER_HOME`, otherwise `~/.gradle`), the same home the baselines use. Gradle writes a lock beside `libnative-platform.so` there; without the grant the in-sandbox client cannot start. A warm Maven build only reads `~/.m2`, which `workspace` already allows. The pinned `mvn` and `gradle` binaries live under `~/.jk/store/tools`, which is not denied. jk's engine is not a child of grok, so the `repos` deny does not change its builds. Grok does not put MCP tools on the model's function list: there is no config key or flag for that, so the model calls them through `search_tool` and `use_tool`.

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
per tool for turns, tokens and wall, the green rate, the signal-quality columns below, one line per
scenario, the findings). A re-run of `--only` replaces its own rows and keeps the rest.
`harness --render` recomputes the signal-quality fields from the transcripts and sandboxes already
on disk and rewrites that table; it does not run an agent.

### What the columns mean

Turns under the cap are informational: the run stopped before the budget, and that count is not the
comparison. The comparison is the green rate, the cost to green, and signal quality.

`first_edit_turn` is the turn of the agent's first successful edit. `first_correct_edit_turn` is the
first of those that touches the injection's locus, or null when none does. The median skips the
nulls. The locus is the injected source file for a compile error or a failing assertion, the deleted
file for a missing resource, and the build file for a missing dependency or a version conflict
(`jk.toml` / `pom.xml` / `build.gradle`; for jk, `jk-lock.toml` is the same locus, because applying
a dependency writes it). An edit is `search_replace`, `Edit`, `Write`, `edit_file`, `write_file`,
or `jk_deps` with `apply: true`. The scripted oracle's `fix` event is an edit. A tool result with
`is_error` is not one.

`edits` counts those calls. `wrong_edits` counts the ones that touch a file outside the locus,
whether that file is still in the final diff or the agent later reverted it. An edit that only
touches the locus is not a wrong edit.

`reads_before_fix` counts file reads (`read_file`, `Read`) and results, diagnostics, and manual
calls that occur before the first locus edit. A read on that same turn that precedes the edit
counts; `grep`, directory listings, and build reruns do not. The scripted oracle records no tool
calls, so each of its turns counts as one results read placed before that turn's fix. When the
locus is never edited, the count is every such call in the run.

`fix_quality` compares the final diff of the sandbox with the injection's inverse. The diff is
tracked files plus untracked files, ignoring `target/`, `build/`, `.gradle/`, and `.kotlin/`.
`exact` means that diff is empty (the green baseline is restored). `collateral` means some other
file changed. `equivalent` means every remaining change stays in the locus and the locus is fixed:
either the injection's inverse is in the tree, or the agent edited and the harness rerun was green
(a same-file repair that is not byte-identical, such as an assertion set to the value the test
saw, or one starter declared as two). A green rerun with no edit does not count. `cheat` means a `src/test` source was deleted, the diff adds `@Disabled`,
`@Ignore`, or `assumeTrue(false)`, or a changed test source has fewer `@Test` /
`@ParameterizedTest` / `@RepeatedTest` annotations or fewer assertion calls than the baseline.
Cheat wins over the other three. A row that is none of these (the injection is still there, the
rerun was not green, nothing outside the locus changed, and there is no cheat) has a null quality
and is left out of the four counts.

The injection's inverse is in the tree when the original compile-error statement is back, the
original assertion text is back and the injected replacement is gone, the removed artifact id is
declared in the build file again, the strict or `:=` pin is gone, or the deleted resource exists
again.

Output+reasoning, uncached input, and cache-read are medians over the tool's runs. Cost to green is
the sum of `cost_usd` on green runs divided by the number of green runs. `reasoning_tokens` is 0
when the API reports reasoning only inside `output_tokens`.

`bench/jar-size/` is the neighbouring bench; this one measures the loop, that one the artefact.
