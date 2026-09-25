---
name: jk
description: Build and fix Java, Kotlin, and Groovy with JumpKick. Use when the tree has jk.toml or the task is a build, test, lockfile, or dependency.
---

# jk

`jk` builds Java, Kotlin, and Groovy. The manifest is `jk.toml`; the lock is `jk-lock.toml` (one file at the workspace root; commit it). Coordinates come from Maven Central. Do not add `pom.xml` or Gradle files, and do not run `mvn` or `gradle`.

## Loop

Run, read the verdict, edit, run again. The verdict is the whole answer; do not scrape other output or open report files.

| | Shell | MCP |
|---|---|---|
| test | `jk --agent test` | `run(kind=test)` |
| build | `jk --agent build` | `run(kind=build)` |
| add / remove a dependency | `jk add g:a`, `jk remove g:a` | `deps(action=add, coords=["g:a"])` |
| why this version | `jk why g:a` | `why(coord=g:a)` |
| every problem, full snippets | `jk results` | `diagnostics(file=…)` |
| one topic of this skill | `jk skill <topic>` | `skill(topic=…)` |

A dependency edit relocks in the same call; the next test needs no separate lock. With MCP, pass `dir` (the project root) on the first call.

## Verdict

```
FAIL build app · 2 errors · 0.9s
E src/main/java/app/Web.java:3:8 package org.springframework.web.bind.annotation does not exist
  +1 more in Web.java
FIX deps(add, org.springframework.boot:spring-boot-starter-web)
```

One line per problem: `E path:line:col message` (paths relative to the project), `T Class#method` for a failed test with its expectation and `at File.java:line`, `FIX` when jk knows the edit (`deps(add, g:a)` is `jk add g:a` in a shell), and `+K more: diagnostics(file=…)` past the cap (`jk results` in a shell). An OK run is one line: `OK test app · 2 tests · 0.5s`.

## Topics

dependencies, tests, workspaces, lockfile, imports, plugins, guards, layout, jdk.
