# Per-class test selection

After a change, JumpKick should run only the test classes the change can affect and replay the rest.
Today the unit that re-runs is a module's whole selection: a run-tests stamp per module and
selection, and on a miss every class of every selected suite runs. That is exact at module grain
(no under-run was ever found), but on JumpKick's own tree any plugin or engine change re-runs jk-cli's
466 classes, and a suite with one repository-scanning test re-runs whole on any edit, because the
files a suite reads are pooled per test JVM and key the whole suite.

This page is the design: the unit, its key, how the static and observed cones check each other,
the holes of class-level analysis and how each is closed, what still forces a whole-suite run,
how results aggregate, the overhead budget, and the order the pieces ship in.

## The unit: a top-level test class

A test class, with every class nested in it, is the unit of memoization. Its key says whether
its last outcome still stands; a class whose key matches a green record is replayed, any other
class runs.

Why not the method:
- JUnit binds a class's methods together: `@BeforeAll`, static fields, `@TestInstance(PER_CLASS)`,
  `@TestMethodOrder`, and an extension's per-class store. A method's outcome depends on the
  methods before it, so a method alone is not a unit whose outcome can be replayed.
- Most of a class's wall is per-class setup (a Spring context, an engine, a container). Running
  one method of five saves little.
- The pieces that exist work by class: the class ledger, the launcher's left-out list, pull
  workers that are handed classes, and the JVM's class loading.

Why not the suite: a per-suite stamp (a unit edit no longer re-runs integration) is a step on the
way and composes with this. It does not help the suite whose own classes are many and mostly
unrelated to the change, which is the cost measured.

Identity is language-neutral: a test is a **container path plus a case**. For Java the container
is `["cc.jumpkick.resolver", "ExclusionLedgerTest"]` (a nested class is one more element) and the
case is the method or invocation. JUnit's unique id rides as an opaque native id. Records keep
JVM-only data (class cones, system properties, class-load counts) in a `jvm` section.

## The key

A class's key, per launch (a suite with settings of its own runs in a launch of its own, as the
class ledger already keys it), is a hash of three parts.

**The suite part**, what every class of the launch sees, taken from the suite stamp's tokens:
- JumpKick's cache-key version, the test JDK (`test-jdk:`), the test runner, JVM flags (the module's and
  the request's), `-ea`, the suite settings of this launch, the suite definitions, the excluded
  classes, network allowed or not, `[test] tools` identities;
- the declared environment values (`[env]`, `[test] env`, profile env) and the shell's locale;
- what the suite read of the caller's environment (`env-read:`, `env-names=`, `env-all=`): a row
  names at most 8 variables, and a program a class starts takes the whole environment unseen, so
  no class's record says all it read, and a changed value re-runs every class of the suite;
- every **external** runtime classpath entry by content: every entry that is not a workspace
  module's output (dependency jars, the JUnit platform and engines, language runtimes);
- the suite resource roots by tree content;
- the selection (`sel:`, `suites-run:`): which suites and tags run decides which of a class's
  methods run, so a class that passed in the fast tier says nothing of the methods a wider run
  selects, and a record from one selection never answers another.

Left out of the suite part, because a finer part replaces them: the test sources and the module's
main classes (the class part), sibling jars (the class part),
the discovered reads (the observed part), the test compiles' action keys (their output is the
class bytes), the lock file (the external classpath is what it decides), and the worker jars the
stamp keys (`worker:`, the observed part, [below](#nested-engines-and-worker-jars)).

**The class part**, its static cone: the class's own bytes and those of every class nested in it,
then every **workspace** class reachable from them, transitively, each by its name and the digest
of its bytes. Workspace classes are those in the module's test and main class trees, its fixtures,
and every sibling module's output on the runtime classpath (read from the sibling's jar). A class
reaches another when its class file names it anywhere: a constant-pool class entry, a field or
method descriptor, a signature, an annotation, or a string constant that spells a workspace class's
binary name in a class file that calls a method loading a class by name (`forName`, `loadClass`,
`findClass`, `tryToLoadClass`), so `Class.forName("cc.acme.Impl")` with a literal is followed. In
any other class file such a string is data, a process argument or a manifest value, and reaches
nothing. A class loaded by name through a workspace helper is caught by the class's record, which
joins what it loaded outside its static cone. The walk stops at the workspace edge; what lies past
it is in the suite part.

Dependency jars are keyed by jar, all of them, in the suite part, not by which classes reach which
jar. Rejected: keying a class only on the jars its cone names. A library reaches another through
`ServiceLoader` (an SLF4J binding, a JDBC driver, a Jackson module), which no reference shows, so a
logging backend upgrade would replay a class whose output it changes. Dependency bumps are rare next
to edits, and the edits are the cost.

Bytes are hashed whole, debug attributes included. A comment line moves line numbers and re-runs
the classes whose cone holds the class; stripping `LineNumberTable` would replay a test that
asserts a stack trace. Whether to strip is measured in shadow mode before it is decided.

**Under `cache = "cone"`, a class keys by its bytes only where its code ran.** jk-engine's cones are
dense: a median cone holds 4,702 of its classes, and a body-only edit of one engine class moved 718
of 941 cones. A class whose code did not run in a test class's window cannot have decided its
outcome through a method body, so it keys by its bytes less every method body but its static
initialiser's (`ClassCones.bodyless`, over a constant pool built anew, so a constant only a body
used leaves no trace): its supertypes, fields, signatures, annotations and the state its static
initialiser leaves, which a caller sees without running the class. Which classes ran comes from
the probe, not the loads: a class loads once per JVM, in whichever window first needs it, and in
jk-engine `SelfHostEngine` loads within the first twelve classes of each of its four test JVMs, so a
sound rule over loads (charge each load to every later class of the JVM) would have spared some 15
classes. The env probe marks every class loaded from a directory or from a jar inside the workspace,
a sibling module's (`EntryMarks`: a call to `ClassUses.used` at the start of every method body; the
engine names the workspace in the agent argument), and the first call since a window opened
records a `jk.ClassUse` event, which `ClassReads` joins to the windows that hold it like a file read;
a use outside every window counts for every class its JVM ran, not another JVM's nor another
launch's. The record row keeps which of the cone's
classes ran as a bitmask over the cone in name order (`coneUsed`), so the next run takes the key
over the same classes. Every class of the cone keys by its bytes for a test class whose own use is
missing (a JVM the probe did not mark, a JDK without the flight recorder), that started the test
JDK's own `java` on its class path, whose classes run unmarked in the second JVM
(`Observation.ranUnmarked`), whose suite named more than `USED_NAMES_KEPT` classes, or whose class
the probe could not mark (that class is named in every window instead). A coverage run is not
marked: JaCoCo must see the classes as compiled. The holes: a body that ran in an earlier window and
left state a later class reads without running that class's code (a static field it changed after
initialisation, an object it cached that the later class reads by reflection) is not in the later
class's key; nor is a workspace class whose code ran from a copy outside the workspace that the run
deleted (a sibling's jar a test copied into its temp directory and loaded), which is no code file
the key holds and was never marked.

**A class's uses, reads and read roots depend on the classes before it in its JVM.** Code that runs
once per JVM (plugin manifests parsed into a static cache, the root `jk.toml` read on first ask) runs
and reads in the window of whichever class first needs it: that class's `coneUsed`, `reads` and
`readRoots` hold it, and a later class that reads what it filled holds none of it. Classes reach
their JVMs in no fixed order, so a class's record can differ from run to run with no code changed,
and a body-only edit of code that only fills such a cache runs the class that filled it and replays
a later class that read the cache (the shared-state hole below). A later class that calls into the
class that filled the cache marks it in its own window, so the hole is a cache that some other class
derived from it.

Keying each class over the uses of every window that started before its own in its JVM would close
the hole, and was priced in shadow and turned down. On a 24-CPU host (30 GiB, shared, load
average near 50), the shadow keyed jk-engine's 958 fast-tier classes both ways in forced runs
(`jk test -m jk-engine -X -r`), each after the one before had left its records:

| Change since the run before | Classes that would run | Carried |
|-----------------------------|------------------------|---------|
| None | 0 | 0 |
| Body of `PluginTableRegistry.loadBuiltIns`, the plugin manifests a JVM parses once | 793 | 872 |
| Body of `SelfHostEngine.selfHosted` | 60 | 390 |

Carrying adds 79 classes to the edit of the cache-filler, none of which ran its code in its own
window, and 330 to the ordinary edit, six and a half times as many, since an early window's uses
spread to every later class of its JVM. Selection keys each class over its own window's uses.

Sibling jars are marked because a module's cones cross into them: the classes of jk-cli-e2e and
jk-engine-e2e reach jk-engine through its jar, and an unmarked jar keyed every class of it by its
bytes, whether or not it ran.

**The observed part**, from the class's last run (its record):
- each file it read in its window (see attribution) that no other part keys, by content, with the
  listing of the directory it sits in when that directory is in the workspace tree, as the suite's
  discovered inputs are keyed today;
- each code file it loaded by path outside everything the suite's key holds (a jar a test opens
  through its own class loader, a worker jar the suite was handed), by content;
- the programs it started, by identity: a tool by the identity the suite's stamp gives it, and a
  class that started a program running none of its JVM's classes (`git` in the checkout, `node`,
  `setsid` around a worker JVM: `ClassReads.EXTERNAL_PROGRAM`, `(external)` among its tools) or
  keyed by its module by every program the stamp keys (`proc:`), so an upgrade of `node` re-runs a
  class that started it; such a program's command keys the workspace files it names by content;
- every worker jar the stamp keys, by content, for a class marked as reaching a started program
  and a class keyed by its module ([below](#nested-engines-and-worker-jars)).

A class's first run has no record: it runs. The run records what it read, and the record is stored
under the key that folds those reads in, once they are proved to have held still while it ran
(the rule the suite marker follows today).

### Nested engines and worker jars

A suite that drives a nested engine (jk-cli-e2e, jk-engine-e2e) is handed the engine's fat jar, the
native client and every first-party plugin jar by path (`-Djk.engine.jar=…`); a declared `[build]
test-plugin-jars` entry is handed the same way. The suite's stamp keys each by content (`worker:`),
a plugin jar with every library it launches on (`WorkerClosure`), so any engine edit misses the suite marker, but the class key leaves them to the observed part: a
body-only edit of an engine class moves the fat jar's bytes, and in the suite part it moved the key
of every class of those suites, whatever its cone held. On a shared Linux host, after a green default suite,
a body-only edit of `SelfHostEngine` ran all 11 fast-tier classes of jk-cli-e2e (9 by a moved key, 2
by the module) and all 3 of jk-engine-e2e (by a moved key), most of which start nothing and never
open the jar.

A worker jar keys a class when:

- the class read it: in its window, or outside every window on a thread whose first read its
  window held (a thread it started). The tests hash a worker jar, copy it into the sandbox home
  and hand it to the programs they start; no loader of the test JVM keeps one open for a later
  class, so a later class does not stand on it the way it stands on any other code file. A read
  outside every window on a thread no window owns (the runner's own) keys every class whose window
  had not closed by then, and the pool when it came after every window;
- the class started a program in its window, or a program was started earlier in its JVM and the
  class's window reached a local peer (`ClassReads.STARTED_PROGRAM`, `(started)` among its tools):
  a loopback or Unix domain socket other than the run's own environment broker, or a pipe (a read
  with no path). Those are the ways a class talks to a program it did not start, and what such a
  program runs (the test JDK's own launcher, a program the test wrote, a utility, a shared engine)
  is read in a process no recording holds. `git` in a repository a test made is no such program;
- the class is keyed by its module, as it stands on every program the stamp keys.

So a class of such a suite replays after an engine edit outside its cone when it read no worker jar,
started no program and talked to no local peer, whatever the classes before it in its JVM did. A
program a class did not start can still reach it through a file the program wrote that the run
removed before it ended; no recording holds that, and the soundness guard is what catches it. jk-cli-e2e's engine extension materializes the
engine only where an engine may be started, never in a fast-tier JVM, and its fast classes start
nothing: the ones that start Node, `git` outside their temp roots or an engine are `integration`,
and the ones that need no engine at all are clients/cli's
([tiers](test-suite-tiers.md)).

**What the nested engine could record (not built).** The engine is another JVM, so no recording
says which engine classes a test class ran. The environment probe in the engine the CLI spawns
(`EngineSpawn.jarCommand`), marking the classes of its fat jar, would: its `jk.ClassUse` events
joined to the spawning test JVM's class windows by wall time. Measured on a Linux host over 52
jk-cli-e2e integration classes on one test JVM:

- A class with isolated roots gets an engine of its own, and its attribution is exact. The tier's
  engine, and each restart of it, served 22 to 27 classes, so most classes share one.
- The engine sees no window, so the probe names each class once per engine. Charging a class with
  every use of its engine up to the end of its window covers what the engine kept in memory for
  later classes. Under that rule a body edit of an engine class the sample used re-runs 74 to 78
  percent of the 52 (41 to 43 percent averaged over all 2,477 classes of the jar, about 300 of
  which every engine runs at start); an edit of `SelfHostEngine` re-runs 32 to 38.
- Per window (the engine naming classes again every 50 ms, a use charged to every window its slot
  overlaps) the share is 31 percent, and `SelfHostEngine` re-runs 11. That rule misses state the
  engine filled for an earlier class; an engine per class closes the hole, at a cold start each.
- Marking adds about 47 µs to defining a class (230 ms for the jar's 4,946); a run's engines wrote
  14 to 16 MB of recording, 37 MB with the 50 ms re-naming. Engines under test start without the
  startup cache, so the agent costs none there.

Two seams stand in the way. The spawned engine's environment drops `JAVA_TOOL_OPTIONS`
(`EngineEnvironment`), so the probe would ride a forwarded `jk.*` property
(`EngineSpawn.forwardedJvmArgs`). And `ClassReads.attribute` joins events only to windows in the same
recording, so a nested recording needs the windows of the test JVM that spawned it.

## Static cone and observed cone

The static cone is primary. It needs no run, it is the same in every JVM, and it does not depend on
which class happened to load a shared class first. The observed cone, what a class actually loaded
and read while it ran, does two things: it adds the inputs no static analysis can see (data files,
jars loaded by path), and it checks the static cone.

The check: a workspace class loaded from a class directory shows in JFR as a `jdk.FileRead` of its
`.class` file, at the moment it loads. A workspace class loaded in a test class's window that is
not in that class's static cone proves the cone incomplete (reflection, a framework scan, a service
file). The loaded class joins the cone with everything it reaches (`ClassKeys.reflective`,
`ClassCones.Index.cone(name, also)`), and the record keeps its name (`reflective`), so the next run
grows the cone the same way: an edit to the loaded class, or to what it reaches, runs the class.
Past `ClassKeys.REFLECTIVE_KEPT` (8) such classes the class is marked **unattributable** and falls
back to the module key: its key is the suite stamp, so it runs whenever anything in the module
changes. The fallback is per class: the rest of the suite keeps its finer keys.

The check sees a class only the first time it loads in a JVM, and only from a directory (a sibling's
classes come from its jar, which reads as the jar). So it catches many incomplete cones, not all;
what it misses is listed below with what closes it.

## Holes of class-level analysis, and what closes each

Selection may over-run. It must never under-run: a class that a full run would fail, or whose
result would differ, must run. Each known hole:

| Hole | What closes it |
|---|---|
| **Reflection** (`Class.forName`, method handles by name, a class name built at run time) | A literal binary name is a reference (above). A computed name loads a class the static cone lacks: the observed check joins the loaded class's cone to the class's when it came from a class directory. A computed name into a sibling jar is not seen by the check: closed by the soundness guard (below) |
| **`ServiceLoader`, Spring component scanning, CDI, Quarkus, Micronaut, classpath scanning** (ArchUnit, ClassGraph, Reflections) | A class whose cone names a scanning entry point falls back to the module key: `java/util/ServiceLoader`, a `META-INF/services` provider named by a workspace service file, `@SpringBootTest`, `@ContextConfiguration`, `@ComponentScan`, `@QuarkusTest`, `@MicronautTest`, ArchUnit's `ClassFileImporter`, ClassGraph, Reflections. The list is data, extended as frameworks are met; the observed check backs it |
| **Annotation processors and generated code** | Generated classes are classes in the main or test tree; the cone sees them as it sees any class. A processor's own change reaches the class bytes through the compile |
| **Inlined constants** (a `static final` primitive or string copied into the caller's bytes) | The caller's bytes hold the value, so a change of value is a change of the caller's bytes, provided the compile recompiles every class that inlined it, which JumpKick's incremental compile does. The constant's class may be absent from the caller's constant pool; it is not needed, since the value is in the caller |
| **Resources and classpath scanning of resources** | Suite resource roots are keyed whole in the suite part. A resource read from a sibling's jar is in the sibling's jar, keyed through the cone's classes of that sibling only if a cone class lives there: closed by keying every workspace jar a class read (observed) and, for the first-load hole, by the guard |
| **State shared between test classes in one JVM** (a static cache filled from a file by one class and read by another; a singleton; a system property one class sets) | Code loads: a code file read in a JVM keys every class that ran in that JVM from then on, so a class loader cached across classes cannot hide a jar it loaded. A worker jar is read, not loaded, and keys only the classes that read it (below). Data: a read keys only the class in whose window it happened, and reads outside every window are pooled to every class of that JVM. A class that reads a cache another class filled is the residual hole; it is closed by the soundness guard, by the order-dependence report (below), and by `[test] cache = "module"` for a module that relies on such state |
| **Order dependence** (a class that passes only after, or only without, another) | A subset run puts different classes in each JVM. A class red in a subset run and green in the last full run under the same class key is reported as order-dependent, by name |
| **Parameterized and dynamic tests** (`@ParameterizedTest`, `@TestFactory`, data from files) | The unit is the class: every invocation runs or none does. Data files read while generating cases are read in the class's window and keyed |
| **Test fixtures** (`src/fixtures`, a sibling's fixtures) | They are workspace classes on the runtime classpath; the cone walks them |
| **Tests that scan the repository** | Their reads land in their own window and key only them. A new file in a directory one of them read changes that directory's listing, keyed. A directory listed where nothing was read is unseen, as today |
| **Programs started** (a test that execs a built jar, `git`, a nested `jk`) | What a child process reads is not recorded. A class that started a program that could run its JVM's workspace classes, or ran in a suite that started one outside every class's window, is unattributable and falls back to the module key: a command that names one of the test JVM's class trees or workspace jars, expands a variable or a command, hands over an argument file (`@args`), or runs a program the test wrote (`ClassPathCommands`). Any other program (`git` in the checkout, `node`, `setsid` around a worker JVM on a plugin jar) keeps the class's cone and keys it by every program the stamp keys and the workspace files its command names; a command that can read nothing its key does not hold (`git` in a repository the test made, a utility or a program the test wrote naming no file outside its temp roots, below) keys it by that program's identity alone. The test JDK's own launcher marks the class as having started a program, and on the test JVM's class path keys every class of its cone by its bytes (above), not only the classes its window ran. A checkout file a JumpKick test names through `RepoRoot.file`, a script it hands to `bash` say, is read once in the test JVM, so the suite's reads key it |
| **Nested engines and worker jars** (jk-cli and jk-engine tests drive a real engine that loads plugin jars by path) | A worker jar keys a class that read it, in its window or on a thread it started; a class that started a program, or talked to a local peer after one was started in its JVM (below); and a class keyed by its module. A class that did none of these never touched the jar's bytes. A cache an in-process engine filled from a worker jar for a later class is the shared-state hole above ([below](#nested-engines-and-worker-jars)) |
| **Line numbers and source file names** (a test asserting a stack trace) | Bytes are hashed whole, debug attributes included |
| **The network** | A suite that reached past the host stores no marker. A class that reached it never replays, and nor does any class of a suite whose pool reached it |
| **A recording that did not finish** (a JVM killed, out of memory) | Every class of that JVM is unattributable for that run; no class record is stored for them |
| **The clock, randomness, a value no input carries** | As today: a class whose result depends on no input is replayed with the result it had. Flakiness is reported (`test-passed-on-rerun`), not keyed |

**The soundness guard.** A full run (`-r`, and a scheduled full run on JumpKick's own tree) compares, for
every class whose key matched a green record, the full run's result with the replayed one. A class
replayed green that fails in the full run under the same key is an unsound skip: the run warns
`per-class-unsound` naming the class, and per-class selection is off for that module until its next
full green run. In shadow mode the same comparison runs on every real run.

## What forces a whole-suite run

- The suite part moved: a JVM flag, the test JDK, the JUnit platform or an engine (external
  classpath), the environment, the suite's definition or settings, JumpKick itself.
- No class records for the launch (a first run, a cleared cache, a selection never run).
- A run red beyond its classes (a worker that died mid-class, tests discovery announced and nobody
  reported): the class records of the launch are cleared, as the class ledger is today.
- `-r`/`--redo`, `--coverage`, `--debug-jvm`, `--affected`, `[test] cache = "module"`.
- A suite whose pool reached the network (outside every class's window).

A red class runs again whatever its key (it has no green record), as under the class ledger today.
A class that falls back to the module key runs whenever the suite stamp moves; that is per class,
not the whole suite.

## Attributing reads to a test class

The listener in the test JVM emits one custom JFR event per top-level test class, `jk.TestClass`
(container path, native id), begun when the class container starts and committed when it finishes,
in the recording the engine already starts. After the JVM exits, each event of the recording
(`jdk.FileRead`, `jdk.ProcessStart`, `jdk.NativeLibraryLoad`, socket events) is joined to the
classes whose window contains its start time, in that JVM:

- **Any thread**: a read made by a background thread while a class runs is that class's. When
  windows overlap (Jupiter's in-process parallelism) the read goes to every overlapping class:
  over-attribution, never under.
- **Outside every window** (static initialisers run during discovery, before the first class, a
  shutdown hook, a background thread between classes): pooled to every class the JVM ran. A
  program started there (other than the test JDK's own launcher, or a tool keyed by its identity)
  keys every class of the suite by its module (`pooled process …`): what it reads is in no
  recording, and no record says which class it served. A tool started there (the header's pooled
  `tools`) keys every class by the identity the suite's stamp gives it, so an upgrade of it re-runs
  them all. JumpKick's own suites build their template catalog before the first class
  (`LocalTemplateCatalog`) in the sandbox slot's temp root for that reason: there its git is git in
  a repository a test made, a tool, where beside the home it was a program that keyed every class of
  jk-engine, jk-cli and jk-cli-e2e by its module.
- **After a class finished**: never that class's. Its outcome was decided by then.
- **A read from another host on a thread that reached one in a class's window**: that class's,
  wherever the read falls. A connection a class leaves open is closed by the server minutes later,
  and its closing reads would otherwise land in an unrelated class's window or, between windows, in
  the pool, where they run every class of the launch. A class that sends a request on such a
  connection writes in its own window, and a write always goes by its time.
- **Code files** (`.jar`, `.zip`, `.jmod`, a `.class` outside the workspace class trees): the class
  in whose window it was read and every class that ran later in that JVM. A worker jar the suite
  was handed is not one of them: it keys the classes whose window read it, and outside every window
  the class whose window held the reading thread's first read.
- **A program started**: the class in whose window it started, and each later class of the JVM
  whose window reached a local peer ([above](#nested-engines-and-worker-jars)).

Pull workers run one class at a time per JVM, so a window is a class's alone in every run JumpKick starts
unless the suite turns Jupiter parallelism on.

The environment probe attributes a lookup to the class named by the JVM-global `jk.test.class`
property, which the listener sets when a class starts. The system-property probe does the same.

## The record

One **class record** per class per launch, kept beside the suite's discovered inputs in JumpKick's cache
for the project, newest run winning:

```json
{"launch":"","container":["cc.acme","OrderTest"],"outcome":"passed","tests":12,"failed":0,"skipped":1,
 "ranAt":1763250000000,"millis":840,"attributed":true,"classKey":"…","wouldRun":"key",
 "reads":["src/test/data/orders.json"],"code":[],"processes":[],"nativeCode":[],
 "osPaths":["/proc/self/status"],"network":[],"env":["LANG"],
 "jvm":{"props":["user.home"],"loaded":38,"cone":"<digest> 41"}}
```

**Every row is bounded, and nothing is held whole.** A class keeps at most 8 data files read, 4 code
files, 2 of each kind of evidence, 8 variable and 8 property names, and the count of workspace classes
it loaded, never their names. A class that read more data files says `readsMore` and keeps up to 8
read roots (`readRoots`: `<dir>/*` for the files directly in a directory, `<dir>/**` for everything
under it; past 8, the two with the deepest common directory become that directory), which cover
every file it read, and up to 8 file names (`readNames`), the names of every file it read. It is
keyed on the files its suite was found to read under those roots that carry one of those names: the
suite's discovered inputs, which hold that set exactly and bounded, so per-class reads use the
suite's own representation rather than a second one. Only a run of every selected class replaces
that list; a run that replayed classes adds what it read to it (`SuiteDiscovery.Run.partial`), since
the classes it replayed read nothing in it, and the programs they started stay keyed. A class whose
reads share no directory keeps no roots, and one that read more than 8 names keeps none; with
neither it is keyed on every file its suite read. A repository-scanning class that reads 50,000
files therefore costs a row like any other and re-runs whenever any input under its roots moves,
which is what it reads anyway, while a class that read every module's `jk.toml` does not re-run
because a neighbour of its suite scanned the engine's sources. A class that read more code
files says `codeMore` and stands on every code file its suite's JVMs read: the suite's line lists
them (`suiteCode`, up to 128, each with its content and since when it has held it), and the class
is replayed only when every one of them held since before the class finished. Whether a class's
loads lay in its cone is decided when it runs and kept as its `fallback`. The suite's line holds
counts, the bounded pool (up to 400 data files) and the suite's code files, never a class's
content. It names the launches it stands for (`launches`): a launch runs its own test JVMs, so what
no window held in them is no input of another launch's classes. A run that starts several
launches at once writes a line for each, its pool what that launch's JVMs did outside every window
(a JVM's launch is the one launch all its windows' classes ran in, so a class with one integration
method among unit tests leaves its unit JVM the default launch's). A class two launches run has a
row for each, and each row holds only what that launch's windows of it did: the integration
method's engine is no input of its unit row. When one JVM's launch cannot be told, its launches
share one line on every JVM's pool, and each class's rows hold every window of it. A run writes its lines first and keeps, after them,
each earlier line for the launches it did not start, so a `jk test -g` whose integration JVMs
started a program or reached the network outside every class leaves the default suite's classes on
their own launch's pool, whether the integration suite ran beside them or in a later run
(`TestClassRecords.headers`). The code-file
list is the newest line's, merged across runs. The record file and the shadow log are written and read a row
at a time; a row past 256 KiB (none this writes) is skipped as unreadable.

**The memory bound.** One suite records at a time across the engine. What it holds outside the
recording reader's permit is at most `ClassReads.PEAK_BYTES`, about 25 MiB: some 9.5 KiB per class
(its kept paths, read roots, names and the bits of the classes whose code ran) for up to 2,048
classes (a class past that keeps nothing and is keyed by its module), 65,536 class loads and 16,384
used class names across the suite, and one JVM's join (8,192 written files, 2,048 code files, 2,048
existence answers). That is `C × 9.5 KiB + 6.5 MiB` for `C` classes: under 16 MiB for jk-engine's
thousand. Memory
admission keeps it free beside the reader's own peak. The index of workspace classes the cones walk
(at most 40,000 classes at 512 bytes, 20 MiB) stands only while the suite holds the reader's permit,
so it never stands beside a recording being read; past 40,000 every class is keyed by the suite's
stamp.

Paths in the workspace are relative to it. `osPaths` and `nativeCode` are **platform evidence**:
the sharing policy reads them to decide that a result is platform-bound. They are never key
material. OS-specific paths are `/proc`, `/sys`, `/dev`, `/etc` on Linux, `/System`, `/Library` and
`/private/etc` on macOS, and the Windows directory on Windows.

The class ledger's entry gains when the class ran and its wall, so a replayed class can say from
when. Build history gets a `class-finish` row for **every** class of a suite the run settled:
a class that ran as today, and a class replayed (from the suite marker, from the class ledger, or by
its class key) with `replayedFrom`, the time it last ran. Rankings of slow tests and per-class walls
read only the rows of classes that ran.

## Aggregating, the settle line and the forecast

The suite's verdict is the classes': green when every class, run or replayed, is green, and then the
suite marker is stored under the suite stamp as today, so a later run whose suite stamp matches
replays the whole suite without computing a class key. The summary counts replayed classes' tests as
passed and skipped as recorded, as the class ledger's left-out classes are counted today. The settle
line says how many ran: `OK test jk-cli · 1,958 tests (1,901 replayed) · 41.2s`.

When nothing the suite reads is still to be built (its compile and every sibling it reads are
current), `jk explain` takes each class's key as the run will (`TestClassRecording.replayedNow`, through
`SuiteDiscovery.replayedByCone`) and prices only the classes whose key moved: `run tests · <the stamp
input that moved> · ~12 tests · 281 classes replayed`, and the module's `rerunTests` (the Tests
row's Rebuild column) counts those tests alone. A red or cut-short run under the same stamp is read
first (`TestClassLedger.recordedIn`): the classes it passed stand, as the run leaves them out, and the
cones decide only the classes that ledger never recorded (`Replayed.over`); a red suite's ledger
holds every class, so its cones are not walked. When a compile is pending the new bytes do not exist
yet, so the forecast reads the edit instead (`TestClassRecording.replayedAfter`): each dirty module's
API hint names the sources whose content moved (`SourceApiIndex.Hint.changed`), the classes on disk
compiled from them are the classes that move, and a class that passed replays unless one of them lies
in its cone and either moved a declaration or ran in its window (`coneUsed`); a module-keyed class
runs. A worker jar the stamp keys whose sibling, or a library it launches on, is rebuilding (any dirty sibling, on the suite's
classpaths or reached by build order alone: jk-cli-e2e reaches jk-engine as a test dependency and is
handed its assembly as `-Djk.engine.jar`) runs the classes it would key
([above](#nested-engines-and-worker-jars)): those whose record names it as a code file, those whose
record says `(started)`, and the module-keyed ones; nothing replays when the record file's pool read
it. A class whose record, or the pool beside it, read more code files than it names stands on the
code files the header lists (`SuiteCodeFiles`), as the selection decides it: it runs when one of
them is rebuilding or changed after the class finished, so jk-engine-e2e's classes, which read every
plugin jar but not the engine's, replay after an engine edit. The step says the same `~12 tests ·
281 classes replayed`. Both read only the records of the launches the selection starts
(`SuiteResources.launchNames`, the names `SuiteResources.launch` gives them): the record file keeps
every launch's rows, so after `jk test -g` it also holds the default selection's. A launch's rows
also outlive its selection: a class tagged out of the suite, or deleted, keeps the row an earlier run
wrote, and the run never starts it. So only the classes whose sources the selection holds count
(`Replayed.within`); in a long-lived home the default launch of jk-engine held about 200
module-keyed rows of classes since tagged `integration`, which the forecast priced as running after
every engine edit. A class that runs
is priced by the tests its record counts, and a class with no record by the tests its source
declares. The two never offset each other: records count every case of a parameterized or property
test, and a source's count is read from its text, a `@Test` in a text block included, so a class
that replays is priced at nothing whatever its source counts. It is an estimate where the run's answer is exact: the cones are walked over the classes
on disk, and a hint reads a private signature edit as body-only, where the class's bodyless digest
moves. An edit the forecast cannot read (a sibling with no hint, a source that is not Java, a moved
test source, a KSP round) prices the whole selection.

## Which classes run together

A subset runs through the launcher as a red re-run does today: the replayed classes are on its
left-out list, the pull workers take the rest. Fewer classes means fewer JVMs (the worker count
already follows the selected classes' walls) and other neighbours in each JVM. A class that passes
only in the full run shows as red in a subset run with its key unchanged except for neighbours;
it is reported `test-order-dependent` with the classes it last passed beside, and `jk test -r`
runs the whole suite. A class green in a subset run and red in the next full run under the same
key is the guard's unsound skip.

## Overhead budget

At most **3% of a suite's wall** for the always-on set: the class event, the system-property probe,
the native-library event, and the engine's attribution pass over the recording. Measured as paired
runs with telemetry on and off (`JK_TEST_TELEMETRY=off`), alternating, N=7 pairs under `jk test -r`,
on a jk-cli subset and on hotel-reservations, reporting the median and spread of the run-tests
step's own time, the recording's size and the attribution pass's time. Computing the cone is engine
time, memoized per class tree content, and is measured the same way in shadow mode.

**The use marks** were measured on their own on a macOS host (10 CPUs, 16 GiB): an engine built from
the tree under a private `JK_HOME`, `jk test -m <module> -X -r` with the test JVMs pinned (`-w`),
marks on (`cache = "cone"`) and off (`"class"`, which leaves the shadow and the attribution pass
running), alternating, caches warm. The host was shared (load average 10 to 30 through the runs)
and the pairs spread by about 10%, so these runs do not resolve whether the marks' wall cost is
inside the budget; that needs an idle host:

| Suite | Pairs | run-tests on / off, median | Pair deltas | Recording | Events | Attribution |
|-------|-------|----------------------------|-------------|-----------|--------|-------------|
| jk-engine, 966 classes, 6 JVMs | 4 | 92.4 s / 89.1 s | −4.5% to +11.0% | 39.2 / 33.6 MiB | 855k / 737k | 2.34 / 2.34 s |
| jk-cli, 374 classes, 2 JVMs | 5 | 40.3 s / 41.9 s | −10.7% to +3.0% | 6.0 / 4.2 MiB | 73k / 34k | 99 / 90 ms |

The marks add a sixth to jk-engine's recording and double jk-cli's events, and leave the attribution
pass's time where it was. That pass is 2.6% of jk-engine's run-tests step with or without them, and
the records and the shadow after it bring the work after the suite to 6.3 s, 7% of the step.

The attribution pass then judged each recorded path anew on every read (resolving it and walking
the layout's roots), though one test JVM reads a few thousand distinct paths a hundred thousand
times; it now keeps a verdict per path for the suite (`PathVerdicts`), and the recording reader
fills each field's slot by index and skips the attributes of metadata it does not keep. Replayed
in-process over one jk-engine fast-tier run's 24 recordings (981k events, 968 classes) on
a 24-CPU shared host (load average 3 to 5), old and new alternating, three runs each,
six timed passes a run after one warm-up: **1.53 s before, 0.59 s after** (median of 18; ranges
1.52 to 1.59 s and 0.58 to 0.69 s), the same observations for every class. The records and the
shadow were not re-measured; they need an engine built from the tree.

## Staging

1. **Records** (selection unchanged). The class event and the attribution pass, the
   system-property probe, native libraries and processes as platform evidence, OS-specific reads
   kept as evidence, class records, `class-finish` rows for run and replayed classes, the ledger's
   run time. Safe on its own: nothing it computes decides what runs.
2. **Shadow keys** (selection unchanged). Each real run computes every class's key and cone beside
   the suite stamp, records which classes per-class selection would have run, and compares that with
   what changed: every class whose outcome or test count changed since its last record must be in
   the would-run set, else the run says `per-class-unsound` (in shadow mode a debug warning, and a
   test failure in JumpKick's own suites).
3. **Selection** behind a third value of the existing setting, `[test] cache = "cone"`, set in
   JumpKick's own root manifest only (built, the root's line waiting on the bootstrap pin: see below). `"class"` (the default: a red or cut-short run re-runs only what has
   not passed under an unchanged stamp) and `"module"` stay as they are, and so does the suite
   marker; `-r` runs every class.

## What is built

**Records.** The test runner's listener opens a `jk.TestClass` window per top-level class
(`ClassWindows`); the suite's recording settings enable it with `jdk.NativeLibraryLoad` (`DiscoveryRules.recordingSettings`; JDK 22
and later: the periodic `jdk.NativeLibrary` lists every library the JVM itself maps, libc included,
and is no evidence of anything); the environment probe also repoints system-property
reads at `PropLookups`, which writes `<pid>.props` beside the variables' `<pid>.lookups`.
`ClassReads` joins a recording's events to the windows; `TestClassRecording`, called from the run's
`SuiteDiscovery.Run` after the suite and before the environment report empties the lookups, writes
`TestClassRecords` (`test-classes.ndjson` in the module's project cache directory). The class
ledger's entries carry when a class ran and its wall, and a run says each left-out class replayed
(`TestClassLedger.replaySettled`), as a suite served from its marker says each of its classes
(`replayCovered`), on `class-finish` with `replayedFrom`. `JK_TEST_TELEMETRY=off` in the caller's
shell turns all of it off, for measuring.

**Shadow.** `ClassCones` reads the constant pools of the module's class trees and every workspace
entry of its runtime classpath and walks a class's cone; `ClassKeys` takes the key's three parts;
`ClassShadow`, after each run that ran classes, asks for every class that ran whether its key over
its last record matches that record's (green) key, holds the outcome to it, appends a line to
`per-class-shadow.ndjson` beside the records (`ran`, `wouldRun`, `why` per class, `changed`,
`unsound`, timings) and leaves each class's new key in its record. `JK_TEST_SHADOW=off` turns the
shadow alone off.

What the shadow showed on real runs of jk-engine (940 classes) and what closed each, in
`ClassReads` unless said:

- A test harness's own command around every class is not a program the class started: JumpKick's
  `CheckoutUntouched` runs `git status` before and after each class and marks it with
  `-c jk.harness=true` (`GitCommand.HARNESS`), which is left out. Before the mark 916 classes fell
  back to the module key for `process git`.
- A code file gone once the run ended (a jar a test wrote in its own temp directory) is no input,
  as a data file gone is not; and JumpKick's own env-probe agent jar (`DiscoveryRules.probeJar`) is keyed,
  as the suite's reads already treat it. Code files are charged to every later class of the JVM, so
  either one put most classes past their bound of 4.
- A sibling's class tree beside its jar on the classpath (`<module>/target/classes`) is a class
  tree: a load there is a class loaded by name and checked against the cone
  (`TestClassRecording.Roots`), never a code file every later class read.
- The pool keeps up to `POOLED_READS_KEPT` (400) data files, not a class's 8: it is one list per
  suite, and past its bound every class was keyed on everything the suite read, which in jk-engine
  is every engine source, so any edit moved every key. jk-engine's pool is 180 template files.
- A string constant spelling a workspace class is followed only in a class file that loads a class
  by name, so a name that is only data widens no cone. jk-client-io's `StoreCasGc` compares a jar's
  `Main-Class` with the literal `cc.jumpkick.engine.EngineMain`, and the client spawns the engine
  with it; followed, it put the whole engine in the cone of every class reaching them: a body edit
  to one engine class reached 682 cones instead of 459.

- A class past its bound of 4 code files stands on the suite's code files (`SuiteCodeFiles`), not on
  its module. jk-engine's in-process compiles load 32 zinc and Scala jars from the local Maven
  repository, charged to every later class of that JVM: 375 to 391 classes a run fell back for them.
  The suite's line lists each file with its content and since when it has held it, merged with the
  run before, so a class replayed in a run that loaded none of them still finds its own. A file new
  to the list, or whose content moved, holds from the start of the run that saw it; past 128 files
  the list starts over from that run's own; past what the run itself can list, a class past its own
  bound is keyed by its module again. The pool's code files past its bound of 4, which keyed no
  class, now hold every class to the same list.

- `git` run in a repository a test made stands on git's identity, not on its module (`GitRuns`).
  A test JVM's git reads no configuration of whoever runs it (`GIT_CONFIG_NOSYSTEM`, a
  `GIT_CONFIG_GLOBAL` in the sandbox home, an allow-listed environment), so what is left is its
  binary and the repository it runs in. The recording's `jdk.ProcessStart` names the command and its
  directory; a command is a tool, kept in the class's `tools`, not its `processes`, when git is the
  one `PATH` finds or an administered one, its directory and every `-C` lie in the test JVMs' temp
  roots (`TestEnv.tempRoots`: the module's test temp root and `~/.jk-test-tmp`) with no repository
  above them, and no argument names a path outside them or another host. `git --version` runs
  anywhere. The key feeds the identity the suite's stamp gives each tool by name (`proc:git=<what it
  says to --version>`, `ClassKeys.programs`); a stamp that names no git yet reads `unstamped`, so the
  next run's stamp moves the key and the class runs once more. 97 classes fell back for `git` on
  a shared Linux host; after, 1 (a template-store refresh run from the workspace with `--git-dir`).

- A utility that reads only what its command names, or a program the test wrote in its temp root,
  stands on its identity too (`OwnPrograms`). The program is one of `OwnPrograms.UTILITIES` or the
  POSIX ones `ToolIdentity` keys by OS family (`sleep`, `setsid`, `timeout`, `sh`, `bash`, `ps`,
  `env`, text filters), bare or in a system program directory, or a file under a temp root, which
  keys as `(written)`: the class's cone holds the code that wrote it. Every word of the command, cut
  at the shell's quotes and operators (outside Windows both as written and with its backslashes
  dropped, as the shell may hand it on), is held: a path lies under a temp root, `/dev`, `/proc` or a
  system program directory (a program there a utility too); outside a temp root a bare word names
  no entry of the directory and holds no wildcard; a bare word that names a program on `PATH` names
  a utility (a shell runs its words, and a build tool reads its user's settings); no word expands a
  variable or a command (`"$@"` aside), starts with `~`, changes directory, names another host or
  runs git. A class keeps up to `ClassReads.TOOLS_KEPT` (8) such programs; past them it started a
  program.

- A workspace class loaded outside a class's static cone (a JUnit extension a properties file
  names, `ShortTempDirs`; a watcher loaded by name) joins its cone, up to 8 of them, rather than
  keying it by its module ([above](#static-cone-and-observed-cone)).

Still keyed by the module before the last two, on a WSL host after a body-only edit of `SelfHostEngine`
(134 of jk-engine's 941 classes, 522 before the suite's code files): a class that starts a program,
whose reads no recording holds (`git` 98, a program the test wrote in its own temp directory 9,
`setsid` 8, `sleep` 4, a shell or `env` 8, `node` 2, a second JDK's `java` 1), and a class that
loaded a workspace class its cone does not name (4). On a shared Linux host, replaying one full jk-engine run's
recordings through the attribution and the fallback: 143 module-keyed before `GitRuns` (`git` 97),
53 after (`git` 1, a workspace class loaded outside the cone 17, other programs 35).

The integration tier replayed nothing after an engine edit. On a Linux host (10 CPUs, 10 GiB), under an
engine from before `GitRuns` and the `StoreCasGc` fix above, with `cache = "cone"` set at the root,
the same body-only edit of `SelfHostEngine` ran every integration class of the three suites an
engine change gates, each after a run that left its records: jk-engine 199 of 199 (the shadow, of
the 198 it keyed: 170 by the module, 28 by a key that moved), jk-cli-e2e 135 of 135 (45 by the
module, 89 by a key that moved, 1 red before), jk-engine-e2e 9 of 9 (all by the module). The
module-keyed classes start the CLI, an engine, `git` or a build, or load code past their bound;
neither later change replays a class that starts the CLI or an engine. Under an engine with both
(main at 7709bc7c5, same box, same edit, each suite after a forced run): jk-engine replayed 47 of
197, 129 of its 1,386 class-seconds, the run-tests step 256 s against 245 s; jk-cli-e2e replayed 0
of 130. The classes that replay are the cheap ones, so selection takes little off a CI run's
integration step.

With uses marked, on a WSL host after the same body-only edit of `SelfHostEngine`: 158 of
jk-engine's 942 fast-tier classes run (849 before), 23 because a class whose code they ran moved and 135 keyed
by the module; the probe run took 1m 07s against 1m 59s. With selection off after a body-only edit
that fails `ClassKeysTest` (a condition dropped from `ClassKeys.replacedToken`), all 942 ran,
selection would have run 153, and the one class whose outcome changed was among them.

**Selection.** `[test] cache = "cone"` (`TestCache.CONE`; a member takes the workspace root's when
it says nothing). JumpKick's own root sets it, so the bootstrap pin must be a release that reads
the value ([bootstrap chain](self-host.md#the-bootstrap-chain)). Before a suite runs, `TestClassRecording.unmoved`
walks the record file a row at a time and asks `ClassShadow.whyRun`, the shadow's own question, of
each class over the cones as they are now; a class it would replay becomes a settled entry of the
class ledger (`TestClassLedger.begin`), so the launcher leaves it out, its `class-finish` row says
`replayedFrom`, its counts join the summary, and it is stored under the new stamp. The cone index
stands under the recording reader's permit, as the shadow's does. A class that reached the network,
a suite whose pool did, a class gone from the workspace and a red record never replay; nor does
anything under `--redo`, `--affected`, `"module"`, or with the shadow off. A launch whose every class is settled or, by
`TestClassIndex`, holds no test starts no JVM (`SuiteResources.runsAnything`). One that still starts
lists its selection first, the settled classes left out; when that discovery finds no class and no
test besides, the launcher starts no suite JVM after it (`JUnitLauncher.settledEverything`): the index
cannot see a test that a method's tag or a framework's own discovery leaves out, the Platform can.

