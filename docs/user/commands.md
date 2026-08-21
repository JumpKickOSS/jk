# Command index

Canonical names. Hidden aliases (Maven/Gradle muscle memory) are listed in
[Aliases](aliases.md) and do **not** appear in `jk --help`.

| Command | What it does | Details |
|---------|--------------|---------|
| `jk new` / `jk init` | Scaffold a project | [Templates](templates.md) |
| `jk add` / `jk remove` | Edit dependencies | [Dependencies](dependencies.md) |
| `jk lock` | Resolve and write `jk-lock.toml` | [Lockfile](lockfile.md) |
| `jk sync` | Materialize cache / offline prep | [Lockfile](lockfile.md) |
| `jk outdated` | Newer versions than the lock (read-only) | [Lockfile](lockfile.md) |
| `jk update` | Re-resolve within ranges | [Lockfile](lockfile.md) |
| `jk tree` / `jk why` | Inspect the graph | [Dependencies](dependencies.md) |
| `jk compile` | Type-check | [Build](build.md) |
| `jk build` | Compile + package | [Build](build.md) |
| `jk test` | Run tests | [Test](test.md) |
| `jk run` | Run the app | [Run](run.md) |
| `jk watch` / `jk dev` | Rebuild on change | [Run](run.md) |
| `jk jshell` | REPL on compile classpath | [Run](run.md) |
| `jk format` | Format Java/Kotlin | [Format](format.md) |
| `jk explain` | Forecast rebuilds / ETA | [Explain](explain.md) |
| `jk tasks` / `jk show` / `jk inspect` | Task catalog | [Explain](explain.md) |
| `jk assemble` | Fat / minified jar | [Packaging](packaging.md) |
| `jk native` | GraalVM native-image | [Native](native.md) |
| `jk image` | OCI image | [Images](images.md) |
| `jk train` | Observe a run for reachability / AOT | [Dynamic surface](dynamic-surface.md) |
| `jk publish` | Publish artifacts | [Publish](publish.md) |
| `jk install` | Local repo + PATH / jkx | [Packaging](packaging.md), [Tools](tools.md) |
| `jk verify` | Rebuild and hash-diff | [Publish](publish.md) |
| `jk audit` / `jk deny` | OSV / source denylist | [Publish](publish.md) |
| `jk jdk …` | JDK install / pin / list | [JDK](jdk.md) |
| `jk tool …` / `jkx` | One-off JVM tools | [Tools](tools.md) |
| `jk library …` | Catalog search / update | [Dependencies](dependencies.md) |
| `jk import` / `jk export` | Maven / Gradle / BOM / IDE | [Migration](migration.md) |
| `jk mvn` / `jk gradle` | Real Maven/Gradle | [Migration](migration.md) |
| `jk ide` / `jk bsp` | IDE + BSP | [IDE](ide.md) |
| `jk web` | Dashboard | [Web](web.md) |
| `jk engine …` | Resident engine | [Engine](engine.md) |
| `jk jobs` / `jk cancel` | Running work | [Engine](engine.md) |
| `jk results` | Latest run report (`jk-results.md`); `--details` dumps `details.jsonl` | [Machine output](machine-output.md) |
| `jk manual` | Playbook for agents and new users (markdown) | [Manual](manual.md) |
| `jk cache …` / `jk storage …` | Disk hygiene | [Cache](cache.md) |
| `jk clean` | Delete `target/` | [Cache](cache.md) |
| `jk self nuke` / `jk self update` | Product data / upgrade | [Cache](cache.md), [Install](install.md) |
| `jk env` | Layered environment | [Install](install.md) |
| `jk doctor` | Host health | [Config](config.md) |
| `jk wrapper` | Bootstrap scripts | [Wrapper](wrapper.md) |
| `jk activate` / `jk completion` | Shell | [Install](install.md) |
| `jk selective …` | Fingerprinted CI subset | [Workspaces](workspaces.md) |
| `jk repo …` / `jk auth …` | Remotes / forges | [Repositories](repositories.md) |

`jk --help` is the live surface. This table is the map into topic pages.
