# IDE and BSP

```bash
jk ide                       # .idea + .vscode + .bsp/jk.json
jk ide --idea
jk ide --vscode
jk bsp install               # .bsp/jk.json only
jk ide --print-model         # ide-model JSON on stdout (no writes)
```

`jk ide` / `jk idea` / `jk vscode` always refresh `.bsp/jk.json` so Metals and JetBrains
BSP can discover JumpKick. IDE launches use `jk bsp serve` (stdio BSP — **no engine jars
in the IDE process**). Requires `jk` on PATH (or `JK_BIN`). Coding agents get the same
files from the MCP tool `jk_ide` (`preview=true` lists them without writing) — see
[MCP](mcp.md).

## Test suites in the IDE

`jk ide` and the IntelliJ plugin register **every discovered test suite** as IDE **test** source
roots in the same module (IntelliJ `.iml` or the plugin's resolved module, VS Code/JDT
`.classpath`, BSP `buildTarget/sources`). One
test output directory; no extra IDE module per suite. Named suite resource dirs are test
resources when present.

Execution still follows the CLI default: `jk test` runs only the **test** (unit) suite. After
`jk ide`, IntelliJ gains shell run configurations (`jk test`, `jk test (all suites)`, and
one per extra suite) and VS Code gets matching `.vscode/tasks.json` entries. Treat
**all suites** as the nightly / release configuration, not the inner loop.

IntelliJ and JDT compile into the IDE-owned `target/jdt/classes/{main,test}` of each module,
never into jk's `target/classes`: a gutter run leaves nothing where the action cache stamps
jk's own compile, so the next `jk build` is still a cache hit.

## BSP

| Capability | Status |
|------------|--------|
| `workspace/buildTargets`, sources, dependency modules | yes |
| Dependency **sources** jars | yes (classifier `sources` when present) |
| `buildTarget/outputPaths` | yes (main + test classes dirs) |
| `buildTarget/compile` | yes; `publishDiagnostics` with file/line/column for javac, kotlinc and groovyc blocks (errors severity 1, warnings 2; anything unparseable lands at project root) |
| `buildTarget/test` | yes; optional suite/tag/class `data`, optional `debug` |
| `buildTarget/run` | yes when main class known; optional `debug` |
| `build/cancel` | yes |
| `workspace/reload` | yes |
| Debug adapter | **JDWP attach, not DAP.** `data.debug` starts the test/app JVM with a JDWP listener; the address arrives as `build/logMessage` before the launch (and in the test result's `data`). Attach with a stock remote-JVM configuration — see below |

`buildTarget/test`: omit `params.data` for default-suite only. Optional extension:

```json
{
  "params": {
    "targets": [{ "uri": "file:///path/to/module#name" }],
    "data": {
      "allSuites": false,
      "suites": ["test", "integration"],
      "includeTags": ["smoke"],
      "excludeTags": ["slow"]
    }
  }
}
```

Fields mirror CLI: `allSuites` ↔ `--all`, `suites` ↔ `--suite`, tags ↔
`--include-tags` / `--exclude-tags`, `classes` ↔ `--class`.

### Debugging through BSP

Add `debug` to the same `data` object on `buildTarget/test` or `buildTarget/run`:

```json
{
  "params": {
    "targets": [{ "uri": "file:///path/to/module#name" }],
    "data": {
      "suites": ["test"],
      "classes": ["com.acme.OrdersTest"],
      "debug": { "port": 0, "suspend": true }
    }
  }
}
```

`debug` is `true` (the defaults: `localhost:5005`, suspended), an object with optional
`host`, `port` (`0` = a free port jk picks) and `suspend`, or a `--debug-jvm` spec string.
The JVM starts with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=…`.
Because a suspended JVM waits for the attach, the address cannot come back in the result —
it is announced first:

```json
{"jsonrpc":"2.0","method":"build/logMessage","params":{"type":3,"originId":"…","message":"Debugger listening on localhost:41873 — the JVM waits for a debugger to attach"}}
```

The `TestResult` also carries it: `"dataKind": "jk-debug"`, `"data": {"address": "localhost:41873", "host": "localhost", "port": 41873, "suspend": true}`.
`RunResult` has no `data` in the protocol, so for `buildTarget/run` the log message is the
announcement. `build/logMessage` is what BSP clients already render (a `build/showMessage`
would pop a dialog), and it precedes the launch, which is when an attach has to happen.

Attach recipe — the same for the CLI's `--debug-jvm` ([Test](test.md#debug-a-test-jvm),
[Run](run.md#debug-the-app-jvm)):

- **IntelliJ IDEA**: *Run → Edit Configurations → + → Remote JVM Debug*, host `localhost`,
  port as announced (5005 is IDEA's default, and jk's). Set breakpoints, then *Debug*; the
  suspended JVM resumes on attach.
- **VS Code** (Java extension): a `launch.json` entry
  `{"type": "java", "request": "attach", "name": "jk", "hostName": "localhost", "port": 5005}`.

This is not a Debug Adapter Protocol server: jk starts a debuggable JVM and tells you where;
the editor's own JDWP client does the rest.

Wire-level BSP notes: [Architecture](../contributors/architecture.md).

## Editor extensions

- **IntelliJ** — the plugin in `clients/intellij/` is the IntelliJ path; package it with
  `./scripts/package-intellij.sh`. It is an IntelliJ *external system*: opening a project with
  `jk.toml` links it and resolves modules, every source and test-suite root, libraries with
  sources, per-module JDKs and the `target/jdt` compiler outputs from the engine `ide-model`
  (`jk ide --print-model`), with no `*.iml` or `.idea/modules.xml` written. A change to
  `jk.toml` or `jk-lock.toml` — from the editor or a terminal `jk add` — re-resolves after a
  2 s quiet window; **Tools → JumpKick → Sync project** re-resolves on demand with progress in
  the Build tool window. `jk ide --idea` is the export for an IDE without the plugin.
- **VS Code** — `clients/vscode/` in the JumpKick repo; package with
  `./scripts/package-vscode.sh`

Both are **wire-only**: they run the `jk` CLI and never load engine jars.
