# What's new in JumpKick

The highlights of each JumpKick release, newest first: the improvements and fixes you'll notice.
Every release's full list of changes is on its [GitHub Release](https://github.com/JumpKickOSS/jk/releases).
JumpKick shows you this page's new entries after it updates itself.

## 0.54.1

- **Code questions and refactorings move to `jk code`.** `jk code usages` (was `jk graph callers`),
  `rename`, `read`, `find` and `query`, and new `delete`, `move` and `pull-up`. The old names still
  run and print the new one. MCP `callers` is now `usages` ([Code graph](graph.md)).
- **MCP `delete`.** Deletes a method, field or type nothing uses, then compiles; with a use left it
  writes nothing and lists each one. On the extended tool list ([MCP](mcp.md)).
- **MCP `move`.** Moves a static method or field to another type, editing every use and import,
  then compiles and proves it; what would break is listed and nothing is written. On the extended
  tool list ([MCP](mcp.md)).
- **MCP `pull_up`.** Pulls an instance method or field up to the class its type extends, then
  compiles and proves it; what would break is listed and nothing is written. On the extended tool
  list ([MCP](mcp.md)).
- **What another version of a dependency breaks, before you move it.**
  `jk code usages dep:guava@33.4.8-jre` lists the API that version removes, renames or changes, up
  or down, and each place in your code that uses it. MCP `update`'s preview counts them per move
  ([Code graph](graph.md#what-another-version-of-a-dependency-breaks)).
- **`jk watch` and `jk dev` on Linux see a save made from another machine.** A project on NFS,
  SMB or a Windows drive under WSL is polled every 2 s, as on macOS; inotify never heard those.

## 0.54.0

- **Breaking: `~/.jk` holds everything JumpKick downloads.** Central's jars no longer go into
  `~/.m2/repository`, and `jk install` and `jk release` install into JumpKick's own repository.
  `[m2] integration = "share"` and `--destination m2` restore the old places
  ([Repositories](repositories.md#sharing-with-maven)).
- **Breaking: Kotlin sources with no `kotlin` key fail the build.** A module holding `.kt` files and
  no `kotlin` key built an empty jar and said OK; it now stops, naming the line to add
  (`kotlin = "2.4.10"`). Groovy and Scala do the same.
- **Breaking: a misspelt or mistyped `jk.toml` key fails naming it.** `[image]`, `[test]`, `[train]`,
  `[deny]`, `[workspace]`, `[jvm]` and plugin entries refuse a key they do not know, where it was
  ignored, and a malformed version range fails when the file is read. Fix or delete the key named.
- **Another web page cannot drive your engine, and a dependency's POM cannot send the resolve into
  your network.** Requests from another page's origin are refused, and a repository a POM declares
  on a private or loopback address is not used unless you declare it under `[repositories]`.
- **`jk watch` and `jk dev` see a save on macOS within milliseconds.** The loop reads FSEvents where
  it polled every 2 s, so a pass starts once the 150 ms debounce window is quiet. A project on a
  network volume is still polled.

## 0.53.1

- **A scratch or test home's engine no longer takes port 8910 from yours.** An engine started
  with `JK_HOME` naming another home serves on a port between 8911 and 8999 instead, so MCP and
  the dashboard keep answering at 8910 in your own sessions. When something still holds 8910,
  `jk engine status` names the home whose engine holds it.

- **A first build no longer gives up waiting for memory it has.** Compiler and plugin workers
  left idle by earlier modules no longer count toward the bound on what heaps may grow to, and
  exit when a job needs their memory, instead of that job failing after ten minutes.
- **Tests start at once on a small CI runner.** On 2 vCPUs and 7 GiB an unseen worker's heap was
  the plan's 3.1 GiB share and its class listing leased all of it; first heaps now stop at a
  quarter of the budget and the listing is leased at what a listing holds.
- **No "learned a test heap" line per module on a fresh machine.** Nothing was learned: the
  figure was the build's own plan. Past a quarter of RAM a test JVM runs at that, silently.
- **A memory wait that gives up says how long nothing moved.** Ten minutes with no lease granted
  to anyone; `JK_ENGINE_QUEUE_WAIT_MS` bounds a job's wait for the engine's own heap, not this one.

- **Micronaut tests pass where your shell exports `HOSTNAME`, as under Maven and Gradle.** On Fedora
  and RHEL that name can reach the host over IPv6 alone, and 0.53.0's test JVMs, which preferred
  IPv4, failed calling it. Test JVMs keep both IP stacks now: no `micronaut.server.host` line is
  needed.

- **An agent's passing build names what its edit left under an old name.** When a hand edit
  renames one side of an anchor (a SQL column, a config key, a route) and other files still use
  the old name, an MCP `run` that builds and passes ends with those places, in five files at
  most; it says nothing otherwise, and the CLI never prints it. `[graph] anchor-tail = "off"` turns
  it off ([After a hand edit](graph.md#after-a-hand-edit)).
- **A pin written lower relocks the whole artifact to it.** Putting OpenJFX back from 27.0.1.1 to
  27.0.1 over an existing lock left javafx-controls at both versions (its platform jar kept
  27.0.1.1) and javafx-graphics and javafx-base at 27.0.1.1, and the converge-versions guard failed
  the build. The relock now gives what a fresh lock gives: 27.0.1 everywhere. A package something
  else still pulls in keeps its locked version, and `jk update` marks a row it lowered as lowered.

## 0.53.0

- **Breaking: tests see your shell.** `FOO=bar jk test` reaches the test, and a cached result
  holds while what it read keeps its value; the undeclared-variable warnings are gone.
  `[env] inherit = false` keeps the old allow-list. Where the shell exports `HOSTNAME`, Micronaut
  tests need `micronaut.server.host=localhost`.
- **Breaking: a version another project installed here resolves with no repository.** `jk install`
  the library, then depend on its version; lock rows say `local-install`. The default guards let a
  developer build use such a snapshot; CI, `jk release` and `jk publish` refuse it.
- **`jk graph callers` reads your tests again, and one call shows what a change reaches.** It
  missed them in projects with `[build-info]`, as `jk new` writes. The graph now links JSON
  properties, Thymeleaf bean paths and ports, and `callers file:<path>` lists a file's anchors.
- **Agents finish renames in fewer turns.** The MCP instructions carry `callers` and `rename` whole,
  so agents call them without looking them up first; a rename never hides ids and says what is
  left, and `AGENTS.md` takes a rename that reports done as done.
- **Share results through a cache server**, off unless `[cache] remote` names one. Templates are
  format-clean, Kotlin Micronaut's second build does nothing, `jk image` trains its AOT cache in the
  image's platform, test heaps stay under a quarter of RAM, and a background `jk` on Linux or macOS
  no longer stops itself.

## 0.52.0

- **Breaking: tests run in en-US and UTC wherever they run.** Your shell's locale and time zone no
  longer change a test's result. Name others with `[test] locale` and `[test] timezone`, or set
  either to `"inherit"` to run in the caller's.
- **Breaking: `jdk` is a table, and cached results follow the Java level.** Write
  `jdk = { vendor, version, required }`; `jdk-vendor`, `jdk-version` and the string forms are refused
  with the table to write. `[cache] jdk-match` takes `level`, `major` (the default) or `build`.
- **Breaking: a stale lock that cannot be refreshed stops the build**, naming why; run `jk lock`
  where your repositories are reachable. Rule packs are locked with source and checksum, and
  `jk image` refuses a base older than your `java` (new templates write none).
- **One root builds every workspace in a repository, and renames go further.** List them in
  `[workspaces] members`. `jk graph rename` renames fields, classes and packages, and the graph links
  routes, SQL columns, config keys and environment variables to the code that uses them.
- **JumpKick for Windows is signed and works with WSL.** `jk.exe` is Authenticode-signed, so Smart
  App Control runs it. Its dashboard reaches every WSL engine, `jk self setup-wsl` installs JumpKick
  in the distros you pick, and `jk self setup-windows` installs it on Windows from a distro.

## 0.51.1

- **The second build after a fresh checkout does nothing.** Cache keys no longer move under the
  build, so a clean clone builds and tests once and the next `jk build` ends with
  `Build successful. Build up to date` instead of repeating the work.
- **Packaged outputs follow their dependencies.** A fat jar, war, lib-dir or jlink image is rebuilt
  when a sibling module it bundles changes, instead of keeping the old classes and reporting up to date.
- **Breaking: retired manifest keys are refused and name their replacement.** `java-opts` is now
  `jvm-args`, and `[test] parallel`, `[build] test-workers` and `[build] test-parallel` are now
  `[test] workers`; rename them where `jk` points.
- **Breaking: `jk snapshot` is retired; `jk install` replaces it.** A clean git checkout whose
  HEAD carries the tag `v<version>` builds the release under any verb, and any other tree builds a
  snapshot. `jk release` and `jk publish` work without `.git`; `jk self rollback` restores the
  client that ran with the engine.
- **Errors, cancels and warnings go to stderr.** With stdout redirected they still reach you, and
  `jk web` and some twenty other commands now print JSON lines under `-O jsonl`.

## 0.51.0

- **Suites pick your tests; profiles no longer do.** `include-tags`, `exclude-tags` and `all-suites`
  are gone from profiles, and a manifest that still has them says which `[test.suites.<name>]` to
  write. Run one with `jk test -s <name>`; a suite at the workspace root runs in every member.
- **Say which environment variables a module needs.** `[env] require` and `optional` pass them to
  its tests, compilers and plugins, and `vars` takes literal values only. A test that reads one you
  didn't declare gets a warning with the line to add.
- **This release is a new floor.** A project or lock written by 0.51 needs 0.51 or newer, template
  ids lose their `none` segment (`java/app`), and an older JumpKick tells you to run
  `jk self update`.
- **Installs and updates are safe to interrupt.** An interrupted install leaves your JumpKick
  working, `jk self rollback` puts back the engine and the client that ran with it, and worker jars
  are checked against the signed release.
- **Output in a file or a pipe is plain, and a build knows when it slept.** No colour or escapes
  off a terminal, and an unknown command now fails. On mains power a build keeps the machine awake;
  a run it slept through says `suspended 4m 34s`.

## 0.50.3

- **Your repository passwords move into the OS keychain.** `jk repo login` and `jk auth login` use
  the macOS keychain, Windows Credential Manager or Secret Service, and fall back to a file on CI
  and in containers.
- **Keep secrets out of `config.toml`.** A credential can come from a file (`${file:path}`) or a
  helper command (`${cmd:...}`), and `settings.xml` encrypted passwords now work, Maven 4's included.
- **A mirror really means everything goes through it.** Tools, plugins, formatters and the Maven
  distribution now download through your `mirror-of` repository too, so locked-down networks just work.
- **A typo in `config.toml` is reported, not ignored.** Commands stop and name the file, line and
  column, and `jk doctor` points at the spot.

## 0.50.2

- **One command sets your project up for coding agents.** `jk agents` writes `AGENTS.md`, the MCP
  server and the agent's own config for Claude Code, Cursor, VS Code, Gemini CLI and more.
  `jk agents --remove` undoes it.
- **First builds download in half the time.** Everything a build needs is fetched up front, in
  parallel, and every download shows its progress in the terminal, the web UI and MCP.
- **Faster downloads from Central's Google mirror**, with every file checked against the checksum
  Central publishes.
- **Agent renames go further.** MCP `rename` also fixes comments and docs that name the method, and
  tells the agent which tests to run.
- **Nerd Font glyphs now survive tmux, screen and zellij**, and `jk self setup-terminal` remembers
  your answer per terminal.

## 0.50.1

- **Build scripts move to `.jk/scripts/`.** Put the scripts that sat in `.jk/` or `jk/` there; a
  script left in the old place is named once and no longer runs.
- **Agents rename a method in one call.** MCP `rename` (or `jk graph rename`) updates every override,
  call and doc link. Claude Code finished renames in 2 to 9 turns instead of 7 to 43.
- **Code questions follow your edits.** Ask about code you just changed and JumpKick compiles it
  first, so callers and overrides match what you wrote.
- **MCP `run` no longer reports a passing build as failed.**
- **Less memory held:** idle graph workers give their memory back within seconds, and engines a test
  suite started stop when it ends.

## 0.50.0

- **JumpKick updates itself.** The engine checks for new releases, downloads them and installs them
  between builds. `jk self update` does it by hand.
- **Ask who calls a method and get a complete answer.** `jk graph callers` (MCP `callers`) works from
  the compiled code. Agents answered in a fifth to two fifths of the turns they needed without it.
- **Formatting can be part of the gate.** `[format] enforce = true` formats what you changed during
  `jk guard`, and fails CI naming each unformatted file.
- **Turn on null checking with one table.** `[nullness]` sets up JSpecify, Error Prone and NullAway,
  with `include` and `exclude` for packages.
- **Builds see your shell's environment**, wherever it can't change what's cached.

## 0.41.1

- **Test your library inside Maven and Gradle builds.** `jk snapshot` installs every module into
  `~/.m2` as a `-SNAPSHOT`, refreshed on each run.
- **Release in one command.** `jk release` builds, tests, installs, optionally publishes, tags, and
  starts the next version; `--major`, `--minor` and `--patch` pick what moves.
- **`jk publish` is safe to re-run** and reads its target, signing and key from `[publish]`.
- **`jk storage usage` takes seconds instead of minutes on Windows.**

## 0.41.0

- **Pass JVM flags to your app.** `[application] jvm-args` applies to `jk run` and `jk dev`
  (`java-opts` works too).
- **Quarkus fast-jar builds package the right runner**, `quarkus-run.jar`.
- **`jk run` says plainly when there's no main method.**

## 0.40.0

- **One copy of every Central jar.** JumpKick now shares `~/.m2` with Maven instead of keeping its
  own copy, and cleans up what it downloaded as the disk fills.
- **Windows builds run at full speed.** JumpKick keeps hybrid CPUs from parking your build on their
  slow efficiency cores.
- **A skipped step is really done.** Caching keys on everything a step reads, and
  `jk build --verify-cache` re-runs every cached step to prove it.
- **The software licences of your dependencies are in the lock**, and guards and `jk deny` can
  check them.
- **Framework fixes** for Quarkus, Grails, Robolectric, Spock, Kotest and Cucumber.

## 0.20.0

- **Every tool a build runs is pinned in the lock.** protoc, aapt2, r8, IntelliJ and the rest are
  checked by sha256 before they run. Update JumpKick before you relock.
- **Compiles start faster.** The Java, Kotlin and Groovy compilers stay warm between builds, and let
  go when the machine is short of memory.
- **`jk outdated` and `jk update` cover framework versions, toolchains and tool pins**, not just
  your dependencies.
- **`.env` reaches your app** under `jk run`, `jk dev`, dev servers and installed tools.
- **A build with nothing to do finishes in one line.**

## 0.15.2

- **Windows on ARM gets the right client.** The installers pick the native client only when the
  release has one for your machine, and the JVM client otherwise.
- **A module's `jdk =` now wins inside a workspace**, for builds, `jk run` and installed apps.
- **Restoring an older file is noticed.** A source copied back with an older timestamp is recompiled.
- **Build IntelliJ Platform plugins** with the new `[intellij]` table.

## 0.15.1

- **A native client for Linux on ARM**, installed by `install.sh` and kept current by `jk self update`.
- **macOS fixes:** the `jk` wrapper works without Homebrew's `xz`, and Intel Macs get the JVM client
  instead of an error.
- **`jk import` brings more Maven projects across**, including Kotlin and Groovy side by side, Quarkus
  tests and Yarn front ends.
- **Locking is faster** on workspaces with many modules.

## 0.15.0

- **Build output now uses Maven's `target/` layout**, one per module: `target/classes`, jars at the
  top, `surefire-reports`. Scripts that read the old paths need updating.
- **Node.js is a first-class toolchain.** Pin `node = 24` like a JDK, and front ends build, test and
  package beside your JVM code, with `jk dev` running their dev servers.
- **The highest version wins, everywhere.** `[resolve] pins` is gone, and `jk import` raises a POM pin
  that is too low for its dependencies.
- **More Maven builds import and build:** web archives, filtered resources and Jenkins plugin tests.

## 0.14.0

- **Older installs need a one-time reinstall.** Clients from 0.13.9 and earlier can't read the new
  release pointer, so reinstall from [jumpkick.build](https://jumpkick.build); updates work again after.
- **`jk update` never moves a dependency backwards**, and lists only the packages it changed.
- **Mixed Java and Scala modules compile correctly**, all on the Scala compiler.

## 0.13.9

- **Two checkouts of one project share a machine safely**, cache and all, and a second build on the
  same checkout waits its turn instead of colliding.
- **`jk outdated` is a report you can read:** one row per dependency, only what an update would move,
  sized for a 100-column terminal.
- **`jk clean` shows progress**, and leaves a checkout alone while a build holds it.
- **The signing key was rotated, so 0.13.8 and earlier can't update to this release.** Reinstall from
  [jumpkick.build](https://jumpkick.build); `jk self update` works from here on.

## 0.13.8

- **`jk install` works on Windows**, and a cancelled job no longer hangs the engine there.
- **A missing jar is named.** The error says which jar and what to run, not the first class the
  compiler couldn't find.
- **Templates under `src/main/resources` no longer confuse language detection.**

## 0.13.7

- **A version you write is the version you get.** `"3.2.2"` in `jk.toml` means exactly that release;
  write `^`, `~` or a range when you want it to float.
- **Paste a Maven coordinate as a dependency:** `mylib = "com.acme:mylib:1.2.3"`.
- **`jk update` bumps your pins** to the newest stable release on the same major, then relocks.
  `--major` crosses majors.
- **`jk results` leads with the verdict**, and up to three lines of why.

## 0.13.6

- **JDK installs show their progress.** Any JDK or GraalVM JumpKick fetches for you gets the same
  download bar as `jk jdk install`.
- **The Windows installer works on Windows PowerShell 5.1.**

## 0.13.5

- **Changing a constant recompiles everything that uses it**, even when javac inlined it into a
  string.
- **JumpKick runs on any machine with a JDK 25.** Hosts without a native client, such as Intel Macs or
  FreeBSD, get the JVM client.
- **Body-only changes don't rebuild dependents.** Compiles key on each dependency's API, and
  `jk explain` tells you in advance.
- **`jk publish -S` writes CycloneDX and SPDX SBOMs.**

## 0.13.3

- **`jk audit` can fail your build** on vulnerable dependencies, and names the version that fixes
  each one. `[audit] ignore` accepts a finding with a reason.
- **Coverage in one flag:** `jk test --coverage` writes a JaCoCo report per module.
- **Debug tests and apps:** `jk test -U` and `jk run -U` wait for your debugger.
- **The engine log caps and rolls itself**, so it never fills your disk.
