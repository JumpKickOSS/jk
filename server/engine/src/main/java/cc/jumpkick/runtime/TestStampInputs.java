// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.FilteredResources;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.TestStamp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a module's run-tests stamp folds beside its source and resource files: the selection and
 * tier the suite runs under, the test JVM's flags, its runtime classpath and the identity tokens.
 * The live run, the forecast and the preflight memo all read them here, so the key a green run is
 * stored under is the key the forecast looks up.
 *
 * @param jvmArgs the plugin steps' contributed flags, the module's and the profile's, then the
 *     tier's own: what rides the fork and its stamp alike
 * @param runtimeClasspath the resolved test runtime classpath with the module's fixtures and own
 *     versions, the plugin steps' contributed entries, then the provided jars last
 */
record TestStampInputs(
        Facts facts,
        ClasspathFingerprint.EntryIdentity identity,
        TestSelection selection,
        TestTier tier,
        List<String> jvmArgs,
        List<Path> runtimeClasspath) {

    /**
     * What the stamp is derived from. Reading these is the caller's business (awaiting a sibling,
     * resolving the lock); deriving the stamp from them is this record's.
     *
     * @param sessionSelection the request's selection, before the workspace's tags are resolved
     * @param workerJars the test worker jars by role, as {@link PlannerSupport#testStampWorkerJars} lists them
     * @param resolvedRuntime the test runtime classpath the classpath step publishes: lock rows,
     *     sibling jars and the provided test-runtime jars ({@link ProvidedClasspath.Provided#onTestRuntime})
     * @param providedRuntime the provided jars the test JVM also gets last ({@link ProvidedClasspath.Provided#runtime})
     * @param compileTestKeys the test compiles' action keys
     */
    record Facts(
            Path dir,
            JkBuild project,
            BuildLayout layout,
            @Nullable String profileName,
            TestSelection sessionSelection,
            @Nullable PluginDeclarations pluginDecls,
            Map<String, String> workerJars,
            List<Path> resolvedRuntime,
            List<Path> providedRuntime,
            TestStamp.CompileTestKeys compileTestKeys) {

        /**
         * What the forecast reads of a module before its build: the lock's and siblings' test
         * runtime with the provided jars, as the classpath step publishes it, under the session's
         * selection.
         */
        static Facts forecast(
                Path dir,
                JkBuild project,
                BuildLayout layout,
                @Nullable String profileName,
                @Nullable PluginDeclarations pluginDecls,
                Lockfile lock,
                ClasspathResolver resolver,
                ProvidedClasspath.Provided provided,
                TestStamp.CompileTestKeys compileTestKeys)
                throws IOException {
            return new Facts(
                    dir,
                    project,
                    layout,
                    profileName,
                    SessionContext.current().testSelection(),
                    pluginDecls,
                    PlannerSupport.testStampWorkerJars(dir, project),
                    provided.onTestRuntime(PlannerSupport.testRuntimeClasspath(dir, project, lock, resolver)),
                    provided.runtime(),
                    compileTestKeys);
        }

        /** The module on its own: the session's selection, no profile, plugin, classpath or test compile. */
        static Facts of(Path dir, JkBuild project) throws IOException {
            return new Facts(
                    dir,
                    project,
                    BuildLayout.of(dir, project),
                    null,
                    SessionContext.current().testSelection(),
                    null,
                    PlannerSupport.testStampWorkerJars(dir, project),
                    List.of(),
                    List.of(),
                    TestStamp.CompileTestKeys.NONE);
        }
    }

    /** The stamp inputs of {@code facts}, with the worker jars read through {@code identity}. */
    static TestStampInputs of(Facts facts, ClasspathFingerprint.EntryIdentity identity) throws IOException {
        TestSelection selection =
                PlannerSupport.effectiveSelection(facts.sessionSelection(), facts.dir(), facts.profileName());
        TestTier tier = TestTier.of(selection, facts.profileName(), fastMethodBudget(facts.project(), facts.dir()));
        List<String> jvmArgs = new ArrayList<>(PlannerKsp.pluginTestJvmArgs(facts.layout(), facts.pluginDecls()));
        jvmArgs.addAll(PlannerSupport.testJvmArgs(facts.project(), facts.profileName()));
        List<Path> runtime = PlannerVersions.withOwnVersions(
                facts.project(),
                facts.layout(),
                PlannerFixtures.withOwnFixtures(facts.project(), facts.layout(), facts.resolvedRuntime()));
        List<Path> classpath = new ArrayList<>(runtime);
        classpath.addAll(PlannerKsp.pluginTestClasspath(facts.layout(), facts.pluginDecls()));
        classpath.addAll(facts.providedRuntime());
        return new TestStampInputs(
                facts, identity, selection, tier, List.copyOf(tier.suiteJvmArgs(jvmArgs)), List.copyOf(classpath));
    }

    /**
     * The stamp's identity tokens: environment values, tool identities, the JVM flags, worker jars
     * and test compiles. Read again on every call, as a suite that recorded what it reads keys
     * those reads on its next stamp.
     */
    List<String> extras() throws IOException {
        List<String> extras = new ArrayList<>(NodeTestStamp.testStampExtras(
                facts.workerJars(), selection, facts.project(), jvmArgs, facts.dir(), identity));
        extras.addAll(SuiteEnvKeys.profileEnv(facts.project(), facts.profileName()));
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
        List<String> discovered =
                TestSuites.available(dir, compact, project.build().testClassSuites());
        // Session selection for suite resolution too — --all widens the suite set, and the
        // forecast's source list must cover the same files the live run stamps.
        var resolved = SessionContext.current().testSelection().resolve(discovered);
        List<String> suites = resolved.ok() ? resolved.suites() : List.of(TestSuites.DEFAULT);
        // The same factory the live plan fills TEST_SOURCES from, so the forecast stamps exactly
        // the files the run stamps: every selected suite's sources in every language, plus the
        // `[test] extra-src` files that belong to the tier and to no suite.
        BuildLayout layout = facts.layout();
        List<Path> stampSrcs = TestSources.collect(project, dir, compact, suites, layout, facts.pluginDecls())
                .all();
        List<Path> stampRt = runtimeClasspath;
        List<String> stampExtras = extras();
        List<String> compiledSuites =
                TestSuites.compiled(dir, compact, suites, project.build().testClassSuites());
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
        String key = TestStamp.computeKey(
                stampSrcs, mainClasses, mainClassesFingerprint, stampRes, lockFile, stampRt, stampExtras, identity);
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
                    suites,
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
}
