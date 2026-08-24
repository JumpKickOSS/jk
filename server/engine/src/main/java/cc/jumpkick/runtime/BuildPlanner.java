// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceClasspath;
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
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.GroovyCompile;
import cc.jumpkick.task.KotlinCompile;
import java.io.IOException;
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
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Assembles {@link BuildPlan} DAGs for build-family commands: core tasks via {@link #coreBuilder},
 * then command-specific tails via {@link #appendDeclaredTails}. Step bodies live in {@code Planner*}
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
    public static final BuildPlanKey<JkBuild> PROJECT = BuildPlanKey.of("project", JkBuild.class);
    public static final BuildPlanKey<Lockfile> LOCKFILE = BuildPlanKey.of("lockfile", Lockfile.class);
    public static final BuildPlanKey<Path> JAVA_HOME = BuildPlanKey.of("java-home", Path.class);
    public static final BuildPlanKey<Integer> RELEASE = BuildPlanKey.of("release", Integer.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> CLASSPATH = BuildPlanKey.of("classpath", List.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> JAVA_SOURCES = BuildPlanKey.of("java-sources", List.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> KOTLIN_SOURCES = BuildPlanKey.of("kotlin-sources", List.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> GROOVY_SOURCES = BuildPlanKey.of("groovy-sources", List.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> JAVAC_ARGS = BuildPlanKey.of("javac-args", List.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> PROCESSOR_CP = BuildPlanKey.of("processor-cp", List.class);

    /** The javac half of the processor split — set by the ksp step (KSP jars removed). */
    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> JAVAC_PROCESSOR_CP = BuildPlanKey.of("javac-processor-cp", List.class);

    /** The [[contribute.provided-classpath]] jars (platform), published for the test step. */
    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> PROVIDED_CP = BuildPlanKey.of("provided-cp", List.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> COMPILE_TEST_CP = BuildPlanKey.of("cp-test", List.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> TEST_RUNTIME_CP = BuildPlanKey.of("cp-runtime", List.class);

    public static final BuildPlanKey<String> ACTION_KEY = BuildPlanKey.of("action-key", String.class);

    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> TEST_SOURCES = BuildPlanKey.of("test-sources", List.class);

    /** Suite resource dirs copied into classes/test — a TestStamp input. */
    @SuppressWarnings("rawtypes")
    public static final BuildPlanKey<List> TEST_RESOURCE_DIRS = BuildPlanKey.of("test-resource-dirs", List.class);

    public static final BuildPlanKey<String> BUILD_OUTCOME = BuildPlanKey.of("build-outcome", String.class);
    public static final BuildPlanKey<String> KOTLIN_OUTCOME = BuildPlanKey.of("kotlin-outcome", String.class);
    public static final BuildPlanKey<String> GROOVY_OUTCOME = BuildPlanKey.of("groovy-outcome", String.class);
    public static final BuildPlanKey<Path> JAR_PATH = BuildPlanKey.of("jar-path", Path.class);

    /** Fingerprint of last {@code target/package-classes} staging. */
    public static final BuildPlanKey<String> STAGED_CLASSES_INPUTS =
            BuildPlanKey.of("staged-classes-inputs", String.class);

    public static final BuildPlanKey<Path> MAIN_CLASSES = BuildPlanKey.of("main-classes", Path.class);
    public static final BuildPlanKey<Path> TEST_CLASSES = BuildPlanKey.of("test-classes", Path.class);
    public static final BuildPlanKey<BuildLayout> LAYOUT = BuildPlanKey.of("layout", BuildLayout.class);
    public static final BuildPlanKey<TestSummary> TEST_RESULT = BuildPlanKey.of("test-result", TestSummary.class);
    public static final BuildPlanKey<Boolean> NO_TEST_SOURCES = BuildPlanKey.of("no-test-sources", Boolean.class);

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
            String profileName,
            Path jdksDir,
            boolean skipTests,
            boolean verbose,
            boolean testOnly,
            boolean compileOnly,
            Set<Path> projectModules,
            Session session,
            String variant,
            Map<String, String> clientEnv,
            boolean ephemeralActions) {

        /**
         * Back-compat: the pre-variant canonical shape. The variant selection defaults from the
         * SESSION — a command that installs a selection there (jk run/test/image/native/publish)
         * parameterizes every plan factory without each one threading it explicitly.
         */
        public Inputs(
                Path dir,
                Path cache,
                Path buildFile,
                Path lockFile,
                Path lockDir,
                int workerCount,
                int estimatedTestCount,
                String profileName,
                Path jdksDir,
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
                    session.clientEnv());
        }

        /** Pre-ephemeralActions canonical shape (defaults false — persistent caches). */
        public Inputs(
                Path dir,
                Path cache,
                Path buildFile,
                Path lockFile,
                Path lockDir,
                int workerCount,
                int estimatedTestCount,
                String profileName,
                Path jdksDir,
                boolean skipTests,
                boolean verbose,
                boolean testOnly,
                boolean compileOnly,
                Set<Path> projectModules,
                Session session,
                String variant,
                Map<String, String> clientEnv) {
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
                    variant,
                    clientEnv,
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
        public UnaryOperator<String> env() {
            return BuildEnv.forModule(dir);
        }

        /** This request with a variant selection + client-resolved env attached. */
        public Inputs withVariant(String variant, Map<String, String> clientEnv) {
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
        public Inputs withProfileName(String profileName) {
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
     * {@code coreBuilder(...).build()} anywhere a FULL build is intended: since JK-2211 the
     * test branch hangs off the terminal join the tails add, and a core-only plan silently
     * prunes run-tests (a fixture that "builds and tests" would stop testing).
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
        Cas cas = JkStores.cas(in.cache()); // artifact store CAS (deps, workers)
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
            if (jkBuild.isWorkspaceRoot() && !CompileSupport.hasSources(in.dir())) {
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
        // reference instead of the loud error above (JK-2318).
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
        final AtomicReference<EffortWeights.Plan> planRef = new AtomicReference<>();
        final Supplier<EffortWeights.Plan> plan = () -> {
            EffortWeights.Plan p = planRef.get();
            if (p == null) {
                planRef.compareAndSet(
                        null,
                        EffortWeights.predict(
                                in, cas, compact, mixedWithJava, kotlinModule, groovyModule, forceRebuild));
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
        final AtomicReference<List<Path>> javaMainSrcRef = new AtomicReference<>();
        final AtomicReference<List<Path>> kotlinMainSrcRef = new AtomicReference<>();
        final AtomicReference<List<Path>> groovyMainSrcRef = new AtomicReference<>();
        // Build-logic anchors (BEFORE_COMPILE / AFTER_COMPILE / AFTER_RESOURCES / BEFORE_PACKAGE)
        // each call BuildLogicSupport.run() independently; a module registering tasks at more
        // than one anchor used to hash its whole source tree once per anchor with tasks. Shared
        // here the same lazy-init-race pattern as javaMainSrcRef above: computed once by whichever
        // anchor task needs it first, reused by the rest.
        final AtomicReference<List<String>> buildLogicInputTokensRef = new AtomicReference<>();
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

        // ---- sync-deps --------------------------------------------------
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

        // ---- compile-test -----------------------------------------------
        Task compileTest = PlannerTest.compileTestStep(cx);

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

        BuildPlan.Builder b =
                BuildPlan.builder("build").addTask(parseBuild).addTask(syncDeps).addTask(ensureJdk);
        // Workspace root with no sources: validate jk.toml + sync deps, nothing more.
        if (workspaceNoSources) return b.terminal(TaskNames.RESOLVE_DEPS);
        // SPI BEFORE_COMPILE / GENERATE: codegen before any language compile (or KSP).
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
        // Build-logic AFTER_COMPILE (SPI) before resources / AFTER_RESOURCES.
        b.addTask(PlannerResources.buildLogicAfterCompileStep(cx));
        b.addTask(copyResources);
        if (in.testOnly() || !in.skipTests()) {
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
        if (in.testOnly()) {
            return b.terminal(TaskNames.RUN_TESTS);
        }
        return b.terminal(TaskNames.PACKAGE_JAR);
    }

    /**
     * The build-scoped services, estimation state, and layout flags shared by every core step
     * the explicit replacement for the effectively-final locals the step lambdas used to capture.
     */
    record Ctx(
            Inputs in,
            Cas cas,
            ActionCache actionCache,
            Supplier<EffortWeights.Plan> plan,
            AtomicReference<List<Path>> javaMainSrcRef,
            AtomicReference<List<Path>> kotlinMainSrcRef,
            AtomicReference<List<Path>> groovyMainSrcRef,
            AtomicReference<List<String>> buildLogicInputTokensRef,
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
    static volatile Path hostEngineSearchOverride;

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

    public static void appendDeclaredTails(BuildPlan.Builder b, Inputs in) {
        PlannerTails.appendDeclaredTails(b, in);
    }

    public static void appendDeclaredTails(BuildPlan.Builder b, Inputs in, Path graalHome) {
        PlannerTails.appendDeclaredTails(b, in, graalHome);
    }

    public static void appendDeclaredTails(BuildPlan.Builder b, Inputs in, Path graalHome, boolean allowNative) {
        PlannerTails.appendDeclaredTails(b, in, graalHome, allowNative);
    }

    static List<KspDiagnostic> kspDiagnostics(String output) {
        return PlannerKsp.kspDiagnostics(output);
    }

    static Path kspOutBase(BuildLayout layout) {
        return PlannerKsp.kspOutBase(layout);
    }

    static boolean beforeCompile(PluginBuild.TaskDecl step) {
        return PlannerPlugin.beforeCompile(step);
    }

    static PluginBuild.TaskDecl transformStep(PluginBuild.Declarations decls) {
        return PlannerPlugin.transformStep(decls);
    }

    static BuildStage pluginWindow(PluginBuild.TaskDecl step) {
        return PlannerPlugin.pluginWindow(step);
    }

    static BuildStage pluginStage(PluginBuild.TaskDecl step) {
        return PlannerPlugin.pluginStage(step);
    }

    static List<String> pluginRequires(PluginBuild.TaskDecl step, PluginBuild.TaskDecl transform) {
        return PlannerPlugin.pluginRequires(step, transform);
    }

    static byte[] applicationSbom(JkBuild project, Lockfile lock, Cas cas) {
        return PlannerPlugin.applicationSbom(project, lock, cas);
    }

    static Task minifiedStep(Inputs in, Path graalHome) {
        return PlannerTails.minifiedStep(in, graalHome);
    }

    public static Task assemblyStep(Path cache, Path lockFile) {
        return PlannerTails.assemblyStep(cache, lockFile);
    }

    public static Task assemblyStep(Path cache, Path lockFile, boolean persist) {
        return PlannerTails.assemblyStep(cache, lockFile, persist);
    }

    public static Task sourcesStep(Path cache) {
        return PlannerTails.sourcesStep(cache);
    }

    public static Task sourcesStep(Path cache, boolean persist) {
        return PlannerTails.sourcesStep(cache, persist);
    }

    public static Task nativeStep(
            Path projectDir,
            Path cache,
            Path lockFile,
            Path jdksDir,
            Path graalHome,
            String binName,
            List<String> extraArgs) {
        return PlannerNative.nativeStep(projectDir, cache, lockFile, jdksDir, graalHome, binName, extraArgs);
    }

    public static Task nativeStep(
            Path projectDir,
            Path cache,
            Path lockFile,
            Path jdksDir,
            Path graalHome,
            String binName,
            List<String> extraArgs,
            boolean persist) {
        return PlannerNative.nativeStep(projectDir, cache, lockFile, jdksDir, graalHome, binName, extraArgs, persist);
    }

    static String formatNativeInputMib(long bytes) {
        return PlannerNative.formatNativeInputMib(bytes);
    }

    static String nativeOutputDisplayName(Path out, boolean shared) {
        return PlannerNative.nativeOutputDisplayName(out, shared);
    }

    static Path resolveNativeImageHome(Path graalHome, Path projectDir, Path jdksDir) {
        return PlannerNative.resolveNativeImageHome(graalHome, projectDir, jdksDir);
    }

    public static List<Path> processorClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings) throws IOException {
        return PlannerSupport.processorClasspath(lock, resolver, siblings);
    }

    public static List<String> unresolvedProcessorDeps(JkBuild project, Lockfile lock) {
        return PlannerSupport.unresolvedProcessorDeps(project, lock);
    }

    public static List<String> unresolvedProcessorDeps(
            JkBuild project, Lockfile lock, WorkspaceClasspath.Result processorSiblings) {
        return PlannerSupport.unresolvedProcessorDeps(project, lock, processorSiblings);
    }

    public static List<Path> mainCompileClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings) throws IOException {
        return PlannerSupport.mainCompileClasspath(lock, resolver, siblings);
    }

    static List<Path> assemblyDependencyJars(Path moduleDir, JkBuild project, Path lockFile, Path cache)
            throws IOException {
        return PlannerSupport.assemblyDependencyJars(moduleDir, project, lockFile, cache);
    }

    static List<Path> mainStampClasspath(
            List<Path> compileCp,
            List<Path> processorCp,
            boolean useKotlin,
            boolean useGroovy,
            BuildLayout layout,
            Path groovyJar) {
        return PlannerSupport.mainStampClasspath(compileCp, processorCp, useKotlin, useGroovy, layout, groovyJar);
    }

    static Set<String> lockModules(Lockfile lock) {
        return PlannerSupport.lockModules(lock);
    }

    static Map<String, Path> siblingMainJars(Path moduleDir) throws IOException {
        return PlannerSupport.siblingMainJars(moduleDir);
    }

    static void storePackagedForTest(
            Path cache,
            String task,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist)
            throws IOException {
        PlannerSupport.storePackagedForTest(cache, task, key, tokens, baseDir, artifacts, persist);
    }

    static boolean needsNestedEngineIsolation(JkBuild project) {
        return PlannerSupport.needsNestedEngineIsolation(project);
    }

    static void enrichCliTestProps(Path moduleDir, Map<String, String> props) throws IOException {
        PlannerSupport.enrichCliTestProps(moduleDir, props);
    }

    static Path resolveEngineJarForNestedTests(Map<String, Path> siblings) {
        return PlannerSupport.resolveEngineJarForNestedTests(siblings);
    }

    static Path locateHostEngineJar() {
        return PlannerSupport.locateHostEngineJar();
    }

    static Path findMonorepoEngineJar(Path start) {
        return PlannerSupport.findMonorepoEngineJar(start);
    }

    static Map<String, String> nestedEngineTestEnv(Path moduleDir) throws IOException {
        return PlannerSupport.nestedEngineTestEnv(moduleDir);
    }

    static TestSelection effectiveSelection(TestSelection sel, Path moduleDir) {
        return PlannerSupport.effectiveSelection(sel, moduleDir);
    }

    public static Map<String, String> testStampWorkerJars(Path dir, JkBuild project) throws IOException {
        return PlannerSupport.testStampWorkerJars(dir, project);
    }

    public static List<String> testStampExtras(Path dir, JkBuild project) throws IOException {
        return PlannerSupport.testStampExtras(dir, project);
    }

    public static String runTestsStampKey(
            Path dir, JkBuild project, boolean compact, Path mainClasses, Path lockFile, List<Path> testRuntimeCp)
            throws IOException {
        return PlannerSupport.runTestsStampKey(dir, project, compact, mainClasses, lockFile, testRuntimeCp);
    }

    public static String runTestsStampKey(
            Path dir,
            JkBuild project,
            boolean compact,
            Path mainClasses,
            String mainClassesFingerprint,
            Path lockFile,
            List<Path> testRuntimeCp)
            throws IOException {
        return PlannerSupport.runTestsStampKey(
                dir, project, compact, mainClasses, mainClassesFingerprint, lockFile, testRuntimeCp);
    }

    static List<String> testStampExtras(
            Map<String, String> workerJars, TestSelection selection, Map<String, String> testEnv, Path moduleDir) {
        return PlannerSupport.testStampExtras(workerJars, selection, testEnv, moduleDir);
    }

    static List<String> testStampExtras(
            Map<String, String> workerJars,
            TestSelection selection,
            Map<String, String> testEnv,
            SecretRedactor redactor,
            EnvLookup lookup) {
        return PlannerSupport.testStampExtras(workerJars, selection, testEnv, redactor, lookup);
    }

    static PluginBuild.Declarations pluginDeclarationsFor(JkBuild project, BuildLayout layout, Path cache)
            throws IOException, InterruptedException {
        return PlannerSupport.pluginDeclarationsFor(project, layout, cache);
    }

    static List<Path> existingContributedDirs(PluginBuild.Declarations decls, BuildLayout layout) {
        return PlannerSupport.existingContributedDirs(decls, layout);
    }

    static String contributionsToken(List<Path> contributed) throws IOException {
        return PlannerSupport.contributionsToken(contributed);
    }

    static Path stageClassesWithContributions(TaskContext ctx, Path classes, List<Path> extra, BuildLayout layout)
            throws IOException {
        return PlannerSupport.stageClassesWithContributions(ctx, classes, extra, layout);
    }

    // Package-private helpers the step collaborators still call via static import.
    static List<String> sourceGenStepSteps(PluginBuild.Declarations decls) {
        return PlannerKsp.sourceGenStepSteps(decls);
    }

    static List<Path> pluginContributedSources(BuildLayout layout, PluginBuild.Declarations decls, String suffix)
            throws IOException {
        return PlannerKsp.pluginContributedSources(layout, decls, suffix);
    }

    static List<Path> pluginContributedSourceDirs(BuildLayout layout, PluginBuild.Declarations decls) {
        return PlannerKsp.pluginContributedSourceDirs(layout, decls);
    }

    static List<Path> pluginTestClasspath(BuildLayout layout, PluginBuild.Declarations decls) {
        return PlannerKsp.pluginTestClasspath(layout, decls);
    }

    static List<Path> contributedProvidedFor(TaskContext ctx) {
        return PlannerKsp.contributedProvidedFor(ctx);
    }

    static List<Path> kspGeneratedSources(BuildLayout layout, String suffix) throws IOException {
        return PlannerKsp.kspGeneratedSources(layout, suffix);
    }

    static List<Path> kotlinJavaSourceRoots(
            boolean mixedWithJava, boolean compact, Path dir, BuildLayout layout, PluginBuild.Declarations decls) {
        return PlannerKsp.kotlinJavaSourceRoots(mixedWithJava, compact, dir, layout, decls);
    }

    static String[] kotlinCompileRequires(PluginBuild.Declarations decls, boolean ksp) {
        return PlannerCompile.kotlinCompileRequires(decls, ksp);
    }

    static String[] javaCompileRequires(
            boolean mixed, boolean mixedGroovy, PluginBuild.Declarations decls, boolean ksp) {
        return PlannerCompile.javaCompileRequires(mixed, mixedGroovy, decls, ksp);
    }

    static String[] groovyCompileRequires(PluginBuild.Declarations decls) {
        return PlannerCompile.groovyCompileRequires(decls);
    }

    static boolean hasProcessorDeps(JkBuild build) {
        return PlannerCompile.hasProcessorDeps(build);
    }

    static String[] beforePackageRequires(Inputs in) {
        return PlannerResources.beforePackageRequires(in);
    }

    static String[] packageRequires(
            Inputs in, PluginBuild.Declarations decls, boolean useJava, boolean useKotlin, boolean useGroovy) {
        return PlannerPackage.packageRequires(in, decls, useJava, useKotlin, useGroovy);
    }

    static boolean ownsMainArtifact(PluginBuild.Active active) {
        return PlannerPackage.ownsMainArtifact(active);
    }

    static boolean settledOutcome(String outcome) {
        return PlannerPackage.settledOutcome(outcome);
    }

    static BuildStage pluginCeiling(PluginBuild.TaskDecl step) {
        return PlannerPlugin.pluginCeiling(step);
    }

    static Task pluginTask(
            Ctx cx, PluginBuild.Active pluginActive, PluginBuild.TaskDecl step, PluginBuild.TaskDecl transform) {
        return PlannerPlugin.pluginTask(cx, pluginActive, step, transform);
    }

    static void packagePlugin(
            TaskContext ctx,
            Inputs in,
            Cas cas,
            JkBuild project,
            Path classes,
            Path jar,
            PluginBuild.Active active,
            PluginBuild.Declarations decls,
            Map<String, String> secrets)
            throws Exception {
        PlannerPlugin.packagePlugin(ctx, in, cas, project, classes, jar, active, decls, secrets);
    }

    static String resolvedMain(JkBuild project, Path moduleDir, Path classes) throws IOException {
        return PlannerPlugin.resolvedMain(project, moduleDir, classes);
    }

    static boolean packagerDeclaresNativeSources(JkBuild project, Path dir) {
        return PlannerNative.packagerDeclaresNativeSources(project, dir);
    }

    static Path nativeImageSourcesDir(JkBuild project, Path dir, Path cache, BuildLayout layout)
            throws IOException, InterruptedException {
        return PlannerNative.nativeImageSourcesDir(project, dir, cache, layout);
    }

    static String stepNameOf(PluginBuild.Active active, JkBuild project, Path dir, Path cache)
            throws IOException, InterruptedException {
        return PlannerNative.stepNameOf(active, project, dir, cache);
    }

    static List<String> frameworkNativeArgs(Path sources, List<String> extras) throws IOException {
        return PlannerNative.frameworkNativeArgs(sources, extras);
    }

    static Path frameworkBinary(Path sources) throws IOException {
        return PlannerNative.frameworkBinary(sources);
    }

    static List<Path> javaSources(TaskContext ctx) {
        return PlannerNative.javaSources(ctx);
    }

    static List<Path> kotlinSources(TaskContext ctx) {
        return PlannerNative.kotlinSources(ctx);
    }

    static List<Path> groovySources(TaskContext ctx) {
        return PlannerNative.groovySources(ctx);
    }

    static List<Path> contributedProvidedClasspath(JkBuild project, Inputs in, Cas cas) {
        return PlannerSupport.contributedProvidedClasspath(project, in, cas);
    }

    static KotlinCompile.Result compileKotlinSources(
            TaskContext ctx,
            Inputs in,
            Cas cas,
            ActionCache actionCache,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            String taskId,
            Path workingDir,
            List<Path> javaSourceRoots)
            throws IOException {
        return PlannerLang.compileKotlinSources(
                ctx, in, cas, actionCache, sources, classpath, outputDir, taskId, workingDir, javaSourceRoots);
    }

    static GroovyCompile.Result compileGroovySources(
            TaskContext ctx,
            Inputs in,
            Cas cas,
            ActionCache actionCache,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            String taskId,
            List<Path> javaSourceRoots,
            Path stubsOut)
            throws IOException {
        return PlannerLang.compileGroovySources(
                ctx, in, cas, actionCache, sources, classpath, outputDir, taskId, javaSourceRoots, stubsOut);
    }

    static Path groovyCompileJar(TaskContext ctx, Cas cas) throws IOException {
        return PlannerSupport.groovyCompileJar(ctx, cas);
    }

    static List<Path> groovyRuntime(TaskContext ctx, Cas cas) throws IOException {
        return PlannerSupport.groovyRuntime(ctx, cas);
    }

    static Path kotlinStdlib(TaskContext ctx, Cas cas) throws IOException {
        return PlannerSupport.kotlinStdlib(ctx, cas);
    }

    static void copyResources(Path resourceDir, Path classesDir) throws IOException {
        PlannerSupport.copyResources(resourceDir, classesDir);
    }

    static boolean restorePackaged(Path cacheRoot, String key, Path baseDir) throws IOException {
        return PlannerSupport.restorePackaged(cacheRoot, key, baseDir);
    }

    static void storePackaged(
            Path cacheRoot,
            String task,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist)
            throws IOException {
        PlannerSupport.storePackaged(cacheRoot, task, key, tokens, baseDir, artifacts, persist);
    }
}
