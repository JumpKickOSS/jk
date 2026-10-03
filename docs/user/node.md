# Node.js

jk builds a JavaScript or TypeScript front end the way it builds a JVM module: one Node.js pinned in
the lock and provisioned on demand, a frozen install, the project's own build and test scripts as
cached steps, and the output packaged where the JVM side reads it.

## The two shapes

**A node module** (preferred): a workspace member whose directory holds `package.json` and no JVM
sources.

```toml
# web/jk.toml
node = 24
```

**A node build inside a JVM module**: `package.json` in `src/main/node` (or the directory
`[node] dir` names), beside `src/main/java`. Its output goes into that module's own jar under
`static/`, or into its war, as frontend-maven-plugin's output would.

```toml
# app/jk.toml
name = "app"
java = 25

[node]
version = 24
# dir = "src/main/frontend"      # default src/main/node
# classpath-root = "public"      # default static
# webapp-root = "jsbundles"      # for a [war] module: where the output lands in the war
```

A `package.json` at a JVM module's root is a project's tooling (linters, formatters) and is left
alone, unless the module writes a `[node]` table without `dir`, which jk refuses.

## Node.js and the lock

`node = 24` (or `[node] version = 24` when the manifest also has a `[node]` table) is a floor;
`"=24.21.0"` pins exactly; `"lts"` takes the newest LTS. `jk lock` records the exact release, its
npm, the package manager (`packageManager` in `package.json`) and every platform's archive digest
under `[node]` in `jk-lock.toml` ([Lockfile](lockfile.md#nodejs)). A build provisions that release,
or reuses one nvm, fnm, volta, mise, asdf, Homebrew or the `PATH` already has. Package managers
are npm, pnpm, Yarn (Berry only) and bun.

## Steps

| Step | What it runs | Cached on |
|---|---|---|
| `node-install` | the frozen install (`npm ci`, `pnpm install --frozen-lockfile`, `yarn install --immutable`, `bun install --frozen-lockfile`) | `package.json`, the lockfile, rc files, the pinned Node |
| `node-build` | the build script the framework implies, or `[node] build` | the node tree less `node_modules` and the output, the env prefixes, the install |
| `node-test` | the test script, with JUnit counts for vitest, jest and `node --test` | as the build |
| `[[node.steps]]` | a script (`run`), a package binary (`npx`) or a program (`exec`), before the build, the tests or packaging | the step's `inputs`, else the tree |

`--skip-node` (or `JK_SKIP_NODE`, or `[node] skip = true`) runs none of them and packages the
output already built. `[test] failures = "report"` applies to node tests too ([Test](test.md)).
An `npx` resolves from `node_modules` only: a package the lockfile does not hold is refused unless
the step sets `allow-unlocked = true`.

## Packaging

See [Packaging § Node modules](packaging.md#node-modules): a node module's output becomes a
resource jar under `classpath-root` for a JVM dependant, or web content under `webapp-root` for a
`[war]` dependant; a node build inside a JVM module goes into that module's jar or war. A node
module whose framework produces a server runs with `jk run` ([Run](run.md#node-modules)).

## Importing frontend-maven-plugin

`jk import pom.xml` turns the plugin's executions into a node build. The version an install goal
pins is `node = "=<version>"`; a frozen install (`npm ci`, `yarn install`) is jk's own
`node-install`; the `build` and `test` scripts are jk's build and test; every other command is a
`[[node.steps]]` entry placed by the phase it ran in (`npx` and `yarn exec` as `npx` steps).
`corepack yarn …` imports as `yarn …`, run by the Yarn jk provisions from `packageManager`.

Where the build lands depends on where its bundler (webpack's `output.path`, Vite's
`build.outDir`) wrote:

- into the module's own war or resources: a side-by-side build in that module, `[node] dir` set to
  the plugin's `workingDirectory`, the output placed by `webapp-root` or `classpath-root`;
- from a `pom` module, or into another module: a generated node module (`web/`). The import moves
  `package.json`, the lockfile, the package manager's and the tools' config files and the source
  directories the bundler config names into it, and the consuming module depends on it with the
  old output path as its `webapp-root` or `classpath-root`.

Either way the bundler config is rewritten to write `dist/`, and jk places the output; nothing is
built into `src/`. `jk import --dry-run` prints every move and rewrite and changes nothing. Bower and
the task-runner goals (grunt, gulp, karma, webpack, ember, jspm) are not imported: run them from an
npm script or an `npx` step. Yarn 1 is not supported.

## Registries and mirrors

`~/.jk/config.toml` `[node]` sets `dist-mirror`, `registry` and per-scope registries; credentials
come from the repository credential chain, and a Maven `settings.xml` mirror is the fallback
([Config § Node network](config.md#node-network)).
