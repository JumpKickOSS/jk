# AGENTS.md

This project uses **JumpKick** (`jk`) for its build — not Maven or Gradle.

It is important that you view the manual by running:

```bash
jk manual
```

If the JumpKick engine MCP server is connected, call **`jk_manual`** (or read `jk://manual`)
instead of shelling out.

Do not add `pom.xml`, `build.gradle`, or `build.gradle.kts`. Do not run `mvn` or `./gradlew`
for this project. The manifest is `jk.toml`; the lockfile is `jk-lock.toml` (commit it);
outputs are under `target/`. After a build or test, triage failures by reading
`target/jk-results.md` with your file/grep tools — that is faster than running `jk results`
as a shell command.

Default `jk test` is the **unit** suite only (the inner loop). Do not pass `--all` as a
habit. Climb with `--suite integration` or `--suite e2e` when the change needs that
rung. Replay the same selection after a failure.
