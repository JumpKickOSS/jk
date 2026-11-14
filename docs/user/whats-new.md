# What's new in JumpKick

The highlights of each JumpKick release, newest first: the improvements and fixes you'll notice.
Every release's full list of changes is on its [GitHub Release](https://github.com/JumpKickOSS/jk/releases).
JumpKick shows you this page's new entries after it updates itself.

## 0.51.0

- **No more code-graph files in your repo.** The graph now lives in JumpKick's cache, shared by
  every clone and worktree on your machine. You can delete `.jk/graph/`, or let the next build
  remove it.
- **Ask about code on a fresh clone and get a complete answer.** The first `callers` question
  compiles whatever hasn't been built yet, and tells you if it couldn't.
- **`jk status` labels its cache row "Actions + Graph"**, so the count adds up.
- **Suites pick your tests; profiles no longer do.** `include-tags`, `exclude-tags` and `all-suites`
  are gone from `[profiles.*]` and `[test]`, and a manifest that still has them says which
  `[test.suites.<name>] tags = [...]` to write instead. Run a suite with `jk test -s <name>`.
- **A suite declared at the workspace root runs in every member.** `jk test -s integration` and
  `jk test -g` find it everywhere, and a member can add settings of its own.
- **Say which environment variables a module needs.** `[env] require` and `optional` pass them to
  its tests, compilers and plugins, and a missing required one stops the build up front with one
  line. `[env] vars` now takes literal values only: move bare names to `optional`.
- **JumpKick tells you when a test reads a variable you didn't declare**, with the `[env]` line to
  add, on every run, a cached one included, and in the agent report.
- **Tests run the same from any terminal.** A test JVM gets your shell's locale and a fixed
  `TERM=dumb`, so tmux, an IDE or an agent shell no longer reruns a green suite.
- **Template ids lose their `none` segment:** `java/app`, `kotlin/ktor-3`. A framework still has
  its own (`java/spring-boot/webapp`).
- **Your laptop stays awake while a build runs on mains power**; closing the lid still puts it
  to sleep. A run the machine slept through anyway says `suspended 4m 34s` instead of looking slow.
- **See which commit you're running.** `jk --version -v`, `jk engine status` and `jk doctor` show
  the client's and the engine's build and say when they differ.
- **An interrupted install leaves your JumpKick working**, and `jk self rollback` puts back the
  engine and the client that ran with it, then holds auto-update until `jk self update`.
- **Worker jars are checked against the signed release** wherever they come from. Behind a
  firewall, point `JK_RELEASES_URL` at your mirror of the releases.
- **Each WSL distro and container gets its own engine id and dashboard port**, so two guests on one
  machine no longer share one.

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
