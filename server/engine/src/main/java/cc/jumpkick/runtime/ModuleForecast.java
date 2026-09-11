// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.JavacLint;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.GroovyPluginSetup;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One module's forecast. {@link Prepared} is what every phase reads and none rewrites; the
 * fields are what a phase leaves for the next — dirtiness, counts, the source lists compile-test
 * found, the drift flags package reads — in the order {@link #run} calls them.
 */
final class ModuleForecast {
    private final BuildGraph.BuildUnit u;
    private TaskForecaster.DepDirtiness dep;
    private final boolean force;
    private final boolean skipTests;
    private final Cas cas;
    private final ClasspathResolver resolver;
    private final ActionCache actionCache;
    private final Path cache;
    private final Map<Path, String> restoredJarShas;
    private final WorkspaceTarget target;
    private final Set<Path> terminalDirs;
    private final @Nullable Path workerJar;

    private boolean compileDepDirty;
    private boolean compileDirty;
    private @Nullable String compileMainKey;
    private final Path dir;
    private boolean haveTests;
    private @Nullable Boolean knownResourceDrift;
    private final Path lockFile;
    private boolean mainResourceDrift;
    private boolean nativeOnBuild;
    private boolean nativeOnNativeCmd;
    private boolean producesImage;
    private boolean producesJar;
    private final JkBuild project;
    private int sourceCount;
    private List<TaskForecast.Task> steps = new ArrayList<>();
    private List<Path> testCompileCp = List.of();
    private int testCount;
    private boolean testDepDirty;
    private boolean testDirty;
    private boolean testResourceDrift;
    private PlannerTest.TestSources testSources =
            new PlannerTest.TestSources(Path.of(""), List.of(), List.of(), List.of(), List.of());

    ModuleForecast(
            BuildGraph.BuildUnit u,
            TaskForecaster.DepDirtiness dep,
            boolean force,
            boolean skipTests,
            Cas cas,
            ClasspathResolver resolver,
            ActionCache actionCache,
            Path cache,
            Map<Path, String> restoredJarShas,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            @Nullable Path workerJar) {
        this.u = u;
        this.dep = dep;
        this.force = force;
        this.skipTests = skipTests;
        this.cas = cas;
        this.resolver = resolver;
        this.actionCache = actionCache;
        this.cache = cache;
        this.restoredJarShas = restoredJarShas;
        this.target = target;
        this.terminalDirs = terminalDirs;
        this.workerJar = workerJar;
        this.project = u.manifest();
        this.dir = u.dir();
        this.lockFile = LockPaths.lockFile(dir);
    }

    /** Resolved once before the phases run; never rewritten. */
    private record Prepared(
            Lockfile lock,
            boolean compact,
            BuildLayout layout,
            int release,
            Path javaHome,
            List<String> javacArgs,
            List<Path> processorCp,
            PackagingKeys.@Nullable Owner plugin,
            PluginBuild.@Nullable Declarations pkgDecls,
            Path mainSrcDir,
            List<Path> mainSrc,
            List<Path> ktSrc,
            List<Path> gvSrc,
            boolean mixedKotlin,
            boolean mixedGroovy) {}

    TaskForecast.Module run() {
        if (dep == null) dep = TaskForecaster.DepDirtiness.NONE;
        compileDepDirty = dep.compileDepDirty();
        testDepDirty = dep.testDepDirty();
        steps = new ArrayList<>();
        if (!Files.isRegularFile(lockFile)) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_MAIN, TaskForecast.Status.RUN, "not locked yet (run `jk build`)", null));
            return new TaskForecast.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
        }
        // Digest-only staleness — the same predicate the build's freshen uses, so the
        // forecast and the live build agree on whether a lock update runs.
        if (AutoLock.isStale(dir, lockFile)) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_MAIN, TaskForecast.Status.RUN, "jk.toml changed — lock update needed", null));
            return new TaskForecast.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
        }
        try {
            Prepared prepared = prepare();
            compileMain(prepared);
            compileKotlin(prepared);
            compileGroovy(prepared);
            compileTest(prepared);
            guard(prepared);
            resource(prepared);
            packageJar(prepared);
            packageAssembly(prepared);
            nativeImage(prepared);
            writeImage(prepared);
            cacheInstall(prepared);
            emit(prepared);
            restore(prepared);
            orderAfter(prepared);
        } catch (Exception e) {
            // Degrade gracefully — never crash explain over one unparseable module.
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_MAIN,
                    TaskForecast.Status.RUN,
                    "could not predict (" + e.getClass().getSimpleName() + ")",
                    null));
        }
        return new TaskForecast.Module(u.dir(), u.coord(), steps, sourceCount, testCount, producesJar, producesImage);
    }

    private Prepared prepare() throws Exception {
        Lockfile lock = LockfileReader.read(lockFile);
        boolean compact = CompileSupport.isSimpleLayout(project.project(), dir);
        BuildLayout layout = BuildLayout.of(dir, project);
        int release = project.project().javaRelease();
        // ActionKey.forJavac hashes the project JDK, so the forecast has to resolve the same
        // one the build will compile with. Never installs: a forecast that could
        // download a JDK is not read-only, and an unresolvable JDK throws out of this block
        // and is reported as a step that will run, which is the pessimistic answer.
        Path javaHome = TaskForecaster.forecastJavaHome(dir, project, lock);
        // Same contributed-args evaluation as the real compile step, against the same
        // lock — forecast action keys must match the keys the build will actually use.
        List<String> javacArgs = JavacLint.effectiveArgs(
                project.build().lint(),
                PluginContributions.javacArgs(project, dir, PlannerSupport.lockModules(lock)),
                List.of());
        // Must mirror BuildPlanner' processor classpath exactly — workspace siblings
        // included — or the forecast hashes a different -processorpath than the
        // build and every KSP module forecasts a phantom rebuild.
        List<Path> processorCp = PlannerSupport.processorClasspath(
                lock, resolver, WorkspaceClasspath.resolve(dir, project, Set.of(Scope.PROCESSOR)));

        // Only compile-scope dirty siblings force main recompile (and package/native cascade).
        // Test-only siblings (cli's jk-engine test-dep) leave main clean.
        compileDirty = compileDepDirty || force;
        // The CURRENT compile-main action key when the content predictor ran — post-clean
        // reconstruction must resolve the record for this key, never lastFor (the last
        // record may belong to a different edit of the sources; see the revert scenario in
        // TaskForecasterCleanPackageTest).
        compileMainKey = null;

        // ---- compile-main (Java) ----
        // Resolved once: the declarations decide both the generated source roots compile-main
        // folds in and, at package time below, whether jk packs the jar or a plugin does.
        PackagingKeys.@Nullable Owner plugin = PackagingKeys.pluginFor(project, layout, cache);
        PluginBuild.@Nullable Declarations pkgDecls = plugin == null ? null : plugin.decls();
        Path mainSrcDir = compact ? dir.resolve("src") : dir.resolve("src/main/java");
        InputTrees.coverModule(dir);
        // The source set the build compiles, derived by its owner: the src walk plus the
        // [build] extra-src overlay, plugin source roots, every .scala (one Zinc session
        // compiles both languages) and the generated roots. Walking src/main/java alone keyed
        // a request the build never makes, so every Scala module and every extra-src module
        // forecast a rebuild that was not due.
        List<Path> mainSrc = PlannerCompile.mainJavaSources(
                PlannerCompile.javaAndScalaSources(
                        project, dir, compact, CompileSupport.collectJavaSources(mainSrcDir)),
                layout,
                pkgDecls);
        // Collected early: mixed-language modules fold the sibling compiler's outputs into the
        // compile-main stamp inputs (shared recipe below); the kotlin/groovy sections reuse
        // them. Owner-derived so extra-src and contributed roots gate and stamp like the build.
        List<Path> ktSrc = PlannerCompile.mainKotlinSources(project, dir, compact);
        List<Path> gvSrc = PlannerCompile.mainGroovySources(project, dir, compact);
        // The same predicate BuildPlanner composes the plan from — a source-list emptiness
        // test is a different question and answers differently for a Scala module.
        var langs = CompileSupport.resolveLanguages(project.project(), dir);
        boolean mixedKotlin = (langs.java() || langs.scala()) && langs.kotlin();
        boolean mixedGroovy = (langs.java() || langs.scala()) && langs.groovy();
        return new Prepared(
                lock,
                compact,
                layout,
                release,
                javaHome,
                javacArgs,
                processorCp,
                plugin,
                pkgDecls,
                mainSrcDir,
                mainSrc,
                ktSrc,
                gvSrc,
                mixedKotlin,
                mixedGroovy);
    }

    private void compileMain(Prepared prepared) throws Exception {
        Lockfile lock = prepared.lock();
        BuildLayout layout = prepared.layout();
        int release = prepared.release();
        Path javaHome = prepared.javaHome();
        List<String> javacArgs = prepared.javacArgs();
        List<Path> processorCp = prepared.processorCp();
        List<Path> mainSrc = prepared.mainSrc();
        boolean mixedKotlin = prepared.mixedKotlin();
        boolean mixedGroovy = prepared.mixedGroovy();
        if (!mainSrc.isEmpty()) {
            WorkspaceClasspath.Result sib = WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN));
            List<Path> cp = PlannerSupport.mainCompileClasspath(lock, resolver, sib);
            Path out = layout.classesDir();
            // Same stamp gate as BuildPlanner compile-main: a post-rebuild tree with a
            // fresh.jstamp is cached even when action-cache keys were not rewritten
            // (a --rebuild skips the store). The input recipe is SHARED with the live check
            // and write-stamp, so a mixed module hashes the same inputs here and stamp-matches.
            Path groovyJar = null;
            boolean groovyJarUnavailable = false;
            if (mixedGroovy) {
                try {
                    String groovyVersion = CompileToolchain.groovyVersionFor(lock, project);
                    var repos = RepoGroupBuilder.buildFor(project, null, cas);
                    groovyJar =
                            GroovyPluginSetup.prepare(repos, cas, groovyVersion).groovyJar();
                } catch (Exception e) {
                    // Cannot reproduce the live stamp inputs without the jar — fall through to
                    // the action-cache prediction rather than guessing.
                    groovyJarUnavailable = true;
                }
            }
            // The Scala toolchain is a compile-main input on both sides: its stdlib jars are
            // freshness-stamp inputs and its version and compiler closure are hashed
            // by ActionKey.forJavac. Resolving it here is what stops a Scala module keying a
            // request with no Scala in it at all.
            ScalaCompile.Setup scalaSetup =
                    mainSrc.stream().anyMatch(pth -> pth.toString().endsWith(".scala"))
                            ? ScalaCompile.prepare(project, lock, cas)
                            : null;
            List<Path> stampInputs = PlannerCompile.mainStampInputs(
                    cp, processorCp, mixedKotlin, mixedGroovy, layout, groovyJar, scalaSetup);
            boolean stampFresh = false;
            if (!compileDepDirty && !force && !groovyJarUnavailable) {
                try {
                    stampFresh = FreshnessStamp.isFresh(out, BuildStamps.JAVA, mainSrc, stampInputs, release);
                } catch (IOException ignored) {
                    stampFresh = false;
                }
            }
            if (stampFresh) {
                steps.add(new TaskForecast.Task(TaskNames.COMPILE_MAIN, TaskForecast.Status.CACHED, "", null));
            } else {
                CompileRequest req = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                        mainSrc,
                        cp,
                        processorCp,
                        layout,
                        out,
                        release,
                        javacArgs,
                        javaHome,
                        mixedKotlin,
                        mixedGroovy,
                        groovyJar,
                        scalaSetup));
                String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, out);
                Path actions = CacheTree.ACTIONS.under(cache);
                Path stateDir = ActionTree.INCREMENTAL_JAVA.under(actions).resolve(taskId);
                long tc = Perf.start();
                var pred = JavaCompile.predict(
                        taskId,
                        req,
                        BuildIdentity.cacheKeyVersion(),
                        actionCache,
                        stateDir,
                        workerJar,
                        layout.generatedSourcesDir("annotations"));
                Perf.end("  predict-compile-main", tc);
                compileMainKey = pred.actionKey();
                steps.add(TaskForecaster.compileStep(TaskNames.COMPILE_MAIN, pred, compileDepDirty || force));
                if (!steps.get(steps.size() - 1).cached()) compileDirty = true;
            }
        }
    }

    private void compileKotlin(Prepared prepared) throws Exception {
        BuildLayout layout = prepared.layout();
        List<Path> ktSrc = prepared.ktSrc();
        // ---- compile-kotlin (freshness stamp; post-clean uses last action record) ----
        if (!ktSrc.isEmpty()) {
            // The stamp lives with the MERGED classes (BuildPlanner writes it to
            // MAIN_CLASSES), not in kotlinc's incremental workspace under target/kotlin/main.
            // Reading the wrong directory never found a stamp, so every Kotlin module
            // forecast a full compile no matter how cached the build actually was.
            boolean fresh = !compileDepDirty
                    && !force
                    && FreshnessStamp.looksFresh(layout.classesDir(), BuildStamps.KOTLIN, ktSrc);
            // After jk clean the stamp is gone with target/, but the action-cache pointer
            // under tasks/ survives. lastFor+present ⇒ live kotlinc will restore — do not
            // price FULL (never-built modules have no pointer and stay FULL).
            boolean restoreHit = !compileDepDirty
                    && !force
                    && !TaskForecaster.classesDirHasContent(layout.classesDir())
                    && TaskForecaster.stampLangActionPresent(
                            actionCache, ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, layout.classesDir()));
            if (fresh || restoreHit) {
                steps.add(new TaskForecast.Task(TaskNames.COMPILE_KOTLIN, TaskForecast.Status.CACHED, "", null));
            } else {
                steps.add(new TaskForecast.Task(
                        TaskNames.COMPILE_KOTLIN,
                        TaskForecast.Status.FULL,
                        "full compile · " + TaskForecaster.count(ktSrc.size(), "source"),
                        null));
                compileDirty = true;
            }
        }
    }

    private void compileGroovy(Prepared prepared) throws Exception {
        BuildLayout layout = prepared.layout();
        List<Path> mainSrc = prepared.mainSrc();
        List<Path> ktSrc = prepared.ktSrc();
        List<Path> gvSrc = prepared.gvSrc();
        // ---- compile-groovy (stamp + post-clean restore, same as Kotlin) ----
        // The groovy stamp lives in the merged classes dir (where write-stamp-groovy
        // writes it), unlike Kotlin's forecast probe of kotlinClassesDir.
        if (!gvSrc.isEmpty()) {
            boolean fresh = !compileDepDirty
                    && !force
                    && FreshnessStamp.looksFresh(layout.classesDir(), BuildStamps.GROOVY, gvSrc);
            boolean restoreHit = !compileDepDirty
                    && !force
                    && !TaskForecaster.classesDirHasContent(layout.classesDir())
                    && TaskForecaster.stampLangActionPresent(
                            actionCache, ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, layout.classesDir()));
            if (fresh || restoreHit) {
                steps.add(new TaskForecast.Task(TaskNames.COMPILE_GROOVY, TaskForecast.Status.CACHED, "", null));
            } else {
                steps.add(new TaskForecast.Task(
                        TaskNames.COMPILE_GROOVY,
                        TaskForecast.Status.FULL,
                        "full compile · " + TaskForecaster.count(gvSrc.size(), "source"),
                        null));
                compileDirty = true;
            }
        }

        producesJar = !mainSrc.isEmpty() || !ktSrc.isEmpty() || !gvSrc.isEmpty();
        try {
            var img = JkBuildParser.imageConfig(dir.resolve(ManifestPaths.MANIFEST));
            producesImage = img.base() != null || img.registry() != null;
        } catch (Exception ignored) {
        }
    }

    private void compileTest(Prepared prepared) throws Exception {
        Lockfile lock = prepared.lock();
        boolean compact = prepared.compact();
        BuildLayout layout = prepared.layout();
        int release = prepared.release();
        Path javaHome = prepared.javaHome();
        List<String> javacArgs = prepared.javacArgs();
        List<Path> processorCp = prepared.processorCp();
        List<Path> mainSrc = prepared.mainSrc();
        List<Path> ktSrc = prepared.ktSrc();
        List<Path> gvSrc = prepared.gvSrc();
        // ---- compile-test (the suites this session selected) ----
        // The same source lists the build derives, for the selection it compiles — not every suite
        // on disk, which would forecast a phantom compile-test on each default build of a
        // multi-suite module.
        try {
            testSources = PlannerTest.TestSources.collect(
                    project,
                    dir,
                    compact,
                    TestSupport.selectedSuites(
                            dir, compact, SessionContext.current().testSelection()));
        } catch (IOException ignored) {
            // forecast degrades
        }
        haveTests = !testSources.isEmpty();
        sourceCount = mainSrc.size()
                + ktSrc.size()
                + gvSrc.size()
                + testSources.all().size()
                + PlannerFixtures.forecastSources(project, dir).size();
        testDirty = false;
        // --skip-tests composes no compile-test/run-tests steps, so don't forecast
        // (or content-hash the inputs of) steps the build will not run.
        testCompileCp = PlannerSupport.testCompileClasspath(dir, project, lock, resolver);
        testDirty = PlannerGuardSuite.addSuiteForecasts(
                steps,
                skipTests,
                compileDirty,
                project,
                dir,
                compact,
                layout,
                processorCp,
                release,
                javacArgs,
                javaHome,
                cache,
                actionCache,
                workerJar,
                testCompileCp,
                cas);
    }

    private void guard(Prepared prepared) throws Exception {
        BuildLayout layout = prepared.layout();
        // ---- guard (module lane): stale verdict → RUN, which is what makes the module dirty ----
        GuardKeys.forecastModuleLane(dir, layout, actionCache, force || compileDirty)
                .ifPresent(steps::add);
        if (PlannerResources.invocationRoot(dir)) steps.addAll(GuardKeys.forecastRootLanes(dir, project, actionCache));

        if (haveTests && !skipTests) {
            compileTestStep(prepared);
            runTestsStep(prepared);
        }
    }

    private void compileTestStep(Prepared prepared) throws Exception {
        Lockfile lock = prepared.lock();
        BuildLayout layout = prepared.layout();
        int release = prepared.release();
        Path javaHome = prepared.javaHome();
        List<String> javacArgs = prepared.javacArgs();
        List<Path> processorCp = prepared.processorCp();
        if (compileDirty) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_TEST, TaskForecast.Status.RUN, "recompile · main changed", null));
            testDirty = true;
        } else if (!testSources.javacSources().isEmpty()) {
            List<Path> baseCp = new ArrayList<>();
            baseCp.add(layout.classesDir());
            baseCp.addAll(testCompileCp);
            baseCp = PlannerFixtures.withOwnFixtures(project, layout, baseCp);
            Path testOut = layout.testClassesDir();
            ScalaCompile.Setup testScala =
                    testSources.scTest().isEmpty() ? null : ScalaCompile.prepare(project, lock, cas);
            List<Path> testSrc = testSources.javacSources();
            CompileRequest req = PlannerCompile.testCompileRequest(new PlannerCompile.TestCompile(
                    testSrc, baseCp, processorCp, testOut, release, javacArgs, javaHome, testScala));
            String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST, testOut);
            Path actions = CacheTree.ACTIONS.under(cache);
            Path stateDir = ActionTree.INCREMENTAL_JAVA.under(actions).resolve(taskId);
            long tt = Perf.start();
            var pred = JavaCompile.predict(
                    taskId,
                    req,
                    BuildIdentity.cacheKeyVersion(),
                    actionCache,
                    stateDir,
                    workerJar,
                    layout.generatedSourcesDir("annotations", "test"));
            Perf.end("  predict-compile-test", tt);
            if (Perf.ENABLED) {
                System.err.println("[jk-perf] forecast-compile-test " + dir
                        + " key=" + pred.actionKey() + " outcome=" + pred.outcome() + " reason=" + pred.reason()
                        + " cp=" + baseCp.size() + " src=" + testSrc.size()
                        + " pp=" + processorCp.size() + " release=" + release
                        + " javaHome=" + javaHome + " out=" + testOut);
            }
            TaskForecast.Task p = TaskForecaster.compileStep(TaskNames.COMPILE_TEST, pred, false);
            steps.add(p);
            if (!p.cached()) testDirty = true;
        } else {
            // Kotlin/Groovy-only tests: no content predictor — assume fresh when main is clean.
            steps.add(new TaskForecast.Task(TaskNames.COMPILE_TEST, TaskForecast.Status.CACHED, "", null));
        }
    }

    private void runTestsStep(Prepared prepared) throws Exception {
        Lockfile lock = prepared.lock();
        boolean compact = prepared.compact();
        BuildLayout layout = prepared.layout();
        // ---- run-tests ----
        int estimated = TestSupport.estimateAllSuiteTestCount(dir, compact);
        testCount = estimated;
        String tests = estimated > 0 ? "~" + TaskForecaster.count(estimated, "test") : "tests";
        // testDepDirty: sibling on test classpath is rebuilding — suite must re-run even
        // when main compile stays cached (cli ← engine test-dep dogfood).
        if (compileDirty || testDirty || testDepDirty) {
            steps.add(
                    new TaskForecast.Task(TaskNames.RUN_TESTS, TaskForecast.Status.RUN, "run tests · " + tests, null));
        } else {
            // Same factory as live run-tests default selection sources +
            // worker/engine jar extras (nested-engine CLI included) so the key matches the
            // stored green marker. After jk clean, project the main: fingerprint from the
            // compile action record — ClasspathFingerprint.entry(empty classes) is
            // missing:… and would falsely forecast a full suite.
            List<Path> testRt = PlannerSupport.testRuntimeClasspath(dir, project, lock, resolver);
            long ts = Perf.start();
            String mainFp = null;
            if (!TaskForecaster.classesDirHasContent(layout.classesDir())) {
                // Resource-drift flag is computed later; empty classes uses compile
                // outputs + resource roots (same merge as package post-clean).
                mainFp = PackagingKeys.classesTokenForPackage(
                        dir, compact, layout, project, actionCache, compileMainKey, null);
                if (mainFp != null && mainFp.startsWith("missing:")) mainFp = null;
            }
            String stampKey = PlannerSupport.runTestsStampKey(
                    dir, project, compact, layout.classesDir(), mainFp, lockFile, testRt);
            Perf.end("  test-stamp-key", ts);
            Optional<ActionCache.ActionRecord> marker =
                    stampKey == null ? Optional.empty() : TaskForecaster.presentRecord(actionCache, stampKey);
            boolean hit = marker.isPresent() && TestStamp.green(marker.get());
            // The same key with a red record is the one shape a live run never skips:
            // say so, or the ETA reads "only the suite is dirty" as stamp drift.
            boolean red = marker.isPresent() && !hit;
            if (Perf.ENABLED) {
                System.err.println(
                        "[jk-perf] forecast-test-stamp " + dir + " key=" + stampKey + " hit=" + hit + " red=" + red);
            }
            steps.add(
                    hit
                            ? new TaskForecast.Task(TaskNames.RUN_TESTS, TaskForecast.Status.CACHED, "· " + tests, null)
                            : new TaskForecast.Task(
                                    TaskNames.RUN_TESTS,
                                    TaskForecast.Status.RUN,
                                    "run tests · " + tests + (red ? " · " + TaskForecast.LAST_RUN_FAILED : ""),
                                    null));
        }
    }

    private void resource(Prepared prepared) throws Exception {
        boolean compact = prepared.compact();
        BuildLayout layout = prepared.layout();
        // ---- resource drift (before package so the jar key projects post-copy content) ----
        // Live package-jar fingerprints classes *after* copy-resources. Forecasting package
        // against a stale on-disk tree leaves package CACHED while copy-resources is RUN, then
        // either under-cascades (jar will change) or — with copy-resources seeding cascade —
        // over-cascades every compile consumer. Detect drift first; package uses a projected
        // post-copy token when drift is present.
        // Walk each tree at most ONCE per forecast and carry the flags to every consumer
        // (emit block, package/assembly tokens): the emit-time and token-time re-walks both
        // re-read the filesystem and could disagree with this detection when the tree changed
        // in between — a copy-resources step for a tree that no longer drifts, with the
        // package token projected from yet another read.
        mainResourceDrift = false;
        testResourceDrift = false;
        knownResourceDrift = null;
        if (!compileDirty && Files.isDirectory(layout.classesDir())) {
            mainResourceDrift = TaskForecaster.mainResourcesOutOfSync(dir, compact, layout.classesDir());
            knownResourceDrift = mainResourceDrift;
            if (haveTests && !skipTests && !testDirty && Files.isDirectory(layout.testClassesDir())) {
                Path resTest = ModuleLayout.testResourcesDir(dir, compact);
                if (TaskForecaster.resourcesOutOfSync(resTest, layout.testClassesDir())) {
                    testResourceDrift = true;
                }
            }
        }
    }

    private void packageJar(Prepared prepared) throws Exception {
        Lockfile lock = prepared.lock();
        boolean compact = prepared.compact();
        BuildLayout layout = prepared.layout();
        Path javaHome = prepared.javaHome();
        PackagingKeys.@Nullable Owner plugin = prepared.plugin();
        PluginBuild.@Nullable Declarations pkgDecls = prepared.pkgDecls();
        List<Path> mainSrc = prepared.mainSrc();
        List<Path> ktSrc = prepared.ktSrc();
        List<Path> gvSrc = prepared.gvSrc();
        // ---- package-jar ----
        // Tokens MUST match BuildPlanner.packageJarStep (classes/main/sbom/manifest).
        // After jk clean the classes tree is gone: reconstruct the classes: token from the
        // compile action record + resource roots (same merge the live build produces) so we
        // still hit the packaging action cache instead of forecasting perpetual "repackage".
        if (mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty()) {
            // Source-less registered module: the live build still runs package-jar and
            // produces an (empty) jar that sibling classpaths demand. Forecasting "nothing
            // to package" left the module unscheduled forever while consumers failed with
            // "sibling not built" — schedule it until its jar exists.
            if (!Files.isRegularFile(layout.mainJar())) {
                steps.add(new TaskForecast.Task(
                        TaskNames.PACKAGE_JAR, TaskForecast.Status.RUN, "package · module has no sources", null));
            }
        } else if (compileDirty) {
            steps.add(new TaskForecast.Task(
                    TaskNames.PACKAGE_JAR, TaskForecast.Status.RUN, "repackage · compile changed", null));
        } else if (PackagingKeys.ownsPackaging(plugin)) {
            // Packaging owned by a plugin (spring-boot, grails, quarkus, minified, android).
            // The build runs the packager, not JarPackager, under a token bag that has nothing
            // in common with the plain jar's — so forecasting the plain-jar key here described
            // a step the build never runs and reported "repackage" forever. The key
            // comes from the same body the build calls; anything that stops us reproducing it
            // (an unfetchable packager tool, an untrusted worker) forecasts RUN, never a hit.
            steps.add(PackagingKeys.pluginPackagerStep(
                    project, dir, layout, cache, lockFile, cas, javaHome, plugin, actionCache));
        } else {
            Path jar = layout.mainJar();
            String mainClass = PackagingKeys.mainClass(dir, project);
            long tp = Perf.start();
            byte[] sbom = null;
            if (project.isApplication()) {
                try {
                    sbom = PlannerPlugin.applicationSbom(project, lock, cas);
                } catch (Exception ignored) {
                    // best-effort: missing SBOM → key still includes empty sbom: like a null sbom
                }
            }
            // classesTokenForPackage projects post-copy content when resources drifted so
            // package CACHED/RUN matches the live step after copy-resources.
            String classesTok = PackagingKeys.classesTokenForPackage(
                    dir, compact, layout, project, actionCache, compileMainKey, knownResourceDrift);
            // Must match BuildPlanner.packageJarStep tokens exactly — omitting contrib: made
            // every module forecast permanent "repackage", cascade depDirty, and price a full
            // monorepo rebuild (~3.5m) while live builds hit the package cache and SKIPPED.
            List<Path> contributed = new ArrayList<>(PlannerSupport.existingContributedDirs(pkgDecls, layout));
            contributed.addAll(PlannerSupport.workerCodecClassDirs(dir, project));
            String contribTok = PlannerSupport.contributionsToken(contributed);
            List<String> tokens = List.of(
                    "classes:" + classesTok,
                    "contrib:" + contribTok,
                    "main:" + (mainClass == null ? "" : mainClass),
                    "sbom:" + (sbom == null ? "" : Hashing.sha256Hex(sbom)),
                    "manifest:" + project.manifest());
            Perf.end("  package-fingerprint", tp);
            String pkgKey = ActionKey.forArtifact(
                    ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, jar), BuildIdentity.cacheKeyVersion(), tokens);
            boolean hit = TaskForecaster.present(actionCache, pkgKey);
            steps.add(
                    hit
                            ? new TaskForecast.Task(
                                    TaskNames.PACKAGE_JAR, TaskForecast.Status.CACHED, "", TaskForecaster.key8(pkgKey))
                            : new TaskForecast.Task(
                                    TaskNames.PACKAGE_JAR,
                                    TaskForecast.Status.RUN,
                                    mainResourceDrift ? "repackage · resources changed" : "repackage",
                                    null));
            if (hit && !Files.isRegularFile(jar)) {
                // Publish the wiped jar's content sha from THIS key's record so downstream
                // assembly forecasts fingerprint the same bytes the live restore produces.
                actionCache.lookup(pkgKey).ifPresent(rec -> rec.outputs().entrySet().stream()
                        .filter(e -> e.getKey().endsWith(jar.getFileName().toString()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .or(() -> rec.outputs().values().stream().findFirst())
                        .ifPresent(
                                sha -> restoredJarShas.put(jar.toAbsolutePath().normalize(), sha)));
            }
        }
    }

    private void packageAssembly(Prepared prepared) throws Exception {
        BuildLayout layout = prepared.layout();
        List<Path> mainSrc = prepared.mainSrc();
        List<Path> ktSrc = prepared.ktSrc();
        List<Path> gvSrc = prepared.gvSrc();
        // ---- package-assembly (fat jar) — only when configured ----
        // Same action-key recipe as PlannerTails.assemblyStep (not "jar exists on disk").
        if (project.assembly() && !(mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty())) {
            if (compileDirty) {
                steps.add(new TaskForecast.Task(
                        TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.RUN, "repackage · compile changed", null));
            } else {
                boolean hit = PackagingKeys.assemblyActionCached(
                        dir,
                        project,
                        layout,
                        lockFile,
                        actionCache,
                        cache,
                        compileMainKey,
                        restoredJarShas,
                        knownResourceDrift);
                steps.add(
                        hit
                                ? new TaskForecast.Task(
                                        TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.CACHED, "", null)
                                : new TaskForecast.Task(
                                        TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.RUN, "repackage", null));
            }
        }
    }

    private void nativeImage(Prepared prepared) throws Exception {
        BuildLayout layout = prepared.layout();
        List<Path> mainSrc = prepared.mainSrc();
        List<Path> ktSrc = prepared.ktSrc();
        List<Path> gvSrc = prepared.gvSrc();
        // ---- native-image — [native] enabled = "always" (same opt-in as jk build) ----
        // Hard cascade: jar dirty ⇒ native dirty. Never forecast package-jar RUN +
        // native-image CACHED (binary mtime vs pre-build jar is not an independent skip).
        nativeOnBuild = project.nativeMode() == JkBuild.NativeMode.ALWAYS;
        // Membership in the resolved terminal set — NOT project.nativeImage(). Re-deriving
        // eligibility from the [native] table made fallback (table-less unique-main) modules
        // invisible (jar clean + binary missing ⇒ skipped ⇒ "success" with no binary) and
        // priced unselected cone prereqs WITH tables as perpetually dirty (their plans get
        // allowNative=false, so the binary they were dirty "for" never appears).
        nativeOnNativeCmd = target == WorkspaceTarget.NATIVE && terminalDirs.contains(dir);
        if ((nativeOnBuild || nativeOnNativeCmd) && !(mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty())) {
            boolean jarDirty = steps.stream().anyMatch(s -> TaskNames.PACKAGE_JAR.equals(s.name()) && !s.cached());
            Path nativeOut = layout.nativeBinary();
            boolean binaryPresent = Files.isRegularFile(nativeOut) || Files.isRegularFile(layout.nativeLibrary());
            // Missing binary after wipe: action-cache hit ⇒ restore (CACHED), not a FULL
            // native wall. lastFor tags the binary path (see PlannerNative).
            boolean nativeRestoreHit = !binaryPresent
                    && !jarDirty
                    && !compileDirty
                    && TaskForecaster.stampLangActionPresent(
                            actionCache, ActionKey.qualifiedTaskId(TaskNames.NATIVE_IMAGE, nativeOut));
            if (jarDirty || compileDirty || (!binaryPresent && !nativeRestoreHit)) {
                String why = jarDirty || compileDirty ? "rebuild · compile changed" : TaskNames.NATIVE_IMAGE;
                steps.add(new TaskForecast.Task(TaskNames.NATIVE_IMAGE, TaskForecast.Status.RUN, why, null));
            } else {
                steps.add(new TaskForecast.Task(TaskNames.NATIVE_IMAGE, TaskForecast.Status.CACHED, "", null));
            }
        }
    }

    private void writeImage(Prepared prepared) throws Exception {
        // ---- write-image — jk image terminal on the selected module(s) ----
        // ImagePlans' contract: a registry push/docker load/tarball write is a side-effect,
        // never a cacheable output — an up-to-date module still runs its image tail. Without
        // this step a clean workspace member forecast "not dirty", was never scheduled, and
        // jk image reported success having pushed nothing.
        if (target == WorkspaceTarget.IMAGE && terminalDirs.contains(dir)) {
            steps.add(new TaskForecast.Task(TaskNames.WRITE_IMAGE, TaskForecast.Status.RUN, "image side-effect", null));
        }
    }

    private void cacheInstall(Prepared prepared) throws Exception {
        // ---- cache-install — jk install terminal. Skip when repos/jk-local already has this
        // jar (matching SHA) and its POM. A packaged-but-never-installed module still runs.
        if (target == WorkspaceTarget.INSTALL && terminalDirs.contains(dir)) {
            boolean jarDirty = steps.stream().anyMatch(s -> TaskNames.PACKAGE_JAR.equals(s.name()) && !s.cached());
            boolean skip = !jarDirty && InstallPlans.alreadyInstalled(project, BuildLayout.of(dir, project), cache);
            steps.add(new TaskForecast.Task(
                    TaskNames.CACHE_INSTALL,
                    skip ? TaskForecast.Status.CACHED : TaskForecast.Status.RUN,
                    skip ? "" : "install to local repo",
                    null));
        }
    }

    private void emit(Prepared prepared) throws Exception {
        // ---- emit resource-drift steps (detected before package) ----
        // Main resource drift schedules the module so the jar ships fresh bytes.
        // Cascade to compile consumers is owned by package-jar above, not by these steps.
        if (mainResourceDrift) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COPY_RESOURCES, TaskForecast.Status.RUN, "resources changed", null));
        }
        if (testResourceDrift) {
            // Distinct name: test-resource drift schedules the module (material) but
            // must not seed the compile-consumer cascade like main-resource drift.
            steps.add(new TaskForecast.Task(
                    TaskNames.COPY_TEST_RESOURCES, TaskForecast.Status.RUN, "test resources changed", null));
        }
    }

    private void restore(Prepared prepared) throws Exception {
        boolean compact = prepared.compact();
        BuildLayout layout = prepared.layout();
        // ---- restore gate ----
        // Right after jk clean every step can predict CACHED (action keys survive the wipe
        // by design), but a clean-forecast module is never scheduled, so nothing restores
        // target/ and workspace links dangle. When the promised outputs are absent, add a
        // restore step: the module schedules and its steps resolve as cheap cache restores.
        // The name is deliberately not compile-*/package-jar so it never seeds downstream
        // dirtiness — restored outputs are byte-identical to what consumers hashed.
        if (steps.stream().allMatch(TaskForecast.Task::cached)) {
            boolean outputsAbsent = false;
            if (producesJar) {
                outputsAbsent = !Files.isRegularFile(layout.mainJar())
                        || !TaskForecaster.classesDirHasContent(layout.classesDir())
                        || (project.assembly() && !Files.isRegularFile(layout.assemblyJar()));
            } else if (!PackagingKeys.packageResourceRoots(dir, compact).isEmpty()) {
                // Resources-only module: its classes tree (copied resources) is consumed
                // straight off sibling classpaths, so an empty tree is a missing output too.
                outputsAbsent = !TaskForecaster.classesDirHasContent(layout.classesDir());
            }
            if (outputsAbsent) {
                steps.add(new TaskForecast.Task(
                        TaskNames.RESTORE_OUTPUTS, TaskForecast.Status.RUN, "restore from cache", null));
            }
        }
    }

    private void orderAfter(Prepared prepared) throws Exception {
        PackagingKeys.@Nullable Owner plugin = prepared.plugin();
        // ---- order-after gate ----
        // A dirty order-after-only prereq prices nothing, but the module must still schedule:
        // its real action keys are what re-check the prereq's out-of-band outputs (e.g. a
        // rebuilt test-plugin jar hashed by the run-tests stamp). Unchanged inputs resolve as
        // cheap cache hits at execute.
        if (dep.orderDepDirty() && steps.stream().allMatch(TaskForecast.Task::cached)) {
            steps.add(new TaskForecast.Task(
                    TaskNames.ORDER_CHECK, TaskForecast.Status.RUN, "ordered-after sibling rebuilding", null));
        }
    }
}
