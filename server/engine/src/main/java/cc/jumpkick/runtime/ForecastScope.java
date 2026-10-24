// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * One module's forecast while the walk prices its plan, step by step: what every predictor reads
 * of the module ({@link #prepared}, its test sources), what the steps it requires left for it
 * (compile dirtiness, the keys a wiped tree is projected from, resource drift), the workspace state
 * it keys siblings by ({@link #restored}, the dependencies' dirtiness and API hints), and the
 * verdicts so far. Each step's predictor lives beside the step's body, in its planner, and reads
 * these fields; the walk ({@code TaskForecaster}) runs them in the plan's order and adds the
 * module verdicts ({@code restore-outputs}, {@code order-check}) once every step is priced.
 */
public final class ForecastScope implements StepForecast.Scope {

    final BuildGraph.BuildUnit u;
    final TaskForecaster.DepDirtiness dep;
    final boolean force;
    final boolean skipTests;
    final Cas cas;
    final ClasspathResolver resolver;
    final ActionCache actionCache;
    final Path cache;

    /**
     * What the walk knows the build restores before a consumer keys on it: wiped sibling trees by
     * the token and identity of the tree that comes back, wiped jars by their payload sha. Every
     * predictor that keys on a sibling reads through it; this module publishes its own trees and
     * jar here once their records are known.
     */
    final RestoredOutputs restored;

    final Map<Path, TaskForecaster.ModuleHint> hints;
    final WorkspaceTarget target;
    final @Nullable Path workerJar;
    final @Nullable String profileName;
    final JkBuild project;
    final Path dir;
    final Path lockFile;

    final boolean compileDepDirty;
    final boolean testDepDirty;

    /** What this module's dirty compile-scope dependencies' edits look like, for its compile texts. */
    final TaskForecaster.DepHint depHint;

    /** A main compile, or a compile-scope sibling's, will run: everything packaged from the classes is dirty. */
    boolean compileDirty;

    /**
     * True when the module's compile is dirty only because a compile-scope sibling is rebuilding —
     * its own key hit — so the package and test steps that follow are dirty for the sibling's jar
     * bytes, not for anything of this module's, and say so.
     */
    boolean depOnlyDirty;

    /** What this module's own edit looks like to its consumers; published into {@link #hints}. */
    SourceApiIndex.Hint ownHint = SourceApiIndex.Hint.UNKNOWN;

    /** The keys each compile priced by: the records a wiped classes or test tree is projected from. */
    @Nullable
    String compileMainKey, compileKotlinKey, compileGroovyKey;

    @Nullable
    String compileTestKey, compileTestKotlinKey, compileTestGroovyKey;

    /** The compile-main classpath, once compile-java has derived it; the javadoc predictor keys on it. */
    List<Path> mainCp = List.of();

    /** A test compile, or the fixtures it reads, will run: the suite runs. */
    boolean testDirty;

    /** Main resources drifted from the classes tree (null when the compile runs and drift is moot). */
    @Nullable
    Boolean knownResourceDrift;

    boolean mainResourceDrift;

    /** {@code [build-info]} would rewrite its files: the checkout moved since the classes tree was written. */
    boolean buildInfoDrift;

    /** The native binary this build must bring back: absent on disk while its step forecasts a restore. */
    boolean nativeBinaryToRestore;

    int testCount;

    private @Nullable Prepared prepared;
    private PlannerTest.@Nullable TestSources testSources;
    private List<Path> testCompileCp = List.of();
    private boolean ownOutputsProjected;
    private final Map<String, TaskForecast.Task> verdicts = new LinkedHashMap<>();
    private final Map<String, Callable<TaskForecast.@Nullable Task>> deferred = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final List<TaskForecast.Task> moduleVerdicts = new ArrayList<>();
    private @Nullable String pricing;
    private @Nullable Map<String, TaskForecast.Task> nodeVerdicts;

    /** A dedicated node module: its steps are node's, and it has no JVM sources to count or restore. */
    final boolean nodeModule;

    ForecastScope(
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
            @Nullable Path workerJar,
            @Nullable String profileName) {
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
        this.workerJar = workerJar;
        this.profileName = profileName;
        this.project = ForecastSteps.planned(u.manifest(), u.dir());
        this.dir = u.dir();
        this.lockFile = LockPaths.lockFile(dir);
        this.compileDepDirty = dep.compileDepDirty();
        this.testDepDirty = dep.testDepDirty();
        this.depHint = TaskForecaster.depHint(dep.compileDeps(), hints);
        // Only compile-scope dirty siblings force main recompile (and package/native cascade).
        // Test-only siblings (cli's jk-engine test-dep) leave main clean.
        this.compileDirty = compileDepDirty || force;
        this.nodeModule = NodeShape.kind(project, dir) == NodeShape.Kind.MODULE;
    }

    /**
     * A scope for pricing one module's tail on its own, outside a walk: no sibling is dirty and
     * nothing is restored ahead of it, so its steps answer from the caches alone.
     */
    public static ForecastScope alone(BuildGraph.BuildUnit unit, Path cache, ActionCache actionCache) {
        Cas cas = JkStores.storeCas();
        return new ForecastScope(
                unit,
                TaskForecaster.DepDirtiness.NONE,
                false,
                false,
                cas,
                new ClasspathResolver(cas),
                actionCache,
                cache,
                new RestoredOutputs(actionCache),
                new ConcurrentHashMap<>(),
                WorkspaceTarget.PACKAGE,
                null,
                null);
    }

    /** The engine's scope behind a predictor's argument. */
    static ForecastScope of(StepForecast.Scope scope) {
        if (scope instanceof ForecastScope s) return s;
        throw new IllegalArgumentException("not an engine forecast scope: " + scope);
    }

    /** Resolved once, the first time a predictor needs it; never rewritten. */
    record Prepared(
            Lockfile lock,
            boolean compact,
            BuildLayout layout,
            int release,
            Path javaHome,
            List<String> javacArgs,
            List<Path> processorCp,
            List<Path> testProcessorCp,
            ActivePlugins.@Nullable Declared plugin,
            @Nullable PluginDeclarations pkgDecls,
            Path mainSrcDir,
            List<Path> mainSrc,
            List<Path> ktSrc,
            List<Path> gvSrc,
            boolean mixedKotlin,
            boolean mixedGroovy,
            boolean kotlinModule,
            ProvidedClasspath.Provided provided) {

        /** The main compile classpath the build derives: the lock's, the siblings', then the plugin-provided jars. */
        List<Path> withProvided(List<Path> cp) {
            List<Path> out = new ArrayList<>(cp);
            out.addAll(provided.compile());
            return out;
        }

        boolean noSources() {
            return mainSrc.isEmpty() && ktSrc.isEmpty() && gvSrc.isEmpty();
        }
    }

    Prepared prepared() throws Exception {
        Prepared p = prepared;
        if (p == null) prepared = p = prepare();
        return p;
    }

    private Prepared prepare() throws Exception {
        // The lock as this member reads it, the view the build compiles against: a partition row
        // the member does not hold must not put its jar on this module's processor path.
        Lockfile lock = MemberRows.view(LockfileReader.read(lockFile), lockFile, dir);
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
        // The build's processor classpath exactly — workspace siblings included — or the forecast
        // hashes a different -processorpath than the build and every KSP module forecasts a
        // phantom rebuild.
        List<Path> processorCp = PlannerSupport.processorClasspath(
                dir, project, lock, resolver, WorkspaceClasspath.resolve(dir, project, Set.of(Scope.PROCESSOR)), false);
        List<Path> testProcessorCp = ProcessorPaths.forecastTest(project, lock, resolver, dir, processorCp);
        // Resolved once: the declarations decide both the generated source roots compile-main
        // folds in and, at package time, whether jk packs the jar or a plugin does.
        ActivePlugins.@Nullable Declared plugin = PackagingKeys.pluginFor(project, layout, cache);
        @Nullable PluginDeclarations pkgDecls = plugin == null ? null : plugin.decls();
        Path mainSrcDir = compact ? dir.resolve("src") : dir.resolve("src/main/java");
        InputTrees.coverModule(dir);
        // The source set the build compiles, derived by its owner: the src walk plus the
        // [build] extra-src overlay, plugin source roots, every .scala (one Zinc session
        // compiles both languages) and the generated roots.
        List<Path> mainSrc = PlannerCompile.mainJavaSources(
                PlannerCompile.javaAndScalaSources(
                        project, dir, compact, CompileSupport.collectJavaSources(mainSrcDir)),
                layout,
                pkgDecls);
        List<Path> ktSrc = PlannerCompile.mainKotlinSourcesWithGenerated(
                PlannerCompile.mainKotlinSources(project, dir, compact), layout, pkgDecls);
        List<Path> gvSrc = PlannerCompile.mainGroovySourcesWithGenerated(
                PlannerCompile.mainGroovySources(project, dir, compact), layout, pkgDecls);
        // The same predicate the planner composes the plan from — a source-list emptiness test
        // is a different question and answers differently for a Scala module.
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
                testProcessorCp,
                plugin,
                pkgDecls,
                mainSrcDir,
                mainSrc,
                ktSrc,
                gvSrc,
                mixedKotlin,
                mixedGroovy,
                langs.kotlin(),
                // The build adds these to every compile and test classpath (PlannerSetup), so a
                // key priced without them names a compile the build never makes.
                ProvidedClasspath.forecast(project, dir, lockFile, cas, !skipTests));
    }

    /** The suites this session selected, as the build's source lists see them (empty when unreadable). */
    PlannerTest.TestSources testSources() throws Exception {
        PlannerTest.TestSources t = testSources;
        if (t != null) return t;
        Prepared p = prepared();
        var selection = SessionContext.current().testSelection();
        try {
            t = PlannerTest.TestSources.collect(
                    project,
                    dir,
                    p.compact(),
                    TestSupport.selectedSuites(dir, p.compact(), project.build(), selection),
                    p.layout(),
                    p.pkgDecls());
        } catch (IOException e) {
            t = new PlannerTest.TestSources(Path.of(""), List.of(), List.of(), List.of(), List.of());
        }
        List<Path> testCp = new ArrayList<>(PlannerSupport.testCompileClasspath(dir, project, p.lock(), resolver));
        testCp.addAll(p.provided().compile());
        testCp.addAll(p.provided().testOnly());
        testCompileCp = testCp;
        return testSources = t;
    }

    /** The test compile classpath the build derives (the plugin-provided jars included). */
    List<Path> testCompileCp() throws Exception {
        testSources();
        return testCompileCp;
    }

    boolean haveTests() throws Exception {
        return !testSources().isEmpty();
    }

    /**
     * Publish this module's own classes tree for what keys on it next — this module's tests and
     * packaging, and the consumers that follow it in the walk — when the tree is not whole on disk
     * but every compile that writes into it is answered by a record: the build restores the tree
     * from those records before anything keys on it. The tree is the merge the build assembles —
     * javac's outputs, then each other compiler's copied over them, then the resource roots and a
     * plugin worker's module-root manifest. A compile that will run projects nothing: what keys on
     * it is dirty on its account already. Once per module, after its compiles are priced.
     */
    void projectOwnOutputs() throws Exception {
        if (ownOutputsProjected) return;
        ownOutputsProjected = true;
        if (compileDirty) return;
        Prepared p = prepared();
        try {
            if (classesTreeWhole(p.layout())) return;
            List<@Nullable String> keys = new ArrayList<>();
            if (!p.mainSrc().isEmpty()) keys.add(compileMainKey);
            if (!p.ktSrc().isEmpty()) keys.add(compileKotlinKey);
            if (!p.gvSrc().isEmpty()) keys.add(compileGroovyKey);
            restored.projectFromRecords(
                    p.layout().classesDir(),
                    keys,
                    PackagingKeys.packageResourceRoots(dir, p.compact()),
                    PackagingKeys.copiedPluginManifest(dir));
        } catch (IOException e) {
            // Without the projection a consumer keys on the tree's absence: the pessimistic
            // answer, never a false hit.
            Log.debug("projectOwnOutputs: consumers key on the tree as it is", e);
        }
    }

    /** The Kotlin and Groovy arms, over this module's dirtiness and its dependencies' hint. */
    ForecastLangArms arms() {
        return new ForecastLangArms(
                project, dir, cache, cas, resolver, actionCache, compileDepDirty, force, depHint, restored);
    }

    /** A dedicated node module's node directory, or null for any other module. */
    @Nullable
    Path nodeDir() {
        return nodeModule ? Objects.requireNonNull(NodeShape.nodeDir(project, dir), "node directory") : null;
    }

    /**
     * A node step's price in a dedicated node module: install, build, test and the declared steps
     * are priced together, once, against the module's lock token. Null in any other module, where a
     * node unit rides with the JVM steps around it.
     */
    TaskForecast.@Nullable Task nodeVerdict(String step) throws Exception {
        Path nodeDir = nodeDir();
        if (nodeDir == null) return null;
        Map<String, TaskForecast.Task> v = nodeVerdicts;
        if (v == null) {
            v = new LinkedHashMap<>();
            for (TaskForecast.Task t : PlannerNode.forecast(
                    project,
                    dir,
                    nodeDir,
                    LockfileReader.read(lockFile),
                    actionCache,
                    skipTests,
                    SessionContext.current().skipNode())) {
                v.put(t.name(), t);
            }
            nodeVerdicts = v;
        }
        return v.get(step);
    }

    /** The verdict a step this one requires left, or null when it reported none. */
    TaskForecast.@Nullable Task verdict(String step) {
        return verdicts.get(step);
    }

    /** True when {@code step} was priced and will run. */
    boolean runs(String step) {
        TaskForecast.Task v = verdicts.get(step);
        return v != null && !v.cached();
    }

    /**
     * Price the current step once every step is priced, for a step whose price depends on work
     * that runs after it (a plugin step ahead of the compile, priced by whether the module
     * compiles). The predictor returns what this returns: nothing, until then.
     */
    TaskForecast.@Nullable Task defer(Callable<TaskForecast.@Nullable Task> price) {
        deferred.put(Objects.requireNonNull(pricing, "defer outside a predictor"), price);
        return null;
    }

    /** Why the module runs before any step is priced (no lock, or a lock jk.toml moved past), or null. */
    @Nullable
    String unlocked() {
        if (!Files.isRegularFile(lockFile)) return "not locked yet (run `jk build`)";
        // Digest-only staleness — the same predicate the build's freshen uses, so the forecast
        // and the live build agree on whether a lock update runs.
        if (AutoLock.isStale(dir, lockFile)) return "jk.toml changed — lock update needed";
        return null;
    }

    /** Ask {@code step}'s predictor for its verdict and keep it under the step's name. */
    void price(String step, StepForecast.Predictor predictor) throws Exception {
        pricing = step;
        order.add(step);
        try {
            record(step, predictor.predict(this));
        } finally {
            pricing = null;
        }
    }

    /** {@code step} runs on every invocation: no key proves it done. */
    void always(String step, String why) {
        order.add(step);
        record(step, new TaskForecast.Task(step, TaskForecast.Status.RUN, why, null));
    }

    void record(String step, TaskForecast.@Nullable Task verdict) {
        if (verdict == null) return;
        verdicts.put(
                step,
                step.equals(verdict.name())
                        ? verdict
                        : new TaskForecast.Task(step, verdict.status(), verdict.text(), verdict.key()));
    }

    /**
     * The module verdicts, once every step is priced: the deferred steps, the restore gate, the
     * order-after gate, and this module's API hint for the consumers after it.
     */
    TaskForecast.Module finish() throws Exception {
        for (var e : deferred.entrySet()) record(e.getKey(), e.getValue().call());
        List<TaskForecast.Task> steps = new ArrayList<>();
        if (nodeModule) {
            publishHint();
            for (String step : order) {
                TaskForecast.Task v = verdicts.get(step);
                if (v != null) steps.add(v);
            }
            // Its outputs are what a JVM sibling compiles and runs against: when every step is cached
            // and they are gone (a jk clean), the module restores them, or the sibling is admitted
            // against outputs nothing produces. The same judgment the preflight memo makes.
            if (allCached() && ModuleOutputs.nodeOutputsMissing(dir, project, actionCache)) {
                steps.add(new TaskForecast.Task(
                        TaskNames.RESTORE_OUTPUTS, TaskForecast.Status.RUN, "restore from cache", null));
            }
            return new TaskForecast.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
        }
        projectOwnOutputs();
        restore();
        orderAfter();
        publishHint();
        Prepared p = prepared();
        for (String step : order) {
            TaskForecast.Task v = verdicts.get(step);
            if (v != null) steps.add(v);
        }
        steps.addAll(moduleVerdicts);
        int sourceCount = p.mainSrc().size()
                + p.ktSrc().size()
                + p.gvSrc().size()
                + testSources().all().size()
                + PlannerFixtures.forecastSources(project, dir).size();
        boolean producesImage =
                project.image().base() != null || project.image().registry() != null;
        return new TaskForecast.Module(
                u.dir(), u.coord(), steps, sourceCount, testCount, !p.noSources(), producesImage);
    }

    /** The module when its lock must be written first: each priced or always-run step of {@code plan} runs. */
    TaskForecast.Module unlocked(List<Task> plan, String why) {
        List<TaskForecast.Task> steps = new ArrayList<>();
        for (Task t : plan) {
            if (t.forecast() instanceof StepForecast.Priced || t.forecast() instanceof StepForecast.Always) {
                steps.add(new TaskForecast.Task(t.name(), TaskForecast.Status.RUN, why, null));
            }
        }
        publishHint();
        return new TaskForecast.Module(u.dir(), u.coord(), steps, 0, 0, false, false);
    }

    /** The module as far as it could be forecast, with why it runs regardless. */
    TaskForecast.Module failed(String reason) {
        ownHint = SourceApiIndex.Hint.UNKNOWN;
        publishHint();
        return new TaskForecast.Module(u.dir(), u.coord(), List.of(), 0, 0, false, false, reason);
    }

    private void publishHint() {
        hints.put(
                dir.toAbsolutePath().normalize(),
                new TaskForecaster.ModuleHint(project.project().name(), ownHint));
    }

    private boolean allCached() {
        return verdicts.values().stream().allMatch(TaskForecast.Task::cached);
    }

    private void restore() throws Exception {
        Prepared p = prepared();
        boolean compact = p.compact();
        BuildLayout layout = p.layout();
        // ---- restore gate ----
        // Right after jk clean every step can predict CACHED (action keys survive the wipe by
        // design), but a clean-forecast module is never scheduled, so nothing restores target/
        // and workspace links dangle. When the promised outputs are absent, add a restore
        // verdict: the module schedules and its steps resolve as cheap cache restores. It is a
        // module verdict, not a compile or package step, so it never seeds downstream
        // dirtiness — restored outputs are byte-identical to what consumers hashed.
        if (!allCached()) return;
        boolean outputsAbsent = false;
        // A non-empty tree that lacks an output its compile record owns is a missing output
        // too: the key hits and every class file present is current, yet the jar packaged
        // from the tree would lack the same classes, build after build, until something
        // else moved the key. The restore brings the record's whole tree back.
        boolean incomplete = false;
        if (!p.noSources()) {
            incomplete = TaskForecaster.classesDirHasContent(layout.classesDir())
                    && !compileOutputsOnDisk(layout.classesDir());
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
        // yet --skip-tests leaves it unproduced while the main outputs are current.
        boolean haveTests = haveTests();
        boolean testViewAbsent = !skipTests && ModuleOutputs.testViewMissing(layout, project, dir, () -> haveTests);
        if (outputsAbsent || testViewAbsent) {
            String why = incomplete
                    ? "restore from cache · classes tree incomplete"
                    : outputsAbsent ? "restore from cache" : "restore from cache · test view absent";
            moduleVerdicts.add(new TaskForecast.Task(TaskNames.RESTORE_OUTPUTS, TaskForecast.Status.RUN, why, null));
        }
    }

    /**
     * A dirty order-after-only prereq prices nothing, but the module must still schedule: its real
     * action keys are what re-check the prereq's out-of-band outputs (a rebuilt test-plugin jar
     * hashed by the run-tests stamp). Unchanged inputs resolve as cheap cache hits at execute.
     */
    private void orderAfter() {
        if (dep.orderDepDirty() && allCached() && moduleVerdicts.isEmpty()) {
            moduleVerdicts.add(new TaskForecast.Task(
                    TaskNames.ORDER_CHECK, TaskForecast.Status.RUN, "ordered-after sibling rebuilding", null));
        }
    }

    /**
     * True when the classes tree is present and holds every output its compile record owns —
     * the tree a live package or test step would hash. Anything else is priced from the record,
     * as after {@code jk clean}, so an incomplete tree forecasts a restore rather than a
     * repackage of the tree it happens to have.
     */
    boolean classesTreeWhole(BuildLayout layout) throws IOException {
        return TaskForecaster.classesDirHasContent(layout.classesDir()) && compileOutputsOnDisk(layout.classesDir());
    }

    /** The tree holds every output of every compile merged into it: javac's, Kotlin's and Groovy's. */
    private boolean compileOutputsOnDisk(Path classesDir) {
        return ModuleOutputs.compileOutputsOnDisk(
                actionCache, Arrays.asList(compileMainKey, compileKotlinKey, compileGroovyKey), classesDir);
    }
}
