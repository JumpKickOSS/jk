# Run, watch, and REPL

## `jk run`

```bash
jk run -- args…
```

At a workspace root, runs the module that has `[application] main`. Needs a main class.
Launches from `target/classes/` (not a packaged jar), so [AOT cache](build.md#jvm-startup-cache-app-aot--cds)
is not available on this path.

`jk run` is a **wrapper** around the process: Ctrl-C is owned by JumpKick (cancels the
engine job, then the process). It is not `exec`-replace.

## Live loops (`jk watch` / `jk dev`)

One mechanism: re-run a verb when sources change. **`jk dev` is only an alias for
`jk watch run`.**

Watches **`src/`**, **`test/src/`**, and project-root **`jk.toml`** by default — not
`target/`, `out/`, `build/`, or VCS trees. Editor save bursts are debounced (default
**150 ms**).

```bash
jk watch compile
jk watch test
jk watch build               # package loop (--skip-tests)
jk watch run
jk dev                       # same as jk watch run
jk dev -- --port=8080        # app args after --
jk watch test --debounce-ms 300
```

`watch run` / `dev` use classes-dir execution, Spring Boot DevTools when present,
otherwise process restart. Android projects redeploy via the packaging plugin.

## jshell / REPL

```bash
jk jshell                    # build --skip-tests if needed, then jshell on compile classpath
jk jshell --no-build         # existing target/classes + lock deps only
jk repl                      # alias
```

Requires a full JDK with `jshell` on `JAVA_HOME` / `java.home`. Run from a **module**
directory (not a pure workspace root). Extra args after the verb are forwarded to jshell.

## Related

[Build](build.md) · [Projects](projects.md) · [Aliases](aliases.md)
