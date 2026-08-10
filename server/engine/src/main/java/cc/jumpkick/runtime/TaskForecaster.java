// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.JavacLint;
import cc.jumpkick.config.ImageConfigParser;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.BuildIdentity;
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
        List<TaskForecast.Module> out = new ArrayList<>();
        // --force/--rerun bypasses jk's build caches, so every step runs — the forecast must say
        // so too (otherwise the plan tree renders "Fully Cached" while the ETA, which honors force,
        // predicts a full rebuild — a self-contradiction).
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false)
                || cc.jumpkick.config.SessionContext.current().config().rebuildOr(false);
        // Dirs whose *main output* will change this build — seeds downstream and
        // cross-module dirtiness. Filled as we walk in dependency order.
        Set<Path> dirty = new java.util.HashSet<>();
        // Jar CAS shas recovered from each walked module's CURRENT package-jar record —
        // consumers fingerprint wiped sibling jars from here, never from an unvalidated
        // last-record pointer (which may name a different edit of the sibling).
        Map<Path, String> restoredJarShas = new java.util.HashMap<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            boolean depDirty = false;
            for (Path dep : graph.edges().getOrDefault(u.dir(), Set.of())) {
                if (dirty.contains(dep)) {
                    depDirty = true;
                    break;
                }
            }
            long t0 = Perf.start();
            TaskForecast.Module m =
                    forecastModule(u, depDirty, force, skipTests, cas, actionCache, cache, restoredJarShas);
            Perf.end("forecast " + u.coord(), t0);
            // A module's consumed output changes — and so seeds downstream dirtiness
            // when its compile does real work (classes change) OR its jar will be
            // (re)packaged, or a dependency already changed. Package matters on its own:
            // a consumer's run-tests/compile classpath hashes the *content* of sibling
            // JARs, so an upstream whose compile is cached but whose jar is stale
            // repackages to a new jar and silently invalidates the consumer — which a
            // per-module lookup against the current (stale) jar would miss, falsely
            // reporting "cached". The build then reruns those steps and the live bar,
            // having reserved nothing for them, backslides. Seeding on package too keeps
            // the forecast pessimistic (safe) for the consumer.
            if (m.steps().stream()
                            .anyMatch(p -> !p.cached()
                                    && (p.name().startsWith("compile-main")
                                            || p.name().startsWith("compile-kotlin")
                                            || p.name().startsWith("compile-groovy")
                                            || p.name().startsWith("package-jar")))
                    || depDirty) {
                dirty.add(u.dir());
            }
            out.add(m);
        }
        return out;
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
        boolean compactEst = false;
        try {
            compactEst =
                    CompileSupport.isSimpleLayout(JkBuildParser.parse(buildFile).project(), dir);
        } catch (Exception ignored) {
            compactEst = !Files.isDirectory(dir.resolve("src/main/java"));
        }
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
                        java.util.Set.of(),
                        cc.jumpkick.config.SessionContext.current())
                .withProjectModules(projectModules);
    }

    private static TaskForecast.Module forecastModule(
            BuildGraph.BuildUnit u,
            boolean depDirty,
            boolean force,
            boolean skipTests,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            Map<Path, String> restoredJarShas) {
        JkBuild project = u.manifest();
        Path dir = u.dir();
        List<TaskForecast.Task> steps = new ArrayList<>();
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(dir);
        if (!Files.isRegularFile(lockFile)) {
            steps.add(new TaskForecast.Task(
                    "compile-main", TaskForecast.Status.RUN, "not locked yet (run `jk build`)", null));
            return new TaskForecast.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
        }
        // Digest-only staleness — the same predicate the build's freshen uses (JK-1358), so the
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

            boolean compileDirty = depDirty || force;
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
                if (!depDirty && !force && !groovyJarUnavailable) {
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
                    steps.add(compileStep("compile-main", pred, depDirty || force));
                    if (!steps.get(steps.size() - 1).cached()) compileDirty = true;
                }
            }

            // ---- compile-kotlin (best-effort: freshness stamp; no content key yet) ----
            if (!ktSrc.isEmpty()) {
                // The stamp lives with the MERGED classes (BuildPlanner writes it to
                // MAIN_CLASSES), not in kotlinc's incremental workspace under target/kotlin/main.
                // Reading the wrong directory never found a stamp, so every Kotlin module
                // forecast a full compile no matter how cached the build actually was.
                boolean fresh = !depDirty
                        && !force
                        && FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, ktSrc);
                steps.add(
                        fresh
                                ? new TaskForecast.Task("compile-kotlin", TaskForecast.Status.CACHED, "", null)
                                : new TaskForecast.Task(
                                        "compile-kotlin",
                                        TaskForecast.Status.FULL,
                                        "full compile · " + count(ktSrc.size(), "source"),
                                        null));
                if (!fresh) compileDirty = true;
            }

            // ---- compile-groovy (stamp-only, like Kotlin's — no content key yet) ----
            // The groovy stamp lives in the merged classes dir (where write-stamp-groovy
            // writes it), unlike Kotlin's forecast probe of kotlinClassesDir.
            if (!gvSrc.isEmpty()) {
                boolean fresh = !depDirty
                        && !force
                        && FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.GROOVY_STAMP, gvSrc);
                steps.add(
                        fresh
                                ? new TaskForecast.Task("compile-groovy", TaskForecast.Status.CACHED, "", null)
                                : new TaskForecast.Task(
                                        "compile-groovy",
                                        TaskForecast.Status.FULL,
                                        "full compile · " + count(gvSrc.size(), "source"),
                                        null));
                if (!fresh) compileDirty = true;
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
                if (compileDirty || testDirty) {
                    steps.add(
                            new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · " + tests, null));
                } else {
                    // Same factory as live run-testsdefault selection sources +
                    // worker/engine jar extras (nested-engine CLI included) so the key matches the
                    // stored green marker.
                    List<Path> testRt = testRuntimeClasspath(dir, project, lock, resolver);
                    long ts = Perf.start();
                    String stampKey =
                            BuildPlanner.runTestsStampKey(dir, project, compact, layout.classesDir(), lockFile, testRt);
                    Perf.end("  test-stamp-key", ts);
                    boolean hit = stampKey != null && present(actionCache, stampKey);
                    steps.add(
                            hit
                                    ? new TaskForecast.Task("run-tests", TaskForecast.Status.CACHED, "· " + tests, null)
                                    : new TaskForecast.Task(
                                            "run-tests", TaskForecast.Status.RUN, "run tests · " + tests, null));
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
                // "sibling not built" (JK-1648) — schedule it until its jar exists.
                if (!Files.isRegularFile(layout.mainJar())) {
                    steps.add(new TaskForecast.Task(
                            "package-jar", TaskForecast.Status.RUN, "package · module has no sources", null));
                }
            } else if (compileDirty) {
                steps.add(new TaskForecast.Task(
                        "package-jar", TaskForecast.Status.RUN, "repackage · compile changed", null));
            } else {
                Path jar = layout.mainJar();
                String mainClass = project.mainClass();
                long tp = Perf.start();
                byte[] sbom = null;
                if (project.isApplication()) {
                    try {
                        sbom = BuildPlanner.applicationSbom(project, lock, cas);
                    } catch (Exception ignored) {
                        // best-effort: missing SBOM → key still includes empty sbom: like a null sbom
                    }
                }
                String classesTok = classesTokenForPackage(dir, compact, layout, project, actionCache, compileMainKey);
                List<String> tokens = List.of(
                        "classes:" + classesTok,
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
                                : new TaskForecast.Task("package-jar", TaskForecast.Status.RUN, "repackage", null));
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
                            dir, project, layout, lockFile, actionCache, cache, compileMainKey, restoredJarShas);
                    steps.add(
                            hit
                                    ? new TaskForecast.Task("package-assembly", TaskForecast.Status.CACHED, "", null)
                                    : new TaskForecast.Task(
                                            "package-assembly", TaskForecast.Status.RUN, "repackage", null));
                }
            }

            // ---- native-image — [native] always = true (same opt-in as jk build) ----
            if (project.nativeMode() == cc.jumpkick.model.JkBuild.NativeMode.ALWAYS
                    && !(mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty())) {
                Path nativeOut = layout.nativeBinary();
                boolean hit = Files.isRegularFile(nativeOut) || Files.isRegularFile(layout.nativeLibrary());
                // Forecast is intentionally coarse: a present binary is treated as cached; a
                // full native action-key match needs the Graal home the live step resolved.
                if (compileDirty || !hit) {
                    steps.add(new TaskForecast.Task(
                            "native-image",
                            TaskForecast.Status.RUN,
                            compileDirty ? "rebuild · compile changed" : "native-image",
                            null));
                } else {
                    steps.add(new TaskForecast.Task("native-image", TaskForecast.Status.CACHED, "", null));
                }
            }

            // ---- resource drift ----
            // The scheduled build re-copies resource trees unconditionally (main → classes, test →
            // test classes) and its package/test keys then see the fresh bytes; a clean-skipped
            // module never does. Any drift ⇒ dirty.
            // After jk clean the classes tree is gone — missing copies are not "drift", they are
            // the restore path. Only compare when an output tree is present.
            if (!compileDirty && Files.isDirectory(layout.classesDir())) {
                if (resourcesOutOfSync(
                        cc.jumpkick.layout.ModuleLayout.mainResourcesDir(dir, compact), layout.classesDir())) {
                    steps.add(new TaskForecast.Task(
                            "copy-resources", TaskForecast.Status.RUN, "resources changed", null));
                } else if (extraResourcesOutOfSync(project, dir, layout.classesDir())) {
                    // extra-resources come from OUTSIDE the module, so the resource-root walk above
                    // cannot see them. Editing a plugin's jk-plugin.toml must still rebuild
                    // whatever bakes it in.
                    steps.add(new TaskForecast.Task(
                            "copy-resources", TaskForecast.Status.RUN, "extra resources changed", null));
                }
                if (haveTests && !skipTests && !testDirty && Files.isDirectory(layout.testClassesDir())) {
                    Path resTest = cc.jumpkick.layout.ModuleLayout.testResourcesDir(dir, compact);
                    if (resourcesOutOfSync(resTest, layout.testClassesDir())) {
                        steps.add(new TaskForecast.Task(
                                "copy-resources", TaskForecast.Status.RUN, "test resources changed", null));
                    }
                }
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
                } else if (!packageResourceRoots(dir, compact, project).isEmpty()) {
                    // Resources-only module: its classes tree (copied resources) is consumed
                    // straight off sibling classpaths, so an empty tree is a missing output too.
                    outputsAbsent = !classesDirHasContent(layout.classesDir());
                }
                if (outputsAbsent) {
                    steps.add(new TaskForecast.Task(
                            "restore-outputs", TaskForecast.Status.RUN, "restore from cache", null));
                }
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
     */
    static String classesTokenForPackage(
            Path dir,
            boolean compact,
            BuildLayout layout,
            JkBuild project,
            ActionCache actionCache,
            String compileMainKey)
            throws IOException {
        Path classesDir = layout.classesDir();
        if (classesDirHasContent(classesDir)) {
            return ClasspathFingerprint.entry(classesDir);
        }
        Map<String, String> compileOut = compileMainKey == null
                ? Map.of()
                : actionCache
                        .lookup(compileMainKey)
                        .map(ActionCache.ActionRecord::outputs)
                        .orElse(Map.of());
        List<Path> resRoots = packageResourceRoots(dir, compact, project);
        if (compileOut.isEmpty() && resRoots.isEmpty()) {
            return ClasspathFingerprint.entry(classesDir); // missing:… — package key will miss
        }
        return ClasspathFingerprint.entryFromCompileAndResources(compileOut, resRoots);
    }

    /** Resource roots that {@code copy-resources} merges into {@code classes/} (main + plugin + extra). */
    static List<Path> packageResourceRoots(Path dir, boolean compact, JkBuild project) {
        List<Path> resDirs = new ArrayList<>();
        Path resMain = cc.jumpkick.layout.ModuleLayout.mainResourcesDir(dir, compact);
        if (Files.isDirectory(resMain)) resDirs.add(resMain);
        for (var root : cc.jumpkick.layout.ModuleLayout.pluginContributedRoots(dir)) {
            if (!root.resource()) continue;
            Path r = dir.resolve(root.relative());
            if (Files.isDirectory(r)) resDirs.add(r);
        }
        // extra-resources are individual files — fold via a synthetic walk is awkward; ExtraResources
        // are checked separately when classes exist. After clean, compile+main-resources covers the
        // common monorepo case; extras still re-copy on the live path when the module runs.
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
     * must come from {@link BuildPlanner#assemblyDependencyJars} (JK-1345) — never the whole
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
            Map<Path, String> restoredJarShas)
            throws IOException {
        Path assemblyJar = layout.assemblyJar();
        String classesTok = classesTokenForPackage(
                dir,
                CompileSupport.isSimpleLayout(project.project(), dir),
                layout,
                project,
                actionCache,
                compileMainKey);
        // Same jar set as BuildPlanner.assemblyStep (ModuleRuntimeClasspath / JK-1345).
        List<Path> depJars = BuildPlanner.assemblyDependencyJars(dir, project, lockFile, cache);
        String depsTok = fingerprintDepJars(depJars, actionCache, restoredJarShas);
        List<String> tokens = List.of(
                "classes:" + classesTok,
                "deps:" + depsTok,
                "main:" + (project.mainClass() == null ? "" : project.mainClass()),
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
        parts.sort(java.util.Comparator.naturalOrder());
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
                // assembly forecast can match the stored key (JK-1369).
                return "file:" + sha;
            }
        }
        return ClasspathFingerprint.entry(jar); // missing:…
    }

    /** True when any file under {@code resDir} is missing from or differs from its copy in {@code outDir}. */
    /** True when any {@code [build] extra-resources} file differs from its copy under {@code outDir}. */
    static boolean extraResourcesOutOfSync(cc.jumpkick.model.JkBuild project, Path dir, Path outDir) {
        try {
            for (ExtraResources.Copy c : ExtraResources.resolve(project, dir)) {
                Path copy = outDir.resolve(c.destination());
                if (!Files.isRegularFile(copy)) return true;
                if (Files.size(copy) != Files.size(c.source())) return true;
                if (Files.getLastModifiedTime(c.source()).compareTo(Files.getLastModifiedTime(copy)) > 0
                        && Files.mismatch(c.source(), copy) >= 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true; // unreadable or unresolvable ⇒ treat as dirty
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
            String name, JavaIncrementalCompile.Prediction pred, boolean depDirty) {
        return switch (pred.outcome()) {
            case CACHE_HIT ->
                depDirty
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
            throws java.io.IOException {
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
            throws java.io.IOException {
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
     * Record exists AND every <em>payload</em> blob is still in the action cache's CAS. LRU
     * eviction removes payloads while their records live on (records die by TTL), and a record
     * whose blobs are gone cannot restore — forecasting it CACHED would over-promise: wrong
     * {@code jk explain}, undercounted dirty set, deflated ETA seed (JK-1529).
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
