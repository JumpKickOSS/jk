# What's new in JumpKick

The highlights of each JumpKick release, newest first: the improvements and fixes you'll notice.
Every release's full list of changes is on its [GitHub Release](https://github.com/JumpKickOSS/jk/releases).
JumpKick shows you this page's new entries after it updates itself.

## 0.54.0

- **`jk cache gc` keeps the outputs its records name.** The sweep did not read an output written
  with its size, the form every record has had since 0.52.0, so it could delete cached outputs that
  were still in use and the prune did not count them; a build then ran the step again.
- **`jk dev` answers `r`, `c` and `q`.** On a terminal, `r` rebuilds and restarts the app (every
  member at a workspace root), `c` clears the screen and `q` stops the session. While the keys are
  read, the app's stdin is not the terminal.
- **`jk dev` re-plans when the workspace root's or a sibling member's `jk.toml` changes**, as it
  does for the app's own; `jk watch` in a member rebuilds on an edit to the root's.
- **`jk doctor` has a `path` row.** It warns when the first `jk` on `PATH` is not the one in
  `~/.jk/bin`, so an update would not be the `jk` that runs. `--engine` with `--no-engine` is now a
  usage error.
- **`jk bsp serve` answers `buildTarget/inverseSources`, `resources`, `jvmRunEnvironment` and
  `jvmTestEnvironment`**, so an IDE can find a file's target and run a main or a test class with
  the classpath JumpKick builds.
- **A value that starts with `-` is refused naming the `=` spelling.** `--jvm-arg -Xmx2g` says
  to write `--jvm-arg=-Xmx2g`, instead of only that the option needs a value.
- **`jk export` writes a workspace sibling as a project reference.** `jk export gradle` writes a
  `workspace = true` sibling as `project(":path")` and `jk export maven` as the sibling's coordinate
  at `${project.version}`, where both wrote a `workspace:` coordinate no build could resolve; a
  `[workspace.dependencies]` entry is written as its coordinate. A `[war]` module exports as `war`
  packaging, and dropped `[dev-dependencies]` are a report row.
- **A misspelt or mistyped `jk.toml` key fails naming it.** `[image]`, `[test]`, `[train]` and
  `[deny]` refuse a key they do not know, as the other tables already did, where it was ignored;
  so does a plugin's entry or named definition (`[generate.<name>]`, `[lint.<name>]`,
  `[android.signing.<name>]`), suggesting the key it is closest to;
  a value of the wrong type (`[build] lint = "no"`, a dependency's `version = 1`) is a parse error
  naming the key, where it failed with an internal error.
- **A plugin's `[contribute.lib-dir]` reads `[native] enabled = false` as no native image**, as
  every other contribution's `native-declared` condition does.

- **A workspace member's SBOM names its own dependencies.** The SBOM an application jar embeds
  and the sidecars `jk publish -S` writes list the member's runtime closure, not every runtime row
  of the workspace lock, with the graph between them: CycloneDX `dependencies`, SPDX `DEPENDS_ON`.
  `--slsa` provenance lists the same closure under `resolvedDependencies`. A member's packaged
  jar is rebuilt once, as its SBOM changed.


- **`[audit] ignore` matches an advisory's aliases.** An entry written for `CVE-2026-1234` now
  covers the `GHSA-…` advisory OSV reports for it, and `-O jsonl` lists each finding's `aliases`.
- **An advisory with a CVSS v3 vector and no severity label gets a severity.** `jk audit` rates it
  by the vector's base score instead of `UNKNOWN`, so `-s HIGH` no longer stops on a low-scored
  advisory GitHub never labelled. A CVSS v4 vector alone still fails closed.
- **A repeat `jk audit` fetches only the advisories OSV changed.** Each advisory's details are kept
  in the cache's `osv-vulns/` until OSV reports it modified.
- **`[android] version-code` sets the app's versionCode.** It reaches the APK or AAB manifest and
  `BuildConfig.VERSION_CODE`, which were always 1.
- **The JRE `jk image` trains an AOT cache with keeps its base image's links.** A JDK package's
  links into the image, such as `lib/security/cacerts -> /etc/ssl/certs/java/cacerts`, were dropped
  when the JRE was extracted; they are kept now, and a link that leads out of the image is skipped.

- **`jk explain` says why a heavy step will run again.** Beside a native image, fat jar or jlink
  image that misses the cache, it names `[cache] heavy-ttl = 0` (the default on CI) or `[build]
  cache-heavy-outputs = false` when either keeps the output out of the cache, as it already named a
  nearly full disk. It now forecasts the jlink image at all, with the reason it relinks.

- **The engine sees a memory limit set on its systemd unit or slice.** Worker memory, the
  overbooking check and the memory the engine reports now follow the tightest cgroup limit on the
  engine's own group and the groups above it, and that group's memory pressure, not only the
  container root's. `jk engine status` also gains a `Backpressure` row counting spilled lines,
  dropped clients, jobs that waited for memory and waits on the shared worker leases.
- **A malformed version range in `jk.toml` fails when the file is read.** A range that names no
  version, such as `(1.0)`, `[]` or `[2.0,1.0]`, is refused naming the file, line and key, instead of
  failing the next `jk lock` with no place to look. So is a comparator with no version (`>=`) or a
  space where the comma goes (`>=1.0 <2.0`), which used to pass and then match the wrong versions,
  and a malformed range in a plugin table's version key, such as `[spring-boot] version = "(4)"`.


- **`jk explain` names the classpath entry that moved.** A suite, native image or plugin step
  keyed on a runtime classpath now keys each entry on its own, so a miss says `runtime classpath
  entry guava-33.4.0-jre.jar changed`, or `runtime classpath order changed` when the same entries
  traded places, instead of `runtime classpath changed`. The first build after the update re-runs
  these steps once.
- **A module's single test JVM runs its plan once when nothing on the test classpath hooks the
  launcher.** It no longer discovers each class a second time to run it; a classpath with a
  launcher session or discovery listener, or an interceptor (Quarkus), still runs class by class.
- **A file Central's mirror lacks is no longer reported as missing from Central.** While Maven
  Central refuses this host, its requests go to Google's mirror, which can lag Central. A file the
  mirror does not have now fails the fetch naming Central's refusal and saying the artifact may
  exist, instead of `not found in central`.

- **A large disk under 10% free still caches your native image.** The disk guard now calls a volume
  nearly full under 2 GiB free, or under 10% and 10 GiB free, so a laptop with 20 GiB left no longer
  rebuilds its heaviest step on every run. When the disk does keep a native image out of the cache,
  `jk explain` says so beside the step.
- **Another web page cannot drive your engine, and a report link is not your dashboard token.** A
  request that would build, write a file, answer a decision or call MCP is refused when it comes from
  another page's origin, whatever token it carries. The coverage **HTML report** link carries a token
  that opens that run's report alone for 8 hours, so the address in your browser's history no longer
  holds the dashboard token.
- **The dashboard opens with no network.** Vue, and the Markdown renderer and sanitizer behind file
  previews, now ship inside JumpKick instead of loading from a CDN, so the dashboard and Markdown
  previews work offline. Diagrams, AsciiDoc and the code editor still load from the CDN when first used.
- **Your engine serves its dashboard and MCP even when another program holds port 8910.** It
  takes a port between 8911 and 8999, keeps it across restarts, and `jk engine status` shows that
  URL with `(port 8910 is held by another program)` beside it. `jk mcp` and `jk web` follow it; an
  MCP client set to `http://localhost:8910/mcp` by hand needs the new URL.
- **`[resolve] pom-repositories = "ignore"` keeps the resolve on your own repositories.** No
  repository a dependency's POM declares is asked anything; `jk lock` notes each one it passed over.
- **A dependency's POM cannot send the resolve into your network.** A repository a POM declares
  whose host resolves to a private, link-local, unique-local or loopback address is not used, and
  `jk lock` notes it, unless the project already resolves through that host. The host is looked
  up again before every request, so a name that later resolves inward, or a redirect onto an
  internal host, is refused too. Declare it under `[repositories]` to use it.
- **A sibling a workspace member uses only from its tests no longer weighs on its main classpath.**
  A member that depends on a sibling only from its tests now resolves that sibling's dependencies in
  its test graph, so they can no longer move a version on its main classpath, and `jk outdated`
  shows them with scope `test`. A member's tests read a workspace main row at the version they need
  without a row of their own, so the lock carries fewer `members` rows.
- **A build plugin's manifest cannot point packaging outside the build.** A `[packaging]`
  `native-image-sources`, `app-dir` or `app-jar` that is absolute, empty, `.` or climbs out with
  `..` is refused when the plugin's manifest is read. A plugin can no longer ship a directory from
  elsewhere on disk in a container image, run a jar from outside its own app tree, or feed
  native-image arguments from outside its output.
- **`jk bsp serve` follows the Build Server Protocol more closely.** Source and output paths are
  reported as directories; a module's build target lists the workspace modules it depends on; every
  diagnostics notification sets `reset`, and a file fixed since the last compile is cleared in the
  IDE. A slow model refresh no longer delays a finished compile's reply. The Maven event recorder no
  longer hangs on an exception whose causes loop, or drops the line of a mojo with no execution id.
  In VS Code, Reconnect starts a new attempt even while an earlier one is still waiting on JumpKick. In
  IntelliJ, closing a project cancels a pending manifest re-sync.
- **The dashboard asks the engine for less.** A finished build reads only the newer runs instead of
  also reloading the whole history, a project's Sessions tab is one request instead of twenty-one, a
  hidden tab stops its one-second clock and catches up when shown, a long backlog of live events
  drains in linear time, and the engines list is shared across tabs for 2 s.
- **A workspace member gets the BOMs of the siblings it depends on.** When a sibling names its BOM
  through the root's `[workspace.dependencies]`, the BOM was silently dropped for the members that
  depend on that sibling; it now constrains them. A member with its own annotation-processor version
  of a library no longer sees the workspace's version on its processor path as well. A project
  reached through a symlink, such as macOS `/tmp` or `/var`, keeps the history id of the directory
  it points at, and a worktree whose `jk.toml` declares an id JumpKick cannot read no longer breaks
  recording another checkout's build.
- **Global options before the command are read the way the parser reads them.** `jk -q check` runs
  `compile`, `jk -qC app build` builds in `app`, `-H` and `-D` skip or replace the config files as
  their long forms do, and `jk -C app ...` reads `app`'s `[config]`. `-j` and `--ram-percent` refuse
  a value they do not take with a usage error instead of silently using the default. Ctrl-C cancels
  the running command's own session, so a command that unwinds reports a cancel rather than a closed
  connection. Closing the terminal restores its modes, and Shift, Ctrl and application-mode arrows
  read as one key.
- **`jk dev` on a stack of node dev servers no longer spins a CPU core, and `jk watch` waits for a
  save burst to finish.** `jk dev` at a workspace root whose members are all node dev servers waits
  on them and stops when they have all exited. `jk watch` and `jk dev` start a pass once sources
  have been quiet for the debounce window (150 ms), at most 2 s after a burst began (or four windows
  when `-U` is longer), so a long save burst or a checkout runs one pass, not two. A `test/src` or
  `src/main/java` deleted and restored is watched again, and a `.scala` edit is reported as
  recompiled. `jk selective` fingerprints test sources, resources, test resources and the workspace
  root `jk.toml`, so an edit there re-runs the module (every module reads as changed once after this
  upgrade), and its dirty-modules line is no longer printed as a failure. `jk verify` passes over a
  module that packages no jar and removes its scratch copy on Ctrl-C too.
- **Guards see more of the bytecode, so a rule may find sites it missed.** Type references now
  include generic type arguments (`List<Banned>`) and record components, so `cycles`, `forbid`,
  `layers` and `classes` rules can find new sites. `split-package` catches two modules compiling the
  same class. A `text` count with `per = "file"` now judges files with no match. `closed = true`
  applies to `layers` class edges too. A call's argument literals include the constant parts of a
  string concatenation and no longer pick up a literal from another branch.
- **`jk export gradle` writes locked versions and classifiers, and `jk export maven` keeps export
  dependencies.** `jk export gradle` writes the versions `jk-lock.toml` holds, keeps a dependency's
  classifier or `kind = "tests"`, applies `java-library` when `[export-dependencies]` become `api`,
  and escapes `$` and line breaks in strings. `jk export maven` writes `[export-dependencies]` as
  compile dependencies and pins the compiler plugin it writes for annotation processors. `jk import`
  of a Gradle build keeps two dependencies that share an artifact name (the second as `<name>-2`,
  with a report row), reads a Groovy `id '...kotlin...' version '...'`, and no longer writes the
  project version into manifest attributes such as `javaVersion`.
- **A client that stops reading no longer holds its event stream or request slot for good.** A
  dashboard tab, MCP client or front-door browser that keeps its connection open but stops reading
  is cut off: each write to an event stream, to an MCP call answered as a stream and to a front-door
  proxied answer must finish within 30 s. A client that sends part of a request body and then
  stalls is cut off too: the whole body must arrive within 30 s. A dashboard that connects while another tab closes keeps
  its live status and cache figures; a sample taken in that window used to stop them for the life of
  the new tab.
- **`jk publish --sign` works with a key file that holds only subkeys.** With a primary kept offline
  (`gpg --export-secret-subkeys`), signing failed with `privateKey`; now the newest key flagged for
  signing that carries its secret and is neither revoked nor expired signs, and a key file with none
  names each key and why it was passed over. The SLSA provenance renders its parameters in key
  order, so a re-run uploads the same `.intoto.json` it did the first time; a snapshot's
  `maven-metadata.xml` lists `.intoto.json` with the extension `intoto.json` and escapes its
  coordinates; and the Central Portal upload escapes the bundle's file name.
- **On macOS the engine no longer leaks a Mach port reference on every memory reading.** The engine
  reads host memory several times a second, and each reading took a reference to the host's Mach
  port that was never given back. Its available-memory figure also counted speculative pages twice,
  so memory admission now sees slightly less available RAM (tens of MiB on a 16 GiB machine), about
  what `vm_stat` reports as reclaimable.
- **A cancelled HTTP or MCP job that ignores the cancel ends in seconds, not at its one-hour
  deadline.** Time the machine slept or the engine was stopped no longer counts against a job's
  deadline. An engine fault while a job was starting no longer leaves the project unbuildable or the
  engine unable to drain until restart. A cancelled job's final line always reaches the client,
  however far behind the client is.
- **A failed cache restore keeps a module's resources, and a damaged cache record is a miss, not a
  failed build.** The action cache keeps a module's copied resources and merged Kotlin/Groovy
  classes when a restore fails on a bad blob. It no longer replaces a local record with a remote one
  made for another platform or JDK, and a cache cleanup no longer deletes a blob another build
  reused while the cleanup ran. A damaged per-JDK record copy or freshness stamp is treated as a
  cache miss instead of failing the build, and leftover per-JDK index files are cleaned up.
- **`jk audit` reports each advisory once per package version, and never offers a commit hash as the
  fix.** The classifier jars of one version (a native jar per platform) no longer repeat a finding,
  and the fixed version comes from OSV's version ranges only. An `--osv-vulns-url` written without a
  trailing slash now keeps its last path segment.
- **Replacing the engine while a build runs leaves the new engine reachable.** When the old engine
  finished draining it deleted the new engine's dashboard URL file, and could delete its endpoint
  pointer. An unused engine that nothing points to no longer exits under a job admitted the moment
  it decided to go, and a stop that reaches an engine already shutting down no longer starts a
  drain.
- **A version range far below a library's newest releases resolves.** A resolve that fails at first
  now retries with every published version of each library, so a range below the newest 48 releases
  resolves instead of reporting no solution. Widening the search after a conflict no longer asks the
  repositories for the versions of a workspace member.
- **A stalled gzip download fails instead of hanging, and an empty gzip answer is read as what it
  is.** A download of a gzip-encoded file that stops before its first bytes now fails after the idle
  limit, naming the URL, instead of hanging the build; an empty 304 or 404 that a server labels gzip is
  read as the answer it is instead of being retried and reported as a failure. Two fetches storing
  different bytes for the same artifact at once no longer leave the first one's checksum recorded
  against the second one's file, also when they come from two JumpKick homes writing one file in
  `~/.m2/repository` under `[m2] integration = "share"`.
- **`jk build --redo` recompiles a Kotlin module from scratch.** Under `--redo` (and `--force`)
  Kotlin's incremental compile found nothing to do, kept whatever classes were in `target/kotlin/`,
  damaged or stray ones included, and stored them in the action cache as a fresh result.
- **Cache keys follow package annotations, classpath order and processors found on the classpath.**
  `@NullMarked` on a package, or an annotation on an enum constant, now recompiles Kotlin code that
  reads the Java source; swapping the components of a Kotlin `@JvmRecord` recompiles its Java
  consumers; a compile that runs annotation processors recompiles when a Kotlin dependency's
  metadata changes; and groovyc recompiles when the body of an AST transform changes, or of a class
  in another jar that the transform calls.
  Processors javac finds on the classpath (`-proc:full` or `-processor` with no processor path) keep
  their generated classes across incremental compiles and run again when the classpath changes, at
  the cost of a whole compile of that module whenever a classpath entry is rewritten. Reordering a
  classpath re-runs tests, rebuilds a native image, a fat jar and a javadoc jar and reruns a plugin
  step that names it. Every cached result is rebuilt once after this upgrade.
- **The installers refuse a pre-release of the release they ship with, and keep cached templates
  when offline.** A latest-release pointer naming `0.53.1-rc1` is refused under a floor of `0.53.1`.
  An offline reinstall keeps the cached templates instead of deleting them. `install.sh` refuses an
  unknown option instead of taking it for a local file, and its fallback `jkx` script works when the
  home path contains `$`, quotes or backticks. On Windows, `install.ps1` turns on TLS 1.2 under
  PowerShell 5.1 and refuses a non-HTTPS `JK_RELEASES_URL` or a malformed `JK_VERSION` before
  downloading anything.
- **Spring Boot jars keep the AOT output's service registrations, and a minified library keeps its
  nested classes.** A Boot jar merges `META-INF/services` files, `spring.factories`, `aot.factories`
  and `*.imports` files that both the application and the Spring AOT output carry, instead of
  keeping only the application's copy; a third nested jar with the same name is `name-2.jar`, not
  `name.jar.2`; and no `target/.jk-aot-activate` folder is left behind. A minified library jar keeps
  nested public classes (`Outer.Inner`) and carries the `[manifest]` table. Quarkus no longer leaves
  its repository-credentials temp file behind when a later temp file cannot be created, and its logs
  and errors no longer show a repository credential. The jOOQ preset prints JDBC URLs without user,
  password or parameters.
- **`jk image` keeps training secrets off the container runtime's command line.** With `aot-cache =
  true`, `[image.env]` and `[image.training-env]` values were passed as `-e KEY=VALUE`, where any
  user of the host could read them; the runtime now reads them from an owner-only env file that is
  deleted after training. A Spring Boot image's dependency jars ship in the `dependencies` and
  `snapshot-dependencies` layers instead of the application layer, so an app change no longer
  re-pushes them (the image digest changes once). A base image whose layer carries an opaque
  whiteout at its root no longer fails the JRE extraction, and builds sharing one base JRE take
  turns extracting it.
- **MCP session ids are not reused, and ending a session stops what waits on it.** An
  `Mcp-Session-Id` is now 128 random bits and is never handed to another client, so an agent
  returning with an ended id gets a `404` instead of joining someone else's session; the label in
  the dashboard stays short. Ending a session (`DELETE /mcp` or expiry) stops any call still waiting
  on that client's answer at once. The `details` tool no longer fails on a line cut short in a
  transcript still being written, and paging deep into a large transcript no longer reads it all
  into memory.
- **A dependency's POM cannot send the resolve to this machine's files or services.** A repository a
  dependency's POM declares is used only over https to a network host: a `file:` tree, an `s3://`,
  `ftp://` or other scheme, and a loopback address (unless the project itself resolves through one)
  are skipped with a lock note, and the note prints the URL without its credentials. A
  `JK_REPO_<ID>_HOST` without a scheme, or an id that is the host, no longer sends a credential to a
  plain `http://` URL off loopback; bind the full `http://` URL to do that. Pre-signed and API-key
  query parameters (`X-Amz-Signature`, `X-Amz-Security-Token`, `X-Amz-Credential`, `sig`, `api_key`,
  `apikey`) are masked wherever JumpKick prints a URL.
- **A cancelled build cannot silently lose a lock it holds.** An interrupt that landed while a lock
  file's description was being written or read closed the file, which released the lock while the
  build still believed it held it. That I/O now runs where an interrupt cannot reach it.
- **An archive entry cannot create a directory outside the install.** Installing a JDK or a build
  tool from a zip no longer creates directories outside the destination when an entry name climbs
  out through a missing directory (`missing/../../x`): the entry is refused before anything is
  written, and one that climbs back inside now extracts where it lands. A tar entry with a negative
  or overflowing size is refused as corrupt, GNU long file names keep their spaces, cancelling a JDK
  download reports a cancel rather than a failed download, and a newly installed Maven or Gradle has
  its launcher marked executable before another install can see it.
- **A plugin task cannot declare a path outside its own output root.** An output or `contributes*`
  path that is absolute, empty, `.` or climbs out with `..` is refused where the plugin declares it
  and again when the engine reads the plugin's declarations, so no plugin can put a directory from
  elsewhere on disk into a module's classes, resources, sources or test classpath, or point test JVM
  arguments at a file outside it.
- **`jkx` runs `.groovy` scripts, and `.kts` scripts start about 12× faster.** `jk tool run`
  compiled a `.kts` with `kotlinc -script` on every run, about 2.6 s each time. A `.kts` now
  compiles once into `$JK_CACHE_DIR/tool-scripts/`, as `.java` and `.kt` already did, and a later
  run starts a plain `java`: about 0.2 s. `.groovy` scripts run the same way (about 0.5 s from the
  cache), with `//DEPS`, `@Grab` and `//GROOVY <version>`. `jk tool install` of either writes a
  launcher over the cached classes ([Tools](tools.md)).
- **Groovy build-logic scripts compile once.** A `.groovy` script under `.jk/scripts/` was
  compiled on every run that executed it. Its classes are now cached under
  `$JK_CACHE_DIR/groovy-scripts/` by the script's content, and a run from the cache never loads the
  Groovy compiler: about 40% faster and 28% less memory per script on a typical one
  ([Build logic](build-logic.md#how-groovy-scripts-run)).
- **Windows: docker and git credentials work at the desktop after an ssh session used JumpKick.**
  The engine, and every docker and git command it ran, kept the logon of whoever started it, so an
  engine an ssh session had started served desktop commands without your credential vault: a
  `docker pull` in a test failed with `A specified logon session does not exist` until `jk engine
  stop -A`. The desktop and logons without one (ssh, services, scheduled tasks) now each get their
  own engine, and `jk engine status` names the kind in a `Logon` row
  ([Engine](engine.md#several-engines)).
- **A README cannot reach outside the dashboard's Markdown preview.** A Markdown file could write
  the fixed text that marked where a Mermaid diagram goes into an attribute and turn the rest of
  that attribute into markup the sanitizer never saw. Diagrams now go in by an unguessable marker,
  placed as page elements after sanitizing. The dashboard also refuses to be framed by another
  site, loads scripts only from the exact CDN packages and versions it uses rather than from any
  package on unpkg, and tells the browser never to guess a response's type.
- **Annotation-processor classes survive `jk clean`.** After `jk clean`, a build served from the
  cache and then an edit, the next compile deleted every class an annotation processor had
  generated and wrote back only those of the edited sources, so they went missing from
  `target/classes` and the jar. The build served from the cache now lays back the record of what
  the processors generated when it restores this checkout's own last compile, so the edit stays
  incremental; a restore of another compile's classes recompiles the module whole once.
- **JVM startup caches no longer pile up on a self-built JumpKick.** The caches the workers and the
  engine map to start fast (`~/.jk/state/aot/`, `~/.jk/state/engine-aot/`) now go when they have
  not been used for 12 hours on a `-SNAPSHOT` build, where every rebuild left a new set for two
  weeks (1.2 GiB in two days on one contributor's machine). A release keeps its caches until you
  install another version, which deletes all of them, or until the disk is nearly full, when the
  unused ones go first. `jk storage usage` shows them and `jk storage clean` removes the unused
  ones ([Cache](cache.md#jvm-startup-caches)). A cache your engine's running workers map is never
  deleted, on Windows too, and a half-written recording a stopped engine left behind goes within
  minutes instead of a day.
- **Breaking: `~/.jk` holds everything JumpKick downloads.** Central's jars no longer go into
  `~/.m2/repository`, so a CI runner that caches `~/.jk/store` has all of them. A jar Maven already
  fetched is still used where it is. `[m2] integration = "share"` in `~/.jk/config.toml` restores
  downloading into `~/.m2` ([Repositories](repositories.md#sharing-with-maven)).
- **A file in `~/.m2` that JumpKick never verified is never pinned unchecked.** A jar or POM Maven
  downloaded is used only once its repository's published checksum matches it. Offline, or with
  the repository unreachable, the check cannot run, so the file is not used: an offline `jk lock`
  that needs it fails with a warning naming it, and one `jk lock` online fixes that. A build whose
  lock pins the file still reads it offline ([Repositories](repositories.md#offline)).
- **Breaking: `jk install` and `jk release` stay out of `~/.m2` unless asked.** They install into
  JumpKick's own repository, where every JumpKick project finds the result; `--destination m2` on
  either command, or `[install] destination = "m2"`, installs for Maven and Gradle builds too.
- **Mark committed generated code, and JumpKick never formats it.** `[build] generated-src` names a
  generated source root that compiles like `extra-src`; `[format] exclude` takes globs for vendored
  code. A plugin can mark the root it writes, so a generator's output needs no setting at all.
- **Keep a workspace member out of a release.** `[publish] skip = true` in a member's `jk.toml`
  keeps tests, examples and tools out of `jk publish` and `jk install`; `jk explain` names them, and
  a shipped member that depends on one stops before it builds.
- **Breaking: Kotlin sources with no `kotlin` key fail the build.** A module that inherits `java`
  and holds `.kt` files in `src/main/kotlin`, `src/main/java` or a test suite's root built an empty
  jar and said OK; it now stops, naming the line to add (`kotlin = "2.4.10"`). Groovy and Scala do
  the same; test data elsewhere under `src` is only warned about, as before.
- **`jk format` leaves generated files alone.** A file whose header comment says `DO NOT EDIT`,
  `@generated` or `GENERATED by …` is never rewritten or flagged by `jk format -c`, however long
  that comment's lines are.
- **A test that reads your shell shares its result through a cache server.** When the server
  hands out a key seed, or your team sets one (`JK_CACHE_KEY_SEED`, or `[cache] key-seed-file` in
  `~/.jk/config.toml`), another machine with the same values replays the result, with no `[env]`
  configuration, credentials included (`[cache] credential-env = "local"` keeps those on the
  machine). Only one combined key leaves the machine, never a value.
- **A CI runner's own cache replays those tests too, with no cache server.** With your team's
  seed set and no shared cache, a result that read your shell is also kept under the seed's key in
  `~/.jk/cache`, so a runner that restores that directory (`actions/cache`, say) and sets the same
  `JK_CACHE_KEY_SEED` replays it; no seed, another seed or another value runs the suite. It needs a
  committed `jk-lock.toml`; cache `~/.jk/cache`, never `~/.jk/state`
  ([Cache](cache.md#a-ci-runners-own-cache-with-no-cache-server)).
- **Without a seed, a result keyed on a value of your environment stays on its machine, `[env]
  scope` included.** A scoped or declared value no longer keys by its plain digest, which anyone
  holding the key and a checkout could test guesses against. If you share scoped suites through a
  cache that issues no seed, set the same `JK_CACHE_KEY_SEED` on every machine to keep sharing
  ([Cache](cache.md#results-that-read-your-environment)).
- **Compile, plugin, packaging and node steps no longer key your environment by plain digest.**
  A value a module forwards to its compilers or plugin steps (`[env]`, a plugin table's `env:NAME`,
  `[env] inherit`), a packager's signing secrets and a node build's `CI`, `NODE_ENV` and
  public-prefix variables are HMAC'd under your home's secret, and under the key seed when shared,
  as a test's reads are. Without a seed those results stay on their machine. Release builds that
  sign through `env:` and node builds on CI share between machines once every machine sets the same
  `JK_CACHE_KEY_SEED` ([Cache](cache.md#results-that-read-your-environment)). Their keys change, so
  the first build after the upgrade runs those steps once more.
- **The key seed stays inside JumpKick.** No program the engine starts (npm, `git`, the AOT
  trainer) is handed a `JK_CACHE_KEY_SEED` the engine's shell exported, a `jk.toml` that names it
  is refused, and a machine that changes to a credential the server hands no seed stops using
  the one it was handed before.
- **JumpKick's own credentials stay out of your tests.** A `JK_REPO_<ID>_TOKEN` (a shared cache's
  token included), `JK_REPO_<ID>_PASSWORD`, `JK_GITHUB_TOKEN` or `JK_GPG_PASSPHRASE` in the shell
  that runs `jk` no longer reaches a test, a compiler, a plugin step or any program the engine
  starts, even under `[env] inherit`. A test that needs one names it, for example
  `[test] env = ["JK_REPO_CORP_TOKEN"]` ([Security](security.md#jumpkicks-own-credentials)).
- **A dropped repository connection reads the same every time.** When a passable repository
  (optional, loopback, or a POM's) drops the connection, its warning names the failure and the
  attempts it took, even when a checksum asked beside the download met the drop first; that
  case lost the failure and named only the fault.
- **An `[audit] ignore` entry's `until` is your local date.** `jk audit` judged it on the date of
  the engine, which a daemon started from a shell in another zone (or, on Windows, the machine's
  zone, whatever `TZ` says) could put a day apart, so an entry could block a day early or ignore a
  day late. It now ignores through that day on the local calendar where `jk audit` runs.
- **`jk why` and `jk import` ask a mirrored repository through its mirror.** Under a `mirror-of`
  table in `~/.jk/config.toml`, `jk why` read the POM of a row from a repository a dependency's
  POM declared, and `jk import` checked a pin against a repository the imported POM declares, at
  that repository's own URL. Both now ask the mirror, as `jk lock` does.

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
