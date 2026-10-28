---
name: jk
description: Build and fix Java, Kotlin, and Groovy with JumpKick. Use when the tree has jk.toml or the task is a build, test, lockfile, or dependency.
---

# jk

The manifest is `jk.toml`; the lock is `jk-lock.toml` (one per workspace, at its root; commit it). Never add `pom.xml` or Gradle files; never run `mvn` or `gradle`.

## Loop

Run, read the verdict, edit, rerun. The verdict is the whole answer; scrape nothing else. Use short flags: `-m` modules, `-s` suite, `-r` redo, `-F` force, `-O jsonl`, `-C dir`.

| | Shell | MCP |
|---|---|---|
| test | `jk -a test` | `run()` |
| build | `jk -a build` | `run(kind=build)` |
| install for a sibling project | `jk -a snapshot` | `run(kind=snapshot)` |
| add / remove a dep | `jk add g:a`, `jk remove g:a` | `deps(action=add, coords=["g:a"])` |
| why this version | `jk why g:a` | `why` (extended list) |
| every problem, with lines | `jk results -A` | `diagnostics()` |
| one skill topic | `jk skill <topic>` | `skill(topic=…)` |

A sibling project that must see your edits to a library gets them through `jk -a snapshot`: the project is installed to `~/.m2` as `<version>-SNAPSHOT`, replaced each run, like a Maven snapshot; the sibling depends on `g:a:<version>-SNAPSHOT`. `jk install` is for apps; `jk release` cuts a library.

A dependency edit relocks in the same call. Registered as `jk mcp`, tools take no `dir`; over HTTP, pass `dir` (the root) on the first call.

## Verdict

```
FAIL build app · 2 errors · 0.9s
E src/main/java/app/Web.java:3:8 package org.springframework.web.bind.annotation does not exist
  +1 more at Web.java:4
FIX deps(add, org.springframework.boot:spring-boot-starter-web)
```

One line per problem: `E path:line:col message` (project-relative), `T Class#method` for a failed test with its expectation and `at path:line`; under either, `N|`, a tab, then the source line verbatim (an Edit's `old_string`); `FIX` when jk knows the edit (`deps(add, g:a)` is `jk add g:a` in a shell), and `+K more: jk results -A | diagnostics(file=…)` past the cap.

## Topics

dependencies, tests, workspaces, lockfile, imports, plugins, guards, layout, jdk, engine.
