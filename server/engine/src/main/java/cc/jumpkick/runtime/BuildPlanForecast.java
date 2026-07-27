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
import cc.jumpkick.task.TestStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Read-only step forecast for each module in a {@link BuildGraph}, using the same cache keys as the
 * real build. Dirty modules and their consumers are marked will-run in dependency order so
 * downstream keys are not trusted against about-to-change inputs (misses are pessimistic, never
 * false cache hits).
 */
public final class BuildPlanForecast {

    private BuildPlanForecast() {}

    /** Forecast every module in {@code graph}, in topological (dependency) order. */
    public static List<BuildPlan.Module> of(BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache) {
        return of(graph, cas, actionCache, cache, false);
    }

    /**
     * Like {@link #of(BuildGraph.Result, Cas, ActionCache, Path)} but omits test steps when
     * {@code skipTests} so a never-tested workspace is not forecast perpetually dirty.
     */
    public static List<BuildPlan.Module> of(
            BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache, boolean skipTests) {
        List<BuildPlan.Module> out = new ArrayList<>();
        // --force/--rerun bypasses jk's build caches, so every step runs — the forecast must say
        // so too (otherwise the plan tree renders "Fully Cached" while the ETA, which honors force,
        // predicts a full rebuild — a self-contradiction).
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false)
                || cc.jumpkick.config.SessionContext.current().config().rebuildOr(false);
        // Dirs whose *main output* will change this build — seeds downstream and
        // cross-module dirtiness. Filled as we walk in dependency order.
        Set<Path> dirty = new java.util.HashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            boolean depDirty = false;
            for (Path dep : graph.edges().getOrDefault(u.dir(), Set.of())) {
                if (dirty.contains(dep)) {
                    depDirty = true;
                    break;
                }
            }
            long t0 = Perf.start();
            BuildPlan.Module m = forecastModule(u, depDirty, force, skipTests, cas, actionCache, cache);
            Perf.end("forecast " + u.coord(), t0);
            // A module's consumed output changes — and so seeds downstream dirtiness —
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
     * The {@link BuildPipelines.Inputs} a real {@code jk build} constructs for one module — the
     * single factory both {@code jk build} ({@code BuildCommand.prepareModule}) and {@code jk
     * explain}'s ETA use, so the two can't drift in what they feed the effort-weight prediction.
     * The {@code jdksDir} default of {@code null} is load-bearing: it routes {@link
     * cc.jumpkick.runtime.EffortWeights#jdkWeight} through the full JDK probe chain (PATH /
     * JAVA_HOME / GraalVM / SDKMAN / …) instead of the empty {@code ~/.jk/jdks}, so an
     * already-installed JDK predicts a zero-cost {@code ensure-jdk} rather than a phantom download.
     */
    public static BuildPipelines.Inputs inputsFor(
            Path dir, Path cache, int workers, Path jdksDir, String profile, boolean skipTests, boolean verbose) {
        return inputsFor(dir, cache, workers, jdksDir, profile, skipTests, verbose, Set.of());
    }

    /**
     * As {@link #inputsFor(Path, Path, int, Path, String, boolean, boolean)} but carrying the sibling
     * module dirs of the build graph, so the effort-weight prediction can borrow a project-tier learned
     * rate for a not-yet-built module (see {@link cc.jumpkick.runtime.EffortWeights#learned}).
     */
    public static BuildPipelines.Inputs inputsFor(
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
     * testOnly} — when true, pipelines stop before packaging ({@code jk test} / MCP {@code jk_test}).
     */
    public static BuildPipelines.Inputs inputsFor(
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
        Path lockFile = dir.resolve("jk.lock");
        // 0 = auto at run-tests (JUnitLauncher); forecast treats as 1 for cost estimates.
        int workerCount = workers > 0 ? workers : 1;
        // testOnly still runs tests (never skip).
        boolean skip = testOnly ? false : skipTests;
        boolean compactEst = false;
        try {
            compactEst = CompileSupport.isSimpleLayout(JkBuildParser.parse(buildFile).project(), dir);
        } catch (Exception ignored) {
            compactEst = !Files.isDirectory(dir.resolve("src/main/java"));
        }
        int estimatedTestCount = skip ? 0 : TestSupport.estimateAllSuiteTestCount(dir, compactEst);
        return new BuildPipelines.Inputs(
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

    private static BuildPlan.Module forecastModule(
            BuildGraph.BuildUnit u,
            boolean depDirty,
            boolean force,
            boolean skipTests,
            Cas cas,
            ActionCache actionCache,
            Path cache) {
        JkBuild project = u.manifest();
        Path dir = u.dir();
        List<BuildPlan.Step> steps = new ArrayList<>();
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.isRegularFile(lockFile)) {
            steps.add(
                    new BuildPlan.Step("compile-main", BuildPlan.Status.RUN, "not locked yet (run `jk build`)", null));
            return new BuildPlan.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
        }
        // Two-tier lock check: mtime first (cheap), deep dep validation only when stale.
        if (cc.jumpkick.runtime.AutoLock.needsRelocking(dir, lockFile)) {
            steps.add(new BuildPlan.Step(
                    "compile-main", BuildPlan.Status.RUN, "jk.toml changed — lock update needed", null));
            return new BuildPlan.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
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
                            project, dir, BuildPipelines.lockModules(lock)),
                    List.of());
            List<Path> processorCp = resolver.classpathFor(lock, Set.of(Scope.PROCESSOR));

            boolean compileDirty = depDirty || force;

            // ---- compile-main (Java) ----
            Path mainSrcDir = compact ? dir.resolve("src") : dir.resolve("src/main/java");
            List<Path> mainSrc = CompileSupport.collectJavaSources(mainSrcDir);
            if (!mainSrc.isEmpty()) {
                WorkspaceClasspath.Result sib =
                        WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN));
                List<Path> cp = BuildPipelines.mainCompileClasspath(lock, resolver, sib);
                Path out = layout.classesDir();
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
                steps.add(compileStep("compile-main", pred, depDirty || force));
                if (!steps.get(steps.size() - 1).cached()) compileDirty = true;
            }

            // ---- compile-kotlin (best-effort: freshness stamp; no content key yet) ----
            List<Path> ktSrc = CompileSupport.collectKotlinSources(dir, compact);
            if (!ktSrc.isEmpty()) {
                boolean fresh = !depDirty
                        && !force
                        && FreshnessStamp.looksFresh(layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, ktSrc);
                steps.add(
                        fresh
                                ? new BuildPlan.Step("compile-kotlin", BuildPlan.Status.CACHED, "", null)
                                : new BuildPlan.Step(
                                        "compile-kotlin",
                                        BuildPlan.Status.FULL,
                                        "full compile · " + count(ktSrc.size(), "source"),
                                        null));
                if (!fresh) compileDirty = true;
            }

            // ---- compile-groovy (stamp-only, like Kotlin's — no content key yet) ----
            // The groovy stamp lives in the merged classes dir (where write-stamp-groovy
            // writes it), unlike Kotlin's forecast probe of kotlinClassesDir.
            List<Path> gvSrc = CompileSupport.collectGroovySources(dir, compact);
            if (!gvSrc.isEmpty()) {
                boolean fresh = !depDirty
                        && !force
                        && FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.GROOVY_STAMP, gvSrc);
                steps.add(
                        fresh
                                ? new BuildPlan.Step("compile-groovy", BuildPlan.Status.CACHED, "", null)
                                : new BuildPlan.Step(
                                        "compile-groovy",
                                        BuildPlan.Status.FULL,
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

            // ---- compile-test (all discovered suites — JK-1145) ----
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
                    steps.add(
                            new BuildPlan.Step("compile-test", BuildPlan.Status.RUN, "recompile · main changed", null));
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
                    BuildPlan.Step p = compileStep("compile-test", pred, false);
                    steps.add(p);
                    if (!p.cached()) testDirty = true;
                } else {
                    // Kotlin/Groovy-only tests: no content predictor — assume fresh when main is clean.
                    steps.add(new BuildPlan.Step("compile-test", BuildPlan.Status.CACHED, "", null));
                }

                // ---- run-tests ----
                int estimated = TestSupport.estimateAllSuiteTestCount(dir, compact);
                testCount = estimated;
                String tests = estimated > 0 ? "~" + count(estimated, "test") : "tests";
                if (compileDirty || testDirty) {
                    steps.add(new BuildPlan.Step("run-tests", BuildPlan.Status.RUN, "run tests · " + tests, null));
                } else {
                    // Mirror the build's run-tests stamp EXACTLY (JK-1243): `jk build` runs the
                    // DEFAULT suite selection, so the stamp's sources and resource dirs must be
                    // default-selection-scoped — the all-suite view above sizes compile-test,
                    // but feeding it into the stamp made the key never match the stored green
                    // marker on any module with a non-default suite.
                    List<String> defaultSuites = defaultSelectionSuites(dir, compact);
                    List<Path> stampSrcs = new ArrayList<>();
                    stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectJavaSources(dir, compact, defaultSuites));
                    stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectKotlinSources(dir, compact, defaultSuites));
                    stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectGroovySources(dir, compact, defaultSuites));
                    List<Path> testRt = testRuntimeClasspath(dir, project, lock, resolver);
                    long ts = Perf.start();
                    String stampKey = TestStamp.computeKey(
                            stampSrcs,
                            layout.classesDir(),
                            cc.jumpkick.layout.ModuleLayout.suiteResourceDirs(dir, compact, defaultSuites),
                            lockFile,
                            testRt,
                            BuildPipelines.testStampExtras(dir, project));
                    Perf.end("  test-stamp-key", ts);
                    boolean hit = stampKey != null && present(actionCache, stampKey);
                    steps.add(
                            hit
                                    ? new BuildPlan.Step("run-tests", BuildPlan.Status.CACHED, "· " + tests, null)
                                    : new BuildPlan.Step(
                                            "run-tests", BuildPlan.Status.RUN, "run tests · " + tests, null));
                }
            }

            // ---- package-jar ----
            // Tokens MUST match BuildPipelines.packageJarStep (classes/main/sbom/manifest).
            // Omitting sbom: caused perpetual "repackage" in explain while live build restored
            // the jar — cascading false depDirty downstream (JK-1176).
            if (mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty()) {
                // Source-less aggregator module — nothing to package.
            } else if (compileDirty) {
                steps.add(new BuildPlan.Step("package-jar", BuildPlan.Status.RUN, "repackage · compile changed", null));
            } else {
                Path jar = layout.mainJar();
                String mainClass = project.mainClass();
                long tp = Perf.start();
                byte[] sbom = null;
                if (project.isApplication()) {
                    try {
                        sbom = BuildPipelines.applicationSbom(project, lock, cas);
                    } catch (Exception ignored) {
                        // best-effort: missing SBOM → key still includes empty sbom: like a null sbom
                    }
                }
                List<String> tokens = List.of(
                        "classes:" + ClasspathFingerprint.entry(layout.classesDir()),
                        "main:" + (mainClass == null ? "" : mainClass),
                        "sbom:" + (sbom == null ? "" : cc.jumpkick.util.Hashing.sha256Hex(sbom)),
                        "manifest:" + project.manifest());
                Perf.end("  package-fingerprint", tp);
                String pkgKey = ActionKey.forArtifact(
                        ActionKey.qualifiedTaskId("package-jar", jar), BuildIdentity.cacheKeyVersion(), tokens);
                boolean hit = present(actionCache, pkgKey);
                steps.add(
                        hit
                                ? new BuildPlan.Step("package-jar", BuildPlan.Status.CACHED, "", key8(pkgKey))
                                : new BuildPlan.Step("package-jar", BuildPlan.Status.RUN, "repackage", null));
            }

            // ---- package-assembly (fat jar) — only when configured ----
            if (project.assembly() && !(mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty())) {
                boolean fresh = !compileDirty && Files.isRegularFile(layout.assemblyJar());
                steps.add(
                        fresh
                                ? new BuildPlan.Step("package-assembly", BuildPlan.Status.CACHED, "", null)
                                : new BuildPlan.Step("package-assembly", BuildPlan.Status.RUN, "repackage", null));
            }

            // ---- resource drift ----
            // The scheduled build re-copies resource trees unconditionally (main → classes, test →
            // test classes) and its package/test keys then see the fresh bytes; a clean-skipped
            // module never does. Any drift ⇒ dirty.
            if (!compileDirty) {
                if (resourcesOutOfSync(
                        cc.jumpkick.layout.ModuleLayout.mainResourcesDir(dir, compact), layout.classesDir())) {
                    steps.add(new BuildPlan.Step("copy-resources", BuildPlan.Status.RUN, "resources changed", null));
                }
                if (haveTests && !skipTests && !testDirty) {
                    Path resTest = cc.jumpkick.layout.ModuleLayout.testResourcesDir(dir, compact);
                    if (resourcesOutOfSync(resTest, layout.testClassesDir())) {
                        steps.add(new BuildPlan.Step(
                                "copy-resources", BuildPlan.Status.RUN, "test resources changed", null));
                    }
                }
            }
        } catch (Exception e) {
            // Degrade gracefully — never crash explain over one unparseable module.
            steps.add(new BuildPlan.Step(
                    "compile-main",
                    BuildPlan.Status.RUN,
                    "could not predict (" + e.getClass().getSimpleName() + ")",
                    null));
        }
        return new BuildPlan.Module(u.dir(), u.coord(), steps, sourceCount, testCount, producesJar, producesImage);
    }

    /** True when any file under {@code resDir} is missing from or differs from its copy in {@code outDir}. */
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
    private static BuildPlan.Step compileStep(String name, JavaIncrementalCompile.Prediction pred, boolean depDirty) {
        return switch (pred.outcome()) {
            case CACHE_HIT ->
                depDirty
                        ? new BuildPlan.Step(name, BuildPlan.Status.RUN, "recompile · dependency changed", null)
                        : new BuildPlan.Step(name, BuildPlan.Status.CACHED, "", key8(pred.actionKey()));
            case INCREMENTAL -> {
                String detail = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : count(pred.sourceCount(), "source") + " changed";
                yield new BuildPlan.Step(name, BuildPlan.Status.PARTIAL, "compile · " + detail, null);
            }
            case FULL -> {
                // JK-1058: surface the concrete gate (classpath, options, first compile, …).
                String why = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : "sources / options / classpath";
                yield new BuildPlan.Step(
                        name,
                        BuildPlan.Status.FULL,
                        "full compile · " + count(pred.sourceCount(), "source") + " · " + why,
                        null);
            }
        };
    }

    // --- the build's test classpaths, mirrored (best-effort; misses fail safe) ---

    private static List<Path> testCompileClasspath(Path dir, JkBuild project, Lockfile lock, ClasspathResolver resolver)
            throws java.io.IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
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

    /** The suites `jk build`'s DEFAULT selection resolves to (falls back to the default suite). */
    private static List<String> defaultSelectionSuites(Path dir, boolean compact) {
        List<String> discovered = cc.jumpkick.layout.TestSuites.discover(dir, compact);
        var resolved = cc.jumpkick.config.TestSelection.DEFAULT.resolve(discovered);
        return resolved.ok() ? resolved.suites() : List.of(cc.jumpkick.layout.TestSuites.DEFAULT);
    }

    private static List<Path> testRuntimeClasspath(Path dir, JkBuild project, Lockfile lock, ClasspathResolver resolver)
            throws java.io.IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
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

    private static boolean present(ActionCache ac, String key) {
        try {
            return ac.lookup(key).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private static String key8(String key) {
        return key != null && key.length() >= 8 ? key.substring(0, 8) : key;
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
