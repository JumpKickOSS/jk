# JumpKick skill

The live playbook is **`jk skill`**. It prints a short core (the loop, the five default MCP tools,
the verdict shape). **`jk skill <topic>`** prints one page. **`jk skill --list`** prints the topic
names.

```bash
jk skill
jk skill dependencies
jk skill --list
jk skill install            # <project>/.agents/skills/jk/
jk skill install <dir>      # <dir>/jk/SKILL.md and the topic files
```

The same bytes are the MCP tool **`skill`** (`topic` optional) and the resource **`jk://skill`**
(`jk://skill/<topic>` is one page).

`jk skill install` writes an [Agent Skills](https://agentskills.io/specification) folder:
`SKILL.md` with `name: jk` and a one-line `description`, plus one markdown file per topic. The
default directory is `<project>/.agents/skills`, the project-level location clients share. The
skill's `name` matches the `jk/` directory it is installed into.

Topics: `dependencies`, `tests`, `workspaces`, `lockfile`, `imports`, `plugins`, `guards`,
`layout`, `jdk`.

## What an agent does

1. Run **`jk skill`** (or MCP **`skill`**) once per session.
2. Read the **verdict**. MCP `run` returns it. On the CLI, `jk --agent` or `JK_AGENT=1` prints it.
   `run=<id>` reads an earlier run.
3. Detail past the cap is MCP **`diagnostics`** (`file=`). Pass `dir` on the first call; that binds
   the connection.
4. Edit dependencies with **`deps`** (it relocks) or `jk add` / `jk remove`. Then `run(kind=test)`.
5. Format after source edits: `jk format`.

Other tools (history, explain, graph, jdk, …) are a `tools/list` with `{"extended": true}`.

Longer pages for people live next to this one: [Getting started](getting-started.md),
[Agents](agents.md), [MCP](mcp.md), [Commands](commands.md).
