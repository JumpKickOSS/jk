# Migration from Maven and Gradle

You do not have to rewrite the build on day one.

```bash
jk mvn package                 # real Maven (wrapper-aware), managed by jk
jk gradle build                # real Gradle
jk import pom.xml              # → jk.toml + fidelity report
jk import settings.gradle.kts  # Gradle evaluates the build in a fork; every module imports
jk export maven                # publishable POM
jk export gradle
jk export idea | vscode
jk export bom                  # freeze lock as a Maven BOM — see Platforms
```

Everything after `jk mvn` / `jk gradle` belongs to the tool, `-Dkey=value` properties included —
`jk mvn -Drevision=1.2.3 -Dtest=FooTest verify` reaches Maven with both, `jk mvn -v` prints Maven's
version, `jk gradle -q build` keeps Gradle quiet, `jk mvn -C install` is Maven's strict-checksums
flag followed by a goal. jk's global flags go before the command name (`jk -q mvn package`,
`jk -C app gradle build`).

The one exception is the command's own four options, which say how jk provisions the tool and
so are matched wherever they appear after the name, spelled out in full: `--tools-dir <dir>`
(where jk installs Maven or Gradle), `--jdks-dir <dir>` (where it finds the JDK it runs them on),
`--no-discover` (skip the look for an installed one) and `--accept-unverified-tool` (below).
Neither Maven nor Gradle has a flag of those names, and only the exact spelling is taken —
`--tools` is Maven's — so nothing of the tool's is lost. `jk mvn --tools-dir /opt/jk-tools clean`
therefore provisions Maven under `/opt/jk-tools` and runs `mvn clean`; `jk --help mvn` lists the
four.

With no wrapper in the checkout, `jk mvn` provisions the newest Maven 3.9.x jk knows — or, when the
root POM's `maven-enforcer-plugin` states a `requireMavenVersion` floor above that (`3.9.11`,
`[3.9.11,)`, or a `${property}` the POM's own `<properties>` resolve), the floor itself — so a repo
that requires a newer Maven is never run on an older one. A wrapper's `distributionUrl` always wins.

A distribution jk downloads is verified before it is unpacked, against the first of these that
exists: the wrapper's own `distributionSha256Sum`; a digest accepted for that version earlier;
the checksum its publisher puts beside the archive — Apache Maven's `.sha512` (3.7 and later),
else its `.sha1` (the 3.6 line publishes only that), Gradle's `.sha256`. The `Maven 3.6.3
downloaded · verified against the published .sha1` line says which one held. A wrapper pinned to
a distribution with none of the three is refused, naming each checksum jk looked for; pin its
SHA-256 in `maven-wrapper.properties`, or accept that one download with
`jk mvn --accept-unverified-tool …` (or `JK_ACCEPT_UNVERIFIED_TOOL=1` in a CI step): jk installs
the archive, records its SHA-256 as `tools/maven/<version>.accepted.sha256` in the store, and
verifies every later download of that version against it, so the next run is silent. The flag
never bypasses a checksum that is published.

`jk mvn` also writes jk's run report for the Maven run: `target/jk-results.md` and the history
row behind `jk results`, MCP `jk_results` and `jk_diagnostics` (header `trigger: cli · tool: mvn`).
The run counts as a build: it takes the project's next build number (`#N` in the header), appears
in `jk history` and on the dashboard's Activity feed labelled `tool: mvn`, so a supervisor sees
what an agent ran through Maven beside what it ran through jk. The row's duration is Maven's wall
clock, from the launch to Maven's exit, and its `Saved` column stays empty: jk ran none of the
steps and cached none of them, so there is no cache saving to price. Provisioning Maven writes no
report of its own — a `jk mvn -v` leaves no `target/jk-results.md` behind.
A small Maven core extension, `jk-maven-spy-<version>.jar`, rides Maven's `-Dmaven.ext.class.path`
and records the reactor's events; after Maven exits the engine folds those events, each module's
`target/surefire-reports` / `target/failsafe-reports` XML and the compiler plugin's
`file:[line,col]` failures into the same Tests, Modules and Diagnostics blocks a jk build gets.
Each mojo is a step with the time Maven spent in it — the spy clocks every execution from its
start to its end, since Maven's own summary times only whole modules — so the Failed steps table
says how long the failing `surefire:test` ran, not just that it failed.
The jar is looked up in this order, first hit wins: the `jk.maven-spy.jar` system property;
`~/.jk/lib/jk-maven-spy-<version>.jar` (where the installers put it from a dist); the store's
`jk-local` shelf (`jk install` from a checkout); `lib/` beside the `jk` binary (the `target/dist`
ship layout). A release install (`curl … | bash`, `install.ps1`, the JVM client) carries no spy
until the first `jk mvn`, which fetches its own version's `jk-maven-spy-<version>.jar` from the
release directory into `~/.jk/lib/` — the same directory, signed `SHA256SUMS` and checksum the
engine jar is held to, shown as a `Maven` download line — and then runs Maven with it. A fetch that
fails (offline, a mirror without the jar) is one line on stderr; Maven still runs, without a
report, and the next `jk mvn` tries again. `jk doctor` has an `mvn` row that names the jar's
place and the one command that fills it. With no jar found, `jk mvn` is a plain passthrough and
writes no report. A project with only a `pom.xml` binds for MCP by its POM coordinate.

**POM import** is the primary path, and it reads the POM the way Maven does: the effective
model, built by Maven's own model builder. Parents are flattened (a sibling `pom.xml` in the
reactor answers first, then any `<repository>` the POM declares, then the repositories jk knows),
`dependencyManagement` is merged so a dependency declared without a version gets the managed one —
written `managed` or as the versionless coordinate when a published BOM or parent chain the manifest
carries as a `[platform-dependencies]` row supplied it, and as the version Maven resolved otherwise
([`managed`](dependencies.md#managed-the-platforms-version-in-the-string-form)) —
its inline pins that no declared dependency uses become `[managed-dependencies]` entries so they
govern transitive versions as they do under Maven (a reactor parent's once, on the workspace root —
[Managed versions](dependencies.md#managed-versions)),
`import`-scope BOMs become `[platform]` entries with their versions resolved, in the order the
POM declares them (two BOMs that manage the same module resolve to the first one's version under
`pins = "nearest"`, as Maven's imports do — [Platforms](platforms.md#two-boms-that-manage-one-module)),
`${property}` placeholders are interpolated, and profiles Maven would activate on this machine (active by
default, JDK, OS) are folded in. The fidelity report names what each parent contributed —
"versions for X, Y managed by parent g:a:v" — and a parent no repository has is a Tier-3 row, not
a failed import. The compiler level is written as `java = N`, never as a `jdk` pin: a level below
17 is raised to jk's floor with a row saying so, and `jdk =` appears only when the POM pins a
toolchain (`maven-toolchains-plugin` or `<jdkToolchain>`). Inactive profiles land by payload
(the table below). A `<classifier>` is carried: the entry is `{ group, name, version, classifier }`
with the handle suffixed by the classifier, so a jar and its `natives-linux` twin are two entries,
and a classifier a POM spells with `${os.detected.classifier}` or `${javafx.platform}` is written
as this host's word. A `<type>` maps where jk has a spelling — `pom` to `[platform-dependencies]`,
`test-jar` to `kind = "tests"`, `ejb-client` to the `client` classifier — and a type jk cannot
spell (`aar`, `war`, `zip`) is a Tier-3 row with the dependency left out rather than written as a
jar that does not exist. `<exclusions>` are carried: each one is an entry in the dependency's
`exclude` list (`group:artifact`, `group:*` for a `*` artifactId, `*:artifact` or `*:*` for a
`*` groupId — [Dependencies](dependencies.md#exclusions)), and the lock prunes the subtree the way
Maven does. An exclusion written under `<dependencyManagement>` lands on the
`[managed-dependencies]` row for its module, so it prunes every edge onto that module, the
dependency POMs' included, as it does under Maven. A `<version>` that no repository the lock reads
lists or serves (`swing-layout 1.0.2`, which Central never published) is a Tier-3 row at import naming
what the catalogs list instead, so the refusal arrives while the POM is still in front of you — a
release whose POM a repository serves while its `maven-metadata.xml` stops short of it (Central's
`jfree:jfreechart 1.0.13`) is found by the POM, as Maven finds it; a repository
that cannot be reached during that check leaves a Tier-2 note, not a claim; a pin whose POM a lock
on this machine already fetched is not checked again. Read that report before trusting the
generated `jk.toml`.

**A repository that never answers stops the import.** The parent and BOM reads run under the
resolve stall window (`JK_RESOLVE_TIMEOUT_MS`, 120 s; `0` never stops): an import whose reads stop
advancing for that long fails naming the coordinate it was reading and the URL it waited on, as
`jk lock` does, instead of sitting silent until something kills it.

**An optional dependency stays optional.** `<optional>true</optional>` is written as
`optional = true`: the module's own dependency, which no consumer inherits — see
[Dependencies](dependencies.md#optional-dependencies).

**A direct version is the version, as it is under Maven.** Import writes every `<dependency>`
version as an exact pin and sets `[resolve] pins = "nearest"` on the root and on every member (a
workspace lock reads the root's), so the lock resolves a pinned module the way Maven's nearest-wins
did: the project's pin is the version, and a transitive POM's range on that module — a plain
version or an open floor such as `[2.0.18,)`, declared by a dependency of the same module or of
any sibling — is reported, not enforced. `cryptofs 2.10.0` declares `jakarta.inject-api 2.0.1.MR`;
the POM's own `2.0.1` wins, the lock edge reads `jakarta.inject-api@2.0.1 <- 2.0.1.MR`, and
`jk lock` prints one warning per overridden range so the divergence from what the library asked for
is on record. A `jk.toml` written by hand keeps the default, `pins = "exact"`, under which the same
shape is a conflict PubGrub refuses with its explanation; delete the `[resolve]` line to get that
strictness back on an imported project. The alternative — importing direct versions as `>=` floors
so highest-wins lifts them — would float every imported project past the versions Maven built
with, which is not what the POM says. Making an existing Maven project work under jk — plugin-aware
mapping, structured results from `jk mvn`, and a jk loop over an unmodified `pom.xml` — is the
first epic of [the 1.0 plan](../contributors/plan-1.0.md).

**A reactor imports as one workspace.** The walk follows `<modules>` the way Maven does: through
every aggregator (a `pom`-packaged module with `<modules>` of its own is a parent and a list, not
a module jk builds) and into the modules a profile active on this machine adds, so a reactor of
181 POMs becomes one `[workspace]` of root-relative paths (`community/kernel`, `websocket/spi`).
A dependency on any module of the reactor is a workspace edge — `{ workspace = true }`, with
`kind = "tests"` for a `test-jar` — wherever the module sits and however its version is spelled,
because siblings match by `groupId:artifactId`. Two leaves that share an artifactId under
different groups (thingsboard's `common/edqs` and `edqs`) are both modules — each builds into its
own `target/<path>/` — and an edge to that name carries the group the POM named,
`edqs = { workspace = true, group = "org.thingsboard.common" }`, so the loader picks that member
([Workspaces](workspaces.md#one-name-in-two-groups)); the report names the carriers and the
dependents that got the qualified edge. A sibling answers as a parent and as an `import`-scope BOM before any repository is asked. A BOM
leaf — packaging `pom`, no `<modules>`, a `<dependencyManagement>` table and nothing else of its
own — is not a workspace module: a `dependencyManagement` that imports it is applied to the
declared dependencies and the BOM is not written as a `[platform]` row (the lock fetches a BOM from
a repository, which a reactor BOM is not in; the row on each importing member says so), and a BOM
no member imports is one row. A `pom`-packaged leaf with plugins of its own stays a module.
A dependency on a reactor POM the workspace does not build — a jar edge to an aggregator, or any
edge to a module only an inactive profile lists — is dropped with a row naming the POM and its
modules or its profile, because no repository has a reactor POM for the lock to fetch. A
`<type>pom</type>` edge to an aggregator is rewritten instead of dropped: the aggregator's own
compile and runtime dependencies, which Maven put on the dependent's classpath through the pom, are
written on the dependent in the edge's place — a reactor member among them as a workspace edge, a
coordinate the dependent declares itself left to that declaration — and the row names them. A
published parent's `<repository>` whose URL is a property nothing values (`${vertx.snapshotRepository}`
in the Vert.x parents) is left out of the lookup the way Maven only fails on a fetch from it, so the
parent still hands its managed versions down; and a dependency an inactive profile declares without a
version takes the one the POM's effective `dependencyManagement` supplies, so a `[features.<id>]`
entry is pinned like the profile would be under `-P`. CI-friendly versions —
`${revision}`, `${changelist}`, `${sha1}` — take their values from the POM chain's
`<properties>`, which is where Maven reads them without `-D`; a placeholder no POM defines is
written as `0.0.0-SNAPSHOT` with a row naming the property. A module list that lives only in
profiles Maven would not activate here is a Tier-3 row, and a workspace whose modules have no
source tree fails `jk build` with a one-line `built nothing` reason instead of finishing green.

### Building a Maven repository without importing

`jk build`, `jk test` and `jk explain` run in a directory that has a `pom.xml` and no `jk.toml`.
The project model is Maven's effective POM — parents flattened, managed versions applied,
properties interpolated — imported exactly as `jk import` imports it and rendered as a *shadow*
manifest at `target/jk/shadow/jk.toml`, with the lock beside it at `target/jk/shadow/jk-lock.toml`.
Nothing is written into the repository: no `jk.toml`, no `jk-lock.toml`. The shadow is a build
artefact: its header lists the POM files it was rendered from — the module's own and every parent
a `<relativePath>` reaches on disk — so editing a parent renders the child again, and `jk clean`
removes it.

```bash
cd my-maven-repo          # pom.xml, src/main/java, src/test/java — no jk.toml
jk build --skip-tests     # compiles into target/classes/main
jk test                   # runs the JUnit suite; target/jk-results.md
jk explain                # the same steps a jk.toml module gets
```

Resolution follows the POM: a bare version is an exact pin, a BOM import is an enforced platform,
and the POM's direct versions win over transitive requests (`[resolve] pins = "nearest"`, the
policy `jk import` writes). The results file's header says which mode ran — `manifest: pom.xml,
no jk.toml (effective POM, built in place)` — so an agent reading `target/jk-results.md` knows the
manifest it should edit is the POM.

A reactor — a POM with `<modules>`, at the top level or in a profile — builds as a workspace.
The root's shadow lists the modules Maven would build here as `[workspace] modules`, every leaf
gets its own shadow under its `target/jk/shadow/` with dependencies on siblings as workspace
edges, and the one lock lives beside the root's shadow. `jk build` at the root builds the whole
graph; in a leaf it builds that module and what it depends on, exactly as in a `jk.toml`
workspace. Nested aggregators belong to the outermost root; a module listed only by a profile
Maven does not activate on this machine is not built.

What the import report would grade Tier 3 (a `<build><extensions>` entry jk has no role for, a Tycho or OSGi-bundle packaging, a `war` packaging, a
`system`-scoped dependency, a parent no repository serves) is not an error here: the build after
a POM change reports each row once, under Warnings, with the remedy — `jk import pom.xml` writes
a `jk.toml` you can edit.

The manifest in this mode is the POM, so the commands that edit `jk.toml` — `jk add`, `jk remove`,
`jk update` and the `jk_deps` / `jk_manifest` / `jk_update` MCP tools — refuse a directory built
this way and name the two ways forward: `jk import pom.xml` to own a `jk.toml`, or edit the POM.

#### Behind a corporate mirror

A Maven shop that reaches Central only through Nexus or Artifactory has that in
`~/.m2/settings.xml` — a `<mirror>` with `mirrorOf` `central` or `*`, often a `<proxy>`, and the
internal repositories in an active profile. A coexistence build and `jk import` read the same file:
every request the resolver makes for `central` opens at the mirror's URL, the proxy carries it, and
the profile's repositories join the shadow manifest's `[repositories]` beside the POM's own. The
lock still records `central` at Central's own URL — the mirror is this machine's transport, so a
lock written behind Nexus is the lock a laptop on the open internet would write. `jk lock` says so
once per mirrored repository, and `jk doctor` lists the mirrors the engine applies. Details and the
`mirrorOf` grammar: [Repositories § Maven `settings.xml`](repositories.md#maven-settingsxml-mirrors-proxies-profiles).

### Where import stands on real repositories

The [Maven top-20 corpus](https://github.com/JumpKickOSS/jk-examples/tree/main/corpus/maven-top20)
in jk-examples clones the twenty most-starred GitHub repositories that build with Maven on Java 17
or newer and drives Maven and jk through one protocol: import, lock, build, test, and the same
cold / no-op / one-file-edit walls for both tools. Its `RESULTS.md` is the ratchet; a run that
lowers a count is a regression.

| Count (of 20) | jk 0.13.7 | run 2 | run 3 | run 4 | run 5 | run 6 | run 7 | run 8 | run 9 | run 10 | run 11 | run 12 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| import with no Tier-3 row | 15 | 13 | 11 | 12 | 12 | 13 | 13 | 12 | 11 | 12 | 12 | 11 |
| `jk lock` succeeds | 2 | 6 | 6 | 4 | 10 | 9 | 14 | 14 | 14 | 16 | 16 | 16 |
| `jk build --skip-tests` compiles something | 1 | 3 | 3 | 2 | 4 | 4 | 8 | 7 | 10 | 9 | 11 | 11 |
| `jk test` runs and passes | 0 | 0 | 0 | 1 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |

The lock and build counts moved because the effective-POM import resolved every managed version;
the import count fell because the same import now reports a parent or BOM it cannot fetch as a
Tier-3 row where 0.13.7 wrote `=unresolved` and failed later. Run 3 lowered it again for the same
reason: the packaging mapping now names a `war` module (apollo, java-design-patterns) as a Tier-3
row instead of importing it as a jar that Maven would never have built that way. An import count
that falls because a silent mismatch became a named one is the ratchet working; a count that falls
because a repository stopped importing is not. Run 4 (main 12de60df5, all of 2026-09-16) is the
first with a repository green end to end: TheAlgorithms/Java runs and passes its 9,745 tests under
jk, in 13 s against 37 s under Maven, once the test JVM kept the platform default thread stack.
Its lock and build counts fell for a reason the table cannot show: run 3 imported dataease as one
module of fifteen and neo4j as three of 181, and locked those fragments; run 4 imports the whole
reactor of each (nested aggregators, CI-friendly versions, sibling edges) and the full graph hits
two walls the fragments never reached. Both are tickets: two imported BOMs managing one artifact,
where Maven takes the first-declared import and jk still refuses (seven repositories); and a
workspace module's exact pin losing to a transitive's floor under the nearest policy (neo4j,
analysis-ik). Run 5 (main c3011aae3) passed both: ten repositories lock, four compile, and
analysis-ik joins TheAlgorithms/Java with every test passing. The walls that stop the others now sit
past the lock, in the compile and test steps, and each is a ticket: a classpath-discovered
annotation processor run without its own dependencies (neo4j), a `module-info.java` compiled off
the module path (cryptomator), a javadoc error the lenient mode still fails on (xxl-job), a
sibling package the import drops (apollo), a test runner that exits before discovery (floci),
and three resolver gaps (an ISO-style exclusive range, a `pkg` type, a version the listing
misses). Run 6 (main 9439e27c4) imports thirteen clean and builds xxl-job, and lost mall's lock to
the same version-listing gap that stops nacos, now a P1. It carries a caveat the table cannot
show: `install.sh` from a tree's `target/dist` does not shelve the tree's worker jars, so the engine
in that run fetched the published 0.13.7 compiler worker and the worker-side fixes of the day
(the module path, the diagnostic bridge) were not in it; cryptomator's wall in run 6 is that
caveat, not a regression. Run 7 (main 39f7ad41d, the tree's own workers shelved by the installer)
locks fourteen and compiles eight: mall, nacos and zipkin lock once the solver's version universe
keeps older exact pins, dataease and jenkins lock once repositories carry their release and
snapshot policy, and the walls move into the compile and test steps (a sibling package the import
drops in nacos and jenkins, a `javax.jms` transitive in zipkin, a JavaFX suite with no display to
open in cryptomator, a test that refuses jk's class directory in apollo), each a ticket. Its wall
columns are not comparable: six gates shared the host during that run. The other walls are named
in the corpus's `tier3-reasons.md`, each with its ticket.
Run 8 (main dbd295c77) holds fourteen locks; its import count fell because neo4j's shaded sibling
is now a named row, and its build count fell because floci's imported `[quarkus]` table runs the
augment, which fails on a jar the POM excludes and that lock did not yet honour. hadoop's import
rows fell from 105 to 4 once reactor parents resolved from the checkout, and quarkus's 760-module
import outgrew the default engine heap, both tickets.
Run 9 (main 3095183a7) compiles ten: spring-cloud-alibaba, floci and zipkin build for the first
time (their test compiles still fall, on a grpc protoc plugin the `[protobuf]` import has no key
for and on a Zinc worker heap). quarkus imports for the first time, 727 Tier-3 rows down to 25, but
its lock asks for the platform BOM the `[quarkus]` table implies at the reactor's own version, which
no repository has; thingsboard's lock ends with the engine's heap in the materialize fan-out; hadoop's
lock passes its parent-version wall and outruns the harness's fifteen-minute cap. The import count
fell because nacos gained the same honest grpc row. Each is a ticket.
Run 10 (main 62707c258) locks sixteen: thingsboard and hadoop lock for the first time, hadoop's in
under five minutes where run 9 outran the harness's cap, and nacos compiles through to packaging. The
build count fell by one because floci's Quarkus augment looks the application's own coordinate up on
Central instead of taking the workspace root, and keycloak's lock outgrows the default engine heap at
966 packages; both are tickets, as are the four new walls the runs exposed (a proto include path,
a library module packaged as an application, `--add-exports` under `--release`, two spellings of one
repository URL).
Run 11 (main bd55560ec) compiles eleven: nacos and jenkins build through to their tests for the
first time (a plain jar where a bare Boot plugin packaged nothing, the `[antlr]` and `[taglib]`
presets for jenkins core) and thingsboard builds until the harness's cap. Both new test walls are
the same gap: a `provided` row Maven's test classpath carries and jk's does not (log4j-core for
nacos's adapter, jsr305 for jenkins-war), one ticket.
Run 12 (main 0dcdff45d) holds the counts while the set behind them moves: quarkus and hadoop lock in
the harness for the first time (389 s and 132 s), thingsboard compiles all 51 modules in 122 s and
reaches its tests where Maven fails to compile it, and floci builds through the Quarkus augment.
Against that, hadoop's build took the 256 MB engine down with a heap dump 13 s in, jenkins's
localizer plugin resolve and dataease's import both stalled in one twenty-minute window, and
floci's Quarkus tests wait on a dev service under jk exactly as they do under surefire. Each is a
ticket; the engine's heap on a hundred-module reactor is the first.

Two of run 7's test walls are the repository's contract with Maven rather than something jk
translates. cryptomator's `SecurePasswordFieldTest` starts the JavaFX platform in `@BeforeAll`;
on a host with no display Maven fails it with the same `UnsupportedOperationException: Unable to
open DISPLAY` (Surefire sets no `java.awt.headless`, and JavaFX would not read it), and the
repository's own CI wraps Maven in `xvfb-run`. Under jk the test JVM gets the display of the shell
running `jk test`, so `xvfb-run jk test` is the same remedy ([Test](test.md#a-suite-that-needs-a-display)).
apollo's `ApolloSqlConverterUtil.getRepositoryDir()` reads the class directory off
`ApolloSqlConverter`'s code source and accepts only a path ending in
`/apollo-build-sql-converter/target/classes`, stripping that suffix to find the repository root;
jk compiles the module to `target/apollo-build-sql-converter/classes/main`, so the check throws
`illegal class path`. No classpath scanner is involved and no `[test]` key spells a class
directory: the test hard-codes Maven's layout, and the portable spelling is the working directory
(`user.dir`), which both Surefire and jk set to the module directory. Until the test says that,
the module's suite fails under jk and the corpus row names it.

### Where Gradle import stands on real repositories

Seven public Gradle builds, each imported from a fresh clone with the Gradle its wrapper pins
(downloaded and verified on first use; 8.3 and 8.14.2 ran on the installed JDK 17 and 21 because
neither runs on 25) and read from a fork whose whole evaluation stayed inside the resolve budget:

| Repository | Build | Import | What the manifest carries |
|---|---|---|---:|
| spring-guides/gs-multi-module `complete/` | Gradle 9.3.1, 2 modules, Boot + dependency-management | 54 s, lossless | root `[workspace]`, `library.workspace = true`, `[spring-boot] version`, Boot BOM as the library's platform; `jk build --skip-tests` compiles both modules |
| square/moshi | Gradle 9.5.1, 11 modules, Kotlin DSL, version catalog, KSP, dokka, japicmp | 10 s, 18 Tier-2 rows | one manifest per module, sibling edges, `[provided-dependencies]`, `[processor-dependencies]`, two `japicmp` modules named by path; rows name the `checkLegacyAbi` tasks, the `java16` source set, the japicmp `baseline`/`latest` configurations and the publish plugin |
| spring-petclinic/spring-petclinic-kotlin | Gradle 9.7.0, Kotlin, Boot 4 | 16 s, 3 rows | `kotlin = "2.4.10"`, `[spring-boot]`, `bootstrap = "…:5.3.8"` and `webjars-locator-lite = "…:1.1.4"` from the script's `val`s; rows name the Jib and allopen plugins |
| junit-pioneer/junit-pioneer | Gradle 8.14.2 on JDK 21 | 37 s | `junit-bom = "org.junit:junit-bom:6.1.0"` from `gradle.properties`, `jimfs = "…:1.3.0"` |
| mapstruct/mapstruct-examples `mapstruct-on-gradle/` | Gradle 8.3 on JDK 17, Groovy DSL, `ext {}` | 9 s | `mapstruct = "1.7.0.Beta1"`, both comma-listed TestNG/FEST test dependencies |
| junit-team/junit-examples `junit-jupiter-extensions/` | Gradle 9.7.1 | 6 s | `[platform-dependencies] junit-bom`, `junit-jupiter-api` under `[dependencies]` through its `because` closure, both `testRuntimeOnly` entries |
| jillesvangurp/kotlin4example | Gradle 9.0.0 on JDK 21, refreshVersions | 23 s | every `_` version is a row naming the dependency and the entry is written version-less |

### Which Maven plugins import, and how well

Counted across 66 public repositories cloned for the Maven corpus and the agent-loop corpus on
2026-09-16 (root and module POMs, `<build>` and `<pluginManagement>`; a repo counts once per
plugin). The fidelity report cites this table per plugin; a grade says how the imported build
relates to the Maven one.

| Plugin | Repos | Lands in jk as | Grade |
|---|---:|---|---|
| spring-boot-maven-plugin | 36 | `[spring-boot] version` at the Boot version the chain resolves (Boot jar, platform BOM) when the plugin packages the module — a `repackage` execution (the starter parent binds one) or a `<mainClass>`; a bare declaration on a library with neither, or `<skip>true</skip>`, writes no table and the module packages a plain jar (a row says so); `<mainClass>` → `[application] main`; the `build-info` goal → `[build-info]`; `<excludes>` and buildpack `<image>` → rows | approximate |
| maven-surefire-plugin | 35 | `<groups>` / `<excludedGroups>` → `[test] include-tags` / `exclude-tags`; `<argLine>` (minus `${argLine}` and the JaCoCo agent) → `[test] jvm-args`; `<systemPropertyVariables>` / `<systemProperties>` → `[test] system-properties`; `<includes>` / `<excludes>` and `skipTests` → rows (`--class`, `--skip-tests`) | exact |
| maven-compiler-plugin | 35 | `java =` (floor 17), `<compilerArgs>` and the `<parameters>`, `<enablePreview>`, `<failOnWarning>` switches → `[javac] args`; the plugin's own `<configuration>` reaches both compiles, an execution bound to the `compile` goal alone reaches `[javac] args` for compile-main and one bound to `testCompile` alone reaches `[javac.test] args`, so Error Prone on the compile goal does not check the suite; `annotationProcessorPaths` → `[processor-dependencies]` from the plugin's own configuration and any execution reaching compile-main (an execution bound to the `compile` goal alone reaches compile-test through that table too, and a row says so), and → `[test-processor-dependencies]` from an execution bound to `testCompile` alone, so compile-test alone runs those; without that element, Lombok, MapStruct, AutoValue, Dagger, Immutables, Micronaut inject-java and Hibernate jpamodelgen declared as plain dependencies → `[processor-dependencies]` too | exact |
| maven-jar-plugin | 24 | `[manifest]` entries, `Main-Class` → `[application]` | exact |
| dokka-maven-plugin | 1 | `[dokka] version` at the plugin's version; a plugin bound only to its `dokka` goal → `format = "html"`; the javadoc jar of a Kotlin module is Dokka output on every `jk build` | exact |
| git-commit-id-maven-plugin / git-commit-id-plugin | 4 | `[build-info]` — `git.properties` with the commit, branch, times and dirty flag in the jar; a `<generateGitPropertiesFilename>` under the output directory → `file`; `<format>json`, another `<dateFormat>` or a file written outside the output directory → row | exact |
| maven-javadoc-plugin | 22 | a library ships the javadoc jar by default; `<failOnError>true` or a `<doclint>` other than `none` → `javadoc = "strict"`; `<doclint>none` or `<failOnError>false` stays lenient | exact |
| maven-source-plugin | 19 | `sources = "always"` — the sources jar on every `jk build` | exact |
| maven-resources-plugin | 19 | nothing to map for the fixed layout; a filtered or non-standard `<resource>` directory is a row | approximate |
| jacoco-maven-plugin | 17 | `jk test --coverage` is a run flag, not a manifest key → row | manual |
| maven-gpg-plugin | 17 | `jk publish --sign` | exact |
| maven-assembly-plugin | 17 | `jar-with-dependencies` → `[application] assembly = true` (needs a `<mainClass>`, else a row); other descriptors → row | approximate |
| maven-enforcer-plugin | 17 | `requireJavaVersion` → `java =`; banned deps → `jk deny`; rest → row | approximate |
| build-helper-maven-plugin | 16 | `add-source` → `[build] extra-src`, `add-test-source` → `[test] extra-src`; a root inside a generator's output (`target/generated-sources/openapi/…`) is that generator's contribution and is not written; other goals → row | approximate |
| exec-maven-plugin | 16 | `java` goal → `[application]`; `exec` goal → row (build logic) | manual |
| maven-dependency-plugin | 15 | an `unpack` / `unpack-dependencies` execution whose output directory a `wire-maven-plugin` reads → that recipe's `unpack` (the artifact at the module's dependency version); analysis / copy goals → row when bound to a phase | manual |
| maven-clean-plugin | 15 | `jk clean` | exact |
| maven-checkstyle-plugin | 14 | `[lint] checkstyle` ← `<configLocation>` (a built-in `sun_checks.xml` / `google_checks.xml` → a placeholder path and a row to copy the rule set in; a file an ancestor POM's directory holds is named by its path from the module, `../style/checks.xml`; a URL → the placeholder path and a row), `<excludes>` → `exclude`, `checkstyle-version` ← the plugin's own `<dependencies>` pin, `<includeTestSourceDirectory>` → `sources` + `src/test/java`, `<violationSeverity>warning` → `fail-on = "warning"` | approximate |
| maven-deploy-plugin | 14 | `jk publish` | exact |
| maven-install-plugin | 13 | `jk install` to `~/.m2` | exact |
| maven-failsafe-plugin | 13 | row naming its patterns (`**/*IT.java`, …) and the move into `src/integration/java`, jk's `integration` suite; `argLine` and system properties → the same `[test]` keys when Surefire set none, else a row | manual |
| maven-shade-plugin | 12 | `[application] assembly = true`, `Main-Class` from the manifest transformer; a whole-package `<relocation>` → `[application] relocate` (see [Packaging § Package relocation](packaging.md#package-relocation)); a relocation with `<includes>`/`<excludes>`/`<rawString>`, filters, other transformers and `minimizeJar` → rows; a shaded member with no main writes no table, and one whose shaded package another member's sources import → Tier-3 row on the shaded member (a sibling compiles against its classes tree, which carries no shaded package) naming the importers and the Maven-built artifact to depend on instead | approximate |
| kotlin-maven-plugin | 12 | `kotlin =` on the module at the plugin's version; when the main source directory also carries `.java` files (Kotlin tests beside a Java main tree, a `test-compile`-only plugin, or both languages in main) the module is mixed and `java =` is written beside it, so javac compiles the Java tree against kotlinc's output and the Kotlin test tree sees both — a row says so | exact |
| maven-release-plugin | 11 | nothing (release flow) | manual |
| central-publishing-maven-plugin | 11 | `jk publish --central` (planned battery) | manual |
| spotbugs-maven-plugin | 10 | `[lint] spotbugs = true`, `<excludeFilterFile>` → `spotbugs-exclude`, `<effort>` → `spotbugs-effort`, `<includeTests>` → `sources` + `src/test/java`, the plugin version minus its last digit → `spotbugs-version`; `<plugins>` (fb-contrib, find-sec-bugs) → row | approximate |
| spotless-maven-plugin | 10 | `jk format` | approximate |
| maven-antrun-plugin | 10 | build logic script → row | manual |
| maven-pmd-plugin | 8 | `[lint] pmd` ← `<rulesets>` (a `/category/…` or `/rulesets/…` path as PMD's built-in, a `file://` URL as a module file, the Maven plugin's own default ruleset or one spelled through a property nothing defines → `rulesets/java/quickstart.xml` and a row), `<includeTests>` → `sources` + `src/test/java`; `<excludeFromFailureFile>` / `<excludeRoots>` / `<excludes>` → row. A lint plugin a module only inherits, with no `<execution>` bound, runs under Maven by hand alone (`mvn pmd:check`): no table on the module, one row at the declaring POM | approximate |
| license-maven-plugin | 8 | nothing → row | manual |
| avro-maven-plugin | 9 | `[avro]`: `<sourceDirectory>` → `src`, `<stringType>` → `string-type` (Maven's own default, `CharSequence`, written when the POM leaves it unset), `<fieldVisibility>` / `<createSetters>` / `<createOptionalGetters>` / `<enableDecimalLogicalType>` → their keys, the plugin version → `version`; the `<outputDirectory>` is the preset's contribution, so a build-helper root inside it is not written; `<imports>` needs no key (the preset orders schemas by definition); `<includes>` / `<excludes>` / `<testSourceDirectory>` → row; the module keeps its `org.apache.avro:avro` dependency at the compiler's version | exact |
| protobuf-maven-plugin | 8 | `[protobuf]` on a module with `.proto` files under `<protoSourceRoot>` (default `src/main/proto`): the `<protocArtifact>` version → `version` (the protobuf-java dependency's when none), the root → `src`, the `<excludes>` of the plugin and its `compile` executions → `exclude`; the output under `target/generated-sources/protobuf` is the preset's contribution, so a build-helper root inside it is not written; a module the plugin reaches by inheritance without protos gets no table; `compile-custom`'s `<pluginId>` + `<pluginArtifact>` → `[protobuf.<pluginId>] plugin` (`<pluginParameter>` → `options`), a row when either is unresolvable | approximate |
| versions-maven-plugin | 7 | `jk outdated` / `jk update` | exact |
| docker-maven-plugin / jib-maven-plugin | 7 | `[image]`: `<from>` → `base`, `<to>` (or `<imageName>` / `<name>`) → `registry`, `name`, `tag`; a row points at docs/user/images.md for the ports, env, labels and entry point the plugin's other settings become | approximate |
| frontend-maven-plugin | 7 | `[dev.sidecars]` + a resource module | manual |
| liquibase-maven-plugin | 4 | row naming the tool recipe ([Database migrations](database.md)): `jk tool install org.liquibase:liquibase-core` with the driver and picocli, `LIQUIBASE_COMMAND_URL` from `.env`; the POM's `<url>` and `<changeLogFile>` are quoted in the row | manual |
| quarkus-maven-plugin | 7 | `[quarkus]` at the platform version; none when that version is one the reactor builds `quarkus-bom` at itself (Quarkus's own reactor at `999-SNAPSHOT`), since the BOM the table implies is published for releases only → one row naming those modules | exact |
| flatten-maven-plugin | 6 | Tier-2 row: the flattened POM is what `mvn deploy` publishes; `jk publish` writes its POM from jk.toml, which has no build-time properties to flatten, and `jk export maven` writes a flat POM | exact |
| os-maven-plugin (a `<build><extensions>` entry or the `detect` goal) | 6 | nothing to write: `os.detected.name`, `os.detected.arch` and `os.detected.classifier` are valued from the host in the effective model, so a `${os.detected.classifier}` classifier is this machine's word | exact |
| native-maven-plugin | 6 | `[native]`: `<imageName>` → `name`, `<buildArgs>` → `args`; `<mainClass>` → `[application] main`; declared bare with its executions only in an inactive profile → Tier-2 row naming the profile, no `[native]` (so Spring AOT stays off) | approximate |
| maven-war-plugin | 5 | not supported (packaging `war`) → Tier-3 row | none |
| jaxb2-maven-plugin (MojoHaus), maven-jaxb2-plugin / jaxb-maven-plugin (jvnet) | 5 | `[jaxb]`: the first `<sources>` directory or `<schemaDirectory>` → `src`, `<packageName>` / `<generatePackage>` → `package`, `<xjbSources>` / `<bindingDirectory>` → `bindings`, `<encoding>`, `<extension>`, `<arguments>` / `<args>` → their keys, `<noPackageLevelAnnotations>` → the `-npa` argument; the output directory is the preset's contribution, so a build-helper root inside it is not written; a `schemagen` or `testXjc` goal and jvnet `<plugins>` → row; the module keeps `jakarta.xml.bind-api` and a JAXB runtime | approximate |
| antlr4-maven-plugin | 5 | `[antlr]`: `<sourceDirectory>` → `src`, `<libDirectory>` → `lib`, `<listener>` / `<visitor>` / `<inputEncoding>` / `<arguments>` / `<options>` → their keys, `<treatWarningsAsErrors>` → the `-Werror` argument, the plugin version → `version`; the `<outputDirectory>` is the preset's contribution, so a build-helper root inside it is not written; `<includes>` / `<excludes>` / `<generateTestSources>` → row; the module keeps its `org.antlr:antlr4-runtime` dependency at the tool's version | exact |
| jooq-codegen-maven | 2 | `[jooq]`: `<target><packageName>` → `package`, `<database><inputSchema>` / `<includes>` / `<excludes>` → their keys, `<generate>` `records` / `pojos` / `daos` / `fluentSetters` → theirs, a `DDLDatabase` `scripts` property → `sql` (`defaultNameCase` → `name-case`, other properties → `properties`), a `<jdbc>` block with a resolvable URL → `jdbc-url` / `jdbc-user` / `jdbc-password` (a row names `driver` and the `sql` key), the plugin version → `version`; the target `<directory>` is the preset's contribution, so a build-helper root inside it is not written, and one under `src/main/java` is a row; the module keeps its `org.jooq:jooq` dependency at the generator's version | approximate |
| flyway-maven-plugin | 1 | row naming the tool recipe ([Database migrations](database.md)): `jk tool install org.flywaydb:flyway-commandline` with the driver, `FLYWAY_URL` from `.env`; the POM's `<url>` and `<locations>` are quoted in the row | manual |
| maven-hpi-plugin | 1 | a `generate-taglib-interface` execution → `[taglib]` with the POM's `<resources>` directories → `resources`; its `<outputDirectory>` is the preset's contribution, so a build-helper root inside it is not written; every other goal (`record-core-location`, the hpi packaging) → row | approximate |
| wire-maven-plugin (`de.m3y.maven` or `com.squareup.wire`) | 1 | `[generate.wire]` over `com.squareup.wire:wire-compiler` at the module's `wire-runtime` version (`latest` with a row when the module has none), `main` the compiler class; `<protoSourceDirectory>` → `inputs` (a glob under it) and `--proto_path`, or `unpack` when a `maven-dependency-plugin` execution unpacks a dependency there; `<includes>` / `<excludes>` → `--includes=` / `--excludes=`; a `generate-test-sources` binding → `contributes = "test-sources"`; the `<generatedSourceDirectory>` is the recipe's contribution, so a build-helper root inside it is not written | approximate |
| localizer-maven-plugin | 1 | `[localizer]`: `<fileMask>` → `mask`, the POM's `<resources>` directories → `resources`, `<outputEncoding>` → `encoding`, `<accessModifierAnnotations>` / `<strictTypes>` / `<keyPattern>` → their keys, the plugin version → `version`; the `<outputDirectory>` is the preset's contribution, so a build-helper root inside it is not written; the module keeps its `org.jvnet.localizer:localizer` dependency for the generated classes' runtime | exact |
| graphqlcodegen-maven-plugin (DGS codegen), graphql-codegen-maven-plugin (graphql-java-codegen) | 1 | row: DGS codegen is a `[generate.<name>]` recipe over `graphql-dgs-codegen-core`'s command line ([Generate](generate.md#graphql--a-recipe-not-a-table)); graphql-java-codegen has no command line, so its step stays under `jk mvn` | manual |
| openapi-generator-maven-plugin | 4 | `[openapi]`: `<inputSpec>` → `spec` (an HTTP URL is fetched once into `api/<file>` beside the manifest, a row says so), `<generatorName>` → `generator`, the `<modelPackage>` root (else the api or invoker package) → `package` with `<apiPackage>` / `<modelPackage>` / `<invokerPackage>` the root does not derive → `api-package` / `model-package` / `invoker-package`, `<configOptions>` / `<additionalProperties>` → `options`, the plugin version → `version`; `<packageName>`, a second `generate` execution and any other option → row | approximate |
| `<build><extensions>` entries and lifecycle plugins whose effect is on how Maven runs — build-reporter-maven-extension, gitflow-incremental-builder, the wagon deploy transports (ssh, ssh-external, WebDAV, ftp), maven-build-cache-extension, takari-smart-builder, jgitver-maven-plugin | 4 | Tier-2 row naming the coordinate, what it does under Maven and what jk has in its place (`jk publish`, the action cache, its own scheduler, `version` in jk.toml); nothing is written | exact |
| `<build><extensions>` entries and lifecycle plugins that are a packaging jk does not build — archetype-packaging, tycho-maven-plugin, maven-bundle-plugin, nar-maven-plugin | 5 | Tier-3 row naming the coordinate and the packaging; keep that step under `jk mvn` | none |
| any other `<build><extensions>` entry | — | Tier-3 row naming the coordinate | none |

In a workspace, a row a parent's `<build>` puts on every module — an extension, a plugin with
no mapping — is said once, at the declaring POM (`the root pom.xml`, `` `build-parent/pom.xml` ``,
or the published parent) with the count of modules inheriting it; a module's own declaration
keeps its row at the module. Any other module row whose text is the same in several modules — a
dependency every module inherits from the workspace parent, a sibling BOM many modules import,
a Surefire setting a parent puts on each of them — is one row at the first module saying it,
with the count and the first few modules: `[core] … (12 modules: core, util, api, …)`.

**Grades.** *exact*: the imported build does what the plugin did. *approximate*: the common
configuration maps; unusual configuration lands in the report. *manual*: the report names the
plugin and where its job belongs. *none*: the module needs `jk mvn`.

### Where Maven profiles land

Maven uses one `<profile>` for five different jobs; jk keeps them apart, so import maps by
payload and activation rather than one-to-one:

| Maven profile shape | Lands in jk as | Why |
|---|---|---|
| Active on this machine (`activeByDefault`, JDK range, OS, property or file that matches) | Folded into the effective model; the report says which profile was applied | That is what Maven itself would build here |
| Not active; `<dependencies>` (versions from the profile's own `<dependencyManagement>` when it has one) | `[features.<id>]` with those deps marked `optional = true`; not in `default` | A feature is exactly "what optional deps you have" |
| Not active; `maven.compiler.*` or `<compilerArgs>`, an `argLine` property | `[profiles.<id>]` `javac` (`--release N`, the args) / `jvm-args` | A jk profile is "how you compile" |
| Not active; `<repositories>` | Merged into the top-level repositories with a Tier-2 row | A repository is never conditional in jk |
| Not active; `<build><plugins>` | Hand-port checklist row naming the plugins | Plugin mapping is its own table; a profile does not change where a plugin lands |
| Not active; `<build>` executions for a plugin the POM declares bare (`native-maven-plugin`, shade, assembly, Jib, Docker) | The plugin writes nothing — no `[native]`, no fat jar, no `[image]` table — and a Tier-2 row names the profile | Under Maven that plugin runs only with `-P`, so a default build does not build the image or the fat jar |
| JDK- or OS-activated with per-platform deps (native classifiers, `os-maven-plugin`) | Tier-2 row proposing a `[variants]` dimension | Which product you build, not what you compile with |
| `<properties>` that only other POM fields read | Interpolated away; nothing written | The effective model already substituted them |

Activation kinds that never map: a property set on the Maven command line (`-Pfoo` /
`-Dfoo=true`) has no jk equivalent, so the import names the profile and its payload's landing
place; `<file>` existence stays a checklist row. A profile with more than one payload kind gets
one row per kind, each naming the same `<id>`; a `<dependencyManagement>` with no dependency of
its own is a row and nothing is written.

**Gradle import** reads the build through Gradle itself. A directory with a
`settings.gradle(.kts)` — name the settings file, a build script beside it, or let auto-detection
find either — is evaluated by the Gradle the wrapper pins (else jk's default), provisioned the
way `jk gradle` provisions it: a healthy install on this machine, or a download verified against
the wrapper's `distributionSha256Sum` or Gradle's published `.sha256`. Gradle runs in a fork, on
the engine's JDK when the distribution accepts it and otherwise on the newest installed JDK it
does (a wrapper pinned to 8.3 runs on JDK 20 or older; none installed is a refusal naming the
range), and writes the evaluated project model: every project, the plugins it applies, the
dependencies each configuration declares, its toolchain, source roots, repositories and
script-registered tasks. Version catalogs, `subprojects { }` / `allprojects { }`, `ext` and
`gradle.properties` placeholders, `buildSrc` and convention plugins therefore all import as what
they evaluate to. The fork's output is the import's progress, and a fork whose output stands
still for the resolve stall window (`JK_RESOLVE_TIMEOUT_MS`, 120 s) is stopped and refused with
the last line it printed, so an import never sits silent.

The evaluated build imports as a workspace: the root `jk.toml` names every compiled project under
`[workspace] modules` (root-relative paths, a project that applies no JVM plugin and declares
nothing is not a module, a `java-platform` project is a row — `jk export bom` writes a BOM), each
member's manifest carries its own coordinates, `project(":sibling")` is a workspace edge on the
sibling (two projects sharing a name are each named by their path, with a row), and every
repository other than Central is hoisted onto the root. Configurations land in the table that
means the same thing: `implementation` and `api` in `[dependencies]`, `compileOnly` and
`compileOnlyApi` in `[provided-dependencies]`, `runtimeOnly` in `[runtime-dependencies]`,
`testImplementation` and `testRuntimeOnly` in `[test-dependencies]` — `testCompileOnly` too, with
a row saying jk has no test-provided table — `annotationProcessor`, `kapt` and `ksp` in
`[processor-dependencies]`, their test forms in `[test-processor-dependencies]`,
`platform(…)` / `enforcedPlatform(…)` in `[platform-dependencies]`, `constraints { }` in
`[managed-dependencies]`, `developmentOnly` in `[dev-dependencies]`. A configuration jk has no
table for (`intTestImplementation`, a japicmp `baseline`) is a row naming it and its
dependencies; the tool configurations Gradle's own plugins declare (`kotlinCompilerClasspath`,
`dokkaPlugin`, …) are not. `exclude` rules ride the edge (`isTransitive = false` is `*:*`); a
classifier is kept; a dynamic version (`1.+`, `latest.release`) is written as `latest` with a row.
A module under the Spring dependency-management plugin without the Boot plugin takes Boot's BOM as
its `[platform-dependencies]` entry; with the Boot plugin the `[spring-boot]` table brings it. A
task the build script registers (`tasks.register("release")`, an ad-hoc `Copy`) is a Tier-2 row
naming the task, never an error; a source set other than `main` and `test`, or a source root
outside jk's layout, is a row; a plugin nothing maps is a row naming its id or class.

When Gradle cannot evaluate the build — no network for a download, no JDK in the distribution's
range, a plugin the build cannot resolve — the import falls back to scanning the root build script
alone and says so in a Tier-3 row; a `settings.gradle` with no build script beside it is then
refused with Gradle's reason. Importing one project's `build.gradle` from inside a larger build
scans that file alone and points at the root's settings file. The scanner reads the declarative
idioms of a single script: the `plugins { }` block (a Kotlin plugin version is `project.kotlin`;
the ids an installed jk plugin claims map to its table), `group`/`version`/`description`,
`java { }` toolchain lines, `application { mainClass }`, `jar { manifest { attributes } }`,
`repositories { }`, and the `dependencies { }` block including on-disk
`gradle/libs.versions.toml` accessors (`libs.guava`, `libs.bundles.testing`; unresolved refs are
rows). The project is named from `rootProject.name` in the settings file beside the build file,
else from the directory. A declaration's closure is read for its `exclude` rules and otherwise
skipped, so an `api("g:a") { because "…" }` imports; a Groovy comma list of coordinates imports
each one. A version spelled through a property — `$junitVersion`, `${mapstructVersion}` — is
written as the value the property has in `gradle.properties`, an `ext { }` block, a Kotlin `extra`
entry or a script-level `val` / `def`, and `${libs.versions.x.get()}` reads the catalog; the same
holds for `id("…") version someVal` in the plugins block. A property nothing defines, or
refreshVersions' `_`, is a row naming it and the dependency is written without a version — a `$`
never reaches the manifest. Versions stay on deps and BOMs — they are not written into jk library
catalog layers. Keep `jk gradle` for modules that still need full Gradle.

**Export** writes what the manifest says: `jk export maven` writes each dependency's `exclude`
list as `<exclusions>`, a `[managed-dependencies]` entry's included on its
`<dependencyManagement>` row, `[processor-dependencies]` as the compiler plugin's
`<annotationProcessorPaths>` and `[test-processor-dependencies]` as a `testCompile` execution whose
paths are the shared table plus its own. `jk export gradle` writes an `exclude(group = …, module =
…)` block per exclusion (`*:*` as `isTransitive = false`) and `testAnnotationProcessor` for the
test processors; a Gradle constraint carries no exclusions, so a managed entry's are a report row.

Single-file scripts: `jk tool run script.java` / `jkx` — [Tools](tools.md).

MCP: `jk_import` auto-detects the build file; `jk_export` writes maven/gradle/bom; `jk_ide`
writes the IDE project files.

## Related

[Projects](projects.md) · [Dependencies](dependencies.md) · [IDE](ide.md) · [Aliases](aliases.md)
