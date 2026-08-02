# `jk run` process model (JK-1163)

## Decision (2026-08-02)

**Keep wrap-`ProcessBuilder` + inheritIO for v1.** Do **not** `exec`-replace the CLI process
with `java` by default.

| Approach | Pros | Cons |
|----------|------|------|
| **Wrap (current)** | Ctrl-C / cancel path stays on the CLI; engine cancel, wedge settle, exit codes owned by jk; works with JSONL/session; safe with Graal native client | Extra process; signals go to wrapper first |
| **exec-replace into java** | One process for the app; simpler `ps`; matches “become the app” mental model | Loses CLI cancel/settle; engine orphan on client death harder; native client would need careful execve |

## Current flow

1. Engine-hosted **build** phase (single module or workspace graph).
2. Engine **exec plan** (native > assembly > jar > main-class scan).
3. Client **`ProcessBuilder(command).inheritIO().start().waitFor()`** (or plugin deploy).
4. Settled play chip (`▶ Run Executing …`) then blank separator before child IO.

## Future option (not default)

`jk run --exec` (or config) could `ProcessBuilder` with no wait after start, or POSIX
`exec` after teardown of TUI — only when the user opts in and cancel semantics are
documented as “client gone = app orphan unless OS job control.”

## Related

- Client disconnect cancel: `docs/features/exclusive-builds.md`
- Soft-failure entry-point probe: only after successful build (JK-1162)
