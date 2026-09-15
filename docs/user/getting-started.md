# Getting started

Install JumpKick, create a project, add a dependency, and build. About five minutes.

If you already have `jk` on `PATH`, skip to [Create a project](#create-a-project).
Install details (paths, Windows, env): [Install](install.md).

## Install

```bash
curl -fsSL https://jumpkick.build/install.sh | bash
jk --help
jk manual                 # playbook for humans and coding agents
```

The installer puts `jk` and `jkx` on your PATH under `~/.jk/bin` (Windows:
`%USERPROFILE%\.local\bin`). JumpKick **requires JDK 25+ to run** and will install one if
needed. Project *language level* is a separate knob (`java = 25` by default) — see
[Concepts](concepts.md).

Optional shell integration (PATH, `JAVA_HOME` hooks, completions):

```bash
jk activate --yes
```

## Create a project

```bash
jk new my-app
cd my-app
```

`jk init` is the same scaffolder without requiring a directory name. `jk new -t spring-boot/hello`
(and other templates) is covered in [Templates](templates.md).

A minimal `jk.toml`:

```toml
group   = "com.example"
name    = "my-app"
version = "0.1.0"
java    = 25

[dependencies]
# add coordinates here, or use: jk add jackson3-databind
```

`jk.toml` is **data** — not a programming language. [Projects](projects.md) lists every
identity field and dependency scope.

## Add a library and build

```bash
jk add jackson3-databind    # catalog short name; GAV also works
jk build                    # writes jk-lock.toml on first need, then compiles and packages
jk test
```

`jk add` edits `jk.toml`, writing today's stable as an exact pin (`jackson3-databind = "3.0.0"`).
The first command that needs a lock writes **`jk-lock.toml`**.
**Commit the lockfile.** Later `jk build` uses it and does **not** re-resolve. To take newer
versions on purpose: `jk outdated`, then `jk update` rewrites the pins and relocks. Details: [Lockfile](lockfile.md),
[Dependencies](dependencies.md).

```bash
jk run -- args…
```

Needs `[application] main = "…"` (the `jk new --executable` / app templates set this).
See [Run](run.md).

## What just happened

| File | Role |
|------|------|
| `jk.toml` | What you declared |
| `jk-lock.toml` | Exact versions + checksums — law for `jk build` |
| `AGENTS.md` | Tells coding agents to run `jk manual` |
| `target/` | Classes, jars, reports |
| `target/jk-results.md` | High-level report of the last run (`jk results` prints it) |

When a build fails: [Troubleshooting](troubleshooting.md). Next: **`jk manual`** (or
[Manual](manual.md) on the web) for the rest of the product.
