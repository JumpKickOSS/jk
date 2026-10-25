---
name: jk
description: Build and fix Java, Kotlin, and Groovy with JumpKick. Use when the tree has jk.toml or the task is a build, test, lockfile, or dependency.
---

# jk

The manifest is `jk.toml`; the lock is `jk-lock.toml` (one per workspace; commit it). Never add `pom.xml` or Gradle files, or run `mvn` or `gradle`.

## Loop

Run, read the verdict, edit, rerun. Scrape nothing but the verdict. Use short flags: `-m` modules, `-s` suite, `-C dir`.

| | Shell | MCP |
|---|---|---|
| test | `jk -a test` | `run()` |
| build | `jk -a build` | `run(kind=build)` |
| add / remove a dep | `jk add g:a`, `jk remove g:a` | `deps(action=add, coords=["g:a"])` |
| why this version | `jk why g:a` | `why(coord=g:a)` |
| find code | `jk graph query "…"` | `query_graph(question=…)` |
| read a symbol | `jk graph read T.m` | `read_symbol(address=…)` |
| links, A to B | `jk graph neighbors X`, `jk graph path A B` | `get_neighbors(label=X)`, `shortest_path(source=A, target=B)` |
| every problem, with lines | `jk results -A` | `diagnostics()` |
| one skill topic | `jk skill <topic>` | `skill(topic=…)` |

Hand edits to a sibling project with `jk -a snapshot` (MCP `run(kind=snapshot)`), a `-SNAPSHOT` replaced each run; `jk install` is for a release.

A dependency edit relocks in the same call. Under `jk mcp` tools take no `dir`; over HTTP pass `dir` (the root) on the first call.

## Verdict

```
FAIL build app · 2 errors · 0.9s
E src/main/java/app/Web.java:3:8 package org.springframework.web.bind.annotation does not exist
FIX deps(add, org.springframework.boot:spring-boot-starter-web)
```

One line per problem: `E path:line:col message` (relative), `T Class#method` for a failed test, its expectation and `at path:line`; under either, `N|`, a tab, then the source line verbatim; `FIX` when jk knows the edit (`deps(add, g:a)` is `jk add g:a` in a shell), and `+K more: jk results -A | diagnostics(file=…)` past the cap.

## Topics

dependencies, tests, workspaces, lockfile, imports, plugins, guards, layout, jdk, engine, graph.
