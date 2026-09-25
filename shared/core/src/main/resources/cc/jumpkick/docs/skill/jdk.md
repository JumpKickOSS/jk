# JDK and the verdict

JumpKick needs JDK 25 or newer to run, and installs it when it is missing. Prefer `java = N` over `jdk =`. Use `jk jdk list`, `jk jdk install`, and `jk jdk pin` only for a specific runtime. Do not download JDK 17 or 21 just to target those levels.

Agent stdout is `jk --agent`, or `JK_AGENT=1`, or a non-terminal spawn. That text is the verdict. `target/jk-results.md` is the human report. `jk results` prints it. MCP `run` returns the verdict of the job it just ran. `run=<id>` reads an earlier one. `diagnostics(file=...)` is the rest past the cap.

`--output json` streams events for CI. It is not the first place to look.

Exit codes: `0` success, `1` the build ran and failed, `2` bad project or argument, `64` bad command line, `70` internal error, `130` interrupted.
