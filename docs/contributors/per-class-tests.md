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
- every **external** runtime classpath entry by content: every entry that is not a workspace
  module's output (dependency jars, the JUnit platform and engines, language runtimes);
- the suite resource roots by tree content.

Left out of the suite part, because a finer part replaces them: the test sources and the module's
main classes (the class part), sibling jars and worker jars (the class part and the observed part),
the discovered reads (the observed part), the test compiles' action keys (their output is the
class bytes), the lock file (the external classpath is what it decides), and the selection
(`sel:`, `suites-run:`): which other suites run does not change what this class computes.

**The class part**, its static cone: the class's own bytes and those of every class nested in it,
then every **workspace** class reachable from them, transitively, each by its name and the digest
of its bytes. Workspace classes are those in the module's test and main class trees, its fixtures,
and every sibling module's output on the runtime classpath (read from the sibling's jar). A class
reaches another when its class file names it anywhere: a constant-pool class entry, a field or
method descriptor, a signature, an annotation, or a string constant that spells a workspace class's
binary name (so `Class.forName("cc.acme.Impl")` with a literal is followed). The walk stops at the
workspace edge; what lies past it is in the suite part.

Dependency jars are keyed by jar, all of them, in the suite part, not by which classes reach which
jar. Rejected: keying a class only on the jars its cone names. A library reaches another through
`ServiceLoader` (an SLF4J binding, a JDBC driver, a Jackson module), which no reference shows, so a
logging backend upgrade would replay a class whose output it changes. Dependency bumps are rare next
to edits, and the edits are the cost.

Bytes are hashed whole, debug attributes included. A comment line moves line numbers and re-runs
the classes whose cone holds the class; stripping `LineNumberTable` would replay a test that
asserts a stack trace. Whether to strip is measured in shadow mode before it is decided.

**The observed part**, from the class's last run (its record):
- each file it read in its window (see attribution) that no other part keys, by content, with the
  listing of the directory it sits in when that directory is in the workspace tree, as the suite's
  discovered inputs are keyed today;
- each code file it loaded by path (a worker jar, a plugin jar, a jar a test opens through its own
  class loader), by content;
- the programs it started, by identity.

A class's first run has no record: it runs. The run records what it read, and the record is stored
under the key that folds those reads in, once they are proved to have held still while it ran
(the rule the suite marker follows today).

## Static cone and observed cone

The static cone is primary. It needs no run, it is the same in every JVM, and it does not depend on
which class happened to load a shared class first. The observed cone, what a class actually loaded
and read while it ran, does two things: it adds the inputs no static analysis can see (data files,
jars loaded by path), and it checks the static cone.

The check: a workspace class loaded from a class directory shows in JFR as a `jdk.FileRead` of its
`.class` file, at the moment it loads. A workspace class loaded in a test class's window that is
not in that class's static cone proves the cone incomplete (reflection, a framework scan, a service
file). The class is marked **unattributable** and falls back to the module key: its key is the
suite stamp, so it runs whenever anything in the module changes, as every class does today. The
fallback is per class: the rest of the suite keeps its finer keys.

The check sees a class only the first time it loads in a JVM, and only from a directory (a sibling's
classes come from its jar, which reads as the jar). So it catches many incomplete cones, not all;
what it misses is listed below with what closes it.

## Holes of class-level analysis, and what closes each

Selection may over-run. It must never under-run: a class that a full run would fail, or whose
result would differ, must run. Each known hole:

| Hole | What closes it |
|---|---|
| **Reflection** (`Class.forName`, method handles by name, a class name built at run time) | A literal binary name is a reference (above). A computed name loads a class the static cone lacks: the observed check marks the class unattributable when the loaded class came from a class directory. A computed name into a sibling jar is not seen by the check: closed by the soundness guard (below) |
| **`ServiceLoader`, Spring component scanning, CDI, Quarkus, Micronaut, classpath scanning** (ArchUnit, ClassGraph, Reflections) | A class whose cone names a scanning entry point falls back to the module key: `java/util/ServiceLoader`, a `META-INF/services` provider named by a workspace service file, `@SpringBootTest`, `@ContextConfiguration`, `@ComponentScan`, `@QuarkusTest`, `@MicronautTest`, ArchUnit's `ClassFileImporter`, ClassGraph, Reflections. The list is data, extended as frameworks are met; the observed check backs it |
| **Annotation processors and generated code** | Generated classes are classes in the main or test tree; the cone sees them as it sees any class. A processor's own change reaches the class bytes through the compile |
| **Inlined constants** (a `static final` primitive or string copied into the caller's bytes) | The caller's bytes hold the value, so a change of value is a change of the caller's bytes, provided the compile recompiles every class that inlined it, which JumpKick's incremental compile does. The constant's class may be absent from the caller's constant pool; it is not needed, since the value is in the caller |
| **Resources and classpath scanning of resources** | Suite resource roots are keyed whole in the suite part. A resource read from a sibling's jar is in the sibling's jar, keyed through the cone's classes of that sibling only if a cone class lives there: closed by keying every workspace jar a class read (observed) and, for the first-load hole, by the guard |
| **State shared between test classes in one JVM** (a static cache filled from a file by one class and read by another; a singleton; a system property one class sets) | Code loads: a code file read in a JVM keys every class that ran in that JVM from then on, so a class loader cached across classes cannot hide a worker jar. Data: a read keys only the class in whose window it happened, and reads outside every window are pooled to every class of that JVM. A class that reads a cache another class filled is the residual hole; it is closed by the soundness guard, by the order-dependence report (below), and by `[test] cache = "module"` for a module that relies on such state |
| **Order dependence** (a class that passes only after, or only without, another) | A subset run puts different classes in each JVM. A class red in a subset run and green in the last full run under the same class key is reported as order-dependent, by name |
| **Parameterized and dynamic tests** (`@ParameterizedTest`, `@TestFactory`, data from files) | The unit is the class: every invocation runs or none does. Data files read while generating cases are read in the class's window and keyed |
| **Test fixtures** (`src/fixtures`, a sibling's fixtures) | They are workspace classes on the runtime classpath; the cone walks them |
| **Tests that scan the repository** | Their reads land in their own window and key only them. A new file in a directory one of them read changes that directory's listing, keyed. A directory listed where nothing was read is unseen, as today |
| **Programs started** (a test that execs a built jar, `git`, a nested `jk`) | What a child process reads is not recorded. A class that started a program is unattributable and falls back to the module key |
| **Nested engines and worker jars** (jk-cli and jk-engine tests drive a real engine that loads plugin jars by path) | A worker jar is a code file read by path: observed, cumulative within its JVM (above). A class that ran in a JVM before any worker loaded is not keyed on the workers |
| **Line numbers and source file names** (a test asserting a stack trace) | Bytes are hashed whole, debug attributes included |
| **The network** | A suite that reached past the host stores no marker, as today, and none of its classes replay |
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
- A suite that reached the network.

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
  shutdown hook, a background thread between classes): pooled to every class the JVM ran.
- **After a class finished**: never that class's. Its outcome was decided by then.
- **Code files** (`.jar`, `.zip`, `.jmod`, a `.class` outside the workspace class trees): the class
  in whose window it was read and every class that ran later in that JVM.

Pull workers run one class at a time per JVM, so a window is a class's alone in every run JumpKick starts
unless the suite turns Jupiter parallelism on.

The environment probe attributes a lookup to the class named by the JVM-global `jk.test.class`
property, which the listener sets when a class starts. The system-property probe does the same.

## The record

One **class record** per class per launch, kept beside the suite's discovered inputs in JumpKick's cache
for the project, newest run winning:

```json
{"container":["cc.acme","OrderTest"],"key":"…","outcome":"passed","tests":12,"failed":0,"skipped":1,
 "ranAt":1763250000000,"millis":840,"reads":["src/test/data/orders.json"],"code":[],"processes":[],
 "nativeCode":[],"osPaths":["/proc/self/status"],"env":["LANG"],
 "jvm":{"cone":"<digest>","coneSize":41,"props":["user.home"],"classesLoaded":38,"unattributed":null}}
```

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

`jk explain` forecasts `run tests · 3 of 466 classes · cc.jumpkick.engine.Planner changed (+2
more)`: the classes whose key moved and, for the first, the first cone class or read that moved.
When the module's compile is cached, the forecast reads the same bytes the run will and its count is
exact. When a compile is pending, the new bytes do not exist yet; the forecast walks the last run's
cones from the classes of the changed sources and states an upper bound, `up to 12 of 466 classes`.
The run never runs a class the bound leaves out, since a class whose cone holds no changed source's
class keeps its key.

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
   JumpKick's own root manifest only. `"class"` (the default: a red or cut-short run re-runs only what has
   not passed under an unchanged stamp) and `"module"` stay as they are, and so does the suite
   marker; `-r` runs every class.
