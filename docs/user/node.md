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
alone, unless the module writes a `[node]` table without `dir`, which jk refuses. A module with a
`package.json` and no JVM sources must declare `node` (jk proposes the version `.nvmrc`,
`.node-version` or `package.json` names); a `[node]` table on a module with no `package.json` is
refused.

A JVM module that declares `node` and has no `package.json` builds no front end: its tests run with
the locked Node.js first on `PATH` and `NODE_HOME` naming it, for suites that shell out to `node`
([Test](test.md#external-tools-the-suite-shells-out-to-test-tools)).

## The `[node]` table

Every key is inferred from `package.json`, its lockfile and the framework's config file; write one
only to override.

| Key | Default |
|---|---|
| `version` | the version, when the manifest also has a `[node]` table (`node = 24` otherwise) |
| `package-manager` | from the lockfile and `packageManager`: npm, pnpm, Yarn (Berry) or bun |
| `framework` | from the scripts and config files (table below), else `plain` |
| `install` / `build` / `test` / `dev` / `start` | the frozen install, then the `build`, `test`, `dev` and server-start commands the framework implies |
| `out` | the framework's output directory |
| `classpath-root` | `static` when a JVM module depends on this one |
| `webapp-root` | the war's root, for a `[war]` dependant |
| `env-prefixes` | the variables the framework inlines into the bundle (and so key the build) |
| `dev-port` | the dev server's port, which `jk dev` waits for |
| `dir` | the module directory; `src/main/node` for a build inside a JVM module |
| `skip` | `false` |

| Framework | `out` | `dev-port` | `env-prefixes` | Server `start` |
|---|---|---|---|---|
| Vite | `dist` | 5173 | `VITE_` | — |
| Next | `.next` (`out` with `output: 'export'`) | 3000 | `NEXT_PUBLIC_` | `node .next/standalone/server.js` when standalone |
| Angular | `dist/<app>/browser` | 4200 | — | — |
| Nuxt | `.output` | 3000 | `NUXT_PUBLIC_` | `node .output/server/index.mjs` |
| SvelteKit | `build` | 5173 | `PUBLIC_` | `node build/index.js` with adapter-node |
| Astro | `dist` | 4321 | `PUBLIC_` | — |
| SolidStart, TanStack Start | `.output` | 3000 | `VITE_` | `node .output/server/index.mjs` |
| React Router | `build` | 5173 | `VITE_` | `react-router-serve build/server/index.js` |

## Node.js and the lock

`node = 24` (or `[node] version = 24` when the manifest also has a `[node]` table) is a floor;
`"=24.21.0"` pins exactly; `"lts"` takes the newest LTS. `jk lock` records the exact release, its
npm, the package manager (`packageManager` in `package.json`) and every platform's archive digest
under `[node]` in `jk-lock.toml` ([Lockfile](lockfile.md#nodejs)). A build provisions that release,
or reuses one nvm, fnm, volta, mise, asdf, Homebrew or the `PATH` already has. Package managers
are npm, pnpm, Yarn (Berry only) and bun.

## Commands

`jk node` manages Node.js the way `jk jdk` manages JDKs; `jk nvm` is the same command. Installs live
under `~/.jk/store/tools/node/<version>`, package managers under `~/.jk/store/tools/<manager>`.
Offline, a build uses what the store (or another manager) already holds and refuses a missing
release by name.

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

## Supply chain

Installs are frozen: the lockfile (`package-lock.json`, `pnpm-lock.yaml`, `yarn.lock`, `bun.lock`) is
required and never rewritten, and a missing one is an error with the command to create it. npm's
cache is jk's (`~/.jk/cache/npm`, pnpm's store `~/.jk/store/pnpm-store`), so `jk cache` and
`jk storage` see them. Lifecycle scripts run as the project's `.npmrc` says; set `ignore-scripts`
there to turn them off.

## Packaging

See [Packaging § Node modules](packaging.md#node-modules): a node module's output becomes a
resource jar under `classpath-root` for a JVM dependant, or web content under `webapp-root` for a
`[war]` dependant; a node build inside a JVM module goes into that module's jar or war. A node
module whose framework produces a server runs with `jk run` ([Run](run.md#node-modules)).

## `jk dev`

`jk dev` in a JVM module runs the `dev` script of every node module it depends on, and of its own
`src/main/node`, beside the app under the locked Node.js — no `[dev.sidecars]` to write. In a node
module it runs that module's dev server alone; at a workspace root, every runnable member at once.
See [Run § Sidecars](run.md#sidecars-devsidecars).

## Images

`jk image` in a node module with a server output builds a Node.js image on the distroless base of
the locked major; a static module gets an nginx image with `[image] kind = "static"`, and none
without it. Layers, defaults and inheritance: [Images § Node modules](images.md#node-modules).

## New projects and adoption

**From a hand-run build.** A README that says "run `npm install && npm run build` first" becomes
`node = 24` in the front end's `jk.toml`: `jk build` installs and bundles it as cached steps, and the
`vite.config` hack that pointed `build.outDir` into a resource directory goes, since jk places the
output.

`jk new --lang node -t <framework> <name>` runs the framework's own generator under the newest LTS
Node.js and writes the `jk.toml` that pins it ([Templates](templates.md#node-front-ends---lang-node)).
`jk new -t webapp --frontend <framework>` swaps the webapp template's front end.

`jk init` in a directory holding a `package.json` (and no JVM sources) makes it a node module,
pinned to what `.nvmrc`, `.node-version` or `package.json` (`devEngines`, `volta`, `engines`)
suggest, and says which file it read. At a root whose subdirectories hold a `package.json`, `jk init`
adds each as a workspace member the same way.

`jk import` maps gradle-node-plugin's `node { version }` and `nodeProjectDir` (a module with no JVM
sources is a node module; a front end in a subdirectory of a JVM module is a `[node] dir` build), and
Quarkus Quinoa's `quarkus.quinoa.package-manager-install.node-version` and `ui-dir` (a `[node] dir`
build served from `META-INF/resources`).

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
