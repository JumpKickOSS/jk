# Command aliases

Hidden conveniences for muscle memory from other tools. None appear in `jk --help` or shell
completion, and product docs always use the canonical name (hidden-surface policy). This table is
the single source of truth; `JkAliasTest` keeps aliases out of the help screen.

## Verb rewrites (first argument only)

Rewritten before dispatch (`Jk.VERB_ALIASES`); they never join unique-prefix matching, so `jk pl`
still means `jk plugin` and `jk wh` means `jk why`.

| Alias           | Canonical | Origin                                   |
| --------------- | --------- | ---------------------------------------- |
| `generate`      | `new`     | Maven `archetype:generate`               |
| `dependencies`  | `tree`    | Gradle `dependencies`                    |
| `package`       | `build`   | Maven `package`                          |
| `deploy`        | `publish` | Maven `deploy`                           |
| `upgrade`       | `update`  | npm / yarn / apt vocabulary              |
| `sh`, `bash`    | `shell`   | shell muscle memory                      |
| `nativeCompile` | `native`  | Gradle `:nativeCompile`                  |
| `verify-target` | `verify`  | Maven `verify` naming                    |
| `plan`          | `explain` | build-plan forecast                      |
| `why-rebuilt`   | `explain` | early-roadmap name for the cache report  |
| `check`         | `compile` | pre-v1.0 name of the compile-only verb   |

## Command-name aliases (dispatcher-registered)

Exact and prefix-matchable like the primary name, still hidden from help.

| Alias                       | Canonical      |
| --------------------------- | -------------- |
| `assembly`                  | `assemble`     |
| `dist`                      | `release`      |
| `kill`                      | `cancel`       |
| `repl`                      | `jshell`       |
| `jdks`                      | `jdk`          |
| `create`                    | `new`          |
| `lib`                       | `library`      |
| `builds`, `activity`, `act` | `jobs`         |
| `exec`                      | `run` (tool)   |

## Subcommand aliases

| Alias                | Canonical           |
| -------------------- | ------------------- |
| `library ls`         | `library list`      |
| `jdk upgrade`        | `jdk update`        |
| `export pom`         | `export maven`      |
| `cache info`         | `cache storage`     |
| `cache prune`        | `cache clean`       |
| `cache purge`        | `cache nuke`        |
| `cache search`       | `repo search`       |
| `self purge`         | `self nuke`         |

`cache info` and `cache search` are pre-split spellings. `cache prune` / `cache purge` /
`self purge` are renames from the clean/nuke vocabulary. Hidden stubs also forward

## Hidden option aliases

| Alias          | Canonical    |
| -------------- | ------------ |
| `--rebuild`    | `-r/--redo`  |
| `--directory`  | `--dir`      |

## Hidden back-compat options

Still functional, but gone from `--help`; the canonical home moved with the storage split.

| Hidden surface                          | Canonical                  |
| --------------------------------------- | -------------------------- |
| `cache clean --sweep`                   | `storage clean`            |

## Hidden global options

Accepted anywhere on the line — before the command, between a group and its subcommand, or
after — but absent from every `--help` screen.

| Option        | Effect                                               |
| ------------- | ---------------------------------------------------- |
| `-y`, `--yes` | Answer yes to confirmation prompts (`jk self nuke`, `jk activate`, …) |
