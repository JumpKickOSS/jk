// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.orchestrator.ClassCones;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.task.JdkToolchain;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.test.ClassReads;
import cc.jumpkick.test.RecordingReader;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.test.TestTelemetry;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * After a suite ran: what each class did, read from the suite's recordings and lookups, kept as its
 * class record ({@link TestClassRecords}), with each class's key and what per-class selection would
 * have done, in shadow ({@link ClassShadow}). Nothing here decides what runs.
 *
 * <p>Bounded: one suite at a time across the engine ({@link #ONE}) holds its classes' observations,
 * at most {@link ClassReads#PEAK_BYTES}, and walks its cones over an index of its workspace classes only while
 * it holds the recording reader's permit, so the index (at most {@link #INDEX_CLASSES} classes,
 * {@link ClassCones#BYTES_PER_CLASS} each) never stands beside a recording being read. Records are
 * rewritten a row at a time.
 */
final class TestClassRecording {

    private TestClassRecording() {}

    /** One suite records its classes at a time. */
    private static final Semaphore ONE = new Semaphore(1);

    /**
     * The most workspace classes an index holds; past it no cone is trusted and every class is keyed
     * by its suite's stamp. jk-cli's classpath, the widest of jk's own, holds some tens of thousands.
     */
    static final int INDEX_CLASSES = 40_000;

    static {
        if (INDEX_CLASSES * ClassCones.BYTES_PER_CLASS > RecordingReader.PEAK_BYTES) {
            throw new IllegalStateException("the class index must fit within the recording reader's peak");
        }
    }

    /**
     * A suite's classes to record once its run has settled: everything but the run's discovered
     * inputs, which settling decides. The stamp's tokens ({@code stampExtras}) are read then too, as
     * the next run's stamp reads them: they name each program the suite was found to start, and a
     * class keyed before settling would stand on no identity for a program first seen in this run.
     * Of what the suite's stamp keys ({@code keyed}), the worker jars ({@code workerJars}) are no
     * class's alone: a class that read one holds it as a code file.
     */
    record Pending(
            Path moduleDir,
            BuildLayout layout,
            Collection<Path> keyed,
            Collection<Path> workerJars,
            TestClassLedger ledger,
            String moduleLabel,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            @Nullable Path javaHome,
            StampExtras stampExtras,
            List<Path> resourceDirs,
            @Nullable String stampKey) {

        /** The suite's discovered inputs under {@code selection}: what a class past its read bound is keyed on. */
        ClassKeys.SuiteReadSet suiteReads(TestSelection selection) {
            if (!TestTelemetry.shadow()) return ClassKeys.SuiteReadSet.NONE;
            return readSet(moduleDir, selection, layout.workspaceRoot());
        }

        /** Record the classes, {@code readsBefore} and {@code readsAfter} the suite's discovered inputs before and after settling. */
        void record(ClassKeys.SuiteReadSet readsBefore, ClassKeys.SuiteReadSet readsAfter) {
            if (!TestTelemetry.on() || ledger.recorded().isEmpty()) return;
            try {
                ONE.acquire();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                recordHeld(readsBefore, readsAfter);
            } finally {
                ONE.release();
            }
        }

        private void recordHeld(ClassKeys.SuiteReadSet readsBefore, ClassKeys.SuiteReadSet readsAfter) {
            long started = Clock.SYSTEM.nanos();
            try {
                Path target = layout.moduleTargetDir();
                Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
                Roots roots = Roots.of(workspace, testClasses, mainClasses, runtimeCp);
                List<Path> coneRoots = roots.cone();
                List<Path> external = roots.external();
                List<Path> scratch = List.of(target, TestHomes.root());
                List<Path> temp = new ArrayList<>();
                for (Path t : TestEnv.tempRoots(moduleDir)) {
                    if (!t.toAbsolutePath().normalize().startsWith(workspace)) temp.add(t);
                }
                Map<String, TestClassRecords.Outcome> ran = TestClassRecords.outcomes(ledger.recorded());
                ClassReads.Attributed seen = ClassReads.attribute(
                        SuiteReads.recordingDir(target),
                        moduleDir,
                        new ClassReads.Layout(
                                roots.classTrees(),
                                classKeyed(keyed, workerJars),
                                roots.jars(),
                                workspace,
                                javaHome,
                                scratch,
                                temp),
                        TestTelemetry.carry(),
                        launchOf(ran));
                long attributedMs = (Clock.SYSTEM.nanos() - started) / 1_000_000;
                TestClassRecords.Lookups lookups = TestClassRecords.Lookups.read(EnvReadReport.recordDir(moduleDir));
                Path file = TestClassRecords.file(moduleDir);
                String previous = TestClassRecords.header(file);
                Map<String, String> headers = TestClassRecords.headers(file);
                SuiteCodeFiles.Listed code =
                        SuiteCodeFiles.listed(previous, seen.code(), workspace, firstFinished(ran));
                Set<String> launches = TestClassRecords.launches(ran);
                List<String> leading = TestClassRecords.headers(
                        moduleLabel,
                        seen,
                        launches,
                        lookups,
                        workspace,
                        javaHome == null ? null : JdkToolchain.token(javaHome),
                        code);
                if (!TestTelemetry.shadow()) {
                    TestClassRecords.merge(
                            moduleDir,
                            leading,
                            launches,
                            ran,
                            (o, prev) -> TestClassRecords.row(o, observation(seen, o), lookups, workspace, null),
                            UnaryOperator.identity());
                    done(started, attributedMs, seen, null);
                    return;
                }
                List<String> extras = stampExtras.read();
                String suitePart = ClassKeys.suitePart(extras, external, resourceDirs);
                Function<String, ClassKeys.Observed> pooledBefore = pooledBy(headers);
                Map<String, ClassKeys.Observed> pools = new HashMap<>();
                // This run's pool is what its own launches' JVMs left; any other launch keeps its own.
                Function<String, ClassKeys.Observed> pooledNow = launch -> launches.contains(launch)
                        ? pools.computeIfAbsent(launch, l -> ClassShadow.observed(seen.pooled(l), workspace))
                        : pooledBefore.apply(launch);
                ClassShadow shadow;
                // The index stands only while no recording is read anywhere in the engine.
                try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
                    ClassCones.Index index = ClassCones.index(coneRoots, INDEX_CLASSES);
                    shadow = new ClassShadow(
                            index,
                            suitePart,
                            workspace,
                            pooledBefore,
                            pooledNow,
                            ClassKeys.programs(extras),
                            readsBefore,
                            readsAfter,
                            SuiteCodeFiles.since(previous, workspace),
                            code.more(),
                            stampKey);
                    ClassShadow s = shadow;
                    TestClassRecords.merge(
                            moduleDir,
                            leading,
                            launches,
                            ran,
                            (o, prev) -> {
                                ClassReads.Observation obs = observation(seen, o);
                                return TestClassRecords.row(o, obs, lookups, workspace, s.of(o, obs, prev));
                            },
                            s::kept);
                }
                if (!shadow.unsound().isEmpty()) {
                    Log.debug(ClassShadow.UNSOUND + " " + moduleDir + ": per-class selection would have replayed "
                            + String.join(", ", shadow.unsound()));
                }
                shadow.log(moduleDir, stampKey, (Clock.SYSTEM.nanos() - started) / 1_000_000);
                done(started, attributedMs, seen, shadow);
            } catch (IOException | RuntimeException e) {
                Log.debug("test class records: " + moduleDir + " not kept", e);
            }
        }

        private void done(long started, long attributedMs, ClassReads.Attributed seen, @Nullable ClassShadow shadow) {
            Log.debug("test class records: " + moduleDir + " " + seen.classes().size() + " classes, " + seen.jvms()
                    + " JVMs, " + seen.events() + " events; attributed in " + attributedMs + " ms, "
                    + (Clock.SYSTEM.nanos() - started) / 1_000_000 + " ms in all"
                    + (shadow == null ? "" : "; " + shadow.wouldRun() + " would run"));
        }
    }

    /** The stamp's tokens as they stand when read ({@link TestStampInputs#extras}). */
    @FunctionalInterface
    interface StampExtras {
        List<String> read() throws IOException;
    }

    /**
     * What the suite's key holds whole for every class: {@code keyed} less the worker jars, which a
     * class's key holds by content where it read them ({@link ClassKeys#replacedToken}).
     */
    static List<Path> classKeyed(Collection<Path> keyed, Collection<Path> workerJars) {
        Set<Path> workers = new HashSet<>();
        for (Path w : workerJars) workers.add(w.toAbsolutePath().normalize());
        List<Path> out = new ArrayList<>();
        for (Path k : keyed) {
            if (!workers.contains(k.toAbsolutePath().normalize())) out.add(k);
        }
        return List.copyOf(out);
    }

    /**
     * Where a suite's classes come from: the class trees they load from by file (with the class tree
     * of each sibling whose jar is on the classpath: a loader handed that tree loads the jar's classes
     * from it, and a load there is the class's by name, never a code file every later class read),
     * the roots its cones walk (its own trees and every sibling's jar), the jars on its classpath, and
     * the classpath entries outside the workspace, which the suite part keys whole.
     */
    record Roots(List<Path> classTrees, List<Path> cone, Set<Path> jars, List<Path> external) {
        static Roots of(Path workspace, Path testClasses, Path mainClasses, List<Path> runtimeCp) {
            List<Path> classTrees = new ArrayList<>(List.of(testClasses, mainClasses));
            List<Path> cone = new ArrayList<>(classTrees);
            Set<Path> jars = new HashSet<>();
            List<Path> external = new ArrayList<>();
            for (Path entry : runtimeCp) {
                Path abs = entry.toAbsolutePath().normalize();
                if (!abs.startsWith(workspace)) {
                    jars.add(abs);
                    external.add(abs);
                    continue;
                }
                cone.add(abs);
                if (Files.isDirectory(abs)) {
                    classTrees.add(abs);
                    continue;
                }
                // A sibling's jar is its classes, which the cone holds one by one.
                jars.add(abs);
                Path target = abs.getParent();
                if (target != null
                        && target.getParent() != null
                        && BuildLayout.TARGET.equals(String.valueOf(target.getFileName()))) {
                    Path tree = BuildLayout.classesDir(target.getParent());
                    if (Files.isDirectory(tree)) classTrees.add(tree);
                }
            }
            return new Roots(classTrees, cone, jars, external);
        }
    }

    /**
     * Before a suite runs under {@code [test] cache = "cone"}: each class whose last record passed and
     * whose key, taken now over its cone and what that record says it read, is still the key the
     * record was stored under, by its launch and class as the class ledger names it, with the counts
     * it replays. Nothing that class reaches changed since it passed. A class that reached past the
     * host, or whose launch's test JVMs did outside every class, is never replayed, nor a class no
     * longer in the workspace, nor one of a launch no header stands for. Empty when the shadow is off ({@link TestTelemetry#shadow}): its keys are not kept.
     */
    static Map<String, TestClassLedger.Entry> unmoved(
            Path moduleDir,
            BuildLayout layout,
            TestSelection selection,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            List<String> stampExtras,
            List<Path> resourceDirs,
            @Nullable String stampKey)
            throws IOException {
        return split(
                        moduleDir,
                        layout,
                        selection,
                        testClasses,
                        mainClasses,
                        runtimeCp,
                        stampExtras,
                        resourceDirs,
                        stampKey,
                        null)
                .unmoved();
    }

    /**
     * For the forecast, what a run of the launches named {@code launches} replays, as {@link #unmoved}
     * decides it, and the tests of the classes recorded under those launches it runs. The record
     * keeps every launch's rows, an earlier selection's included, so only these launches' count.
     */
    static Replayed replayedNow(
            Path moduleDir,
            BuildLayout layout,
            TestSelection selection,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            List<String> stampExtras,
            List<Path> resourceDirs,
            @Nullable String stampKey,
            Set<String> launches)
            throws IOException {
        Split split = split(
                moduleDir,
                layout,
                selection,
                testClasses,
                mainClasses,
                runtimeCp,
                stampExtras,
                resourceDirs,
                stampKey,
                launches);
        return new Replayed(split.replays(), split.runs());
    }

    /**
     * The rows {@link #unmoved} replays, and by class the tests of the recorded classes it replays
     * ({@code replays}) and of those it runs ({@code runs}) under the launches asked.
     */
    private record Split(
            Map<String, TestClassLedger.Entry> unmoved, Map<String, Long> replays, Map<String, Long> runs) {
        static final Split NONE = new Split(Map.of(), Map.of(), Map.of());
    }

    /** {@link #unmoved} over the rows of {@code launches}, every launch's when null. */
    private static Split split(
            Path moduleDir,
            BuildLayout layout,
            TestSelection selection,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            List<String> stampExtras,
            List<Path> resourceDirs,
            @Nullable String stampKey,
            @Nullable Set<String> launches)
            throws IOException {
        if (!TestTelemetry.shadow()) return Split.NONE;
        Path file = TestClassRecords.file(moduleDir);
        String header = TestClassRecords.header(file);
        if (header == null) return Split.NONE;
        Map<String, String> headers = TestClassRecords.headers(file);
        Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
        Roots roots = Roots.of(workspace, testClasses, mainClasses, runtimeCp);
        String suitePart = ClassKeys.suitePart(stampExtras, roots.external(), resourceDirs);
        ClassKeys.SuiteReadSet reads = readSet(moduleDir, selection, layout.workspaceRoot());
        Map<String, TestClassLedger.Entry> out = new TreeMap<>();
        Map<String, Long> replays = new HashMap<>();
        Map<String, Long> runs = new HashMap<>();
        // Whether a launch has no header, or its pool reached another host: none of its classes replays.
        Map<String, Boolean> poolReached = new HashMap<>();
        try {
            ONE.acquire();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Split.NONE;
        }
        // The index stands only while no recording is read anywhere in the engine.
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            ClassCones.Index index = ClassCones.index(roots.cone(), INDEX_CLASSES);
            ClassShadow keys = new ClassShadow(
                    index,
                    suitePart,
                    workspace,
                    pooledBy(headers),
                    launch -> ClassKeys.Observed.NONE,
                    ClassKeys.programs(stampExtras),
                    reads,
                    reads,
                    SuiteCodeFiles.since(header, workspace),
                    false,
                    stampKey);
            TestClassRecords.forEachRow(file, (key, row) -> {
                String launch = Jsonl.str(row, "launch");
                String cls = TestClassRecords.className(Jsonl.strArray(row, "container"));
                if (launch == null || (launches != null && !launches.contains(launch)) || !index.contains(cls)) return;
                long tests = Jsonl.longValue(row, "tests", 0);
                if (poolReached.computeIfAbsent(launch, l -> {
                            String own = headers.get(l);
                            return own == null || reachedPastHost(Jsonl.nested(own, "pooled"));
                        })
                        || reachedPastHost(row)
                        || keys.whyRun(row, launch, cls) != null) {
                    runs.merge(cls, tests, Long::sum);
                    return;
                }
                replays.merge(cls, tests, Long::sum);
                out.put(
                        key,
                        new TestClassLedger.Entry(
                                tests,
                                0,
                                Jsonl.longValue(row, "skipped", 0),
                                Jsonl.longValue(row, "ranAt", 0),
                                Jsonl.longValue(row, "millis", 0)));
            });
        } finally {
            ONE.release();
        }
        return new Split(out, replays, runs);
    }

    /** What the suite at {@code moduleDir} was found to read under {@code selection}, as {@link SuiteReads#digest} reads it. */
    private static ClassKeys.SuiteReadSet readSet(Path moduleDir, TestSelection selection, Path workspace) {
        return new ClassKeys.SuiteReadSet(SuiteReads.tokens(moduleDir, selection, workspace), workspace);
    }

    /**
     * Before a compile an edit is waiting on, under {@code [test] cache = "cone"}: which of the
     * suite's classes that passed last time in the launches named {@code launches} will replay, and
     * how many tests they hold, once {@code edits} are built, for the forecast to count only the rest. Each edit names the sources whose content moved in one
     * module (this one or a sibling on the suite's classpath) and whether their declarations kept.
     * A class replays when its last record passed, kept its key, reached no other host, fell back
     * to no module key, and no class compiled from a moved source lies in its cone, or, where the
     * module's edit kept every declaration, none it ran ({@code coneUsed}). A worker jar the stamp
     * keys that is rebuilding ({@code rebuildingJars}) runs the classes that read it, those in whose
     * JVM a program was started ({@link ClassKeys#startedProgram}), and those keyed by their module,
     * every class of a launch whose pool started a program ({@link ClassKeys#pooledFallback});
     * nothing of a launch replays when its pool read it, and a launch's pool is the one of the header
     * its rows stand on ({@link TestClassRecords#headers}). A class that read more code files than its record names, or ran
     * beside a pool that did, stands on the suite's code files as the header lists them ({@link
     * SuiteCodeFiles}): it runs when one of them is rebuilding or changed after it finished, as the
     * selection decides it. The cones are walked over the classes on disk, the edit's own not yet
     * compiled. Null when the records cannot tell: none, an edit of unknown shape, the shadow off.
     */
    static @Nullable Replayed replayedAfter(
            Path moduleDir,
            BuildLayout layout,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            List<SourceApiIndex.Hint> edits,
            Collection<Path> rebuildingJars,
            Set<String> launches)
            throws IOException {
        if (!TestTelemetry.shadow()) return null;
        for (SourceApiIndex.Hint e : edits) {
            if (e.changed() == null || e.kind() == SourceApiIndex.Kind.UNKNOWN) return null;
        }
        Path file = TestClassRecords.file(moduleDir);
        String header = TestClassRecords.header(file);
        if (header == null) return null;
        Map<String, String> headers = TestClassRecords.headers(file);
        Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
        Set<Path> jars = new HashSet<>();
        for (Path j : rebuildingJars) jars.add(j.toAbsolutePath().normalize());
        Map<String, PoolAfter> pools = new HashMap<>();
        long codeSince = listsAny(header, jars, workspace) ? Long.MAX_VALUE : SuiteCodeFiles.since(header, workspace);
        Roots roots = Roots.of(workspace, testClasses, mainClasses, runtimeCp);
        Map<String, Long> replays = new HashMap<>();
        Map<String, Long> runs = new HashMap<>();
        try {
            ONE.acquire();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        }
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            ClassCones.Index index = ClassCones.index(roots.cone(), INDEX_CLASSES);
            if (index.full()) return Replayed.NONE;
            Set<String> moved = new HashSet<>();
            Set<String> bodiesOnly = new HashSet<>();
            for (SourceApiIndex.Hint e : edits) {
                for (String source : Objects.requireNonNull(e.changed(), "checked above")) {
                    Set<String> classes = index.compiledFrom(source);
                    moved.addAll(classes);
                    if (e.kind() == SourceApiIndex.Kind.BODY_ONLY) bodiesOnly.addAll(classes);
                }
            }
            TestClassRecords.forEachRow(file, (key, row) -> {
                String cls = TestClassRecords.className(Jsonl.strArray(row, "container"));
                String launch = String.valueOf(Jsonl.str(row, "launch"));
                if (!launches.contains(launch) || !index.contains(cls)) return;
                long tests = Jsonl.longValue(row, "tests", 0);
                PoolAfter pool = pools.computeIfAbsent(launch, l -> PoolAfter.of(headers.get(l), jars, workspace));
                boolean codeMoved = Jsonl.str(row, "fallback") == null
                        && !pool.process()
                        && (pool.codeMore() || Jsonl.bool(row, "codeMore", false))
                        && codeSince >= Jsonl.longValue(row, "ranAt", 0);
                if (pool.runsAll()
                        || codeMoved
                        || runsAfter(row, cls, index, moved, bodiesOnly, jars, pool.process(), workspace)) {
                    runs.merge(cls, tests, Long::sum);
                    return;
                }
                replays.merge(cls, tests, Long::sum);
            });
        } finally {
            ONE.release();
        }
        return new Replayed(replays, runs);
    }

    /**
     * Whether the class {@code cls} of a record {@code row} runs once the {@code moved} classes (of
     * which {@code bodiesOnly} kept their declarations) and the rebuilding {@code jars} are built, as
     * {@link #replayedAfter} says.
     */
    private static boolean runsAfter(
            String row,
            String cls,
            ClassCones.Index index,
            Set<String> moved,
            Set<String> bodiesOnly,
            Set<Path> jars,
            boolean pooledProcess,
            Path workspace) {
        if (Jsonl.str(row, "classKey") == null
                || !"passed".equals(Jsonl.str(row, "outcome"))
                || reachedPastHost(row)
                || ((Jsonl.str(row, "fallback") != null || pooledProcess) && !(moved.isEmpty() && jars.isEmpty()))
                || readsAny(row, jars, workspace)) return true;
        ClassCones.Cone cone = index.cone(cls, Jsonl.strArray(row, "reflective"));
        String used = Jsonl.str(row, "coneUsed");
        Predicate<String> ran = used == null ? c -> true : cone.ranBy(used);
        for (String c : moved) {
            if (cone.contains(c) && (!bodiesOnly.contains(c) || ran.test(c))) return true;
        }
        return false;
    }

    /**
     * Whether a record row, or the pool of a header, may have read one of {@code jars}: it names one
     * as a code file, or saw a program started that may run one. What it read past the files it
     * names it reads through the header's list ({@link #listsAny}).
     */
    private static boolean readsAny(@Nullable String row, Set<Path> jars, Path workspace) {
        if (row == null || jars.isEmpty()) return false;
        if (ClassKeys.startedProgram(ClassShadow.fromRow(row))) return true;
        for (String code : Jsonl.strArray(row, "code")) {
            if (jars.contains(workspace.resolve(code).toAbsolutePath().normalize())) return true;
        }
        return false;
    }

    /** Whether the suite's code files a {@code header} lists ({@link SuiteCodeFiles}) hold one of {@code jars}. */
    private static boolean listsAny(String header, Set<Path> jars, Path workspace) {
        if (jars.isEmpty()) return false;
        for (String e : Jsonl.strArray(header, SuiteCodeFiles.FIELD)) {
            String[] parts = e.split(" ", 3);
            if (parts.length == 3
                    && jars.contains(
                            workspace.resolve(parts[2]).toAbsolutePath().normalize())) return true;
        }
        return false;
    }

    /**
     * By class, the tests of each recorded class a suite replays ({@code replays}) and of each it runs
     * ({@code runs}), under the launches asked.
     */
    record Replayed(Map<String, Long> replays, Map<String, Long> runs) {
        static final Replayed NONE = new Replayed(Map.of(), Map.of());

        /** How many classes replay. */
        int classes() {
            return replays.size();
        }

        /** The tests the replayed classes hold. */
        long tests() {
            return sum(replays);
        }

        /** The tests of the recorded classes that run. */
        long ran() {
            return sum(runs);
        }

        /** Every class with a record under the launches asked. */
        Set<String> recorded() {
            Set<String> out = new HashSet<>(replays.keySet());
            out.addAll(runs.keySet());
            return out;
        }

        /**
         * Only the classes {@code selected} names. A launch's rows outlive the selection that wrote
         * them: a class later tagged out of the suite, or deleted, keeps its row, and the run never
         * starts it, so the forecast must not count it either.
         */
        Replayed within(Set<String> selected) {
            Map<String, Long> keptReplays = new HashMap<>(replays);
            Map<String, Long> keptRuns = new HashMap<>(runs);
            keptReplays.keySet().retainAll(selected);
            keptRuns.keySet().retainAll(selected);
            return new Replayed(keptReplays, keptRuns);
        }

        /**
         * The tests the run re-runs, for a selection whose sources count {@code bySource} tests by
         * class: each selected class that runs by its record ({@link #within}), and each class no
         * record covers by its sources. A source's count is read from its text and a record's from
         * what ran, a parameterized test's every case among it, so the two never offset each other: a
         * class whose source counts more tests than its record says ran still replays whole.
         */
        long rerun(Map<String, Integer> bySource) {
            long out = within(bySource.keySet()).ran();
            for (Map.Entry<String, Integer> source : bySource.entrySet()) {
                String cls = source.getKey();
                if (!replays.containsKey(cls) && !runs.containsKey(cls)) out += source.getValue();
            }
            return out;
        }

        private static long sum(Map<String, Long> tests) {
            long out = 0;
            for (long n : tests.values()) out += n;
            return out;
        }
    }

    /**
     * How each class recorded under the launches named {@code launches} meets the files {@code
     * changed} names (workspace-relative): {@code cone-ran:<class>} when its cone holds a class
     * compiled from a changed source whose code it ran, {@code reads:<path>} when it read a changed
     * file, or one under a directory it read more of than its record names, {@code cone:<class>}
     * when its cone holds such a class it never ran, and the empty string when none. A class with no
     * record under those launches is absent. The cones are walked over the classes on disk, so a
     * changed source is found only once it is compiled.
     */
    static Map<String, String> overlaps(
            Path moduleDir,
            BuildLayout layout,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            Collection<String> changed,
            Set<String> launches)
            throws IOException {
        Path file = TestClassRecords.file(moduleDir);
        if (!Files.isRegularFile(file)) return Map.of();
        Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
        Set<String> paths = new HashSet<>();
        for (String c : changed) paths.add(c.replace('\\', '/'));
        Roots roots = Roots.of(workspace, testClasses, mainClasses, runtimeCp);
        Map<String, String> out = new TreeMap<>();
        try {
            ONE.acquire();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            ClassCones.Index index = ClassCones.index(roots.cone(), INDEX_CLASSES);
            if (index.full()) return Map.of();
            Map<String, String> moved = new TreeMap<>();
            for (String path : paths) {
                for (String cls : index.compiledFrom(path)) moved.putIfAbsent(cls, path);
            }
            TestClassRecords.forEachRow(file, (key, row) -> {
                String cls = TestClassRecords.className(Jsonl.strArray(row, "container"));
                if (!launches.contains(String.valueOf(Jsonl.str(row, "launch"))) || !index.contains(cls)) return;
                String why = overlap(row, cls, index, moved, paths);
                out.merge(cls, why, (a, b) -> strength(b) > strength(a) ? b : a);
            });
        } finally {
            ONE.release();
        }
        return out;
    }

    /** How one record {@code row} meets the {@code moved} classes and changed {@code paths}, as {@link #overlaps} says. */
    private static String overlap(
            String row, String cls, ClassCones.Index index, Map<String, String> moved, Set<String> paths) {
        for (String read : Jsonl.strArray(row, "reads")) {
            if (paths.contains(read)) return "reads:" + read;
        }
        for (String root : Jsonl.strArray(row, "readRoots")) {
            boolean deep = root.endsWith("**");
            String dir = root.substring(0, root.length() - (deep ? 2 : 1));
            for (String p : paths) {
                if (p.startsWith(dir) && (deep || p.indexOf('/', dir.length()) < 0)) return "reads:" + p;
            }
        }
        if (moved.isEmpty()) return "";
        ClassCones.Cone cone = index.cone(cls, Jsonl.strArray(row, "reflective"));
        String used = Jsonl.str(row, "coneUsed");
        Predicate<String> ran = used == null ? c -> true : cone.ranBy(used);
        String reached = "";
        for (String c : moved.keySet()) {
            if (!cone.contains(c)) continue;
            if (ran.test(c)) return "cone-ran:" + c;
            if (reached.isEmpty()) reached = "cone:" + c;
        }
        return reached;
    }

    /** The order of {@link #overlaps}' answers, the strongest highest. */
    static int strength(String overlap) {
        if (overlap.startsWith("cone-ran:") || overlap.startsWith("reads:")) return 2;
        return overlap.isEmpty() ? 0 : 1;
    }

    /** When the first of {@code ran} finished: the start of the run, as its records date it. */
    private static long firstFinished(Map<String, TestClassRecords.Outcome> ran) {
        long first = Long.MAX_VALUE;
        for (TestClassRecords.Outcome o : ran.values()) {
            if (o.ranAt() > 0) first = Math.min(first, o.ranAt());
        }
        return first == Long.MAX_VALUE ? Clock.SYSTEM.millis() : first;
    }

    /**
     * What a launch's pool says of every class of the launch once the rebuilding {@code jars} are
     * built ({@link #replayedAfter}): all of them run ({@code runsAll}) when the launch has no header,
     * its pool reached another host or may have read one of the jars; {@code process} when its pool
     * started a program, which keys every class by its module; {@code codeMore} when its pool read
     * more code files than it names.
     */
    private record PoolAfter(boolean runsAll, boolean process, boolean codeMore) {
        static PoolAfter of(@Nullable String header, Set<Path> jars, Path workspace) {
            if (header == null) return new PoolAfter(true, false, false);
            String pool = Jsonl.nested(header, "pooled");
            return new PoolAfter(
                    reachedPastHost(pool) || readsAny(pool, jars, workspace),
                    ClassKeys.pooledFallback(ClassShadow.pooledOf(header)) != null,
                    pool != null && Jsonl.bool(pool, "codeMore", false));
        }
    }

    /** Each launch's pool, from the header its rows stand on ({@link TestClassRecords#headers}); nothing for a launch none names. */
    static Function<String, ClassKeys.Observed> pooledBy(Map<String, String> headers) {
        Map<String, ClassKeys.Observed> pools = new HashMap<>();
        return launch -> pools.computeIfAbsent(launch, l -> ClassShadow.pooledOf(headers.get(l)));
    }

    /** Whether a record row, or the pool of its header, says the network was reached. */
    private static boolean reachedPastHost(@Nullable String row) {
        return row != null && !Jsonl.strArray(row, "network").isEmpty();
    }

    /**
     * The launch of a recording that held the windows of {@code classes}, by the launch each ran in
     * ({@code ran}): the one launch all of them ran in, null when they ran in more than one, or one of
     * them in none of this run's, or there were none.
     */
    static Function<Collection<String>, @Nullable String> launchOf(Map<String, TestClassRecords.Outcome> ran) {
        Map<String, Set<String>> byClass = new HashMap<>();
        for (TestClassRecords.Outcome o : ran.values()) {
            byClass.computeIfAbsent(o.testClass(), c -> new HashSet<>()).add(o.launch());
        }
        return classes -> {
            String one = null;
            for (String c : classes) {
                Set<String> in = byClass.get(c);
                if (in == null || in.size() != 1) return null;
                String launch = in.iterator().next();
                if (one == null) one = launch;
                else if (!one.equals(launch)) return null;
            }
            return one;
        };
    }

    /** The observation of the class {@code o} names, when its recording was read whole. */
    private static ClassReads.@Nullable Observation observation(
            ClassReads.Attributed seen, TestClassRecords.Outcome o) {
        return seen.complete() ? seen.classes().get(o.testClass()) : null;
    }
}
