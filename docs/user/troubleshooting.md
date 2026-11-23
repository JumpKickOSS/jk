# Troubleshooting

How to diagnose a failing JumpKick build. This is the page to open when someone says
**“fix my failing build.”**

## First look: `jk results`

Every hosted run writes a high-level markdown report of the **whole invocation** — not a
JUnit rollup. The first screen is the outcome (`OK` / `FAIL` / `CANCELLED`), a non-zero
**exit N**, and up to three bullets of why it failed. Compile errors, crashed test workers,
and failed steps all count. Cache skips are not listed as failures. `details.jsonl` is the
exhaustive step log; this file points at it rather than duplicating it.

```bash
jk results              # latest jk-results.md
jk results -d    # that run's details.jsonl
```

On disk:

```text
{project}/target/jk-results.md
~/.jk/state/builds/projects/<key>/runs/<build-number>/jk-results.md
```

The two files are the same report. Prefer the command over `cat` so you do not have to
know the state-dir path.

**Agents:** run `jk skill` once. Then MCP `build` (the reply is the verdict) or
`jk -a`. Do not scrape the TTY. Do not turn on `--verbose` as your primary API.

JUnit XML for CI stays at `target/surefire-reports/` (`target/failsafe-reports/` for the integration suite); each class's
`<system-out>` there holds what it printed, passing or not ([Test](test.md#what-a-test-printed)). A test run removes the
report of a class that is no longer in the module. Failed-test stacks are in
`jk-results.md` — there is no separate `test-results.md`.

More on the report and the JSONL transcript: [Machine output](machine-output.md).

## If MCP is configured

The engine hosts MCP at `POST {httpUrl}/mcp` (token required). `jk engine status` prints
**MCP** and JSON includes `mcpUrl`.

Recommended loop:

1. `build` with `kind=build` or `kind=test` and `dir` = the project directory (that first
   `dir` binds the connection; later calls may omit it). The reply is the verdict.
   `diagnostics` with `file` is the rest.
2. Edit sources.
3. `build` again (`wait` defaults true).
4. If stalled: `status`, then `cancel` (both on the extended `tools/list`).

Do not dump full journal records. Open `details` only when you need the raw
`details.jsonl` transcript.

Tool catalog, resources, and prompts (including `fix-failing-build`): [MCP](mcp.md).
Agent playbook: [Agents](agents.md).

## If MCP is not configured

```bash
# Verdict for an agent (the human report is `jk results`):
jk -a test
jk -a build

# Rebuild / retest:
jk test
jk build

# Live machine events (one JSON object per line):
jk test -O jsonl
# or: export JK_OUTPUT=jsonl

# Forecast (why would this rebuild?):
jk explain
```

Session log (same JSONL shape as `-O jsonl`), live-appended: records reach the file in whole
lines every 250 ms and when the command ends, so `tail -F` trails the run by at most that:

```text
~/.jk/state/builds/projects/<key>/runs/<build-number>/details.jsonl
```

Disable the transcript with `JK_CLI_DETAILS=off`. Chrome tracing (timings, not failures):
`target/jk-profile.json`.

## Common failure classes

| Symptom | Where to look | Typical fix |
|---------|---------------|-------------|
| Compile error | `jk-results.md` (file:line) | Edit the source; `jk compile` or `jk build` |
| `package X does not exist` with a `locked without a file:` line, or `dependency g:a:v has no file` | the named lock row: its POM is `packaging=pom` and no jar was found beside it when the lock was written | Add the repository that publishes the jar to `[repositories]` and re-run `jk lock`; [Lockfile § Rows without a file](lockfile.md#rows-without-a-file) |
| Test failure | `jk-results.md` (class, stack); JUnit XML under `target/surefire-reports/` | Fix, then **replay the same selection** (`jk test`, not `--all`). Suites/tags: [Test](test.md) |
| `official fetch failed: … has sha256 …, but the signed jk <v> release lists …; refusing it`, or `… no signed jk <v> release at … lists its workers` | a worker jar download: the repository or mirror served other bytes, or the release root did not answer | Behind a firewall, point `JK_RELEASES_URL` at a mirror of `jumpkick.build/releases/` beside the repository mirror ([hosts](config.md#hosts-jumpkick-contacts)); a `-SNAPSHOT` JumpKick has no published release, so run `jk install` in its checkout |
| A variable set in the shell is `null` in a test | the module's `[env]`: `inherit = false` (here or at the workspace root) or a `scope` it does not match; an `env-undeclared` warning names the test class and the variable | Pass it: `[env] optional = ["NAME"]` (or `require`, which fails the module up front when unset), or widen the `scope`; [Test § Environment variables a suite reads](test.md#environment-variables-a-suite-reads) |
| A suite reruns after an unrelated variable changed, or never replays | the suite's `[env]`: without a `scope`, a suite that lists the environment (Spring Boot, Quarkus, Micronaut, Flyway, Testcontainers) or starts a program is keyed on every variable; `jk explain` names the one that moved, and the module's first such run printed the scope for its framework | Scope what its tests see: `[env] scope = ["SPRING_*", "<YOURAPP>_*"]` (the tests then see only those names); [Test § Scoping what a suite may see](test.md#scoping-what-a-suite-may-see) |
| A Micronaut HTTP test fails with `Read Timeout` or `Unable to resolve host` where the shell exports `HOSTNAME` (Fedora, RHEL) | the JumpKick version: under 0.53.0 test JVMs preferred IPv4, and the test calls the `HOSTNAME` it read, a name that may resolve to IPv6 link-local addresses alone | Update JumpKick: test JVMs keep both IP stacks, as under Maven and Gradle. On 0.53.0, `micronaut.server.host=localhost` in a test resource; [Test § The test JVM's network stack](test.md#the-test-jvms-network-stack) |
| A suite that read a variable (scoped or not) runs on every machine although CI stored it | the shared cache's key seed: without one, a result that read a value of your environment stays on the machine that ran it; `remote-cache-key-seed` says when the server did not hand its seed out | A server that issues none: set your team's seed on every machine (`JK_CACHE_KEY_SEED` in CI, `[cache] key-seed-file` in `~/.jk/config.toml`) and the same cache URL everywhere; with no server, the same seed on every run that saves or restores the runner's `~/.jk/cache`, and a committed `jk-lock.toml`; `JK_CACHE_CREDENTIAL_ENV=local` keeps results keyed on a credential on their machine. [Cache § Results that read your environment](cache.md#results-that-read-your-environment) |
| `suite-env-unhooked`: the test JVMs got your whole environment | the test JDK starts programs in a way JumpKick's agent cannot reach, so a program a test starts would not see your shell; the suite is keyed on every value instead | Nothing to fix; another JDK (`java =` in `jk.toml`) brings back per-variable keys. [Test § Environment variables a suite reads](test.md#environment-variables-a-suite-reads) |
| `app/jk.toml:7:1: [build] unknown key \`bogus\` — expected one of: …` | the manifest, line and column it names, relative to the directory `jk` ran in (or the one `-C` names) when the file is below it | Fix or remove the key; every `jk.toml` that does not parse or validate is named this way, a workspace member's by its own file, and one refused as a whole by the file alone (`lib/jk.toml: jk.toml is missing required key \`name\``) |
| `NAME is not set; core/jk.toml:12 requires it` (`env-required`) | the manifest line it names | Export `NAME` in the shell running `jk` or put it in `.env`; move it to `[env] optional` when the module runs without it; [Build § Worker environment](build.md#worker-environment-env) |
| Resolve / lock conflict | `jk lock` prose; `jk why <coord>`; `jk tree` | Relax a range, add a BOM, or pin; [Lockfile](lockfile.md), [Platforms](platforms.md) |
| `HTTP 403` / `HTTP 429` from `repo.maven.apache.org` | **Lock notes** in `jk-results.md`: `Maven Central is blocking this host (Cloudflare)` or `is rate-limiting this host (HTTP 429)`, `using the mirror for 4 h` | Nothing: Central-bound requests go to Google's mirror for the window. Only when `JK_CENTRAL_MIRROR=off` is set does the refusal reach the build; [Repositories](repositories.md#when-central-refuses-this-host) |
| Checksum / trust | lock-time error naming repo + coordinate | `jk repo refresh <coord>` if you intentionally replaced bits; otherwise treat as compromise. A repository with no checksum sidecars or a plaintext `http://` URL is refused until its table opts in: [Repositories](repositories.md#transport-and-checksum-trust) |
| Cache surprise (rebuilt / didn’t) | `jk explain` | [Explain](explain.md) |
| “Build #N already running” | another `jk` in this checkout, through this engine or another on the machine (`target/.jk/build.lock`) | `jk jobs` then `jk cancel`; or wait. Worktrees are separate slots |
| Disk / CAS full | `jk cache usage` / `jk storage usage` | `jk cache clean` first; [Cache](cache.md) |
| `job #N waited 600s for memory and gave up` (or `for a JVM slot`) | the line itself: what the fork needed, what is free, whether the held workers' heap ceiling stopped it, and who held the memory; `jk engine status` while it waits | Idle warm workers give way on their own, so it means working forks of other builds held the memory or the slots for ten minutes with nothing granted (a wait behind the build's own forks does not give up). A test worker whose lease gave up reads `test worker never started: job #N waited 600s for memory and gave up: …`. Lower a pinned `-Xmx`, or run fewer modules at once (`-j`, `jk test -L`). `JK_ENGINE_QUEUE_WAIT_MS` does not shorten this wait; [Engine](engine.md) |
| Engine won’t start / version skew | `jk engine status` | `jk engine stop` then retry; [Engine](engine.md) |
| Windows: `error getting credentials … A specified logon session does not exist` from docker, or a git credential helper failing, in a build or a test | `jk engine status`: `Logon … ssh or service: no Windows credential vault` means the command ran in a logon without a desktop (ssh, a service, a scheduled task), where Windows keeps the vault out of reach | Run the command from a desktop terminal, which JumpKick serves with an engine of its own ([Engine § Several engines](engine.md#several-engines)) |
| MCP or the dashboard stopped answering at 8910; `jk engine status` shows `Web UI … failed to start (port 8910 is held by the jk engine of home …)` | the home it names: an engine of another `JK_HOME` (a scratch, test or gate home run by an older JumpKick) holds the port | `jk engine stop` with `JK_HOME` set to that home, then `jk engine stop` in yours; engines of other homes no longer take 8910 ([Engine § HTTP and MCP](engine.md#http-and-mcp)) |
| An MCP client set to `http://localhost:8910/mcp` gets no answer; `jk engine status` shows another port on the Web UI line, `(port 8910 is held by another program)` | a program that is not a JumpKick engine holds 8910, so your engine serves on the port shown. On Windows with WSL's `networkingMode=mirrored`, a port a WSL program listened on has been seen to stay refused on Windows for hours after it closed, with nothing listed by `netstat` | Point the client at the MCP URL `jk engine status` prints, or use `jk mcp` (what `jk agents` sets up), which follows the engine. Once 8910 is free, `jk engine stop`, and the next engine binds it; [Engine § HTTP and MCP](engine.md#http-and-mcp) |
| Plugin worker dies with `NoSuchMethodError` / `NoClassDefFoundError` (`jk image`, `jk test`, a compiler) | `jk doctor -v` — the `worker:` line names the jar’s source repo and lists the launch classpath | A `jk-local` worker was installed from a checkout and shadows the published one: `jk storage clean -w`, then rerun; [Cache](cache.md#plugin-workers) |
| Format check failed | `jk format -c` | `jk format` (no `--check`); [Format](format.md) |
| Windows: `jk.exe` blocked by Application Control | toast / “An Application Control policy has blocked this file” | Use the thin client (`jk.bat`); [Install](install.md). Releases after 0.51.1 ship an Authenticode-signed `jk.exe`. Turning Smart App Control off is optional for contributors who run an unsigned local build. |

## Cancel a stuck job

```bash
jk jobs            # jid on each row
jk cancel          # this project’s live jobs
jk cancel 42       # that jid
```

Ctrl-C on a running CLI command cancels the engine job(s) for this project, then exits.
MCP: `cancel`. Details: [Engine](engine.md).

## Parallel test flakes

Default `jk test` overlaps **module** suites and may shard classes across worker JVMs
(`-w`). Failure lines include **module** (and **worker** when `W > 1`).

```bash
jk test -w1              # one test JVM per module
jk test -L   # one module’s tests at a time
```

Pin a hermetic module with `[test] workers = 1`. See [Test](test.md).

## Still stuck

- `jk doctor` — host health (config, disk, current/login shell hooks, installed plugin workers and
  the classpath each launches on; `-v` lists the entries), and the project's lock and JumpKick pins
  (rule packs, plugin SDK) behind the running JumpKick, which `jk update` moves
- `jk explain -v` — per-task forecast
- [Config](config.md) — `NO_COLOR`, `--offline`, `JK_HOME` isolation
- [Install](install.md) — layout and env
