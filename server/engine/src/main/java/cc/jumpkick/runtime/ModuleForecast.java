// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
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
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.GroovyPluginSetup;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.SourceApiIndex;
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

    /**
     * What the walk knows the build restores before a consumer keys on it: wiped sibling trees by
     * the token and identity of the tree that comes back, wiped jars by their payload sha. Every
     * arm that keys on a sibling reads through it; this module publishes its own trees and jar
     * here once their records are known.
     */
    private final RestoredOutputs restored;

    private final Map<Path, TaskForecaster.ModuleHint> hints;
    private final WorkspaceTarget target;
    private final Set<Path> terminalDirs;
    private final @Nullable Path workerJar;

    private boolean compileDepDirty;
    private boolean compileDirty;

    /**
     * True when the module's compile is dirty only because a compile-scope sibling is rebuilding —
     * its own key hit — so the package and test steps that follow are dirty for the sibling's jar
     * bytes, not for anything of this module's, and say so.
     */
    private boolean depOnlyDirty;

    /** What this module's dirty compile-scope dependencies' edits look like, for its compile texts. */
    private TaskForecaster.DepHint depHint = TaskForecaster.DepHint.NONE;

    /** What this module's own edit looks like to its consumers; published into {@link #hints}. */
    private SourceApiIndex.Hint ownHint = SourceApiIndex.Hint.UNKNOWN;

    private @Nullable String compileMainKey;

    /** The keys the stamp-language arms priced by, when they priced by key; they project the merged tree. */
    private @Nullable String compileKotlinKey, compileGroovyKey;

    private @Nullable String compileTestKey;
    private @Nullable String compileTestKotlinKey;
    private @Nullable String compileTestGroovyKey;
    private final Path dir;
    private boolean haveTests;
    private @Nullable Boolean knownResourceDrift;
    private final Path lockFile;
    private boolean mainResourceDrift;
    /** {@code [build-info]} would rewrite its files: the checkout moved since the classes tree was written. */
    private boolean buildInfoDrift;
    /** The compile-main classpath, once {@link #compileMain} has derived it; the javadoc arm keys on it. */
    private List<Path> mainCp = List.of();

    private boolean nativeOnBuild;
    private boolean nativeOnNativeCmd;

    /** The native binary this build must bring back: absent on disk while its step forecasts a restore. */
    private boolean nativeBinaryToRestore;

    private boolean producesImage;
    private boolean producesJar;
    private final JkBuild project;
    private int sourceCount;
    private List<TaskForecast.Task> steps = new ArrayList<>();
    private List<Path> testCompileCp = List.of();
    private final @Nullable String profileName;
    private final @Nullable Path m2Dir;
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
            RestoredOutputs restored,
            Map<Path, TaskForecaster.ModuleHint> hints,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            @Nullable Path workerJar,
            @Nullable String profileName,
            @Nullable Path m2Dir) {
        this.u = u;
        this.dep = dep;
        this.force = force;
        this.skipTests = skipTests;
        this.cas = cas;
        this.resolver = resolver;
        this.actionCache = actionCache;
        this.cache = cache;
        this.restored = restored;
        this.hints = hints;
        this.target = target;
        this.terminalDirs = terminalDirs;
        this.workerJar = workerJar;
        this.profileName = profileName;
        this.m2Dir = m2Dir;
        this.project = u.manifest();
        this.dir = u.dir();
        this.lockFile = LockPaths.lockFile(dir);
    }

    /** Resolved once before the phases run; never rewritten. */
    record Prepared(
            Lockfile lock,
            boolean compact,
            BuildLayout layout,
            int release,
            Path javaHome,
            List<String> javacArgs,
            List<Path> processorCp,
            ActivePlugins.@Nullable Declared plugin,
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
        depHint = TaskForecaster.depHint(dep.compileDeps(), hints);
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
            // kotlinc runs ahead of javac in a mixed module and javac reads its output: the
            // Kotlin arm goes first so that tree is published before compile-main keys on it.
            if (prepared.mixedKotlin()) compileKotlin(prepared);
            compileMain(prepared);
            if (!prepared.mixedKotlin()) compileKotlin(prepared);
            compileGroovy(prepared);
            projectOwnOutputs(prepared);
            compileTest(prepared);
            guard(prepared);
            resource(prepared);
            packageJar(prepared);
            packageTails(prepared);
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
            ownHint = SourceApiIndex.Hint.UNKNOWN;
        }
        hints.put(
                dir.toAbsolutePath().normalize(),
                new TaskForecaster.ModuleHint(project.project().name(), ownHint));
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
        // The build's own derivation, against the same lock and the same --profile: forecast
        // action keys must match the keys the build will actually use, and a profile whose
        // javac args differ from the default's keys a different compile.
        List<String> javacArgs = PlannerSetup.effectiveJavacArgs(project, dir, lock, profileName);
        // Must mirror BuildPlanner' processor classpath exactly — workspace siblings
        // included — or the forecast hashes a different -processorpath than the
        // build and every KSP module forecasts a phantom rebuild.
        List<Path> processorCp = PlannerSupport.processorClasspath(
                project, lock, resolver, WorkspaceClasspath.resolve(dir, project, Set.of(Scope.PROCESSOR)), false);

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
        ActivePlugins.@Nullable Declared plugin = PackagingKeys.pluginFor(project, layout, cache);
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
        List<Path> ktSrc = PlannerCompile.mainKotlinSourcesWithGenerated(
                PlannerCompile.mainKotlinSources(project, dir, compact), layout, pkgDecls);
        List<Path> gvSrc = PlannerCompile.mainGroovySourcesWithGenerated(
                PlannerCompile.mainGroovySources(project, dir, compact), layout, pkgDecls);
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
            WorkspaceClasspath.Result sib = WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.COMPILE_SCOPES);
            List<Path> cp = PlannerSupport.mainCompileClasspath(project, lock, resolver, sib, false);
            mainCp = cp;
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
            // The same request the live compile keys with: its option digest and its classpath
            // token lines are the stamp's inputs.
            CompileRequest req = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                    mainSrc,
                    cp,
                    processorCp,
                    layout,
                    out,
                    release,
                    javacArgs,
                    project.build().javac(),
                    javaHome,
                    mixedKotlin,
                    mixedGroovy,
                    groovyJar,
                    scalaSetup));
            boolean stampFresh = false;
            if (!compileDepDirty && !force && !groovyJarUnavailable) {
                try {
                    stampFresh = FreshnessStamp.isFresh(
                            out,
                            BuildStamps.JAVA,
                            mainSrc,
                            FreshnessStamp.ClasspathTokens.of(ActionKey.javacClasspathTokens(req, restored.abiToken())),
                            release,
                            ActionKey.javacOptionsDigest(req));
                } catch (IOException ignored) {
                    stampFresh = false;
                }
            }
            if (stampFresh) {
                // The stamp names the compile that produced this tree; that record is what the
                // tree is held against below and what a wiped tree would be reconstructed from.
                compileMainKey =
                        FreshnessStamp.stampedKey(out, BuildStamps.JAVA).orElse(null);
                steps.add(new TaskForecast.Task(TaskNames.COMPILE_MAIN, TaskForecast.Status.CACHED, "", null));
                ownHint = new SourceApiIndex.Hint(SourceApiIndex.Kind.BODY_ONLY, List.of());
            } else {
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
                        layout.generatedSourcesDir("annotations"),
                        WorkerEnv.forModule(project.build().env(), layout.moduleRoot(), layout.moduleTargetDir()),
                        restored.abiToken());
                Perf.end("  predict-compile-main", tc);
                compileMainKey = pred.actionKey();
                steps.add(TaskForecaster.compileStep(
                        TaskNames.COMPILE_MAIN, pred, compileDepDirty || force, req, depHint));
                if (!steps.get(steps.size() - 1).cached()) compileDirty = true;
                // The key hit against the dependency's current output: nothing of this module's
                // own moved, and its package and tests are dirty only for the sibling's jar bytes.
                depOnlyDirty = compileDepDirty && pred.outcome() == JavaCompile.Outcome.CACHE_HIT && !force;
                ownHint = ownApiHint(prepared, pred);
            }
        }
    }

    /**
     * Publish this module's own classes tree for the consumers that follow it in the walk when
     * the tree is not whole on disk but every compile that writes into it is answered by a
     * record: the build restores the tree from those records before any consumer keys on it, so
     * the consumer must key on the restored tree, not on {@code missing:}. The tree is the merge
     * the build assembles — javac's outputs, then each other compiler's copied over them, then the
     * resource roots and a plugin worker's module-root manifest — so a mixed module projects the
     * same tree a Java-only one does. A module with no sources still owns a tree (its copied
     * resources) and projects that. A compile that will run projects nothing: its consumers are
     * dirty on its account already.
     */
    private void projectOwnOutputs(Prepared prepared) {
        if (compileDirty) return;
        try {
            if (classesTreeWhole(prepared.layout())) return;
            List<@Nullable String> keys = new ArrayList<>();
            if (!prepared.mainSrc().isEmpty()) keys.add(compileMainKey);
            if (!prepared.ktSrc().isEmpty()) keys.add(compileKotlinKey);
            if (!prepared.gvSrc().isEmpty()) keys.add(compileGroovyKey);
            restored.projectFromRecords(
                    prepared.layout().classesDir(),
                    keys,
                    PackagingKeys.packageResourceRoots(dir, prepared.compact()),
                    PackagingKeys.copiedPluginManifest(dir));
        } catch (IOException e) {
            // Without the projection a consumer keys on the tree's absence: the pessimistic
            // answer, never a false hit.
            Log.debug("projectOwnOutputs: consumers key on the tree as it is", e);
        }
    }

    /**
     * What this module's edit looks like to its consumers, before it compiles: its own Java sources
     * classified against the declaration baseline its last compile left ({@link SourceApiIndex}),
     * folded with what its own dirty dependencies look like — a constant copied from a dependency
     * whose API moved moves this module's API too. Unknown whenever the answer needs a compile:
     * a forced rebuild, an option or release change, a source the baseline does not describe.
     */
    private SourceApiIndex.Hint ownApiHint(Prepared prepared, JavaCompile.Prediction pred) {
        if (force) return SourceApiIndex.Hint.UNKNOWN;
        if (pred.outcome() == JavaCompile.Outcome.CACHE_HIT) {
            return compileDepDirty
                    ? new SourceApiIndex.Hint(depHint.kind(), List.of())
                    : new SourceApiIndex.Hint(SourceApiIndex.Kind.BODY_ONLY, List.of());
        }
        String reason = pred.reason();
        if (reason.contains("options changed") || reason.contains("release changed")) {
            return SourceApiIndex.Hint.UNKNOWN;
        }
        SourceApiIndex.Hint own;
        try {
            // A processor may shape public output from a private member; without one, private
            // members are invisible to every consumer and their edits are body-only.
            own = SourceApiIndex.classify(
                    dir,
                    SourceApiIndex.load(SourceApiIndex.path(prepared.layout().buildDir())),
                    prepared.mainSrc(),
                    !prepared.processorCp().isEmpty());
        } catch (IOException e) {
            Log.debug("ownApiHint: no baseline", e);
            return SourceApiIndex.Hint.UNKNOWN;
        }
        if (own.kind() == SourceApiIndex.Kind.UNKNOWN) return own;
        if (compileDepDirty && depHint.kind() == SourceApiIndex.Kind.UNKNOWN) return SourceApiIndex.Hint.UNKNOWN;
        if (compileDepDirty && depHint.kind() == SourceApiIndex.Kind.API_CHANGED) {
            return new SourceApiIndex.Hint(SourceApiIndex.Kind.API_CHANGED, own.files());
        }
        return own;
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
            ForecastLangArms arms = arms();
            ForecastLangArms.KotlinArm arm = arms.kotlinArm(prepared);
            boolean fresh = !compileDepDirty && !force && arms.kotlinStampFresh(prepared, arm);
            if (fresh) {
                steps.add(new TaskForecast.Task(TaskNames.COMPILE_KOTLIN, TaskForecast.Status.CACHED, "", null));
            } else {
                // The stamp is gone (a wiped target/) or stale: price the step by the action key of
                // the build's own request, as the build will — never by the tasks/ pointer, whose
                // last record may belong to another edit of the sources.
                ForecastLangArms.LangStep step = arms.kotlinStep(prepared, arm);
                steps.add(step.task());
                if (step.task().cached()) {
                    restored.projectFromRecord(layout.kotlinClassesDir(), compileKotlinKey = step.key());
                } else {
                    compileDirty = true;
                    ownHint = SourceApiIndex.Hint.UNKNOWN;
                }
            }
        }
    }

    private void compileGroovy(Prepared prepared) throws Exception {
        BuildLayout layout = prepared.layout();
        List<Path> mainSrc = prepared.mainSrc();
        List<Path> ktSrc = prepared.ktSrc();
        List<Path> gvSrc = prepared.gvSrc();
        // ---- compile-groovy (stamp, then the action key of the build's own request) ----
        if (!gvSrc.isEmpty()) {
            ForecastLangArms.LangStep step = arms().groovyStep(prepared);
            steps.add(step.task());
            if (step.task().cached()) {
                restored.projectFromRecord(layout.groovyClassesDir(), compileGroovyKey = step.key());
            } else {
                compileDirty = true;
                ownHint = SourceApiIndex.Hint.UNKNOWN;
            }
        }

        producesJar = !mainSrc.isEmpty() || !ktSrc.isEmpty() || !gvSrc.isEmpty();
        try {
            var img = JkBuildParser.imageConfig(ManifestPaths.manifestIn(dir));
            producesImage = img.base() != null || img.registry() != null;
        } catch (Exception e) {
            Log.debug("compileGroovy: Exception ignored", e);
        }
    }

    /** The Kotlin and Groovy arms, over this module's dirtiness and its dependencies' hint. */
    private ForecastLangArms arms() {
        return new ForecastLangArms(
                project, dir, cache, cas, resolver, actionCache, compileDepDirty, force, depHint, restored);
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
                            dir, compact, SessionContext.current().testSelection()),
                    layout,
                    prepared.pkgDecls());
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
                cas,
                restored);
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
            // Dependency-only dirtiness: the tests recompile only if main really does.
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_TEST,
                    TaskForecast.Status.RUN,
                    depOnlyDirty ? "recompile · only if the compile runs" : "recompile · main changed",
                    null));
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
                    testSrc,
                    baseCp,
                    processorCp,
                    testOut,
                    project.build().javac().testRelease(release),
                    javacArgs,
                    project.build().javac().forTests(),
                    javaHome,
                    testScala,
                    layout.classesDir()));
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
                    layout.generatedSourcesDir("annotations", "test"),
                    WorkerEnv.forModule(project.build().env(), layout.moduleRoot(), layout.moduleTargetDir()),
                    restored.abiToken());
            Perf.end("  predict-compile-test", tt);
            Perf.note(
                    "forecast-compile-test " + dir,
                    "key",
                    pred.actionKey(),
                    "outcome",
                    pred.outcome(),
                    "reason",
                    pred.reason(),
                    "cp",
                    baseCp.size(),
                    "src",
                    testSrc.size(),
                    "pp",
                    processorCp.size(),
                    "release",
                    release,
                    "javaHome",
                    javaHome,
                    "out",
                    testOut);
            TaskForecast.Task p = TaskForecaster.compileStep(TaskNames.COMPILE_TEST, pred, false, req);
            steps.add(p);
            compileTestKey = pred.actionKey();
            if (!p.cached()) testDirty = true;
        } else {
            // Kotlin/Groovy-only tests: no content predictor — assume fresh when main is clean.
            steps.add(new TaskForecast.Task(TaskNames.COMPILE_TEST, TaskForecast.Status.CACHED, "", null));
        }
        if (!compileDirty) {
            compileTestKotlinKey =
                    testSources.ktTest().isEmpty() ? null : lastTestCompileKey(TaskNames.COMPILE_TEST_KOTLIN, layout);
            compileTestGroovyKey =
                    testSources.gvTest().isEmpty() ? null : lastTestCompileKey(TaskNames.COMPILE_TEST_GROOVY, layout);
        }
    }

    /**
     * The key the live Kotlin or Groovy test compile will replay: the record its {@code tasks/}
     * pointer names. Neither compiler has a content predictor here, so the forecast assumes the
     * compile fresh (above) and folds the key of the record a fresh compile restores from — the
     * same key the live stamp folded when it was stored. A module whose tests have never compiled
     * has no pointer and folds nothing, as the live run does on its first build.
     */
    private @Nullable String lastTestCompileKey(String task, BuildLayout layout) {
        try {
            return actionCache
                    .lastFor(ActionKey.qualifiedTaskId(task, layout.testClassesDir()))
                    .map(ActionCache.ActionRecord::actionKey)
                    .orElse(null);
        } catch (IOException e) {
            Log.debug("lastTestCompileKey: no key for " + task, e);
            return null;
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
            // A suite re-run for a sibling's jar bytes alone reads as that, not as this module's
            // change: the runtime classpath is keyed on full bytes where the compile is keyed on
            // ABI, and why-rebuilt must be able to tell the two apart.
            boolean jarOnly = !testDirty && (depOnlyDirty || (!compileDirty && testDepDirty));
            String why = jarOnly ? "run tests · dependency jar changed · " + tests : "run tests · " + tests;
            steps.add(new TaskForecast.Task(TaskNames.RUN_TESTS, TaskForecast.Status.RUN, why, null));
        } else {
            // Same factory as live run-tests default selection sources +
            // worker/engine jar extras (nested-engine CLI included) so the key matches the
            // stored green marker. After jk clean, project the main: fingerprint from the
            // compile action record — ClasspathFingerprint.entry(empty classes) is
            // missing:… and would falsely forecast a full suite.
            List<Path> testRt = PlannerSupport.testRuntimeClasspath(dir, project, lock, resolver);
            long ts = Perf.start();
            String mainFp = null;
            if (!classesTreeWhole(layout)) {
                // Resource-drift flag is computed later; an empty or incomplete tree uses compile
                // outputs + resource roots (same merge as package post-clean) — the walk's own
                // projection when it made one, which also covers a mixed module's merged tree.
                mainFp = restored.projectedIdentity(layout.classesDir());
                if (mainFp == null)
                    mainFp = PackagingKeys.classesTokenForPackage(
                            dir, compact, layout, project, actionCache, compileMainKey, null);
                if (mainFp != null && mainFp.startsWith("missing:")) mainFp = null;
            }
            // The runtime classpath as the run will hash it: a sibling jar or fixtures tree that
            // jk clean took is read as the bytes the build restores before the suite runs.
            String stampKey = PlannerSupport.runTestsStampKey(
                    dir,
                    project,
                    compact,
                    layout.classesDir(),
                    mainFp,
                    lockFile,
                    testRt,
                    new TestStamp.CompileTestKeys(compileTestKey, compileTestKotlinKey, compileTestGroovyKey),
                    restored.identity(),
                    profileName,
                    prepared.pkgDecls());
            Perf.end("  test-stamp-key", ts);
            Optional<ActionCache.ActionRecord> marker =
                    stampKey == null ? Optional.empty() : TaskForecaster.presentRecord(actionCache, stampKey);
            boolean hit = marker.isPresent() && TestStamp.green(marker.get());
            // The same key with a red record is the one shape a live run never skips:
            // say so, or the ETA reads "only the suite is dirty" as stamp drift.
            boolean red = marker.isPresent() && !hit;
            Perf.note("forecast-test-stamp " + dir, "key", stampKey, "hit", hit, "red", red);
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
        buildInfoDrift = false;
        testResourceDrift = false;
        knownResourceDrift = null;
        if (!compileDirty && Files.isDirectory(layout.classesDir())) {
            mainResourceDrift = TaskForecaster.mainResourcesOutOfSync(dir, compact, layout.classesDir());
            JkBuild.BuildInfo buildInfo = project.build().buildInfo();
            if (buildInfo != null) {
                // A rewritten git.properties changes the classes tree package-jar keys on, so the
                // jar is forecast as the resource drift it is.
                buildInfoDrift = PlannerBuildInfo.outOfSync(dir, project, buildInfo, layout.classesDir(), Clock.SYSTEM);
                mainResourceDrift |= buildInfoDrift;
            }
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
        ActivePlugins.@Nullable Declared plugin = prepared.plugin();
        PluginBuild.@Nullable Declarations pkgDecls = prepared.pkgDecls();
        List<Path> mainSrc = prepared.mainSrc();
        List<Path> ktSrc = prepared.ktSrc();
        List<Path> gvSrc = prepared.gvSrc();
        // ---- package-jar ----
        // Tokens MUST match BuildPlanner.packageJarStep (classes/main/sbom/manifest).
        // After jk clean the classes tree is gone: reconstruct the classes: token from the
        // compile action record + resource roots (same merge the live build produces) so we
        // still hit the packaging action cache instead of forecasting perpetual "repackage".
        boolean noSources = mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty();
        if (noSources && (Files.isRegularFile(layout.mainJar()) || project.isWorkspaceRoot())) {
            // A source-less member packages an empty jar (its copied resources) that sibling
            // classpaths demand; with the jar in place there is nothing to forecast. A source-less
            // workspace root runs its scripts and packages nothing.
            return;
        }
        if (compileDirty) {
            // Dependency-only dirtiness: the jar is re-packaged only if the compile really runs.
            steps.add(new TaskForecast.Task(
                    TaskNames.PACKAGE_JAR,
                    TaskForecast.Status.RUN,
                    depOnlyDirty ? "repackage · only if the compile runs" : "repackage · compile changed",
                    null));
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
                    sbom = PlannerPlugin.applicationSbom(project, lock);
                } catch (Exception e) {
                    // best-effort: missing SBOM → key still includes empty sbom: like a null sbom
                    Log.debug("packageJar: best-effort", e);
                }
            }
            // classesTokenForPackage projects post-copy content when resources drifted so
            // package CACHED/RUN matches the live step after copy-resources. A wiped tree takes
            // the walk's own projection, which merges every compiler's record.
            String classesTok = restored.projectedIdentity(layout.classesDir());
            if (classesTok == null) {
                classesTok = PackagingKeys.classesTokenForPackage(
                        dir, compact, layout, project, actionCache, compileMainKey, knownResourceDrift);
            }
            // Must match BuildPlanner.packageJarStep tokens exactly — omitting contrib: made
            // every module forecast permanent "repackage", cascade depDirty, and price a full
            // monorepo rebuild (~3.5m) while live builds hit the package cache and SKIPPED. A
            // worker's vendored sibling class dirs are read as the trees the build restores.
            List<Path> contributed = new ArrayList<>(PlannerSupport.existingContributedDirs(pkgDecls, layout));
            contributed.addAll(PlannerSupport.workerCodecClassDirs(dir, project, restored::willBePresent));
            String contribTok = PlannerSupport.contributionsToken(contributed, restored.identity());
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
            // A source-less module's jar (its copied resources) is one sibling classpaths demand:
            // forecasting nothing would leave it unscheduled while its consumers fail on it, so a
            // missing jar with no record is scheduled until it exists.
            String miss = noSources
                    ? "package · module has no sources"
                    : mainResourceDrift ? "repackage · resources changed" : "repackage";
            steps.add(
                    hit
                            ? new TaskForecast.Task(
                                    TaskNames.PACKAGE_JAR, TaskForecast.Status.CACHED, "", TaskForecaster.key8(pkgKey))
                            : new TaskForecast.Task(TaskNames.PACKAGE_JAR, TaskForecast.Status.RUN, miss, null));
            if (hit && !Files.isRegularFile(jar)) {
                // Publish the wiped jar's content sha from THIS key's record so downstream
                // assembly, native and test-stamp forecasts fingerprint the same bytes the live
                // restore produces.
                actionCache.lookup(pkgKey).ifPresent(rec -> rec.outputs().entrySet().stream()
                        .filter(e -> e.getKey().endsWith(jar.getFileName().toString()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .or(() -> rec.outputs().values().stream().findFirst())
                        .ifPresent(sha -> restored.pinJar(jar, sha)));
            }
        }
    }

    /** The tails beside package-jar: fat jar and javadoc jar, each read against its own key. */
    private void packageTails(Prepared prepared) throws Exception {
        TaskForecast.Task assembly = ForecastPackagingTails.assembly(
                project,
                dir,
                prepared,
                lockFile,
                actionCache,
                cache,
                compileMainKey,
                restored,
                knownResourceDrift,
                compileDirty);
        if (assembly != null) steps.add(assembly);
        TaskForecast.Task javadoc = ForecastPackagingTails.javadoc(
                project, dir, prepared, mainCp, restored.abiToken(), actionCache, cas, compileDirty);
        if (javadoc != null) steps.add(javadoc);
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
            boolean binaryPresent =
                    Files.isRegularFile(layout.nativeBinary()) || Files.isRegularFile(layout.nativeLibrary());
            if (jarDirty || compileDirty) {
                steps.add(new TaskForecast.Task(
                        TaskNames.NATIVE_IMAGE, TaskForecast.Status.RUN, "rebuild · compile changed", null));
            } else if (binaryPresent) {
                steps.add(new TaskForecast.Task(TaskNames.NATIVE_IMAGE, TaskForecast.Status.CACHED, "", null));
            } else if (nativeRestores(layout)) {
                // After jk clean the binary is gone but the step's key still hits: the executable
                // comes back from the cache in seconds, and the restore gate below schedules the
                // module for it. The key is replayed from the step's last record — see
                // PackagingKeys.nativeActionCached for what is recomputed and what is trusted.
                nativeBinaryToRestore = true;
                steps.add(new TaskForecast.Task(
                        TaskNames.NATIVE_IMAGE,
                        TaskForecast.Status.CACHED,
                        "binary missing · restores from cache",
                        null));
            } else {
                // A missing binary whose key the replay cannot vouch for is priced as work: the
                // step itself still restores when its key hits; only the forecast is pessimistic.
                steps.add(new TaskForecast.Task(
                        TaskNames.NATIVE_IMAGE,
                        TaskForecast.Status.RUN,
                        "native-image · binary missing (restores when its key still hits)",
                        null));
            }
        }
    }

    /** The replayed native key hits — a read-only answer that fails safe to "no". */
    private boolean nativeRestores(BuildLayout layout) {
        try {
            return PackagingKeys.nativeActionCached(
                    dir, project, layout, lockFile, actionCache, cache, restored.jarShas());
        } catch (Exception e) {
            Log.debug("nativeImage: the last record could not be replayed read-only", e);
            return false;
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
        // ---- cache-install — jk install terminal ----
        if (target == WorkspaceTarget.INSTALL && terminalDirs.contains(dir)) {
            boolean jarDirty = steps.stream().anyMatch(s -> TaskNames.PACKAGE_JAR.equals(s.name()) && !s.cached());
            steps.add(cacheInstallForecast(project, BuildLayout.of(dir, project), cache, m2Dir, jarDirty));
        }
    }

    /**
     * The cache-install step's forecast: cached when the shelf already holds this jar (matching
     * SHA) and its POM — and, with {@code [m2] install} on, the Maven local repo under {@code
     * m2Dir}, the request's {@code --m2-dir} root the step writes to. A packaged-but-never-installed
     * module still runs, and so does one whose jar this build rewrites.
     */
    static TaskForecast.Task cacheInstallForecast(
            JkBuild project, BuildLayout layout, Path cache, @Nullable Path m2Dir, boolean jarDirty) {
        boolean skip = !jarDirty && InstallPlans.alreadyInstalled(project, layout, cache, m2Dir);
        return new TaskForecast.Task(
                TaskNames.CACHE_INSTALL,
                skip ? TaskForecast.Status.CACHED : TaskForecast.Status.RUN,
                skip ? "" : "install to local repo",
                null);
    }

    private void emit(Prepared prepared) throws Exception {
        // ---- emit resource-drift steps (detected before package) ----
        // Main resource drift schedules the module so the jar ships fresh bytes.
        // Cascade to compile consumers is owned by package-jar above, not by these steps.
        if (mainResourceDrift) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COPY_RESOURCES, TaskForecast.Status.RUN, "resources changed", null));
        }
        if (buildInfoDrift) {
            steps.add(new TaskForecast.Task(TaskNames.BUILD_INFO, TaskForecast.Status.RUN, "checkout moved", null));
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
            // A non-empty tree that lacks an output its compile record owns is a missing output
            // too: the key hits and every class file present is current, yet the jar packaged
            // from the tree would lack the same classes, build after build, until something
            // else moved the key. The restore brings the record's whole tree back.
            boolean incomplete = false;
            if (producesJar) {
                incomplete = TaskForecaster.classesDirHasContent(layout.classesDir())
                        && !ModuleOutputs.compileOutputsOnDisk(actionCache, compileMainKey, layout.classesDir());
                outputsAbsent = !Files.isRegularFile(layout.mainJar())
                        || !TaskForecaster.classesDirHasContent(layout.classesDir())
                        || incomplete
                        || (project.assembly() && !Files.isRegularFile(layout.assemblyJar()))
                        || nativeBinaryToRestore;
            } else if (!project.isWorkspaceRoot()) {
                // Source-less member: the build still packages its jar, and a resources-only
                // module's classes tree (copied resources) is consumed straight off sibling
                // classpaths, so either missing is a missing output too.
                outputsAbsent = !Files.isRegularFile(layout.mainJar())
                        || (!PackagingKeys.packageResourceRoots(dir, compact).isEmpty()
                                && !TaskForecaster.classesDirHasContent(layout.classesDir()));
            }
            // The test view is an output of a tests-enabled build too: this module's suite runs
            // from it and a sibling's fixtures = true or kind = "tests" edge compiles against it,
            // yet --skip-tests leaves it unproduced while the main outputs are current. A build
            // that runs tests restores it, or the sibling that reads it is admitted against a
            // tree nothing produces.
            boolean testViewAbsent = !skipTests && ModuleOutputs.testViewMissing(layout, project, dir, () -> haveTests);
            if (outputsAbsent || testViewAbsent) {
                String why = incomplete
                        ? "restore from cache · classes tree incomplete"
                        : outputsAbsent ? "restore from cache" : "restore from cache · test view absent";
                steps.add(new TaskForecast.Task(TaskNames.RESTORE_OUTPUTS, TaskForecast.Status.RUN, why, null));
            }
        }
    }

    /**
     * True when the classes tree is present and holds every output its compile record owns —
     * the tree a live package or test step would hash. Anything else is priced from the record,
     * as after {@code jk clean}, so an incomplete tree forecasts a restore rather than a
     * repackage of the tree it happens to have.
     */
    private boolean classesTreeWhole(BuildLayout layout) throws IOException {
        return TaskForecaster.classesDirHasContent(layout.classesDir())
                && ModuleOutputs.compileOutputsOnDisk(actionCache, compileMainKey, layout.classesDir());
    }

    private void orderAfter(Prepared prepared) throws Exception {
        ActivePlugins.@Nullable Declared plugin = prepared.plugin();
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
