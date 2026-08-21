// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.JavacLint;
import cc.jumpkick.config.ImageConfigParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.JavaIncrementalCompile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only step forecast for each module in a {@link BuildGraph}, using the same cache keys as the
 * real build. Dirty modules and their consumers are marked will-run in dependency order so
 * downstream keys are not trusted against about-to-change inputs (misses are pessimistic, never
 * false cache hits).
 */
public final class TaskForecaster {

    private TaskForecaster() {}

    /** Forecast every module in {@code graph}, in topological (dependency) order. */
    public static List<TaskForecast.Module> of(BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache) {
        return of(graph, cas, actionCache, cache, false);
    }

    /**
     * Like {@link #of(BuildGraph.Result, Cas, ActionCache, Path)} but omits test steps when
     * {@code skipTests} so a never-tested workspace is not forecast perpetually dirty.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache, boolean skipTests) {
        return of(graph, cas, actionCache, cache, skipTests, WorkspaceTarget.PACKAGE);
    }

    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target) {
        return of(graph, cas, actionCache, cache, skipTests, target, Set.of());
    }

    /**
     * As {@link #of(BuildGraph.Result, Cas, ActionCache, Path, boolean, WorkspaceTarget)} with the
     * resolved terminal module set: the dirs that will receive the target's terminal step
     * (native-image / write-image), mirroring {@code WorkspaceExecute.assemblePlan} eligibility.
     * The forecast must consume the same set the plan assembly uses — re-deriving eligibility
     * here (e.g. from {@code [native]} tables) skips fallback modules and prices unselected ones.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            Set<Path> terminalDirs) {
        List<TaskForecast.Module> out = new ArrayList<>();
        // --force/--rerun bypasses jk's build caches, so every step runs — the forecast must say
        // so too (otherwise the plan tree renders "Fully Cached" while the ETA, which honors force,
        // predicts a full rebuild — a self-contradiction).
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false)
                || cc.jumpkick.config.SessionContext.current().config().rebuildOr(false);
        // Dirs whose *main output* will change this build — seeds downstream and
        // cross-module dirtiness. Filled as we walk in dependency order.
        Set<Path> dirty = new HashSet<>();
        // Jar CAS shas recovered from each walked module's CURRENT package-jar record —
        // consumers fingerprint wiped sibling jars from here, never from an unvalidated
        // last-record pointer (which may name a different edit of the sibling).
        Map<Path, String> restoredJarShas = new HashMap<>();
        // Sibling lookup for scope-aware dirtiness (coord + bare name → dir).
        Map<String, Path> dirByCoord = new HashMap<>();
        Map<String, Path> dirByName = new HashMap<>();
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            dirByCoord.put(unit.coord(), unit.dir());
            dirByName.put(unit.manifest().project().name(), unit.dir());
        }
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            // Scope-aware: a dirty *test-only* sibling (e.g. cli → engine via test-dependencies)
            // must not force compile/package/native — only tests re-run against the new jar.
            // Treating every graph edge as compile-dirty was pricing full native-image (~35s)
            // on dogfood engine edits while live builds skipped compile+package+native.
            DepDirtiness dep =
                    depDirtiness(u, graph.edges().getOrDefault(u.dir(), Set.of()), dirty, dirByCoord, dirByName);
            long t0 = Perf.start();
            TaskForecast.Module m = forecastModule(
                    u, dep, force, skipTests, cas, actionCache, cache, restoredJarShas, target, terminalDirs);
            Perf.end("forecast " + u.coord(), t0);
            // Seed main-output dirtiness for *compile* consumers only when this module's
            // consumed jar/classes will change — not when only test-scope work is dirty.
            // Package matters on its own: a consumer's compile classpath hashes sibling JAR
            // *content*, so an upstream whose compile is cached but whose jar is stale
            // repackages and invalidates the consumer.

            // Also seed when a compile-scope dep is dirty even if predictors still look cached
            // against pre-rebuild sibling jars (pessimistic; avoids under-reserve).
            if (seedsCompileConsumerCascade(m) || dep.compileDepDirty()) {
                dirty.add(u.dir());
            }
            out.add(m);
        }
        return out;
    }

    /**
     * Whether this module's forecast should force compile-scope dependents dirty.
     *
     * <p>True when compile or package will change the jar/classes consumers hash. False for
     * resource-only drift ({@code copy-resources} RUN + package CACHED): the producer still
     * schedules via {@link TaskForecast.Module#dirty()}, but dependents must not inherit full
     * recompile+test ETA while the packaged jar stays byte-identical.
     */
    static boolean seedsCompileConsumerCascade(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        return m.steps().stream()
                .anyMatch(p -> !p.cached()
                        && (p.name().startsWith("compile-main")
                                || p.name().startsWith("compile-java")
                                || p.name().startsWith("compile-kotlin")
                                || p.name().startsWith("compile-groovy")
                                || "package-jar".equals(p.name())
                                || "package-assembly".equals(p.name())));
    }

    /**
     * Which dirty prereqs affect this module's main compile vs tests only. A dirty prereq
     * reachable only via {@code order-after} (incl. {@code test-plugin-jars}) forces no
     * compile/test pricing, but still marks {@link #orderDepDirty} — the dependent must
     * <em>schedule</em> so its real action keys re-check the prereq's out-of-band outputs
     * (test-plugin jars ride the run-tests stamp; users add order-after precisely for
     * consumption the classpath cannot express). Pricing nothing keeps ETA honest; skipping
     * the module entirely shipped stale outputs.
     */
    record DepDirtiness(boolean compileDepDirty, boolean testDepDirty, boolean orderDepDirty) {
        static final DepDirtiness NONE = new DepDirtiness(false, false, false);
    }

    static DepDirtiness depDirtiness(
            BuildGraph.BuildUnit u,
            Set<Path> prereqs,
            Set<Path> dirty,
            Map<String, Path> dirByCoord,
            Map<String, Path> dirByName) {
        if (prereqs == null || prereqs.isEmpty() || dirty.isEmpty()) return DepDirtiness.NONE;
        boolean compile = false;
        boolean test = false;
        boolean order = false;
        JkBuild m = u.manifest();
        for (Path dep : prereqs) {
            if (!dirty.contains(dep)) continue;
            boolean viaCompile = false;
            boolean viaTest = false;
            for (Scope scope : Scope.values()) {
                for (Dependency d : m.dependencies().of(scope)) {
                    Path hit = ModuleOrder.resolveSibling(d, dirByCoord, dirByName);
                    if (hit == null || !hit.equals(dep)) continue;
                    if (scope == Scope.TEST || scope == Scope.TEST_DEV) viaTest = true;
                    else viaCompile = true;
                }
            }
            if (viaCompile) compile = true;
            else if (viaTest) test = true;
            else order = true; // order-after-only prereq: schedule, price nothing
        }
        return new DepDirtiness(compile, test, order);
    }

    /**
     * The {@link BuildPlanner.Inputs} a real {@code jk build} constructs for one module — the
     * single factory both {@code jk build} ({@code BuildCommand.prepareModule}) and {@code jk
     * explain}'s ETA use, so the two can't drift in what they feed the effort-weight prediction.
     * The {@code jdksDir} default of {@code null} is load-bearing: it routes {@link
     * cc.jumpkick.runtime.EffortWeights#jdkWeight} through the full JDK probe chain (PATH /
     * JAVA_HOME / GraalVM / SDKMAN / …) instead of the empty {@code the managed JDK root}, so an
     * already-installed JDK predicts a zero-cost {@code ensure-jdk} rather than a phantom download.
     */
    public static BuildPlanner.Inputs inputsFor(
            Path dir, Path cache, int workers, Path jdksDir, String profile, boolean skipTests, boolean verbose) {
        return inputsFor(dir, cache, workers, jdksDir, profile, skipTests, verbose, Set.of());
    }

    /**
     * As {@link #inputsFor(Path, Path, int, Path, String, boolean, boolean)} but carrying the sibling
     * module dirs of the build graph, so the effort-weight prediction can borrow a project-tier learned
     * rate for a not-yet-built module (see {@link cc.jumpkick.runtime.EffortWeights#learned}).
     */
    public static BuildPlanner.Inputs inputsFor(
            Path dir,
            Path cache,
            int workers,
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            Set<Path> projectModules) {
        return inputsFor(dir, cache, workers, jdksDir, profile, skipTests, verbose, projectModules, false);
    }

    /**
     * As {@link #inputsFor(Path, Path, int, Path, String, boolean, boolean, Set)} with {@code
     * testOnly} — when true, plans stop before packaging ({@code jk test} / MCP {@code jk_test}).
     */
    public static BuildPlanner.Inputs inputsFor(
            Path dir,
            Path cache,
            int workers,
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            Set<Path> projectModules,
            boolean testOnly) {
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(dir);
        // 0 = auto at run-tests (JUnitLauncher); forecast treats as 1 for cost estimates.
        int workerCount = workers > 0 ? workers : 1;
        // testOnly still runs tests (never skip).
        boolean skip = testOnly ? false : skipTests;
        boolean compactEst = CompileSupport.isSimpleLayout(dir);
        int estimatedTestCount = skip ? 0 : TestSupport.estimateAllSuiteTestCount(dir, compactEst);
        return new BuildPlanner.Inputs(
                        dir,
                        cache,
                        buildFile,
                        lockFile,
                        dir,
                        workerCount,
                        estimatedTestCount,
                        profile,
                        jdksDir,
                        skip,
                        verbose,
                        testOnly,
                        false,
                        Set.of(),
                        cc.jumpkick.config.SessionContext.current())
                .withProjectModules(projectModules);
    }

    private static TaskForecast.Module forecastModule(
            BuildGraph.BuildUnit u,
            DepDirtiness dep,
            boolean force,
            boolean skipTests,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            Map<Path, String> restoredJarShas,
            WorkspaceTarget target,
            Set<Path> terminalDirs) {
        if (dep == null) dep = DepDirtiness.NONE;
        boolean compileDepDirty = dep.compileDepDirty();
        boolean testDepDirty = dep.testDepDirty();
        JkBuild project = u.manifest();
        Path dir = u.dir();
        List<TaskForecast.Task> steps = new ArrayList<>();
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(dir);
        if (!Files.isRegularFile(lockFile)) {
            steps.add(new TaskForecast.Task(
                    "compile-main", TaskForecast.Status.RUN, "not locked yet (run `jk build`)", null));
            return new TaskForecast.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
        }
        // Digest-only staleness — the same predicate the build's freshen uses, so the
        // forecast and the live build agree on whether a lock update runs.
        if (cc.jumpkick.runtime.AutoLock.isStale(dir, lockFile)) {
            steps.add(new TaskForecast.Task(
                    "compile-main", TaskForecast.Status.RUN, "jk.toml changed — lock update needed", null));
            return new TaskForecast.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
        }
        int sourceCount = 0, testCount = 0;
        boolean producesJar = false, producesImage = false;
        try {
            Lockfile lock = LockfileReader.read(lockFile);
            ClasspathResolver resolver = new ClasspathResolver(cas);
            boolean compact = CompileSupport.isSimpleLayout(project.project(), dir);
            BuildLayout layout = BuildLayout.of(dir, project);
            int release = project.project().javaRelease();
            // Same contributed-args evaluation as the real compile step, against the same
            // lock — forecast action keys must match the keys the build will actually use.
            List<String> javacArgs = JavacLint.effectiveArgs(
                    project.build().lint(),
                    cc.jumpkick.plugin.manifest.PluginContributions.javacArgs(
                            project, dir, BuildPlanner.lockModules(lock)),
                    List.of());
            // Must mirror BuildPlanner' processor classpath exactly — workspace siblings
            // included — or the forecast hashes a different -processorpath than the
            // build and every KSP module forecasts a phantom rebuild.
            List<Path> processorCp = BuildPlanner.processorClasspath(
                    lock, resolver, WorkspaceClasspath.resolve(dir, project, Set.of(Scope.PROCESSOR)));

            // Only compile-scope dirty siblings force main recompile (and package/native cascade).
            // Test-only siblings (cli's jk-engine test-dep) leave main clean.
            boolean compileDirty = compileDepDirty || force;
            // The CURRENT compile-main action key when the content predictor ran — post-clean
            // reconstruction must resolve the record for this key, never lastFor (the last
            // record may belong to a different edit of the sources; see the revert scenario in
            // TaskForecasterCleanPackageTest).
            String compileMainKey = null;

            // ---- compile-main (Java) ----
            Path mainSrcDir = compact ? dir.resolve("src") : dir.resolve("src/main/java");
            List<Path> mainSrc = CompileSupport.collectJavaSources(mainSrcDir);
            // Collected early: mixed-language modules fold the sibling compiler's outputs into the
            // compile-main stamp inputs (shared recipe below); the kotlin/groovy forecast sections
            // reuse these lists.
            List<Path> ktSrc = CompileSupport.collectKotlinSources(dir, compact);
            List<Path> gvSrc = CompileSupport.collectGroovySources(dir, compact);
            boolean mixedKotlin = !mainSrc.isEmpty() && !ktSrc.isEmpty();
            boolean mixedGroovy = !mainSrc.isEmpty() && !gvSrc.isEmpty();
            if (!mainSrc.isEmpty()) {
                WorkspaceClasspath.Result sib =
                        WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN));
                List<Path> cp = BuildPlanner.mainCompileClasspath(lock, resolver, sib);
                Path out = layout.classesDir();
                // Same stamp gate as BuildPlanner compile-main: a post-rebuild tree with a
                // fresh.jstamp is cached even when action-cache keys were not rewritten
                // (historical --rebuild skipped store). The input recipe is SHARED with the live
                // check and write-stamp — mixed modules previously hashed different
                // inputs here and never stamp-matched.
                Path groovyJar = null;
                boolean groovyJarUnavailable = false;
                if (mixedGroovy) {
                    try {
                        String groovyVersion = CompileToolchain.groovyVersionFor(lock, project);
                        var repos = RepoGroupBuilder.buildFor(project, null, cas);
                        groovyJar = GroovyPluginSetup.prepare(repos, cas, groovyVersion)
                                .groovyJar();
                    } catch (Exception e) {
                        // Cannot reproduce the live stamp inputs without the jar — fall through to
                        // the action-cache prediction rather than guessing.
                        groovyJarUnavailable = true;
                    }
                }
                List<Path> stampInputs =
                        BuildPlanner.mainStampClasspath(cp, processorCp, mixedKotlin, mixedGroovy, layout, groovyJar);
                boolean stampFresh = false;
                if (!compileDepDirty && !force && !groovyJarUnavailable) {
                    try {
                        stampFresh =
                                FreshnessStamp.isFresh(out, FreshnessStamp.JAVA_STAMP, mainSrc, stampInputs, release);
                    } catch (IOException ignored) {
                        stampFresh = false;
                    }
                }
                if (stampFresh) {
                    steps.add(new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", null));
                } else {
                    CompileRequest req = CompileRequest.builder()
                            .sources(mainSrc)
                            .classpath(cp)
                            .outputDir(out)
                            .release(release)
                            .extraOptions(javacArgs)
                            .processorPath(processorCp)
                            .build();
                    String taskId = ActionKey.qualifiedTaskId("compile-main", out);
                    Path stateDir =
                            cache.resolve("actions").resolve("incremental-java").resolve(taskId);
                    long tc = Perf.start();
                    var pred = JavaIncrementalCompile.predict(
                            taskId, req, BuildIdentity.cacheKeyVersion(), actionCache, stateDir);
                    Perf.end("  predict-compile-main", tc);
                    compileMainKey = pred.actionKey();
                    steps.add(compileStep("compile-main", pred, compileDepDirty || force));
                    if (!steps.get(steps.size() - 1).cached()) compileDirty = true;
                }
            }

            // ---- compile-kotlin (freshness stamp; post-clean uses last action record) ----
            if (!ktSrc.isEmpty()) {
                // The stamp lives with the MERGED classes (BuildPlanner writes it to
                // MAIN_CLASSES), not in kotlinc's incremental workspace under target/kotlin/main.
                // Reading the wrong directory never found a stamp, so every Kotlin module
                // forecast a full compile no matter how cached the build actually was.
                boolean fresh = !compileDepDirty
                        && !force
                        && FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, ktSrc);
                // After jk clean the stamp is gone with target/, but the action-cache pointer
                // under tasks/ survives. lastFor+present ⇒ live kotlinc will restore — do not
                // price FULL (never-built modules have no pointer and stay FULL).
                boolean restoreHit = !compileDepDirty
                        && !force
                        && !classesDirHasContent(layout.classesDir())
                        && stampLangActionPresent(
                                actionCache,
                                ActionKey.qualifiedTaskId(
                                        cc.jumpkick.run.TaskNames.COMPILE_KOTLIN, layout.classesDir()));
                if (fresh || restoreHit) {
                    steps.add(new TaskForecast.Task("compile-kotlin", TaskForecast.Status.CACHED, "", null));
                } else {
                    steps.add(new TaskForecast.Task(
                            "compile-kotlin",
                            TaskForecast.Status.FULL,
                            "full compile · " + count(ktSrc.size(), "source"),
                            null));
                    compileDirty = true;
                }
            }

            // ---- compile-groovy (stamp + post-clean restore, same as Kotlin) ----
            // The groovy stamp lives in the merged classes dir (where write-stamp-groovy
            // writes it), unlike Kotlin's forecast probe of kotlinClassesDir.
            if (!gvSrc.isEmpty()) {
                boolean fresh = !compileDepDirty
                        && !force
                        && FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.GROOVY_STAMP, gvSrc);
                boolean restoreHit = !compileDepDirty
                        && !force
                        && !classesDirHasContent(layout.classesDir())
                        && stampLangActionPresent(
                                actionCache,
                                ActionKey.qualifiedTaskId(
                                        cc.jumpkick.run.TaskNames.COMPILE_GROOVY, layout.classesDir()));
                if (fresh || restoreHit) {
                    steps.add(new TaskForecast.Task("compile-groovy", TaskForecast.Status.CACHED, "", null));
                } else {
                    steps.add(new TaskForecast.Task(
                            "compile-groovy",
                            TaskForecast.Status.FULL,
                            "full compile · " + count(gvSrc.size(), "source"),
                            null));
                    compileDirty = true;
                }
            }

            producesJar = !mainSrc.isEmpty() || !ktSrc.isEmpty() || !gvSrc.isEmpty();
            try {
                var img = ImageConfigParser.parse(dir.resolve("jk.toml"));
                producesImage = img.base() != null || img.registry() != null;
            } catch (Exception ignored) {
            }

            // ---- compile-test (all discovered suites —---
            List<Path> allTestSrc = List.of();
            try {
                allTestSrc = TestSupport.collectAllSuiteTestSources(dir, compact);
            } catch (IOException ignored) {
                // forecast degrades
            }
            List<Path> javaTest = allTestSrc.stream()
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
            List<Path> ktTest = allTestSrc.stream()
                    .filter(p -> p.getFileName().toString().endsWith(".kt"))
                    .toList();
            boolean haveTests = !allTestSrc.isEmpty();
            sourceCount = mainSrc.size() + ktSrc.size() + gvSrc.size() + allTestSrc.size();
            boolean testDirty = false;
            // --skip-tests composes no compile-test/run-tests steps, so don't forecast
            // (or content-hash the inputs of) steps the build will not run.
            if (haveTests && !skipTests) {
                if (compileDirty) {
                    steps.add(new TaskForecast.Task(
                            "compile-test", TaskForecast.Status.RUN, "recompile · main changed", null));
                    testDirty = true;
                } else if (!javaTest.isEmpty()) {
                    List<Path> baseCp = new ArrayList<>();
                    baseCp.add(layout.classesDir());
                    baseCp.addAll(testCompileClasspath(dir, project, lock, resolver));
                    Path testOut = layout.testClassesDir();
                    // Mirror TestSupport.compileWithCache EXACTLY, including the
                    // processor path it now passes — the build runs declared
                    // annotation processors over test sources, so the action key
                    // hashes the same `pp:` lines. Omitting it here would compute a
                    // different key, miss the cache, and falsely forecast a rebuild.
                    CompileRequest req = CompileRequest.builder()
                            .sources(javaTest)
                            .classpath(baseCp)
                            .outputDir(testOut)
                            .release(release)
                            .extraOptions(javacArgs)
                            .processorPath(processorCp)
                            .build();
                    String taskId = ActionKey.qualifiedTaskId("compile-test", testOut);
                    Path stateDir =
                            cache.resolve("actions").resolve("incremental-java").resolve(taskId);
                    long tt = Perf.start();
                    var pred = JavaIncrementalCompile.predict(
                            taskId, req, BuildIdentity.cacheKeyVersion(), actionCache, stateDir);
                    Perf.end("  predict-compile-test", tt);
                    TaskForecast.Task p = compileStep("compile-test", pred, false);
                    steps.add(p);
                    if (!p.cached()) testDirty = true;
                } else {
                    // Kotlin/Groovy-only tests: no content predictor — assume fresh when main is clean.
                    steps.add(new TaskForecast.Task("compile-test", TaskForecast.Status.CACHED, "", null));
                }

                // ---- run-tests ----
                int estimated = TestSupport.estimateAllSuiteTestCount(dir, compact);
                testCount = estimated;
                String tests = estimated > 0 ? "~" + count(estimated, "test") : "tests";
                // testDepDirty: sibling on test classpath is rebuilding — suite must re-run even
                // when main compile stays cached (cli ← engine test-dep dogfood).
                if (compileDirty || testDirty || testDepDirty) {
                    steps.add(
                            new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · " + tests, null));
                } else {
                    // Same factory as live run-tests default selection sources +
                    // worker/engine jar extras (nested-engine CLI included) so the key matches the
                    // stored green marker. After jk clean, project the main: fingerprint from the
                    // compile action record — ClasspathFingerprint.entry(empty classes) is
                    // missing:… and would falsely forecast a full suite.
                    List<Path> testRt = testRuntimeClasspath(dir, project, lock, resolver);
                    long ts = Perf.start();
                    String mainFp = null;
                    if (!classesDirHasContent(layout.classesDir())) {
                        // Resource-drift flag is computed later; empty classes uses compile
                        // outputs + resource roots (same merge as package post-clean).
                        mainFp = classesTokenForPackage(
                                dir, compact, layout, project, actionCache, compileMainKey, null);
                        if (mainFp != null && mainFp.startsWith("missing:")) mainFp = null;
                    }
                    String stampKey = BuildPlanner.runTestsStampKey(
                            dir, project, compact, layout.classesDir(), mainFp, lockFile, testRt);
                    Perf.end("  test-stamp-key", ts);
                    boolean hit = stampKey != null && present(actionCache, stampKey);
                    steps.add(
                            hit
                                    ? new TaskForecast.Task("run-tests", TaskForecast.Status.CACHED, "· " + tests, null)
                                    : new TaskForecast.Task(
                                            "run-tests", TaskForecast.Status.RUN, "run tests · " + tests, null));
                }
            }

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
            boolean mainResourceDrift = false;
            boolean testResourceDrift = false;
            Boolean knownResourceDrift = null;
            if (!compileDirty && Files.isDirectory(layout.classesDir())) {
                boolean mainOut = resourcesOutOfSync(
                        cc.jumpkick.layout.ModuleLayout.mainResourcesDir(dir, compact), layout.classesDir());
                boolean pluginManifestOut = !mainOut && pluginManifestOutOfSync(dir, layout.classesDir());
                mainResourceDrift = mainOut || pluginManifestOut;
                knownResourceDrift = mainResourceDrift;
                if (haveTests && !skipTests && !testDirty && Files.isDirectory(layout.testClassesDir())) {
                    Path resTest = cc.jumpkick.layout.ModuleLayout.testResourcesDir(dir, compact);
                    if (resourcesOutOfSync(resTest, layout.testClassesDir())) {
                        testResourceDrift = true;
                    }
                }
            }

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
                            "package-jar", TaskForecast.Status.RUN, "package · module has no sources", null));
                }
            } else if (compileDirty) {
                steps.add(new TaskForecast.Task(
                        "package-jar", TaskForecast.Status.RUN, "repackage · compile changed", null));
            } else {
                Path jar = layout.mainJar();
                String mainClass = cc.jumpkick.plugin.PluginModule.mainClass(dir, project);
                long tp = Perf.start();
                byte[] sbom = null;
                if (project.isApplication()) {
                    try {
                        sbom = BuildPlanner.applicationSbom(project, lock, cas);
                    } catch (Exception ignored) {
                        // best-effort: missing SBOM → key still includes empty sbom: like a null sbom
                    }
                }
                // classesTokenForPackage projects post-copy content when resources drifted so
                // package CACHED/RUN matches the live step after copy-resources.
                String classesTok = classesTokenForPackage(
                        dir, compact, layout, project, actionCache, compileMainKey, knownResourceDrift);
                // Must match BuildPlanner.packageJarStep tokens exactly — omitting contrib: made
                // every module forecast permanent "repackage", cascade depDirty, and price a full
                // monorepo rebuild (~3.5m) while live builds hit the package cache and SKIPPED.
                PluginBuild.Declarations pkgDecls = BuildPlanner.pluginDeclarationsFor(project, layout, cache);
                List<Path> contributed = BuildPlanner.existingContributedDirs(pkgDecls, layout);
                String contribTok = BuildPlanner.contributionsToken(contributed);
                List<String> tokens = List.of(
                        "classes:" + classesTok,
                        "contrib:" + contribTok,
                        "main:" + (mainClass == null ? "" : mainClass),
                        "sbom:" + (sbom == null ? "" : cc.jumpkick.util.Hashing.sha256Hex(sbom)),
                        "manifest:" + project.manifest());
                Perf.end("  package-fingerprint", tp);
                String pkgKey = ActionKey.forArtifact(
                        ActionKey.qualifiedTaskId("package-jar", jar), BuildIdentity.cacheKeyVersion(), tokens);
                boolean hit = present(actionCache, pkgKey);
                steps.add(
                        hit
                                ? new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "", key8(pkgKey))
                                : new TaskForecast.Task(
                                        "package-jar",
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
                            .ifPresent(sha ->
                                    restoredJarShas.put(jar.toAbsolutePath().normalize(), sha)));
                }
            }

            // ---- package-assembly (fat jar) — only when configured ----
            // Same action-key recipe as BuildPlanner.assemblyStep (not "jar exists on disk").
            if (project.assembly() && !(mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty())) {
                if (compileDirty) {
                    steps.add(new TaskForecast.Task(
                            "package-assembly", TaskForecast.Status.RUN, "repackage · compile changed", null));
                } else {
                    boolean hit = assemblyActionCached(
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
                                    ? new TaskForecast.Task("package-assembly", TaskForecast.Status.CACHED, "", null)
                                    : new TaskForecast.Task(
                                            "package-assembly", TaskForecast.Status.RUN, "repackage", null));
                }
            }

            // ---- native-image — [native] enabled = "always" (same opt-in as jk build) ----
            // Hard cascade: jar dirty ⇒ native dirty. Never forecast package-jar RUN +
            // native-image CACHED (binary mtime vs pre-build jar is not an independent skip).
            boolean nativeOnBuild = project.nativeMode() == cc.jumpkick.model.JkBuild.NativeMode.ALWAYS;
            // Membership in the resolved terminal set — NOT project.nativeImage(). Re-deriving
            // eligibility from the [native] table made fallback (table-less unique-main) modules
            // invisible (jar clean + binary missing ⇒ skipped ⇒ "success" with no binary) and
            // priced unselected cone prereqs WITH tables as perpetually dirty (their plans get
            // allowNative=false, so the binary they were dirty "for" never appears) — JK-2088.
            boolean nativeOnNativeCmd = target == WorkspaceTarget.NATIVE && terminalDirs.contains(dir);
            if ((nativeOnBuild || nativeOnNativeCmd) && !(mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty())) {
                boolean jarDirty = steps.stream().anyMatch(s -> "package-jar".equals(s.name()) && !s.cached());
                Path nativeOut = layout.nativeBinary();
                boolean binaryPresent = Files.isRegularFile(nativeOut) || Files.isRegularFile(layout.nativeLibrary());
                // Missing binary after wipe: action-cache hit ⇒ restore (CACHED), not a FULL
                // native wall. lastFor tags the binary path (see PlannerNative).
                boolean nativeRestoreHit = !binaryPresent
                        && !jarDirty
                        && !compileDirty
                        && stampLangActionPresent(
                                actionCache,
                                ActionKey.qualifiedTaskId(cc.jumpkick.run.TaskNames.NATIVE_IMAGE, nativeOut));
                if (jarDirty || compileDirty || (!binaryPresent && !nativeRestoreHit)) {
                    String why = jarDirty || compileDirty ? "rebuild · compile changed" : "native-image";
                    steps.add(new TaskForecast.Task("native-image", TaskForecast.Status.RUN, why, null));
                } else {
                    steps.add(new TaskForecast.Task("native-image", TaskForecast.Status.CACHED, "", null));
                }
            }

            // ---- write-image — jk image terminal on the selected module(s) ----
            // ImagePlans' contract: a registry push/docker load/tarball write is a side-effect,
            // never a cacheable output — an up-to-date module still runs its image tail. Without
            // this step a clean workspace member forecast "not dirty", was never scheduled, and
            // jk image reported success having pushed nothing (JK-2084).
            if (target == WorkspaceTarget.IMAGE && terminalDirs.contains(dir)) {
                steps.add(new TaskForecast.Task(
                        cc.jumpkick.run.TaskNames.WRITE_IMAGE, TaskForecast.Status.RUN, "image side-effect", null));
            }

            // ---- cache-install — jk install terminal. Skip when repos/local already has this
            // jar (matching SHA) and its POM. A packaged-but-never-installed module still runs.
            if (target == WorkspaceTarget.INSTALL && terminalDirs.contains(dir)) {
                boolean jarDirty = steps.stream()
                        .anyMatch(s -> cc.jumpkick.run.TaskNames.PACKAGE_JAR.equals(s.name()) && !s.cached());
                boolean skip = !jarDirty
                        && InstallPlans.alreadyInstalled(
                                project, cc.jumpkick.layout.BuildLayout.of(dir, project), cache);
                steps.add(new TaskForecast.Task(
                        cc.jumpkick.run.TaskNames.CACHE_INSTALL,
                        skip ? TaskForecast.Status.CACHED : TaskForecast.Status.RUN,
                        skip ? "" : "install to local repo",
                        null));
            }

            // ---- emit resource-drift steps (detected before package) ----
            // Main resource drift schedules the module so the jar ships fresh bytes.
            // Cascade to compile consumers is owned by package-jar above, not by these steps.
            if (mainResourceDrift) {
                steps.add(new TaskForecast.Task("copy-resources", TaskForecast.Status.RUN, "resources changed", null));
            }
            if (testResourceDrift) {
                // Distinct name: test-resource drift schedules the module (material) but
                // must not seed the compile-consumer cascade like main-resource drift.
                steps.add(new TaskForecast.Task(
                        "copy-test-resources", TaskForecast.Status.RUN, "test resources changed", null));
            }

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
                            || !classesDirHasContent(layout.classesDir())
                            || (project.assembly() && !Files.isRegularFile(layout.assemblyJar()));
                } else if (!packageResourceRoots(dir, compact).isEmpty()) {
                    // Resources-only module: its classes tree (copied resources) is consumed
                    // straight off sibling classpaths, so an empty tree is a missing output too.
                    outputsAbsent = !classesDirHasContent(layout.classesDir());
                }
                if (outputsAbsent) {
                    steps.add(new TaskForecast.Task(
                            "restore-outputs", TaskForecast.Status.RUN, "restore from cache", null));
                }
            }

            // ---- order-after gate ----
            // A dirty order-after-only prereq prices nothing, but the module must still schedule:
            // its real action keys are what re-check the prereq's out-of-band outputs (e.g. a
            // rebuilt test-plugin jar hashed by the run-tests stamp). Unchanged inputs resolve as
            // cheap cache hits at execute.
            if (dep.orderDepDirty() && steps.stream().allMatch(TaskForecast.Task::cached)) {
                steps.add(new TaskForecast.Task(
                        "order-check", TaskForecast.Status.RUN, "ordered-after sibling rebuilding", null));
            }
        } catch (Exception e) {
            // Degrade gracefully — never crash explain over one unparseable module.
            steps.add(new TaskForecast.Task(
                    "compile-main",
                    TaskForecast.Status.RUN,
                    "could not predict (" + e.getClass().getSimpleName() + ")",
                    null));
        }
        return new TaskForecast.Module(u.dir(), u.coord(), steps, sourceCount, testCount, producesJar, producesImage);
    }

    /**
     * {@code classes:} fingerprint for package/assembly keys — live tree when present, else the
     * record of the CURRENT compile key ({@code compileMainKey}) merged with current resource
     * roots (post-{@code jk clean} restore path). Never {@code lastFor}: after an edit → build →
     * revert → clean, the last record names the other edit's outputs while the live build would
     * restore the reverted ones — reconstruction must match the live restore or the forecast
     * flips to false CACHED/RUN.
     *
     * <p>When the live classes tree is present but main/extra resources have drifted, projects the
     * post-{@code copy-resources} tree (class files + source resource roots) so package CACHED/RUN
     * matches the live package step after the copy — not the stale pre-copy classes dir.
     */
    static String classesTokenForPackage(
            Path dir,
            boolean compact,
            BuildLayout layout,
            JkBuild project,
            ActionCache actionCache,
            String compileMainKey,
            Boolean knownResourceDrift)
            throws IOException {
        Path classesDir = layout.classesDir();
        if (classesDirHasContent(classesDir)) {
            // Reuse the forecast's single drift detection when it ran — a re-walk here
            // could disagree with it and project the token from a different tree state.
            boolean drifted =
                    knownResourceDrift != null ? knownResourceDrift : mainResourcesOutOfSync(dir, compact, classesDir);
            if (drifted) {
                return classesTokenProjectedAfterResourceCopy(dir, compact, layout, project);
            }
            return ClasspathFingerprint.entry(classesDir);
        }
        Map<String, String> compileOut = compileMainKey == null
                ? Map.of()
                : actionCache
                        .lookup(compileMainKey)
                        .map(ActionCache.ActionRecord::outputs)
                        .orElse(Map.of());
        List<Path> resRoots = packageResourceRoots(dir, compact);
        if (compileOut.isEmpty() && resRoots.isEmpty()) {
            return ClasspathFingerprint.entry(classesDir); // missing:… — package key will miss
        }
        return ClasspathFingerprint.entryFromCompileAndResources(compileOut, resRoots);
    }

    /** Main resource roots (or a module-root {@code jk-plugin.toml}) differ from copies under {@code classesDir}. */
    static boolean mainResourcesOutOfSync(Path dir, boolean compact, Path classesDir) {
        if (resourcesOutOfSync(cc.jumpkick.layout.ModuleLayout.mainResourcesDir(dir, compact), classesDir)) {
            return true;
        }
        return pluginManifestOutOfSync(dir, classesDir);
    }

    /**
     * Projected {@code classes:} token after {@code copy-resources} would merge source resource
     * roots over the current classes tree. Matches the live package-jar fingerprint once the
     * copy step has run — used when main resources are out of sync so package CACHED/RUN does not
     * lie about a pre-copy tree.
     */
    static String classesTokenProjectedAfterResourceCopy(Path dir, boolean compact, BuildLayout layout, JkBuild project)
            throws IOException {
        return ClasspathFingerprint.entryProjectedAfterResourceCopy(
                layout.classesDir(), packageResourceRoots(dir, compact));
    }

    /** Resource roots that {@code copy-resources} merges into {@code classes/} (main + plugin). */
    static List<Path> packageResourceRoots(Path dir, boolean compact) {
        List<Path> resDirs = new ArrayList<>();
        Path resMain = cc.jumpkick.layout.ModuleLayout.mainResourcesDir(dir, compact);
        if (Files.isDirectory(resMain)) resDirs.add(resMain);
        for (var root : cc.jumpkick.layout.ModuleLayoutPlugins.pluginContributedRoots(dir)) {
            if (!root.resource()) continue;
            Path r = dir.resolve(root.relative());
            if (Files.isDirectory(r)) resDirs.add(r);
        }
        return resDirs;
    }

    static boolean classesDirHasContent(Path classesDir) throws IOException {
        if (!Files.isDirectory(classesDir)) return false;
        try (var walk = Files.walk(classesDir)) {
            return walk.anyMatch(p -> {
                if (!Files.isRegularFile(p)) return false;
                return !FreshnessStamp.isStampFile(p.getFileName().toString());
            });
        }
    }

    /**
     * Whether {@code package-assembly}'s action cache holds a hit for the same key the live step
     * computes (classes + module runtime-closure deps + main + manifest + packaging:fat). Dep jars
     * must come from {@link BuildPlanner#assemblyDependencyJars} — never the whole
     * workspace lock RUNTIME set, or explain permanently shows "repackage" after a warm assembly.
     * Sibling jars missing after clean are fingerprinted via CAS shas recovered from each sibling's
     * package record.
     */
    static boolean assemblyActionCached(
            Path dir,
            JkBuild project,
            BuildLayout layout,
            Path lockFile,
            ActionCache actionCache,
            Path cache,
            String compileMainKey,
            Map<Path, String> restoredJarShas,
            Boolean knownResourceDrift)
            throws IOException {
        Path assemblyJar = layout.assemblyJar();
        String classesTok = classesTokenForPackage(
                dir,
                CompileSupport.isSimpleLayout(project.project(), dir),
                layout,
                project,
                actionCache,
                compileMainKey,
                knownResourceDrift);
        // Same jar set as BuildPlanner.assemblyStep (ModuleRuntimeClasspath / ).
        List<Path> depJars = BuildPlanner.assemblyDependencyJars(dir, project, lockFile, cache);
        String depsTok = fingerprintDepJars(depJars, actionCache, restoredJarShas);
        // contrib: must match live assemblyStep tokens (same bug class as package-jar).
        PluginBuild.Declarations pkgDecls;
        try {
            pkgDecls = BuildPlanner.pluginDeclarationsFor(project, layout, cache);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        List<Path> contributed = BuildPlanner.existingContributedDirs(pkgDecls, layout);
        String contribTok = BuildPlanner.contributionsToken(contributed);
        String mainClass = cc.jumpkick.plugin.PluginModule.mainClass(dir, project);
        List<String> tokens = List.of(
                "classes:" + classesTok,
                "contrib:" + contribTok,
                "deps:" + depsTok,
                "main:" + (mainClass == null ? "" : mainClass),
                "manifest:" + project.manifest(),
                "packaging:fat");
        String shTask = ActionKey.qualifiedTaskId("package-assembly", assemblyJar);
        String shKey = ActionKey.forArtifact(shTask, BuildIdentity.cacheKeyVersion(), tokens);
        return present(actionCache, shKey);
    }

    /**
     * Content fingerprint of dep jars matching {@link ClasspathFingerprint#of}, recovering sibling
     * jars wiped by {@code jk clean} from the CAS shas the walk pinned off each sibling's current
     * package record.
     */
    static String fingerprintDepJars(List<Path> depJars, ActionCache actionCache, Map<Path, String> restoredJarShas)
            throws IOException {
        List<String> parts = new ArrayList<>(depJars.size());
        for (Path jar : depJars) {
            parts.add(fingerprintJarOrCached(jar, actionCache, restoredJarShas));
        }
        parts.sort(Comparator.naturalOrder());
        return cc.jumpkick.util.Hashing.sha256Hex(String.join("\n", parts));
    }

    static String fingerprintJarOrCached(Path jar, ActionCache actionCache, Map<Path, String> restoredJarShas)
            throws IOException {
        if (Files.isRegularFile(jar)) {
            return ClasspathFingerprint.entry(jar);
        }
        // After clean: sibling jars live under target/ — recover content from the sha the walk
        // pinned when the sibling's CURRENT package key hit. The pinned sha names a payload blob
        // in the ACTION-CACHE pool (cache tier), not the artifact store. An unpinned wiped jar
        // stays missing:… (assembly forecasts RUN — pessimistic, never a false hit): an
        // unvalidated last-record pointer could name a different edit of the sibling.
        String sha = restoredJarShas.get(jar.toAbsolutePath().normalize());
        if (sha != null) {
            Path blob = actionCache.cas().pathFor(sha);
            if (Files.isRegularFile(blob)) {
                // The blob path would classify as "cas:<abs>", but the live step fingerprinted the
                // on-disk sibling as "file:<content sha>" — return that form so a post-clean
                // assembly forecast can match the stored key.
                return "file:" + sha;
            }
        }
        return ClasspathFingerprint.entry(jar); // missing:…
    }

    /** True when a module-root {@code jk-plugin.toml} differs from its copy at the classes root. */
    static boolean pluginManifestOutOfSync(Path dir, Path outDir) {
        Path src = dir.resolve("jk-plugin.toml");
        Path copy = outDir.resolve("jk-plugin.toml");
        // A deleted (or renamed-away) manifest with a copy still in classes/ is the JK-2174
        // orphan: the jar stays "self-describing" with an obsolete manifest until a clean build.
        if (!Files.isRegularFile(src)) return Files.isRegularFile(copy);
        try {
            if (!Files.isRegularFile(copy)) return true;
            if (Files.size(copy) != Files.size(src)) return true;
            if (Files.getLastModifiedTime(src).compareTo(Files.getLastModifiedTime(copy)) > 0
                    && Files.mismatch(src, copy) >= 0) {
                return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    static boolean resourcesOutOfSync(Path resDir, Path outDir) {
        if (!Files.isDirectory(resDir)) return false;
        try (var stream = Files.walk(resDir)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(source)) continue;
                Path copy = outDir.resolve(resDir.relativize(source));
                if (!Files.isRegularFile(copy)) return true;
                if (Files.size(copy) != Files.size(source)) return true;
                if (Files.getLastModifiedTime(source).compareTo(Files.getLastModifiedTime(copy)) > 0
                        && Files.mismatch(source, copy) >= 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true; // unreadable ⇒ treat as dirty
        }
    }

    /** Map a {@link JavaIncrementalCompile.Prediction} to a step, honoring upstream dirtiness. */
    private static TaskForecast.Task compileStep(
            String name, JavaIncrementalCompile.Prediction pred, boolean compileDepDirty) {
        return switch (pred.outcome()) {
            case CACHE_HIT ->
                // Only force RUN when a *compile-scope* sibling is dirty (action key still sees
                // the pre-rebuild jar). Test-only siblings never reach here as compileDepDirty.
                compileDepDirty
                        ? new TaskForecast.Task(name, TaskForecast.Status.RUN, "recompile · dependency changed", null)
                        : new TaskForecast.Task(name, TaskForecast.Status.CACHED, "", key8(pred.actionKey()));
            case INCREMENTAL -> {
                String detail = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : count(pred.sourceCount(), "source") + " changed";
                yield new TaskForecast.Task(name, TaskForecast.Status.PARTIAL, "compile · " + detail, null);
            }
            case FULL -> {
                // surface the concrete gate (classpath, options, first compile, …).
                String why = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : "sources / options / classpath";
                yield new TaskForecast.Task(
                        name,
                        TaskForecast.Status.FULL,
                        "full compile · " + count(pred.sourceCount(), "source") + " · " + why,
                        null);
            }
        };
    }

    // --- the build's test classpaths, mirrored (best-effort; misses fail safe) ---

    private static List<Path> testCompileClasspath(Path dir, JkBuild project, Lockfile lock, ClasspathResolver resolver)
            throws IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
        cp.addAll(sib.jars());
        for (Path sl : sib.siblingLockfiles()) {
            try {
                Lockfile s = LockfileReader.read(sl);
                for (Path p : resolver.classpathFor(s, ClasspathResolver.COMPILE_MAIN)) if (!cp.contains(p)) cp.add(p);
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        return cp;
    }

    private static List<Path> testRuntimeClasspath(Path dir, JkBuild project, Lockfile lock, ClasspathResolver resolver)
            throws IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.TEST));
        cp.addAll(sib.jars());
        for (Path sl : sib.siblingLockfiles()) {
            try {
                Lockfile s = LockfileReader.read(sl);
                for (Path p : resolver.classpathFor(s, ClasspathResolver.RUNTIME)) if (!cp.contains(p)) cp.add(p);
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        return cp;
    }

    /**
     * True when the stamp-language compile ({@code compile-kotlin} / {@code compile-groovy}) has a
     * surviving action-cache pointer whose payloads are still present — the post-{@code jk clean}
     * restore path. Never-built modules have no {@code tasks/} pointer.
     */
    static boolean stampLangActionPresent(ActionCache ac, String taskId) {
        try {
            var rec = ac.lastFor(taskId);
            return rec.isPresent() && present(ac, rec.get().actionKey());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Record exists AND every <em>payload</em> blob is still in the action cache's CAS. LRU
     * eviction removes payloads while their records live on (records die by TTL), and a record
     * whose blobs are gone cannot restore — forecasting it CACHED would over-promise: wrong
     * {@code jk explain}, undercounted dirty set, deflated ETA seed.
     *
     * <p>Only 64-char hex values are payload digests. Marker records (run-tests green stamp)
     * park small scalars such as {@code tests.total=0} in the same map — those are not CAS
     * keys and must not fail the presence check, or a successful empty/green suite is forever
     * forecast as dirty (plan shows {@code run-tests [run]} after every build).
     *
     * <p>Presence check only ({@code pathFor} + {@code isRegularFile}); never hashes bytes.
     */
    static boolean present(ActionCache ac, String key) {
        try {
            var rec = ac.lookup(key);
            if (rec.isEmpty()) return false;
            for (String sha : rec.get().outputs().values()) {
                if (!isSha256Hex(sha)) continue; // marker scalar, not a CAS blob
                if (!Files.isRegularFile(ac.cas().pathFor(sha))) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Same shape {@link ActionCache} meters by — 64-char hex digests only. */
    static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static String key8(String key) {
        return key != null && key.length() >= 8 ? key.substring(0, 8) : key;
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
