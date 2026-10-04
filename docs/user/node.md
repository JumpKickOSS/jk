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

## Commands

`jk node` manages Node.js the way `jk jdk` manages JDKs; `jk nvm` is the same command.

| Command | Does |
|---|---|
| `jk node list` | Installed versions: jk's own and those nvm, fnm, volta, mise, asdf, Homebrew or the `PATH` put there; `*` marks the project's |
| `jk node list-remote` | Releases you can install, the newest of each major; `--lts`, `--major 24`, `--all` (also `ls-remote`) |
| `jk node install [spec]` | Install `24`, `24.21.0`, `lts` (the default) or `latest` into jk's store; `--no-discover` downloads even when another manager has it. `jk tool install node:24` is the same install |
| `jk node uninstall <version>` | Remove one of jk's installs; another manager's is never touched |
| `jk node which` | Print the `node` the project uses: the lock's version, else the newest install that satisfies `node =` (also `home`) |
| `jk node exec -- <cmd…>` | Run a command with the project's Node.js and package manager first on `PATH`, installing them if missing; `--node <spec>` uses another once |
| `jk node run <script>` | Run a `package.json` script with the project's package manager |
| `jk node pin [spec]` | Write `node =` (or `[node] version` when the module has a `[node]` table) into the nearest `jk.toml` and relock. With no spec: what `.nvmrc`, `.node-version` or `package.json` names, else the newest LTS. `-m <module>` pins a member; `--file` also writes `.node-version` for editors; `--no-lock` skips the relock |
| `jk node verify` | Check each of jk's installs still runs and reports its version |
| `jk node update` | Refresh the release index and report the project's pin against the newest of its major (`jk update` moves the lock) |

Every command takes `--output json` ([machine output](machine-output.md#jk-node)). With the shell
hook installed (`jk activate`), entering a project puts its locked Node.js and its package
manager's shims first on `PATH` (`JK_NODE_HOME`, `JK_NODE_SHIMS`), beside `JAVA_HOME`; leaving takes
them off. `jk shell` does the same for a subshell.

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

## Images

`jk image` in a node module with a server output builds a Node.js image on the distroless base of
the locked major; a static module gets an nginx image with `[image] kind = "static"`, and none
without it. Layers, defaults and inheritance: [Images § Node modules](images.md#node-modules).

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
