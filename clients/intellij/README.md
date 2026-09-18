# JumpKick for IntelliJ

Wire-only IntelliJ plugin: an IntelliJ **external system** (the API family the bundled Gradle and
Maven integrations use) whose resolver runs the `jk` CLI (or `JK_BIN`) and owns the project
model. Never loads the JumpKick engine jar into the IDE.

## What it does

| Trigger | Behaviour |
|---|---|
| Project open with `jk.toml` at the base | Links the JumpKick project (first open) and resolves it once; no prompt, no generated files |
| `jk.toml` or `jk-lock.toml` changes under the project (editor save or a terminal `jk add` / `jk remove`) | Re-resolves after a 2 s quiet window; libraries and roots update in place |
| **Tools → JumpKick → Sync project** | Explicit re-resolve; progress and the CLI's output in the Build tool window's Sync tab |
| Tools → JumpKick → Build / Test / Lock / Sync dependencies and sources / Install BSP connection | `jk build` / `test` / `lock` / `sync --sources` / `bsp install` |
| Gutter **Run** / **Debug** on a test class or method, or on a `main` method | A **JumpKick** run configuration: `jk test -m <module> --class <fqcn>` for a class, `--class <fqcn>#<method>` for one method, or `jk run <module>` from the workspace root. Debug starts the same command with `--debug-jvm=localhost:<port>` and attaches the Java debugger to that address once the JVM listens — no hand-made Remote JVM Debug configuration |

A resolve runs `jk ide --print-model` in the linked directory, decodes the engine's `ide-model`
JSON with the plugin's own small reader, and hands IntelliJ:

- one module per workspace module (plus a root module for the workspace directory), with the
  module directory as content root and `target/` excluded;
- every source, resource, test and test-resource root as it lies on disk — every discovered test
  suite and the guard suite are test roots — read the way `jk ide` reads them;
- generated-source roots when a module has annotation processors;
- project-level libraries by coordinate with their sources jars — every `-sources.jar` the store
  or the Maven local repository holds; **Sync dependencies and sources** (`jk sync --sources`)
  fetches one for every library that publishes it — and per-module dependencies in IntelliJ's
  scope vocabulary (a tests-kind edge is one module dependency with *production on test*);
- the per-module JDK under its stable `jk-<vendor>-<level>` name, registered in the IDE's JDK
  table when missing, and the project default JDK;
- compiler output pointed at the IDE-owned `target/jdt/classes/{main,test}` of each module, never
  at jk's `target/classes`.

Modules are stored externally, as for Gradle: a linked project gains no `*.iml` and no
`.idea/modules.xml`. A failing resolve surfaces in the Build tool window with the CLI's first
error line; a missing `jk` binary names the `JK_BIN` / PATH fix.

`jk ide --idea` remains the offline export for users without the plugin; the plugin never runs it.

## Distribution

Not on the JetBrains Marketplace: `publishPlugin` is not wired and no release runs it. Install from
the zip `buildPlugin` produces, or run from source. Editor intelligence for `jk.toml` does not
depend on this plugin — see the JSON Schema note in `docs/user/projects.md`.

## Requirements

- IntelliJ IDEA 2024.1+ (Community or Ultimate)
- `jk` on PATH (`jk --version`), or `JK_BIN` / system property `jk.bin`

## Build installable zip

```bash
./scripts/package-intellij.sh
# → clients/intellij/build/distributions/jumpkick-intellij-*.zip
```

**Install from disk:** Settings → Plugins → ⚙ → Install Plugin from Disk…

## Architecture

```
IntelliJ plugin (external system)  ──Process──►  jk CLI  ──wire──►  engine JVM
       │                                          │
       │  ide --print-model  → ProjectResolver    └── jk bsp serve (via .bsp/jk.json)
       │  build / test / lock / sync
       └── no engine jars
```

`JkProjectResolver` (the external-system resolver) → `JkWireModel` (the JSON) → `JkSourceRoots`
(roots from disk) → `JkProjectGraph` (the `DataNode` tree `ProjectDataManager` applies).
`JkManifestWatcher` debounces the manifest and lockfile trigger. Same constraint as the VS Code
extension (`clients/vscode/`).

## Tests

`clients/intellij/gradlew test` runs two tiers:

- unit tests over the reader, the wire model, root discovery and the graph (a captured
  two-module `ide-model` under `src/test/resources`), the debouncer and the manifest filter;
- IntelliJ Platform tests (`HeavyPlatformTestCase`): the debounced trigger over real VFS
  events, and `JkWorkspaceImportTest`, which imports this repository's own workspace through the
  real external-system path — link, resolver running the installed `jk`, platform applying the
  result — and asserts every module, roots, libraries (with sources jars, after a
  `jk sync --sources` of the checkout), SDKs, `target/jdt` outputs and that no `.iml` lands in
  the checkout. It skips, printing why, when no `jk` is on PATH.

## Run and debug through jk

`JkRunConfigurationProducer` turns the gutter's context into a `JkRunConfiguration`: a class under
a test root of a JumpKick module is `jk test -m <module> --class <fqcn>`, an annotated method in
it is `--class <fqcn>#<method>`; a class with a `main` method under a source root is `jk run
<module>`. Both run from the workspace root in the Run
tool window's console. The producer is preferred over the bundled JUnit and Application ones on
these modules, which stay in the list as alternatives. Under Debug, `JkCommandState` picks a free
loopback port, passes `--debug-jvm=localhost:<port>` — jk starts the one JVM suspended with a
JDWP listener there — and `JkDebugRunner` attaches the Java debugger to that address, retrying
while jk builds (up to ten minutes); a breakpoint in the test or the application stops as usual.
`--class <fqcn>#<method>` selects one method, so a method's gutter action runs that method alone.

## Manual today

- The Build tool window rendering of a sync (progress, the first error line) is verified by
  hand: the headless tests assert the resolver's output and error, not the tool window.
