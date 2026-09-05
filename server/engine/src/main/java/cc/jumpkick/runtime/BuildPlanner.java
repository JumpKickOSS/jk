// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.PlannerTails.appendDeclaredTails;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Variants;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClassAbi;
import cc.jumpkick.test.AffectedTests;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Assembles {@link BuildPlan} DAGs for build-family commands: core tasks via {@link #coreBuilder},
 * then command-specific tails via {@link PlannerTails#appendDeclaredTails}. Step bodies live in {@code Planner*}
 * collaborators. Not itself a runnable plan — callers {@code build()} the returned
 * {@link BuildPlan.Builder} and {@link BuildPlan#run() run} it.
 */
public final class BuildPlanner {

    static {
        // Wire session cancel into TaskContext.cancelled (lazy; pool tasks see it via
        // SessionContext propagation on JkThreads).
        SessionCancel.bind(() -> SessionContext.current().cancelled());
    }

    private BuildPlanner() {}

    // ---- shared cross-step keys ---------------------------------------
    public static final BuildPlanKey<JkBuild> PROJECT = BuildPlanKey.scalar("project", JkBuild.class);
    public static final BuildPlanKey<Lockfile> LOCKFILE = BuildPlanKey.scalar("lockfile", Lockfile.class);
    public static final BuildPlanKey<Path> JAVA_HOME = BuildPlanKey.scalar("java-home", Path.class);
    public static final BuildPlanKey<Integer> RELEASE = BuildPlanKey.scalar("release", Integer.class);

    public static final BuildPlanKey<List<Path>> CLASSPATH = BuildPlanKey.list("classpath", Path.class);

    public static final BuildPlanKey<List<Path>> JAVA_SOURCES = BuildPlanKey.list("java-sources", Path.class);

    public static final BuildPlanKey<List<Path>> KOTLIN_SOURCES = BuildPlanKey.list("kotlin-sources", Path.class);

    public static final BuildPlanKey<List<Path>> GROOVY_SOURCES = BuildPlanKey.list("groovy-sources", Path.class);

    public static final BuildPlanKey<List<String>> JAVAC_ARGS = BuildPlanKey.list("javac-args", String.class);

    public static final BuildPlanKey<List<Path>> PROCESSOR_CP = BuildPlanKey.list("processor-cp", Path.class);

    /** The javac half of the processor split — set by the ksp step (KSP jars removed). */
    public static final BuildPlanKey<List<Path>> JAVAC_PROCESSOR_CP =
            BuildPlanKey.list("javac-processor-cp", Path.class);

    /** The [[contribute.provided-classpath]] jars (platform), published for the test step. */
    public static final BuildPlanKey<List<Path>> PROVIDED_CP = BuildPlanKey.list("provided-cp", Path.class);

    public static final BuildPlanKey<List<Path>> COMPILE_TEST_CP = BuildPlanKey.list("cp-test", Path.class);

    public static final BuildPlanKey<List<Path>> TEST_RUNTIME_CP = BuildPlanKey.list("cp-runtime", Path.class);

    public static final BuildPlanKey<String> ACTION_KEY = BuildPlanKey.scalar("action-key", String.class);

    public static final BuildPlanKey<List<Path>> TEST_SOURCES = BuildPlanKey.list("test-sources", Path.class);

    /** Suite resource dirs copied into classes/test — a TestStamp input. */
    public static final BuildPlanKey<List<Path>> TEST_RESOURCE_DIRS =
            BuildPlanKey.list("test-resource-dirs", Path.class);

    public static final BuildPlanKey<String> BUILD_OUTCOME = BuildPlanKey.scalar("build-outcome", String.class);
    public static final BuildPlanKey<String> KOTLIN_OUTCOME = BuildPlanKey.scalar("kotlin-outcome", String.class);
    public static final BuildPlanKey<String> GROOVY_OUTCOME = BuildPlanKey.scalar("groovy-outcome", String.class);

    /** Fingerprint of last {@code target/package-classes} staging. */
    public static final BuildPlanKey<String> STAGED_CLASSES_INPUTS =
            BuildPlanKey.scalar("staged-classes-inputs", String.class);

    public static final BuildPlanKey<Path> MAIN_CLASSES = BuildPlanKey.scalar("main-classes", Path.class);
    public static final BuildPlanKey<Path> TEST_CLASSES = BuildPlanKey.scalar("test-classes", Path.class);
    public static final BuildPlanKey<BuildLayout> LAYOUT = BuildPlanKey.scalar("layout", BuildLayout.class);
    public static final BuildPlanKey<TestSummary> TEST_RESULT = BuildPlanKey.scalar("test-result", TestSummary.class);
    public static final BuildPlanKey<Boolean> NO_TEST_SOURCES = BuildPlanKey.scalar("no-test-sources", Boolean.class);

    public static final BuildPlanKey<Map<String, ClassAbi.Fingerprint>> PRE_COMPILE_ABI =
            BuildPlanKey.map("pre-compile-abi", String.class, ClassAbi.Fingerprint.class);

    public static final BuildPlanKey<List<Path>> COMPILED_MAIN_SOURCES =
            BuildPlanKey.list("compiled-main-sources", Path.class);

    public static final BuildPlanKey<AffectedTests> AFFECTED_TESTS =
            BuildPlanKey.scalar("affected-tests", AffectedTests.class);

    private static final List<BuildPlanKey<?>> STATE_KEYS = List.of(
            PROJECT,
            LOCKFILE,
            JAVA_HOME,
            RELEASE,
            CLASSPATH,
            JAVA_SOURCES,
            KOTLIN_SOURCES,
            GROOVY_SOURCES,
            JAVAC_ARGS,
            PROCESSOR_CP,
            JAVAC_PROCESSOR_CP,
            PROVIDED_CP,
            COMPILE_TEST_CP,
            TEST_RUNTIME_CP,
            ACTION_KEY,
            TEST_SOURCES,
            TEST_RESOURCE_DIRS,
            BUILD_OUTCOME,
            KOTLIN_OUTCOME,
            GROOVY_OUTCOME,
            STAGED_CLASSES_INPUTS,
            MAIN_CLASSES,
            TEST_CLASSES,
            LAYOUT,
            TEST_RESULT,
            NO_TEST_SOURCES,
            PRE_COMPILE_ABI,
            COMPILED_MAIN_SOURCES,
            AFFECTED_TESTS);

    /** Serializes {@code run-tests} across concurrent modules unless {@code parallelTests}. */
    static final Semaphore TEST_GATE = new Semaphore(1);

    /** Everything a build needs that isn't carried through the plan's state. */
    public record Inputs(
            Path dir,
            Path cache,
            Path buildFile,
            Path lockFile,
            Path lockDir,
            int workerCount,
            int estimatedTestCount,
            @Nullable String profileName,
            @Nullable Path jdksDir,
            boolean skipTests,
            boolean verbose,
            boolean testOnly,
            boolean compileOnly,
            Set<Path> projectModules,
            Session session,
            String variant,
            @Nullable Map<String, String> clientEnv,
            boolean ephemeralActions) {

        /**
         * Variant and client env from the session; durable action cache. Commands that install a
         * selection on the session ({@code jk run}, {@code test}, {@code image}, {@code native},
         * {@code publish}) parameterize every plan factory without each one threading those fields.
         */
        public Inputs(
                Path dir,
                Path cache,
                Path buildFile,
                Path lockFile,
                Path lockDir,
                int workerCount,
                int estimatedTestCount,
                @Nullable String profileName,
                @Nullable Path jdksDir,
                boolean skipTests,
                boolean verbose,
                boolean testOnly,
                boolean compileOnly,
                Set<Path> projectModules,
                Session session) {
            this(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    projectModules,
                    session,
                    session.variant(),
                    session.clientEnv(),
                    false);
        }

        /** Copy with {@link #ephemeralActions()} set ({@code jk verify} scratch rebuild). */
        public Inputs withEphemeralActions(boolean ephemeralActions) {
            return new Inputs(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    projectModules,
                    session,
                    variant,
                    clientEnv,
                    ephemeralActions);
        }

        /**
         * Environment lookup for this request: the caller's shell environment, falling back to the
         * engine's own.
         *
         * <p>The build's authoritative parse runs inside a long-lived daemon, so reading
         * {@code System.getenv} directly meant {@code FOO=x jk build} had no effect on
         * {@code ${FOO}} in {@code [repositories]} — while variant selection, handed this same
         * client env, did see it. Same precedence the plugin-config {@code env:} indirection
         * already documents.
         */
        public Function<String, @Nullable String> env() {
            return BuildEnv.forModule(dir);
        }

        /** This request with a variant selection + client-resolved env attached. */
        public Inputs withVariant(@Nullable String variant, @Nullable Map<String, String> clientEnv) {
            return new Inputs(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    projectModules,
                    session,
                    variant == null ? "" : variant,
                    clientEnv == null ? Map.of() : clientEnv,
                    ephemeralActions);
        }

        /** Copy with {@link #workerCount()} set (request-level {@code --workers}). */
        public Inputs withWorkerCount(int workerCount) {
            return new Inputs(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    projectModules,
                    session,
                    variant,
                    clientEnv,
                    ephemeralActions);
        }

        /** Copy with {@link #profileName()} set (request-level {@code --profile}). */
        public Inputs withProfileName(@Nullable String profileName) {
            return new Inputs(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    projectModules,
                    session,
                    variant,
                    clientEnv,
                    ephemeralActions);
        }

        /** Copy carrying the project/workspace module set — set by the estimate paths (explain/build). */
        public Inputs withProjectModules(Set<Path> modules) {
            return new Inputs(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    modules == null ? Set.of() : modules,
                    session,
                    variant,
                    clientEnv,
                    ephemeralActions);
        }
    }

    // Static relative progress-bar weights (time-ish budgets; bar normalises Σ).
    static final int W_PARSE = 4;
    static final int W_SYNC = 6;
    static final int W_JDK = 3;
    static final int W_COMPILE = 30;
    static final int W_COMPILE_KT = 30;
    static final int W_COMPILE_GROOVY = 30;
    static final int W_ASSEMBLE = 2;
    static final int W_RESOURCES = 1;
    static final int W_COMPILE_TEST = 12;
    static final int W_RUN_TESTS = 30;
    static final int W_PACKAGE = 5;
    static final int W_STAMP = 1;
    static final int W_SHADOW = 10;
    static final int W_SOURCES = 3;
    /** Always-run tail for a fully-cached module (token touch, not full static weight). */
    static final int W_CACHED_TOUCH = 1;

    static final int W_NATIVE = 90;

    /**
     * Core + declared tails — the exact plan {@code jk build} runs. Prefer this over a bare
     * {@code coreBuilder(...).build()} anywhere a FULL build is intended: the test branch hangs
     * off the terminal join the tails add, and a core-only plan silently prunes run-tests.
     */
    public static BuildPlan fullPlan(Inputs in) {
        BuildPlan.Builder b = coreBuilder(in);
        appendDeclaredTails(b, in);
        return b.build();
    }

    /** Core build steps plus assembly/native tails from {@code jk.toml}. */
    public static BuildPlan.Builder coreBuilder(Inputs in) {
        return coreBuilder(in, false);
    }

    /** As {@link #coreBuilder(Inputs)} with upstream-dirty {@code forceRebuild} for weight prediction. */
    public static BuildPlan.Builder coreBuilder(Inputs in, boolean forceRebuild) {
        Cas cas = JkStores.storeCas(); // artifact store CAS (deps, workers)
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(in.cache()), CacheTree.ACTIONS.under(in.cache()));

        // Compose only the language steps the project uses, so a single-language
        // project never shows a no-op step for the other. Explicit jk.toml
        // opt-ins (java/kotlin) win; otherwise the languages are inferred from
        // the source tree (see CompileSupport.resolveLanguages).
        boolean useKotlin = false;
        boolean useGroovy = false;
        boolean useScala = false;
        boolean useJava = true;
        boolean compactLayout = false;
        boolean workspaceNoSources = false;
        JkBuild parsedBuild = null;
        Map<String, String> variantSecrets = Map.of();
        try {
            var jkBuild = JkBuildParser.parse(in.buildFile());
            // Third-party plugin pre-flight: extract any locked-but-unmaterialized manifests
            // from the CAS and re-parse, so a declared plugin's table validates (and its
            // contributions apply) on the very first build after `jk sync`.
            if (!jkBuild.plugins().isEmpty() && PluginDescriptorOps.ensureMaterialized(in.dir(), in.cache())) {
                jkBuild = JkBuildParser.reparse(in.buildFile());
            }
            // CLI packaging override (jk assemble --minified / --fat) wins over jk.toml for this run.
            // Read from Inputs.session (not ambient SessionContext) — single-build constructs the
            // plan outside SessionContext.where.
            jkBuild = applyAssemblyOverride(jkBuild, in.session());
            // Variant overlays fold into plugin configs HERE, so describe keys, contribution
            // predicates, step/packager action keys, and plugin specs all see one flat effective
            // config (parameterized plans, not configured objects).
            var applied = VariantApply.apply(jkBuild, in.dir(), Variants.Selection.parse(in.variant()), in.clientEnv());
            jkBuild = applied.build();
            variantSecrets = applied.secrets();
            parsedBuild = jkBuild;
            var project = jkBuild.project();
            Languages langs = CompileSupport.resolveLanguages(project, in.dir());
            useJava = langs.java() || langs.scala();
            useKotlin = langs.kotlin();
            useGroovy = langs.groovy();
            useScala = langs.scala();
            // [processor-dependencies] on a Kotlin module can generate Java sources (Hilt's
            // components are Java) — route through the mixed plan so javac compiles them.
            if (useKotlin && !useJava && PlannerCompile.hasProcessorDeps(jkBuild)) {
                useJava = true;
            }
            compactLayout = CompileSupport.isSimpleLayout(project, in.dir());
            // Workspace root with no source tree: nothing to compile or package.
            if (CompileSupport.coordinatorOnly(jkBuild, in.dir())) {
                useJava = false;
                useKotlin = false;
                useGroovy = false;
                workspaceNoSources = true;
            }
        } catch (Exception ignored) {
            // Unparseable/missing jk.toml — parse-build will surface the real error.
        }
        // Triple-language modules are out of scopefail loudly rather than guess an
        // ordering between two stub-generating compilers.
        if (useKotlin && useGroovy) {
            throw new IllegalStateException(
                    "groovy+kotlin in one module is not supported yet — split the languages into separate modules");
        }
        // Scala compiles with Java in one Zinc session, but a second stub-generating compiler
        // (kotlinc / groovyc) can't parse .scala, so the combo fails with a cryptic unresolved
        // reference instead of the loud error above.
        if (useScala && (useKotlin || useGroovy)) {
            throw new IllegalStateException("scala+" + (useKotlin ? "kotlin" : "groovy")
                    + " in one module is not supported — split the languages into separate modules");
        }

        // Build-plugin code layer: learn the registered steps/packager
        // over the file-cached describe protocol. A missing plugin jar or a broken registration
        // must fail the build loudly here, not mid-plan.
        PluginBuild.Active pluginActive = null;
        PluginBuild.Declarations pluginDecls = null;
        if (parsedBuild != null) {
            var activeOpt = PluginBuild.activeCodePlugin(parsedBuild, in.dir());
            if (activeOpt.isPresent()) {
                try {
                    BuildLayout layout = BuildLayout.of(in.dir(), parsedBuild);
                    pluginDecls = PluginBuild.declarations(
                            activeOpt.get(), parsedBuild, in.dir(), in.cache(), layout.moduleTargetDir());
                    pluginActive = activeOpt.get();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "plugin " + activeOpt.get().manifest().id() + ": " + e.getMessage(), e);
                }
            }
        }
        final PluginBuild.Active pluginActiveF = pluginActive;
        final PluginBuild.Declarations pluginDeclsF = pluginDecls;
        final Map<String, String> variantSecretsF = variantSecrets;

        // Plugin-contributed generated sources can be Java even in a Kotlin- or Groovy-only
        // module (protoc's --kotlin_out DSL wraps its own --java_out classes) — same
        // mixed-plan routing the KSP/Hilt case above takes, decided here because the
        // declarations only exist after the describe round.
        if ((useKotlin || useGroovy) && !useJava && pluginDecls != null) {
            for (PluginBuild.TaskDecl step : pluginDecls.steps()) {
                if (!step.contributesSources().isEmpty()) {
                    useJava = true;
                    break;
                }
            }
        }

        // Effectively-final copies for the step lambdas.
        final boolean mixedWithJava = useJava;
        final boolean compact = compactLayout;
        // Mixed module: Kotlin compiles first (it reads Java *declarations* from
        // source; the Kotlin compiler never emits Java bytecode), then javac
        // against the Kotlin output, then `assemble-classes` merges both into the
        // shared classes dir — so Java↔Kotlin references resolve in both
        // directions. Each compiler owns a private output dir; they can't share
        // one, because javac's content-hash action cache snapshots its whole
        // output dir and would cache (then on restore, clobber) the other's
        // classes. The terminal step downstream steps wait on is the assembler
        // when mixed, else whichever single compiler ran.
        final boolean mixed = useJava && useKotlin;
        // Groovy takes the same shape: joint groovyc first (sweeps.java for resolution,
        // emits ONLY Groovy classes + Java-visible stubs), then javac (stubs on its
        // sourcepath, Groovy classes on its classpath), then assemble merges.
        final boolean mixedGroovy = useJava && useGroovy;
        final boolean kotlinModule = useKotlin; // effectively-final copy for lambdas
        final boolean groovyModule = useGroovy;
        String mainCompile = (mixed || mixedGroovy)
                ? TaskNames.ASSEMBLE_CLASSES
                : (useKotlin
                        ? TaskNames.COMPILE_KOTLIN
                        : (useGroovy ? TaskNames.COMPILE_GROOVY : TaskNames.COMPILE_JAVA));

        // Predict each step's bar weight from the work it will actually do this
        // run (skipped/cached steps collapse to ~1; real work dominates). Computed
        // once, lazily, when the first weight supplier fires during plan-start
        // estimation — so the prediction (stamps/lock/CAS) is read off disk once.
        final AtomicReference<@Nullable List<Path>> javaMainSrcRef = new AtomicReference<>();
        final AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = new AtomicReference<>();
        final AtomicReference<@Nullable List<Path>> groovyMainSrcRef = new AtomicReference<>();
        final AtomicReference<EffortWeights.@Nullable Plan> planRef = new AtomicReference<>();
        final Supplier<EffortWeights.Plan> plan = () -> {
            EffortWeights.Plan p = planRef.get();
            if (p == null) {
                planRef.compareAndSet(
                        null,
                        EffortWeights.predict(
                                in,
                                cas,
                                compact,
                                mixedWithJava,
                                kotlinModule,
                                groovyModule,
                                forceRebuild,
                                // The caches below, not fresh walks: prediction and the plan that
                                // follows it read the same source lists.
                                new SourceRefs(javaMainSrcRef, kotlinMainSrcRef, groovyMainSrcRef)));
                p = planRef.get();
            }
            return p;
        };

        // Source-list caches shared between the tick suppliers (estimate step)
        // and the parse-build execute (authoritative collection). The scope fires
        // first (plan-start estimation); parse-build execute reuses the result
        // instead of walking the same directories again. Using AtomicReference
        // with lazy init: whichever side fires first populates the cache; the
        // other side finds the value already set.
        // Build-logic anchors each call BuildLogicSupport.run() independently; share one lazy
        // source-tree hash across anchors (same pattern as javaMainSrcRef).
        final AtomicReference<@Nullable List<String>> buildLogicInputTokensRef = new AtomicReference<>();
        final Path javaMainSrcDir = compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java");

        // ---- parse-build ------------------------------------------------
        final boolean kspEnabled = useKotlin && parsedBuild != null && PlannerCompile.hasProcessorDeps(parsedBuild);
        Ctx cx = new Ctx(
                in,
                cas,
                actionCache,
                plan,
                javaMainSrcRef,
                kotlinMainSrcRef,
                groovyMainSrcRef,
                buildLogicInputTokensRef,
                javaMainSrcDir,
                compact,
                mixed,
                mixedGroovy,
                kotlinModule,
                groovyModule,
                mixedWithJava,
                mainCompile,
                kspEnabled);

        Task parseBuild = PlannerSetup.parseBuildStep(cx);

        // ---- resolve-deps -----------------------------------------------
        Task syncDeps = PlannerSetup.syncDepsStep(cx);

        // ---- ensure-jdk -------------------------------------------------
        Task ensureJdk = PlannerSetup.ensureJdkStep(cx);

        // ---- compile-java -----------------------------------------------
        Task compileJava = PlannerCompile.compileJavaStep(cx, pluginDeclsF);

        // ---- compile-kotlin ---------------------------------------------
        Task compileKotlin = PlannerCompile.compileKotlinStep(cx, pluginDeclsF);

        // ---- compile-groovy ---------------------------------------------
        Task compileGroovy = PlannerCompile.compileGroovyStep(cx, pluginDeclsF);

        // ---- copy-resources ---------------------------------------------
        Task copyResources = PlannerResources.copyResourcesStep(cx);

        // ---- compile-test-fixtures --------------------------------------
        boolean hasFixtures = parsedBuild != null && PlannerFixtures.declared(parsedBuild);
        Task compileTestFixtures = PlannerFixtures.compileTestFixturesStep(cx);

        // ---- compile-test -----------------------------------------------
        Task compileTest = PlannerTest.compileTestStep(cx, hasFixtures);

        // ---- run-tests --------------------------------------------------
        // In testOnly plans no package path exists to anchor the freshness stamps, so
        // run-tests carries them in its requires — otherwise the target-closure prune drops
        // them and the edit→test loop re-runs the main compile every invocation.
        List<String> testStampRequires = new ArrayList<>();
        if (in.testOnly()) {
            if (useJava) testStampRequires.add(TaskNames.WRITE_STAMP);
            if (useKotlin) testStampRequires.add(TaskNames.WRITE_STAMP_KOTLIN);
            if (useGroovy) testStampRequires.add(TaskNames.WRITE_STAMP_GROOVY);
        }
        Task runTests = PlannerTest.runTestsStep(cx, pluginDeclsF, testStampRequires);

        // ---- plugin steps ------------------------------------------------
        List<Task> pluginSteps = new ArrayList<>();
        PluginBuild.TaskDecl transform = PlannerPlugin.transformStep(pluginDeclsF);
        if (pluginDeclsF != null) {
            for (PluginBuild.TaskDecl step : pluginDeclsF.steps()) {
                pluginSteps.add(PlannerPlugin.pluginTask(cx, pluginActiveF, step, transform));
            }
        }

        // ---- package-jar ------------------------------------------------
        Task packageJar = PlannerPackage.packageJarStep(cx, pluginActiveF, pluginDeclsF, variantSecretsF);

        // ---- write-stamp ------------------------------------------------
        Task writeStamp = PlannerPackage.writeStampStep(cx);

        // ---- write-stamp-kotlin -----------------------------------------
        // Kotlin's freshness companion (cf. write-stamp for Java). Mirrors the
        // input set compile-kotlin checked: Kotlin sources, plus Java sources in
        // a mixed module. No action-cache key exists yet — the direct kotlinc
        // path leaves it empty until incremental Kotlin lands.
        Task writeStampKotlin = PlannerPackage.writeStampKotlinStep(cx);

        // ---- write-stamp-groovy -----------------------------------------
        // Groovy's freshness companion, mirroring write-stamp-kotlin.
        Task writeStampGroovy = PlannerPackage.writeStampGroovyStep(cx);

        // ---- assemble-classes (mixed modules only) ----------------------
        // Merge the per-language output dirs into the shared classes dir that
        // packaging, tests, and the run/native tails all read.
        Task assembleClasses = PlannerPackage.assembleClassesStep(cx);

        BuildPlan.Builder b = BuildPlan.builder("build")
                .stateKeys(STATE_KEYS)
                .addTask(parseBuild)
                .addTask(syncDeps)
                .addTask(ensureJdk);
        // Workspace root with no sources: validate jk.toml + sync deps, nothing more.
        // Workspace root with no sources: validate jk.toml, sync deps, and run the root's own
        // `after-build` logic. The graph orders this unit behind every member, so by the time the
        // step executes the whole workspace is built.
        if (workspaceNoSources) {
            if (BuildLogicToml.resolve(in.dir()).isPresent()) {
                b.addTask(PlannerResources.buildLogicAfterBuildStep(cx));
                String gate = PlannerResources.appendGate(b, cx, false, in.testOnly(), true);
                return b.terminal(gate != null ? gate : TaskNames.BUILD_LOGIC_AFTER_BUILD);
            }
            return b.terminal(TaskNames.RESOLVE_DEPS);
        }
        // BEFORE_COMPILE / GENERATE: codegen before any language compile (or KSP).
        b.addTask(PlannerResources.buildLogicBeforeCompileStep(cx));
        if (kspEnabled) {
            b.addTask(PlannerKsp.kspStep(cx, pluginDeclsF));
        }
        if (useGroovy) {
            b.addTask(compileGroovy);
        }
        if (useJava) {
            b.addTask(compileJava);
        }
        if (useKotlin) {
            b.addTask(compileKotlin);
        }
        if (mixed || mixedGroovy) {
            b.addTask(assembleClasses);
        }
        // `jk compile` stops here: lock → sync → compile (+ freshness stamps),
        // no resources/test/package. Everything later depends on these steps.
        if (in.compileOnly()) {
            List<String> stamps = new ArrayList<>();
            if (useJava) {
                b.addTask(writeStamp);
                stamps.add(TaskNames.WRITE_STAMP);
            }
            if (useKotlin) {
                b.addTask(writeStampKotlin);
                stamps.add(TaskNames.WRITE_STAMP_KOTLIN);
            }
            if (useGroovy) {
                b.addTask(writeStampGroovy);
                stamps.add(TaskNames.WRITE_STAMP_GROOVY);
            }
            if (stamps.isEmpty()) return b.terminal(mainCompile);
            if (stamps.size() == 1) return b.terminal(stamps.get(0));
            // Mixed module: every language's stamp (and the classes assembler) is an
            // independent leaf — a single-stamp terminal would prune the others and the
            // pruned language recompiles every run. Join them so the closure keeps each
            // one (same idiom as the deliver join).
            if (mixed || mixedGroovy) {
                stamps.add(TaskNames.ASSEMBLE_CLASSES);
            }
            b.addTask(Task.builder(COMPILE_JOIN)
                    .stage(BuildStage.COMPILE)
                    .requires(stamps.toArray(String[]::new))
                    .weight(0)
                    .ticks(0)
                    .execute(ctx -> {
                        /* join only */
                    })
                    .build());
            return b.terminal(COMPILE_JOIN);
        }
        // Build-logic AFTER_COMPILE before resources / AFTER_RESOURCES.
        b.addTask(PlannerResources.buildLogicAfterCompileStep(cx));
        b.addTask(copyResources);
        boolean skipJUnit = PlannerResources.skipJUnit(in);
        if (!skipJUnit) {
            if (hasFixtures) b.addTask(compileTestFixtures);
            b.addTask(compileTest).addTask(runTests);
        }
        // `jk test` stops at run-tests — it never packages a jar. Plugin steps run only
        // when packaging does: they exist to feed the packaged/native artifact. The exception
        // is plugin tasks run-tests itself requires (test-only or test-classpath contributors,
        // mirroring runTestsStep's requires) plus any plugin tasks those transitively require —
        // without them the plan fails validation before anything runs.
        if (!in.testOnly()) {
            for (Task p : pluginSteps) b.addTask(p);
            b.addTask(PlannerResources.buildLogicBeforePackageStep(cx));
            b.addTask(packageJar);
        } else if (pluginDeclsF != null) {
            Map<String, Task> pluginByName = new LinkedHashMap<>();
            for (Task p : pluginSteps) pluginByName.put(p.name(), p);
            ArrayDeque<String> want = new ArrayDeque<>();
            for (PluginBuild.TaskDecl step : pluginDeclsF.steps()) {
                if (step.testOnly() || !step.contributesTestClasspath().isEmpty()) {
                    want.add("plugin-" + step.name());
                }
            }
            Set<String> testPlugins = new HashSet<>();
            while (!want.isEmpty()) {
                String name = want.poll();
                Task p = pluginByName.get(name);
                if (p == null || !testPlugins.add(name)) continue;
                for (String r : p.requires()) {
                    if (pluginByName.containsKey(r)) want.add(r);
                }
            }
            for (Map.Entry<String, Task> e : pluginByName.entrySet()) {
                if (testPlugins.contains(e.getKey())) b.addTask(e.getValue());
            }
        }
        // write-stamp is the Java-compile freshness companion; only when Java ran.
        if (useJava) {
            b.addTask(writeStamp);
        }
        // write-stamp-kotlin is the Kotlin-compile freshness companion.
        if (useKotlin) {
            b.addTask(writeStampKotlin);
        }
        // write-stamp-groovy is the Groovy-compile freshness companion.
        if (useGroovy) {
            b.addTask(writeStampGroovy);
        }
        String gate = PlannerResources.appendGate(b, cx, !skipJUnit, in.testOnly(), false);
        if (in.testOnly()) {
            if (gate != null) return b.terminal(gate);
            if (!skipJUnit) return b.terminal(TaskNames.RUN_TESTS);
            return b.terminal(TaskNames.COPY_RESOURCES);
        }
        return b.terminal(TaskNames.PACKAGE_JAR);
    }

    /** The build-scoped services, estimation state, and layout flags shared by every core step. */
    record Ctx(
            Inputs in,
            Cas cas,
            ActionCache actionCache,
            Supplier<EffortWeights.Plan> plan,
            AtomicReference<@Nullable List<Path>> javaMainSrcRef,
            AtomicReference<@Nullable List<Path>> kotlinMainSrcRef,
            AtomicReference<@Nullable List<Path>> groovyMainSrcRef,
            AtomicReference<@Nullable List<String>> buildLogicInputTokensRef,
            Path javaMainSrcDir,
            boolean compact,
            boolean mixed,
            boolean mixedGroovy,
            boolean kotlinModule,
            boolean groovyModule,
            boolean mixedWithJava,
            String mainCompile,
            boolean ksp) {}

    /** One processor-authored KSP diagnostic: the reporting severity plus the bare message. */
    record KspDiagnostic(String severity, String message) {}

    /**
     * Synthetic join for multiple declared tails (assembly + native + sources). Not a real work
     * step — only exists so {@link BuildPlan.Builder#terminal} can keep every leaf.
     */
    static final String DELIVER_JOIN = "deliver";

    /** Zero-work join terminal for mixed-language {@code jk compile}. */
    static final String COMPILE_JOIN = "compile-join";

    static final String SBOM_JAR_ENTRY = PlannerPlugin.SBOM_JAR_ENTRY;
    static final String NATIVE_IMAGE_ARGS = PlannerNative.NATIVE_IMAGE_ARGS;

    /** Test hook: restrict host-engine-jar discovery to this monorepo root. */
    static volatile @Nullable Path hostEngineSearchOverride;

    /** Apply CLI {@code --fat}/{@code --minified} over the parsed manifest for this invocation. */
    static JkBuild applyAssemblyOverride(JkBuild build, Session session) {
        String raw = session != null ? session.assemblyOverride() : "";
        if (raw == null || raw.isBlank()) {
            raw = SessionContext.current().assemblyOverride();
        }
        if (raw == null || raw.isBlank()) return build;
        JkBuildParser.ArtifactOverride override = JkBuildParser.parseArtifactOverride(raw);
        if (override == null) return build;
        return JkBuildParser.withArtifactOverride(build, override);
    }
}
