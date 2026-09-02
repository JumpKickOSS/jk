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
in the IDE process**). Requires `jk` on PATH (or `JK_BIN`).

## Test suites in the IDE

`jk ide` registers **every discovered test suite** as IDE **test** source roots in the
same module (IntelliJ `.iml`, VS Code/JDT `.classpath`, BSP `buildTarget/sources`). One
test output directory; no extra IDE module per suite. Named suite resource dirs are test
resources when present.

Execution still follows the CLI default: `jk test` runs only the **test** (unit) suite. After
`jk ide`, IntelliJ gains shell run configurations (`jk test`, `jk test (all suites)`, and
one per extra suite) and VS Code gets matching `.vscode/tasks.json` entries. Treat
**all suites** as the nightly / release configuration, not the inner loop.

## BSP

| Capability | Status |
|------------|--------|
| `workspace/buildTargets`, sources, dependency modules | yes |
| Dependency **sources** jars | yes (classifier `sources` when present) |
| `buildTarget/outputPaths` | yes (main + test classes dirs) |
| `buildTarget/compile` | yes; `publishDiagnostics` with file/line/column for javac, kotlinc and groovyc blocks (errors severity 1, warnings 2; anything unparseable lands at project root) |
| `buildTarget/test` | yes; optional suite/tag `data` |
| `buildTarget/run` | yes when main class known |
| `build/cancel` | yes |
| `workspace/reload` | yes |
| Debug adapter | **no** |

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
`--include-tags` / `--exclude-tags`.

Wire-level BSP notes: [Architecture](../contributors/architecture.md).

## Editor extensions

- **VS Code** — `clients/vscode/` in the JumpKick repo; package with
  `./scripts/package-vscode.sh`
- **IntelliJ** — `clients/intellij/`; package with `./scripts/package-intellij.sh`.
  **Tools → JumpKick → Sync project** runs `jk ide --print-model` + `jk ide --idea` +
  `jk bsp install`. On open, projects with `jk.toml` are offered Sync (auto-Sync when no
  IDEA modules yet).

Both are **wire-only**.
