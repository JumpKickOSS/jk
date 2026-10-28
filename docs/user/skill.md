# JumpKick skill

The live playbook is **`jk skill`**. It prints a short core (the loop, the default MCP tools,
the verdict shape). **`jk skill <topic>`** prints one page. **`jk skill -l`** prints the topic
names.

```bash
jk skill
jk skill dependencies
jk skill -l
jk skill install            # <project>/.agents/skills/jk/
jk skill install <dir>      # <dir>/jk/SKILL.md and the topic files
jk skill agents             # create or update jk's section of AGENTS.md
```

`jk skill agents` writes [jk's section of `AGENTS.md`](agents.md#jks-section-of-agentsmd) at the
workspace root: the file is created when missing, the section inserted when absent, and rewritten
when it is stale. Text outside the section is never touched.

The same bytes are the MCP tool **`skill`** (`topic` optional) and the resource **`jk://skill`**
(`jk://skill/<topic>` is one page).

`jk skill install` writes an [Agent Skills](https://agentskills.io/specification) folder:
`SKILL.md` with `name: jk` and a one-line `description`, plus one markdown file per topic. The
default directory is `<project>/.agents/skills`, the project-level location clients share. The
skill's `name` matches the `jk/` directory it is installed into.

Topics: `dependencies`, `tests`, `workspaces`, `lockfile`, `imports`, `plugins`, `guards`,
`layout`, `jdk`, `engine`.

## What an agent does

1. Read **`jk skill`** (or MCP **`skill`**) when a task needs more than the `AGENTS.md` section.
2. Read the **verdict**. MCP `run` returns it. On the CLI, `jk -a` or `JK_AGENT=1` prints it.
   `run=<id>` reads an earlier run.
3. Detail past the cap is MCP **`diagnostics`** (`file=`). Over `jk mcp` the tools take no `dir`;
   over HTTP, pass `dir` on the first call and it binds the connection.
4. Edit dependencies with **`deps`** (it relocks) or `jk add` / `jk remove`. Then `run()`.
5. Before handing work back: `jk guard`; with `[format] enforce` it also formats what you changed.

Other tools (history, explain, graph, jdk, …) are a `tools/list` with `{"extended": true}`.

Longer pages for people live next to this one: [Getting started](getting-started.md),
[Agents](agents.md), [MCP](mcp.md), [Commands](commands.md).
