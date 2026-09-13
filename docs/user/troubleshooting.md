# Troubleshooting

How to diagnose a failing JumpKick build. This is the page to open when someone says
**“fix my failing build.”**

## First look: `jk results`

Every hosted run writes a high-level markdown report of the whole invocation: compile
errors and warnings, test failures, install / publish / native / image / package outcomes.

```bash
jk results              # latest jk-results.md
jk results --details    # that run's details.jsonl
```

On disk:

```text
{project}/target/jk-results.md
~/.jk/state/builds/projects/<key>/runs/<build-number>/jk-results.md
```

The two files are the same report. Prefer the command over `cat` so you do not have to
know the state-dir path.

**Agents:** run `jk manual` once. Then `target/jk-results.md` (read/grep) or MCP
`jk_results` first. Do not scrape the TTY. Do not turn on `--verbose` as your primary API.

JUnit XML for CI stays at `target/reports/test-results/`. Failed-test stacks are in
`jk-results.md` — there is no separate `test-results.md`.

More on the report and the JSONL transcript: [Machine output](machine-output.md).

## If MCP is configured

The engine hosts MCP at `POST {httpUrl}/mcp` (token required). `jk engine status` prints
**MCP** and JSON includes `mcpUrl`.

Recommended loop:

1. `jk_bind` with the project directory.
2. `jk_results` (same markdown as `jk results` / `target/jk-results.md`) or `jk_diagnostics`
   (structured compiler/test failures).
3. Edit sources.
4. `jk_run` with `kind=build` or `kind=test` (`wait` defaults true).
5. If stalled: `jk_status`, then `jk_job` `cancel`.

Do not dump full journal records. Open `jk_details` only when you need the raw
`details.jsonl` transcript.

Tool catalog, resources, and prompts (including `fix-failing-build`): [MCP](mcp.md).
Agent playbook: [Agents](agents.md).

## If MCP is not configured

```bash
# High-level:
jk results

# Rebuild / retest:
jk test
jk build

# Live machine events (one JSON object per line):
jk test --output json
# or: export JK_OUTPUT=json

# Forecast (why would this rebuild?):
jk explain
```

Session log (same JSONL shape as `--output json`), live-appended:

```text
~/.jk/state/builds/projects/<key>/runs/<build-number>/details.jsonl
```

Disable the transcript with `JK_CLI_DETAILS=off`. Chrome tracing (timings, not failures):
`target/jk-profile.json`.

## Common failure classes

| Symptom | Where to look | Typical fix |
|---------|---------------|-------------|
| Compile error | `jk-results.md` (file:line) | Edit the source; `jk compile` or `jk build` |
| Test failure | `jk-results.md` (class, stack); JUnit XML under `target/reports/test-results/` | Fix, then **replay the same selection** (`jk test`, not `--all`). Suites/tags: [Test](test.md) |
| Resolve / lock conflict | `jk lock` prose; `jk why <coord>`; `jk tree` | Relax a range, add a BOM, or pin; [Lockfile](lockfile.md), [Platforms](platforms.md) |
| Checksum / trust | lock-time error naming repo + coordinate | `jk repo refresh <coord>` if you intentionally replaced bits; otherwise treat as compromise. A repository with no checksum sidecars or a plaintext `http://` URL is refused until its table opts in: [Repositories](repositories.md#transport-and-checksum-trust) |
| Cache surprise (rebuilt / didn’t) | `jk explain` | [Explain](explain.md) |
| “Build #N already running” | another `jk` in this checkout | `jk jobs` then `jk cancel`; or wait. Worktrees are separate slots |
| Disk / CAS full | `jk cache usage` / `jk storage usage` | `jk cache clean` first; [Cache](cache.md) |
| Engine won’t start / version skew | `jk engine status` | `jk engine stop` then retry; [Engine](engine.md) |
| Plugin worker dies with `NoSuchMethodError` / `NoClassDefFoundError` (`jk image`, `jk test`, a compiler) | `jk doctor -v` — the `worker:` line names the jar’s source repo and lists the launch classpath | A `jk-local` worker was installed from a checkout and shadows the published one: `jk storage clean --workers`, then rerun; [Cache](cache.md#plugin-workers) |
| Format check failed | `jk format --check` | `jk format` (no `--check`); [Format](format.md) |
| Windows: `jk.exe` blocked by Application Control | toast / “An Application Control policy has blocked this file” | Use the thin client (`jk.bat`); [Install](install.md). Released natives are signed when published. Turning Smart App Control off is optional for contributors who run an unsigned local build. |

## Cancel a stuck job

```bash
jk jobs            # jid on each row
jk cancel          # this project’s live jobs
jk cancel 42       # that jid
```

Ctrl-C on a running CLI command cancels the engine job(s) for this project, then exits.
MCP: `jk_cancel` / `jk_job` `cancel`. Details: [Engine](engine.md).

## Parallel test flakes

Default `jk test` overlaps **module** suites and may shard classes across worker JVMs
(`-w`). Failure lines include **module** (and **worker** when `W > 1`).

```bash
jk test -w1              # one test JVM per module
jk test --serial-tests   # one module’s tests at a time
```

Pin a hermetic module with `[test] workers = 1`. See [Test](test.md).

## Still stuck

- `jk doctor` — host health (config, disk, current/login shell hooks, installed plugin workers and
  the classpath each launches on; `-v` lists the entries)
- `jk explain --verbose` — per-task forecast
- [Config](config.md) — `NO_COLOR`, `--offline`, `JK_HOME` isolation
- [Install](install.md) — layout and env
