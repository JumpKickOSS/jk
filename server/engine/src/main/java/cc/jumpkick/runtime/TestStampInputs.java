// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.ModuleEnvKeys;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.SuiteSettings;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.FilteredResources;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.task.ToolIdentity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a module's run-tests stamp folds beside its source and resource files: the selection and
 * tier the suite runs under, the test JVM's flags, its runtime classpath and the identity tokens.
 * The live run, the forecast and the preflight memo all read them here, so the key a green run is
 * stored under is the key the forecast looks up.
 *
 * @param suites the suites the run runs, {@link #resolveSuites} over the effective {@code selection}:
 *     what compile-test collects, the suite filter admits and the stamp keys
 * @param jvmArgs the plugin steps' contributed flags, the module's and the profile's, then the
 *     tier's own: what rides the fork and its stamp alike
 * @param runtimeClasspath the resolved test runtime classpath with the module's fixtures and own
 *     versions, the plugin steps' contributed entries, then the provided jars last
 */
public record TestStampInputs(
        Facts facts,
        ClasspathFingerprint.EntryIdentity identity,
        TestSelection selection,
        TestSelection.Resolved suites,
        TestTier tier,
        List<String> jvmArgs,
        List<Path> runtimeClasspath) {

    /**
     * What the stamp is derived from. Reading these is the caller's business (awaiting a sibling,
     * resolving the lock); deriving the stamp from them is this record's.
     *
     * @param sessionSelection the request's selection, before the workspace's tags are resolved
     * @param workerJars the test worker jars by role, as {@link #testStampWorkerJars} lists them
     * @param resolvedRuntime the test runtime classpath the classpath step publishes ({@link ModuleClasspaths#testRuntime})
     * @param providedRuntime the provided jars the test JVM also gets last ({@link ModuleClasspaths#providedRuntime})
     * @param compileTestKeys the test compiles' action keys
     * @param javaHome the JDK the test JVM runs on: the module's resolved JDK, which a module with
     *     no JDK pin does not compile on, so no test compile key names it
     */
    record Facts(
            Path dir,
            JkBuild project,
            BuildLayout layout,
            Path javaHome,
            @Nullable String profileName,
            TestSelection sessionSelection,
            @Nullable PluginDeclarations pluginDecls,
            Map<String, String> workerJars,
            List<Path> resolvedRuntime,
            List<Path> providedRuntime,
            TestStamp.CompileTestKeys compileTestKeys) {

        /**
         * What the forecast reads of a module before its build: its test runtime and provided jars
         * as the classpath step publishes them, under the session's selection.
         */
        static Facts forecast(
                Path dir,
                JkBuild project,
                BuildLayout layout,
                Path javaHome,
                @Nullable String profileName,
                @Nullable PluginDeclarations pluginDecls,
                ModuleClasspaths classpaths,
                TestStamp.CompileTestKeys compileTestKeys)
                throws IOException {
            return new Facts(
                    dir,
                    project,
                    layout,
                    javaHome,
                    profileName,
                    SessionContext.current().testSelection(),
                    pluginDecls,
                    testStampWorkerJars(dir, project),
                    classpaths.testRuntime(),
                    classpaths.providedRuntime(),
                    compileTestKeys);
        }

        /**
         * The module on its own: the JDK the resolution walk finds for it, the session's selection,
         * no profile, plugin, classpath or test compile.
         */
        static Facts of(Path dir, JkBuild project) throws IOException {
            return new Facts(
                    dir,
                    project,
                    BuildLayout.of(dir, project),
                    JavaHomes.resolveJavaHome(dir),
                    null,
                    SessionContext.current().testSelection(),
                    null,
                    testStampWorkerJars(dir, project),
                    List.of(),
                    List.of(),
                    TestStamp.CompileTestKeys.NONE);
        }
    }

    /** The stamp inputs of {@code facts}, with the worker jars read through {@code identity}. */
    @KeyOwner(step = TaskNames.RUN_TESTS)
    static TestStampInputs of(Facts facts, ClasspathFingerprint.EntryIdentity identity) throws IOException {
        TestSelection selection = facts.sessionSelection();
        boolean compact = CompileSupport.isSimpleLayout(facts.project().project(), facts.dir());
        TestSelection.Resolved suites = resolveSuites(
                selection, facts.dir(), compact, facts.project().build().runSuites());
        TestTier tier = TestTier.of(selection, facts.profileName(), fastMethodBudget(facts.project(), facts.dir()));
        List<String> jvmArgs = new ArrayList<>(PlannerKsp.pluginTestJvmArgs(facts.layout(), facts.pluginDecls()));
        jvmArgs.addAll(testJvmArgs(facts.project(), facts.profileName()));
        List<Path> runtime = PlannerVersions.withOwnVersions(
                facts.project(),
                facts.layout(),
                PlannerFixtures.withOwnFixtures(facts.project(), facts.layout(), facts.resolvedRuntime()));
        List<Path> classpath = new ArrayList<>(runtime);
        classpath.addAll(PlannerKsp.pluginTestClasspath(facts.layout(), facts.pluginDecls()));
        classpath.addAll(facts.providedRuntime());
        return new TestStampInputs(
                facts,
                identity,
                selection,
                suites,
                tier,
                List.copyOf(tier.suiteJvmArgs(jvmArgs)),
                List.copyOf(classpath));
    }

    /**
     * The run-tests stamp a green run is stored under and the forecast looks up: the suite's sources
     * and resources, the main classes ({@code mainClassesFingerprint} stands for a tree {@code jk
     * clean} took, null to read it), the lock, this record's runtime classpath under its identity,
     * and {@code extras}. Null when an input could not be read.
     */
    @KeyOwner(step = TaskNames.RUN_TESTS)
    @Nullable
    String key(
            List<Path> sources,
            Path mainClasses,
            @Nullable String mainClassesFingerprint,
            List<Path> resources,
            Path lockFile,
            List<String> extras) {
        return TestStamp.computeKey(
                sources, mainClasses, mainClassesFingerprint, resources, lockFile, runtimeClasspath, extras, identity);
    }

    /**
     * The stamp's identity tokens: environment values, tool identities, the JVM flags, worker jars
     * and test compiles. Read again on every call, as a suite that recorded what it reads keys
     * those reads on its next stamp.
     */
    List<String> extras() throws IOException {
        List<String> extras = new ArrayList<>(
                testStampExtras(facts.workerJars(), selection, facts.project(), jvmArgs, facts.dir(), identity));
        extras.add(suitesToken(suites));
        String node = NodeTestStamp.nodeTestToken(facts.project(), facts.dir());
        if (node != null) extras.add(node);
        extras.addAll(SuiteEnvKeys.profileEnv(facts.project(), facts.profileName()));
        // The JDK the suite runs on decides what it computes; a patch update in place moves it too.
        extras.add("test-jdk:" + ActionKey.jdkToken(facts.javaHome()));
        return TestStamp.withCompileTest(extras, facts.compileTestKeys());
    }

    /**
     * The {@link TestStamp} key the live run-tests stores a green run under, for the forecast:
     * this runtime classpath and these extras over the files the selected suites read.
     * {@code mainClassesFingerprint} overrides the on-disk main-classes tree when non-null (a wiped
     * tree read as its compile record). The runtime classpath is read through {@link #identity}: after {@code jk clean} the sibling jars and the fixtures tree the stamp hashes are
     * gone until the build restores them, and the forecast reads each as the bytes that come back.
     */
    @Nullable
    String forecastKey(boolean compact, Path mainClasses, @Nullable String mainClassesFingerprint, Path lockFile)
            throws IOException {
        Path dir = facts.dir();
        JkBuild project = facts.project();
        // The suites the run runs, as the planner resolves them: --all widens the set, and the
        // forecast's source list must cover the same files the live run stamps.
        List<String> run = suites.ok() ? suites.suites() : List.of(TestSuites.DEFAULT);
        // The same factory compile-test collects its sources with, so the forecast stamps exactly
        // the files the run stamps: every selected suite's sources in every language, plus the
        // `[test] extra-src` files that belong to the tier and to no suite.
        BuildLayout layout = facts.layout();
        List<Path> stampSrcs = TestSources.collect(project, dir, compact, run, layout, facts.pluginDecls())
                .all();
        List<Path> stampRt = runtimeClasspath;
        List<String> stampExtras = extras();
        List<String> compiledSuites =
                TestSuites.compiled(dir, compact, run, project.build().runSuites());
        // As the live copy: the test KSP round's output roots, then the suites' resources.
        List<Path> stampRes = new ArrayList<>(PlannerKsp.outputRoots(PlannerKsp.kspTestOutBase(layout)));
        stampRes.addAll(ModuleLayout.suiteResourceDirs(dir, compact, compiledSuites));
        if (compiledSuites.contains(TestSuites.DEFAULT)) {
            // The live run keys the default suite's [resources] test-dirs and staged test-filtered
            // roots too. A staged root that no longer holds what its source expands to is one the
            // run restages: nothing on disk prices it, so the suite is forecast to run.
            if (FilteredResources.testStageOutOfSync(dir, project, layout)) return null;
            stampRes.addAll(FilteredResources.testRootsAsStaged(dir, project, layout));
        }
        String key = key(stampSrcs, mainClasses, mainClassesFingerprint, stampRes, lockFile, stampExtras);
        if (Perf.enabled()) {
            // The key itself, not just its inputs: when this disagrees with `live-test-stamp` for
            // the same module, the forecast is predicting a suite re-run the build will skip. That
            // is how `jk explain`'s missing test selection was found — every input printed here
            // matched and only the key differed, which narrowed it to the one input not printed.
            Perf.note(
                    "fstamp " + dir,
                    "key",
                    key,
                    "src",
                    stampSrcs.size(),
                    "res",
                    stampRes.size(),
                    "rt",
                    stampRt.size(),
                    "extras",
                    stampExtras.size(),
                    "X",
                    stampExtras,
                    "suites",
                    run,
                    "cpFp",
                    ClasspathFingerprint.of(stampRt, identity),
                    "mainFp",
                    mainClassesFingerprint != null ? mainClassesFingerprint : ClasspathFingerprint.entry(mainClasses));
        }
        return key;
    }

    /**
     * {@code [test] fast-method-budget} in milliseconds: the module's own, else its workspace
     * root's, so one line at the root holds every member to it; 0 when neither sets one.
     */
    private static long fastMethodBudget(JkBuild module, Path moduleDir) throws IOException {
        long own = module.build().testFastMethodBudgetMs();
        if (own > 0) return own;
        Path root = WorkspaceScan.owningRoot(moduleDir).orElse(null);
        if (root == null || root.normalize().equals(moduleDir.normalize())) return 0;
        return JkBuildParser.parseLocal(ManifestPaths.manifestIn(root)).build().testFastMethodBudgetMs();
    }

    /**
     * The suites a run of {@code moduleDir} runs under {@code selection}, resolved against the
     * module's suites, the built-in {@code integration} suite always among them ({@link
     * DeclaredSuite#withConvention}, so a caller handing the raw tables cannot lose it). The one
     * derivation of it: the planner's compile-test and suite filter, the stamp, the forecast and the
     * test-count estimate all read it here, so the suites a green marker is stored under are the
     * suites that ran.
     *
     * @param declared the module's {@link cc.jumpkick.model.BuildBlock#runSuites}
     */
    @KeyOwner(step = TaskNames.RUN_TESTS)
    public static TestSelection.Resolved resolveSuites(
            TestSelection selection, Path moduleDir, boolean compact, Map<String, DeclaredSuite> declared) {
        return TestSuites.resolve(selection, moduleDir, compact, DeclaredSuite.withConvention(declared));
    }

    /**
     * The stamp token of the suites the run runs: a suite's {@code explicit} flag changes the set
     * without changing any source the stamp hashes.
     */
    private static String suitesToken(TestSelection.Resolved suites) {
        return "suites-run:" + String.join(",", suites.suites())
                + (suites.ok() ? "" : "?missing=" + String.join(",", suites.missing()));
    }

    /**
     * Worker / engine jar props that feed both the forked test JVM and the {@link
     * TestStamp} extras. Includes declared {@code [build] test-plugin-jars} and,
     * for nested-engine CLI modules, every first-party {@link PluginJar} plus the engine assembly
     * forecast and live run-tests must hash the same set).
     */
    static Map<String, String> testStampWorkerJars(Path dir, JkBuild project) throws IOException {
        Map<String, String> props = new LinkedHashMap<>(
                SiblingJars.workerJarProps(dir, project.build().testPluginJars()));
        if (SelfHostEngine.needsNestedEngineIsolation(project)) {
            SelfHostEngine.enrichCliTestProps(dir, props);
        }
        return props;
    }

    /**
     * Every flag the module's forked test JVMs get beyond jk's own tuning: {@code [test] jvm-args},
     * one {@code -D} per {@code [test] system-properties} entry, then the active profile's {@code
     * jvm-args}, so the profile wins where they disagree. Empty when the module declares none and no
     * profile applies.
     */
    static List<String> testJvmArgs(JkBuild project, @Nullable String profileName) {
        Profile profile = CompileSupport.resolveProfile(project.profiles(), profileName);
        List<String> declared = project.build().testJvm().flags();
        if (profile == null || profile.jvmArgs().isEmpty()) return declared;
        if (declared.isEmpty()) return profile.jvmArgs();
        List<String> all = new ArrayList<>(declared);
        all.addAll(profile.jvmArgs());
        return List.copyOf(all);
    }

    /**
     * The stamp tokens of the selected suites' own JVM settings: they change what those suites'
     * tests see. An unselected suite's settings change nothing this run runs, so they stay out.
     */
    static List<String> suiteRunExtras(BuildBlock build, @Nullable TestSelection selection) {
        List<String> out = new ArrayList<>();
        build.runSuites().forEach((suite, spec) -> {
            SuiteSettings run = spec.settings();
            if (run.isEmpty() || selection == null || !selection.runs(suite, spec.explicit())) return;
            out.add("suite-run:" + suite + "=" + String.join(" ", run.forkArgs()) + ";env=" + run.env());
        });
        return out;
    }

    /**
     * Stamp extras. Declared {@code [env]} and {@code [test] env} values only, as {@link
     * ModuleEnvKeys#testJvm} keys them (sandbox defaults stay out — they are absolute paths that
     * would defeat cache sharing).
     */
    private static List<String> testStampExtras(
            Map<String, String> workerJars,
            TestSelection selection,
            JkBuild project,
            List<String> jvmArgs,
            Path moduleDir,
            ClasspathFingerprint.EntryIdentity identity) {
        BuildBlock build = project.build();
        List<String> extras = new ArrayList<>();
        extras.add("jk:" + BuildIdentity.cacheKeyVersion());
        // Suite + tag filters are part of the outcome.
        if (selection != null) extras.add("sel:" + selection.identityToken());
        // Whether the suite JVM ran with -ea decides what an `assert` did.
        extras.add("assertions:" + build.testAssertions());
        // Excluded classes are classes the suite does not run.
        if (!build.testExcludeClasses().isEmpty()) {
            extras.add("exclude-classes:" + String.join(",", build.testExcludeClasses()));
        }
        // A declared suite decides which tests the default suite and every other suite runs.
        build.runSuites()
                .forEach((suite, spec) -> extras.add("suite:" + suite + "=" + String.join(",", spec.classes())
                        + (spec.excludeClasses().isEmpty() ? "" : "!" + String.join(",", spec.excludeClasses()))
                        + (spec.tags().isEmpty() && spec.excludeTags().isEmpty()
                                ? ""
                                : "@" + String.join(",", spec.tags())
                                        + (spec.excludeTags().isEmpty()
                                                ? ""
                                                : "!" + String.join(",", spec.excludeTags())))));
        extras.addAll(suiteRunExtras(build, selection));
        // The test JVM's flags change what the suite sees (-D properties, heap), so they retest:
        // the module's, then the request's own, which the suite fork appends from the same source.
        if (!jvmArgs.isEmpty()) extras.add("jvm-args:" + String.join(" ", jvmArgs));
        List<String> userArgs = JvmOptions.suiteUserArgs();
        if (!userArgs.isEmpty()) extras.add("jvm-user-args:" + String.join(" ", userArgs));
        // What the test JVM is handed of the environment changes what the suite sees, so it retests.
        extras.addAll(ModuleEnvKeys.testJvm(project, moduleDir));
        // [test] tools by identity — where the name resolves on the PATH the test JVM gets and what
        // it says to --version — so a node or git upgrade retests the suites that shell out to it.
        String path = BuildEnv.machine().get("PATH");
        for (String tool : build.testTools()) {
            extras.add("tool:" + tool + "=" + ToolIdentity.of(tool, path, moduleDir));
        }
        // Plugin jars by content — a plugin change retests the module that forks it. A wiped
        // plugin jar the forecast knows the build restores reads as the bytes that come back.
        for (Map.Entry<String, String> e : workerJars.entrySet()) {
            String fp;
            try {
                fp = identity.of(Path.of(e.getValue()));
            } catch (IOException ex) {
                fp = "err";
            }
            extras.add("worker:" + e.getKey() + "=" + fp);
        }
        extras.addAll(SuiteEnvKeys.tokens(build, moduleDir, selection == null ? TestSelection.DEFAULT : selection));
        return extras;
    }
}
