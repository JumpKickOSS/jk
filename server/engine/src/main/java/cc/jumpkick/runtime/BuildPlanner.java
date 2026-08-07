// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.AssemblyPackager;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.JarPackager;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.compile.WorkerClasspath;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Scope;

import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.TestProgressListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Assembles {@link BuildPlan} DAGs for build-family commands: core tasks via {@link #coreBuilder},
 * then command-specific tails (native, image, install, …). Not itself a runnable plan — callers
 * {@code build()} the returned {@link BuildPlan.Builder} and {@link BuildPlan#run() run} it.
 */
public final class BuildPlanner {

    static {
        // Wire session cancel into TaskContext.cancelled (lazy; pool tasks see it via
        // SessionContext propagation on JkThreads).
        cc.jumpkick.run.SessionCancel.bind(
                () -> cc.jumpkick.config.SessionContext.current().cancelled());
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
    public static final BuildPlanKey<Path> MAIN_CLASSES = BuildPlanKey.of("main-classes", Path.class);
    public static final BuildPlanKey<Path> TEST_CLASSES = BuildPlanKey.of("test-classes", Path.class);
    public static final BuildPlanKey<BuildLayout> LAYOUT = BuildPlanKey.of("layout", BuildLayout.class);
    public static final BuildPlanKey<TestSummary> TEST_RESULT = BuildPlanKey.of("test-result", TestSummary.class);
    public static final BuildPlanKey<Boolean> NO_TEST_SOURCES = BuildPlanKey.of("no-test-sources", Boolean.class);

    /**
     * Process-wide gate that serializes the {@code run-tests} step across concurrently-built units
     * (parallel workspace module builds). Tests commonly contend on shared resources — ports, lock
     * files, fixtures — so they run one at a time by default; the request's {@link
     * cc.jumpkick.config.Session#parallelTests} (default on; {@code --serial-tests} holds the gate).
     *
     * <p>The gate itself remains a per-invocation shared primitive (one process, one build at a
     * time in the CLI); a per-session gate is part of the M1c server-hardening remainder.
     */
    private static final java.util.concurrent.Semaphore TEST_GATE = new java.util.concurrent.Semaphore(1);

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
            // Dirs of the other modules in this build graph / workspace. Used ONLY to compute a
            // project-tier learned rate (EffortWeights.learned): a not-yet-built module borrows the
            // median rate of its sibling modules — same frameworks/fixtures, a closer prior than the
            // whole-host median — before falling back to that host median. Empty (the default) leaves
            // the fallback chain as module → host-median → static, exactly as before.
            Set<Path> projectModules,
            // The request-scoped session (config incl. --force, working dir, cache/JDK roots).
            // Threaded so the engine reads request state explicitly instead of the ambient
            // global — callers that want the ambient session say SessionContext.current OUT
            // LOUD at the call site; no overload hides that read anymore.
            cc.jumpkick.config.Session session,
            // The variant selection ("", "release", "release|tier=free") — folded into plugin
            // configs at parse time (VariantApply.apply), so plans are parameterized, never configured.
            String variant,
            // Client-resolved env values (env: indirection in plugin configs — signing secrets):
            // the user's shell env rides the request; the engine env is only the fallback.
            Map<String, String> clientEnv,
            // True for jk verify's scratch rebuild: action keys are scratch-salted and can never
            // recur, so tasks must not persist action-cache records or incremental state.
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
                cc.jumpkick.config.Session session) {
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
                cc.jumpkick.config.Session session,
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
        public java.util.function.UnaryOperator<String> env() {
            return cc.jumpkick.config.BuildEnv.forModule(dir);
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

    /** Core build steps plus assembly/native tails from {@code jk.toml}. */
    public static BuildPlan.Builder coreBuilder(Inputs in) {
        return coreBuilder(in, false);
    }

    /** As {@link #coreBuilder(Inputs)} with upstream-dirty {@code forceRebuild} for weight prediction. */
    public static BuildPlan.Builder coreBuilder(Inputs in, boolean forceRebuild) {
        Cas cas = JkStores.cas(in.cache()); // artifact store CAS (deps, workers)
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(in.cache()), in.cache().resolve("actions"));

        // Compose only the language steps the project uses, so a single-language
        // project never shows a no-op step for the other. Explicit jk.toml
        // opt-ins (java/kotlin) win; otherwise the languages are inferred from
        // the source tree (see CompileSupport.resolveLanguages).
        boolean useKotlin = false;
        boolean useGroovy = false;
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
            // CLI packaging override (jk assemble --shrink / --fat) wins over jk.toml for this run.
            // Read from Inputs.session (not ambient SessionContext) — single-build constructs the
            // plan outside SessionContext.where.
            jkBuild = applyAssemblyOverride(jkBuild, in.session());
            // Variant overlays fold into plugin configs HERE, so describe keys, contribution
            // predicates, step/packager action keys, and plugin specs all see one flat effective
            // config (parameterized plans, not configured objects).
            var applied = cc.jumpkick.plugin.manifest.VariantApply.apply(
                    jkBuild, in.dir(), cc.jumpkick.model.Variants.Selection.parse(in.variant()), in.clientEnv());
            jkBuild = applied.build();
            variantSecrets = applied.secrets();
            parsedBuild = jkBuild;
            var project = jkBuild.project();
            cc.jumpkick.layout.Languages langs = CompileSupport.resolveLanguages(project, in.dir());
            useJava = langs.java();
            useKotlin = langs.kotlin();
            useGroovy = langs.groovy();
            // [processor-dependencies] on a Kotlin module can generate Java sources (Hilt's
            // components are Java) — route through the mixed plan so javac compiles them.
            if (useKotlin && !useJava && hasProcessorDeps(jkBuild)) {
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
        final java.util.concurrent.atomic.AtomicReference<EffortWeights.Plan> planRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.function.Supplier<EffortWeights.Plan> plan = () -> {
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
        final java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<List<Path>> groovyMainSrcRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Path javaMainSrcDir = compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java");

        // ---- parse-build ------------------------------------------------
        final boolean kspEnabled = useKotlin && parsedBuild != null && hasProcessorDeps(parsedBuild);
        Ctx cx = new Ctx(
                in,
                cas,
                actionCache,
                plan,
                javaMainSrcRef,
                kotlinMainSrcRef,
                groovyMainSrcRef,
                javaMainSrcDir,
                compact,
                mixed,
                mixedGroovy,
                kotlinModule,
                groovyModule,
                mixedWithJava,
                mainCompile,
                kspEnabled);

        Task parseBuild = parseBuildStep(cx);

        // ---- sync-deps --------------------------------------------------
        Task syncDeps = syncDepsStep(cx);

        // ---- ensure-jdk -------------------------------------------------
        Task ensureJdk = ensureJdkStep(cx);

        // ---- compile-java -----------------------------------------------
        Task compileJava = compileJavaStep(cx, pluginDeclsF);

        // ---- compile-kotlin ---------------------------------------------
        Task compileKotlin = compileKotlinStep(cx, pluginDeclsF);

        // ---- compile-groovy ---------------------------------------------
        Task compileGroovy = compileGroovyStep(cx, pluginDeclsF);

        // ---- copy-resources ---------------------------------------------
        Task copyResources = copyResourcesStep(cx);

        // ---- compile-test -----------------------------------------------
        Task compileTest = compileTestStep(cx);

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
        Task runTests = runTestsStep(cx, pluginDeclsF, testStampRequires);

        // ---- plugin steps ------------------------------------------------
        List<Task> pluginSteps = new ArrayList<>();
        PluginBuild.TaskDecl transform = transformStep(pluginDeclsF);
        if (pluginDeclsF != null) {
            for (PluginBuild.TaskDecl step : pluginDeclsF.steps()) {
                pluginSteps.add(pluginTask(cx, pluginActiveF, step, transform));
            }
        }

        // ---- package-jar ------------------------------------------------
        Task packageJar = packageJarStep(cx, pluginActiveF, pluginDeclsF, variantSecretsF);

        // ---- write-stamp ------------------------------------------------
        Task writeStamp = writeStampStep(cx);

        // ---- write-stamp-kotlin -----------------------------------------
        // Kotlin's freshness companion (cf. write-stamp for Java). Mirrors the
        // input set compile-kotlin checked: Kotlin sources, plus Java sources in
        // a mixed module. No action-cache key exists yet — the direct kotlinc
        // path leaves it empty until incremental Kotlin lands.
        Task writeStampKotlin = writeStampKotlinStep(cx);

        // ---- write-stamp-groovy -----------------------------------------
        // Groovy's freshness companion, mirroring write-stamp-kotlin.
        Task writeStampGroovy = writeStampGroovyStep(cx);

        // ---- assemble-classes (mixed modules only) ----------------------
        // Merge the per-language output dirs into the shared classes dir that
        // packaging, tests, and the run/native tails all read.
        Task assembleClasses = assembleClassesStep(cx);

        BuildPlan.Builder b =
                BuildPlan.builder("build").addTask(parseBuild).addTask(syncDeps).addTask(ensureJdk);
        // Workspace root with no sources: validate jk.toml + sync deps, nothing more.
        if (workspaceNoSources) return b.terminal(TaskNames.RESOLVE_DEPS);
        // SPI BEFORE_COMPILE / GENERATE: codegen before any language compile (or KSP).
        b.addTask(buildLogicBeforeCompileStep(cx));
        if (kspEnabled) {
            b.addTask(kspStep(cx, pluginDeclsF));
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
        b.addTask(buildLogicAfterCompileStep(cx));
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
            b.addTask(buildLogicBeforePackageStep(cx));
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
    private record Ctx(
            Inputs in,
            Cas cas,
            ActionCache actionCache,
            java.util.function.Supplier<EffortWeights.Plan> plan,
            java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef,
            java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef,
            java.util.concurrent.atomic.AtomicReference<List<Path>> groovyMainSrcRef,
            Path javaMainSrcDir,
            boolean compact,
            boolean mixed,
            boolean mixedGroovy,
            boolean kotlinModule,
            boolean groovyModule,
            boolean mixedWithJava,
            String mainCompile,
            boolean ksp) {}

    private static Task parseBuildStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> groovyMainSrcRef = cx.groovyMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.PARSE_BUILD)
                .stage(BuildStage.RESOLVE)
                .label("Parsing")
                .weight(() -> plan.get().fullyCached() ? W_CACHED_TOUCH : W_PARSE)
                .ticks(() -> {
                    if (Files.exists(in.lockFile())) {
                        try {
                            return LockfileReader.read(in.lockFile())
                                            .artifacts()
                                            .size()
                                    + 5;
                        } catch (Exception ignored) {
                        }
                    }
                    return 10;
                })
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project;
                    try {
                        project = cc.jumpkick.plugin.manifest.VariantApply.apply(
                                        JkBuildParser.parse(in.buildFile()),
                                        in.dir(),
                                        cc.jumpkick.model.Variants.Selection.parse(in.variant()),
                                        in.clientEnv())
                                .build();
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    BuildLayout layout = BuildLayout.of(in.dir(), project);
                    ctx.put(LAYOUT, layout);

                    if (!Files.exists(in.lockFile())) {
                        ctx.label("resolve deps (first run)");
                        LockFlow.Result result;
                        try {
                            // noDefaultFeatures=false — same feature selection as `jk lock` (JK-1358).
                            result = LockFlow.run(in.lockDir(), in.cache(), List.of(), false, null);
                        } catch (UnsatisfiableException e) {
                            ctx.error("verbatim", e.getMessage());
                            throw new RuntimeException("dependency resolution failed");
                        }
                        if (result.status() != 0) {
                            ctx.error(
                                    "verbatim",
                                    result.error() != null ? result.error() : "dependency resolution failed");
                            throw new RuntimeException("lock failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                    } else if (AutoLock.isStale(in.dir(), in.lockFile())) {
                        ctx.label("jk.toml changed — updating lock");
                        Lockfile existing = LockfileReader.read(in.lockFile());
                        Lockfile updated = AutoLock.maybeReLock(
                                in.dir(),
                                existing,
                                in.lockFile(),
                                in.cache(),
                                null,
                                cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
                                List.of(),
                                true,
                                cc.jumpkick.resolver.ResolveObserver.NOOP,
                                ctx::output);
                        ctx.put(LOCKFILE, updated != null ? updated : existing);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(in.lockFile()));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);
                    // Reading the lock keeps its deps fresh against the 90-day cache GC.
                    cc.jumpkick.task.AccessLedger.atDefaultPath().touchLock(lock);
                    ctx.label("resolve classpath");
                    ClasspathResolver resolver = new ClasspathResolver(cas);

                    WorkspaceClasspath.Result mainSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN));
                    if (!mainSiblings.missingSiblingJars().isEmpty()) {
                        for (String missing : mainSiblings.missingSiblingJars())
                            ctx.error("workspace", "sibling not built — " + missing);
                        throw new RuntimeException("missing workspace siblings");
                    }
                    // Lockfile + sibling jars + siblings' transitive lockfile deps — the
                    // exact classpath `jk explain` re-derives, so the action keys match.
                    List<Path> mainCp = mainCompileClasspath(lock, resolver, mainSiblings);
                    // Plugin-contributed PROVIDED classpath (an Android platform jar): javac
                    // sees it, runtime/packaging never do. Resolved through the same engine
                    // fetch the steps use, so the compile action key fingerprints it.
                    List<Path> contributedProvided = contributedProvidedClasspath(project, in, cas);
                    mainCp.addAll(contributedProvided);

                    Profile profile = CompileSupport.resolveProfile(project.profiles(), in.profileName());
                    // Default lint (deprecation/unchecked) unless [build] lint = false;
                    // the profile's own javac args win (appended after). Shared by the
                    // main- and test-compile steps (both read JAVAC_ARGS).
                    ctx.put(
                            JAVAC_ARGS,
                            cc.jumpkick.compile.JavacLint.effectiveArgs(
                                    project.build().lint(),
                                    cc.jumpkick.plugin.manifest.PluginContributions.javacArgs(
                                            project, in.dir(), lockModules(lock)),
                                    profile == null ? List.of() : profile.javacArgs()));
                    ctx.put(CLASSPATH, mainCp);

                    // Annotation processors live in their own scope (kept off the
                    // compile classpath); javac discovers them via -processorpath and
                    // KspProcessors.split routes the KSP ones to the forked KSP2 round.
                    // Workspace siblings must merge in exactly as they do for main/test
                    // a processor declared `{ workspace = true }` is never in the
                    // lock, so a lock-only path silently yields no processors at all.
                    WorkspaceClasspath.Result processorSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.PROCESSOR));
                    // A declared processor that cannot be found generates nothing, and a build
                    // that silently skips code generation is worse than one that fails
                    // . Mirror the main-classpath missing-sibling guard above.
                    if (!processorSiblings.missingSiblingJars().isEmpty()) {
                        for (String missing : processorSiblings.missingSiblingJars())
                            ctx.error("workspace", "processor sibling not built — " + missing);
                        throw new RuntimeException("missing workspace siblings");
                    }
                    List<String> unresolvedProcessors = unresolvedProcessorDeps(project, lock, processorSiblings);
                    if (!unresolvedProcessors.isEmpty()) {
                        for (String unresolved : unresolvedProcessors)
                            ctx.error(
                                    "processor",
                                    "processor dependency '" + unresolved + "' is declared in"
                                            + " [processor-dependencies] but is not in jk-lock.toml —"
                                            + " run `jk lock`");
                        throw new RuntimeException("unresolved processor dependencies");
                    }
                    ctx.put(PROCESSOR_CP, processorClasspath(lock, resolver, processorSiblings));

                    WorkspaceClasspath.Result testSiblings = WorkspaceClasspath.resolve(
                            in.dir(),
                            project,
                            Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
                    List<Path> compileTestCp =
                            new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
                    compileTestCp.addAll(testSiblings.jars());
                    List<Path> testRuntimeCp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.TEST));
                    testRuntimeCp.addAll(testSiblings.jars());
                    // A sibling's own external deps (e.g. resolver's maven-artifact) must
                    // also reach the test classpath, or tests exercising sibling code hit
                    // NoClassDefFoundError. Mirrors the main-cp sibling-lockfile loop above.
                    for (java.nio.file.Path sibLock : testSiblings.siblingLockfiles()) {
                        try {
                            cc.jumpkick.lock.Lockfile sl = cc.jumpkick.lock.LockfileReader.read(sibLock);
                            for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN)) {
                                if (!compileTestCp.contains(p)) compileTestCp.add(p);
                            }
                            for (Path p : resolver.classpathFor(sl, ClasspathResolver.RUNTIME)) {
                                if (!testRuntimeCp.contains(p)) testRuntimeCp.add(p);
                            }
                        } catch (Exception ignored) {
                            /* best-effort */
                        }
                    }
                    compileTestCp.addAll(contributedProvided);
                    ctx.put(PROVIDED_CP, contributedProvided);
                    ctx.put(COMPILE_TEST_CP, compileTestCp);
                    ctx.put(TEST_RUNTIME_CP, testRuntimeCp);
                    // Reuse source lists that the tick suppliers may have already walked.
                    // If the ticks haven't fired yet (unusual ordering), populate and cache now.
                    List<Path> javaMainSrcs = javaMainSrcRef.get();
                    if (javaMainSrcs == null) {
                        javaMainSrcs = CompileSupport.collectJavaSources(javaMainSrcDir);
                        javaMainSrcRef.compareAndSet(null, javaMainSrcs);
                        javaMainSrcs = javaMainSrcRef.get();
                    }
                    List<Path> kotlinMainSrcs = kotlinMainSrcRef.get();
                    if (kotlinMainSrcs == null) {
                        kotlinMainSrcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        kotlinMainSrcRef.compareAndSet(null, kotlinMainSrcs);
                        kotlinMainSrcs = kotlinMainSrcRef.get();
                    }
                    List<Path> groovyMainSrcs = groovyMainSrcRef.get();
                    if (groovyMainSrcs == null) {
                        groovyMainSrcs = CompileSupport.collectGroovySources(in.dir(), compact);
                        groovyMainSrcRef.compareAndSet(null, groovyMainSrcs);
                        groovyMainSrcs = groovyMainSrcRef.get();
                    }
                    // [build] extra-src roots (variant overlays folded in by VariantApply) and
                    // plugin-contributed source roots ([[contribute.source-roots]] — grails-app/…)
                    // join the source set here — the tick suppliers' pre-walk never saw them.
                    List<Path> extraSrcDirs = new ArrayList<>(CompileSupport.extraSrcDirs(project, in.dir()));
                    for (var root : cc.jumpkick.plugin.manifest.PluginContributions.sourceRoots(project, in.dir())) {
                        if (!root.resource()) extraSrcDirs.add(in.dir().resolve(root.dir()));
                    }
                    if (!extraSrcDirs.isEmpty()) {
                        javaMainSrcs = CompileSupport.withExtraSources(javaMainSrcs, extraSrcDirs, ".java");
                        kotlinMainSrcs = CompileSupport.withExtraSources(kotlinMainSrcs, extraSrcDirs, ".kt");
                        groovyMainSrcs = CompileSupport.withExtraSources(groovyMainSrcs, extraSrcDirs, ".groovy");
                        javaMainSrcRef.set(javaMainSrcs);
                        kotlinMainSrcRef.set(kotlinMainSrcs);
                        groovyMainSrcRef.set(groovyMainSrcs);
                    }
                    ctx.put(JAVA_SOURCES, javaMainSrcs);
                    ctx.put(KOTLIN_SOURCES, kotlinMainSrcs);
                    ctx.put(GROOVY_SOURCES, groovyMainSrcs);
                    ctx.put(RELEASE, project.project().javaRelease());
                    ctx.put(MAIN_CLASSES, layout.classesDir());
                    ctx.put(TEST_CLASSES, layout.testClassesDir());
                    ctx.progress(1);
                })
                .build();
    }

    private static Task syncDepsStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .label("Syncing")
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .weight(() -> plan.get().sync())
                .ticks(() -> {
                    try {
                        return LockfileReader.read(in.lockFile()).artifacts().size();
                    } catch (Exception ignored) {
                        return 10;
                    }
                })
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild project = ctx.require(PROJECT);
                    boolean mirrorToM2 = project.project().m2install();
                    // Ticks are already counted up front by estimateTicks (artifact
                    // count); progress(1)-per-artifact below fills it.
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + pkg.name());
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void skipped(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String err) {
                            ctx.error("dep", pkg.name() + " — " + err);
                            ctx.progress(1);
                        }
                    };
                    boolean refresh = in.session().config().forceOr(false);
                    var report = new CacheSync(cas, new Http(), mirrorToM2).sync(lock, observer, refresh);
                    if (report.hasErrors()) throw new RuntimeException("dep sync had errors");
                })
                .build();
    }

    private static Task ensureJdkStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.ENSURE_JDK)
                .stage(BuildStage.RESOLVE)
                .label("JDK")
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .weight(() -> EffortWeights.jdkWeight(in.dir(), in.jdksDir()))
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve JDK");
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild project = ctx.require(PROJECT);
                    try {
                        JdkEnsure.Outcome outcome =
                                JdkEnsure.ensure(in.dir(), in.jdksDir(), project, lock, m -> ctx.warn("jdk", m));
                        // JAVA_HOME is published HERE, not in parse-build: resolving before the
                        // ensure meant the FIRST build against a never-installed pin snapshotted
                        // the running JVM and compiled/tested on the wrong JDK (self-healing on
                        // the next build — but wrong once is wrong). The ensure's own outcome is
                        // authoritative; the walk is only the no-pin fallback.
                        ctx.put(
                                JAVA_HOME,
                                outcome.jdk()
                                        .map(cc.jumpkick.jdk.InstalledJdk::home)
                                        .orElseGet(() -> JavaHomes.resolveJavaHome(in.dir())));
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** The step names of every source-generating plugin step the compilers must wait for. */
    private static List<String> sourceGenStepSteps(PluginBuild.Declarations decls) {
        List<String> out = new ArrayList<>();
        if (decls != null) {
            for (PluginBuild.TaskDecl step : decls.steps()) {
                if (beforeCompile(step)) out.add("plugin-" + step.name());
            }
        }
        return out;
    }

    /**
     * Plugin-contributed generated sources ({@code contributesSources} of before-compile steps):
     * files with {@code suffix} under each contributed scratch dir. They join the compiler's
     * source list, so the freshness stamp and the javac action key see them like any source.
     */
    private static List<Path> pluginContributedSources(
            BuildLayout layout, PluginBuild.Declarations decls, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.TaskDecl step : decls.steps()) {
            for (String rel : step.contributesSources()) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (!Files.isDirectory(dir)) continue;
                try (var walk = Files.walk(dir)) {
                    walk.filter(f -> f.toString().endsWith(suffix) && Files.isRegularFile(f))
                            .sorted()
                            .forEach(out::add);
                }
            }
        }
        return out;
    }

    /** Plugin steps' declared source-contribution dirs (existing ones only). */
    private static List<Path> pluginContributedSourceDirs(BuildLayout layout, PluginBuild.Declarations decls) {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.TaskDecl step : decls.steps()) {
            for (String rel : step.contributesSources()) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (Files.isDirectory(dir)) out.add(dir);
            }
        }
        return out;
    }

    /** Plugin steps' declared test-classpath contribution dirs (existing ones only). */
    private static List<Path> pluginTestClasspath(BuildLayout layout, PluginBuild.Declarations decls) {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.TaskDecl step : decls.steps()) {
            for (String rel : step.contributesTestClasspath()) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (Files.isDirectory(dir)) out.add(dir);
            }
        }
        return out;
    }

    /** The provided-classpath contribution (platform jars), re-read for the test step. */
    @SuppressWarnings("unchecked")
    private static List<Path> contributedProvidedFor(cc.jumpkick.run.TaskContext ctx) {
        return (List<Path>) ctx.get(PROVIDED_CP).orElse(List.of());
    }

    /** Generated-source dirs the KSP round writes (checked by the compile-step unions). */
    /** One processor-authored KSP diagnostic: the reporting severity plus the bare message. */
    record KspDiagnostic(String severity, String message) {}

    /**
     * The processor-authored diagnostics in a successful KSP round's output.
     *
     * <p>KSP's CLI prefixes them {@code w:} / {@code i:} / {@code v:}, usually with a {@code [ksp]}
     * tag. Everything else on that stream is host noise — the JVM's {@code sun.misc.Unsafe}
     * deprecation banner from KSP's bundled IntelliJ containers, stack frames, blank lines — and
     * reprinting it on every green build would train people to ignore the channel entirely.
     *
     * <p>Both prefixes are stripped: the reporter already renders the step and severity, so
     * carrying them in the text too gives {@code Warning [ksp/ksp]: w: [ksp] …}.
     */
    static List<KspDiagnostic> kspDiagnostics(String output) {
        List<KspDiagnostic> out = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.length() < 2 || trimmed.charAt(1) != ':') continue;
            String severity =
                    switch (trimmed.charAt(0)) {
                        case 'w' -> "warn";
                        case 'i' -> "info";
                        case 'v' -> "verbose";
                        default -> null;
                    };
            if (severity == null) continue;
            // KSP tags its own output; kotlinc-level warnings on the same stream are the Kotlin
            // compile step's business, not ours.
            String rest = trimmed.substring(2).strip();
            if (!rest.startsWith("[ksp]")) continue;
            String message = rest.substring("[ksp]".length()).strip();
            if (message.isEmpty()) continue;
            out.add(new KspDiagnostic(severity, message));
        }
        return out;
    }

    static Path kspOutBase(BuildLayout layout) {
        return layout.moduleTargetDir().resolve("ksp");
    }

    /** Files with {@code suffix} under the KSP output tree, sorted — empty when no round ran. */
    private static List<Path> kspGeneratedSources(BuildLayout layout, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String lang : List.of("kotlin", "java")) {
            Path dir = kspOutBase(layout).resolve(lang);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                walk.filter(f -> f.toString().endsWith(suffix) && Files.isRegularFile(f))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    /**
     * KSP2 round: fork {@code KSPJvmMain} with KSP processor jars ({@link
     * cc.jumpkick.compile.KspProcessors}); outputs under {@code target/ksp/} join compile sources.
     */
    private static Task kspStep(Ctx cx, PluginBuild.Declarations pluginDecls) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        boolean compact = cx.compact();
        // Plugin-contributed sources (protoc output, variant extra-src) must exist before the
        // round and join its source roots — a contributed @Module/@Entity is processor input
        // like any hand-written one.
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        requires.addAll(sourceGenStepSteps(pluginDecls));
        return Task.builder("ksp")
                .stage(BuildStage.COMPILE)
                .label("KSP")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp = (List<Path>) ctx.require(PROCESSOR_CP);
                    var split = cc.jumpkick.compile.KspProcessors.split(processorCp);
                    ctx.put(JAVAC_PROCESSOR_CP, split.javac());
                    if (split.ksp().isEmpty()) {
                        ctx.label("no KSP processors");
                        ctx.progress(1);
                        return;
                    }
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path outBase = kspOutBase(layout);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> ktSources = kotlinSources(ctx);
                    List<Path> javaSources = javaSources(ctx);

                    List<Path> stampInputs = new ArrayList<>(ktSources);
                    stampInputs.addAll(javaSources);
                    // Contributed sources are round input too — an extra-src/protoc edit re-runs.
                    stampInputs.addAll(pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".kt"));
                    stampInputs.addAll(pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".java"));
                    List<Path> stampCp = new ArrayList<>(classpath);
                    stampCp.addAll(split.ksp());
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun
                            && cc.jumpkick.task.FreshnessStamp.isFresh(
                                    outBase, KSP_STAMP, stampInputs, stampCp, ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // cache/stamp skip — token tick
                        ctx.label("up to date");
                        ctx.progress(1);
                        return;
                    }

                    ctx.label("KSP: " + split.ksp().size() + " processor jar(s)");
                    JkBuild project = ctx.require(PROJECT);
                    String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), project);
                    if (kotlinVersion == null || kotlinVersion.isBlank()) {
                        kotlinVersion = cc.jumpkick.kotlin.KotlinResolver.DEFAULT_VERSION;
                    }
                    List<Path> kspClasspath;
                    Path stdlib;
                    try {
                        cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
                        String kspVersion = KspResolver.discoverVersion(repos);
                        kspClasspath = KspResolver.resolveClasspath(repos, cas, kspVersion);
                        stdlib = KotlinBtaResolver.resolveStdlib(repos, cas, kotlinVersion);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted resolving KSP", e);
                    }

                    // A stale round's outputs must not survive into the source union.
                    for (String sub : List.of("kotlin", "java", "classes", "resources")) {
                        cc.jumpkick.util.PathUtil.deleteRecursively(outBase.resolve(sub));
                    }
                    Files.createDirectories(outBase.resolve("caches"));

                    List<Path> srcRoots = new ArrayList<>(
                            compact
                                    ? List.of(in.dir().resolve("src"))
                                    : List.of(
                                            in.dir().resolve("src/main/kotlin"),
                                            in.dir().resolve("src/main/java")));
                    // Plugin-contributed source dirs (protoc output, generated code) and
                    // [build] extra-src roots (variant overlays) are processor input like any
                    // hand-written source.
                    srcRoots.addAll(pluginContributedSourceDirs(ctx.require(LAYOUT), pluginDecls));
                    srcRoots.addAll(CompileSupport.extraSrcDirs(project, in.dir()));
                    List<Path> ktRoots = new ArrayList<>();
                    for (Path root : srcRoots) {
                        if (Files.isDirectory(root)) ktRoots.add(root);
                    }
                    String sep = java.io.File.pathSeparator;
                    List<Path> libs = new ArrayList<>(classpath);
                    libs.add(stdlib);

                    String languageVersion = majorMinor(kotlinVersion);
                    // KSP is jk's tool: it runs on jk's own runtime, not the project's pinned
                    // JDK (same rule as every plugin — requirements.md "plugin host"). AGP runs
                    // KSP in the Gradle daemon's JVM the same way; the project JDK stays the
                    // -jdk-home cross-compile input below.
                    Path javaHome = ctx.require(JAVA_HOME);
                    List<String> cmd = new ArrayList<>();
                    cmd.add(cc.jumpkick.jdk.JavaHomes.runningJavaHome()
                            .resolve("bin/java")
                            .toString());
                    cmd.addAll(cc.jumpkick.engine.plugin.JvmOptions.batchFlags(1));
                    cmd.add("-cp");
                    cmd.add(joinPaths(kspClasspath, sep));
                    cmd.add(KspResolver.KSP_MAIN);
                    cmd.add("-module-name=" + project.project().name());
                    cmd.add("-source-roots=" + joinPaths(ktRoots, sep));
                    cmd.add("-java-source-roots=" + joinPaths(ktRoots, sep));
                    cmd.add("-project-base-dir=" + in.dir().toAbsolutePath());
                    cmd.add("-output-base-dir=" + outBase.toAbsolutePath());
                    cmd.add("-caches-dir=" + outBase.resolve("caches").toAbsolutePath());
                    cmd.add("-class-output-dir=" + outBase.resolve("classes").toAbsolutePath());
                    cmd.add("-kotlin-output-dir=" + outBase.resolve("kotlin").toAbsolutePath());
                    cmd.add("-java-output-dir=" + outBase.resolve("java").toAbsolutePath());
                    cmd.add("-resource-output-dir="
                            + outBase.resolve("resources").toAbsolutePath());
                    cmd.add("-language-version=" + languageVersion);
                    cmd.add("-api-version=" + languageVersion);
                    cmd.add("-jvm-target=" + CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)));
                    cmd.add("-jdk-home=" + javaHome.toAbsolutePath());
                    cmd.add("-libraries=" + joinPaths(libs, sep));
                    // Processor options: plugin-contributed ([[contribute.compiler-args]] ksp
                    // Hilt's superclass-validation toggle) plus project-declared ([build]
                    // ksp-options — Room's schemaLocation; last wins, so the project overrides).
                    // KSP's map syntax joins entries with the platform path separator, same as
                    // its list args; relative option paths resolve against the module dir (the
                    // KSP process CWD).
                    List<String> kspOptions =
                            new ArrayList<>(cc.jumpkick.plugin.manifest.PluginContributions.kspOptions(
                                    project, in.dir(), lockModules(ctx.require(LOCKFILE))));
                    kspOptions.addAll(project.build().kspOptions());
                    if (!kspOptions.isEmpty()) {
                        cmd.add("-processor-options=" + String.join(sep, kspOptions));
                    }
                    // The trailing processor classpath is the WHOLE [processor-dependencies]
                    // closure — a provider jar (room-compiler) loads its own deps from it.
                    cmd.add(joinPaths(processorCp, sep));

                    ProcessBuilder pb =
                            new ProcessBuilder(cmd).directory(in.dir().toFile()).redirectErrorStream(true);
                    Process proc = pb.start();
                    // Read on a drainer thread and bound the wait: on an internal error KSP's JVM
                    // can linger (non-daemon compiler pools survive the main thread's exception),
                    // which would hang a plain readAllBytes forever.
                    StringBuilder captured = new StringBuilder();
                    Thread drainer = new Thread(() -> {
                        try (var in2 = proc.getInputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in2.read(buf)) >= 0) {
                                captured.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                            }
                        } catch (IOException ignored) {
                            // stream closed with the process
                        }
                    });
                    drainer.setDaemon(true);
                    drainer.start();
                    int exit;
                    try {
                        if (!proc.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)) {
                            proc.destroyForcibly();
                            ctx.error("ksp", "KSP timed out after 15 minutes\n" + captured);
                            throw new RuntimeException("KSP timed out");
                        }
                        exit = proc.exitValue();
                        drainer.join(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        proc.destroyForcibly();
                        throw new RuntimeException("interrupted waiting for KSP", e);
                    }
                    String output = captured.toString();
                    if (exit != 0) {
                        ctx.error("ksp", output.isBlank() ? ("KSP exited " + exit) : output);
                        throw new RuntimeException("KSP processing failed");
                    }
                    // A green round still has things to say. Processor `logger.warn`/`info` is how
                    // an annotation-driven framework explains what it did and what to do
                    // differently; dropping it on success meant guidance only ever appeared once
                    // the build was already broken. Surfaced the same way javac
                    // diagnostics are, so -q/-v behave consistently.
                    for (KspDiagnostic diagnostic : kspDiagnostics(output)) {
                        ctx.warn(diagnostic.severity(), diagnostic.message());
                    }
                    cc.jumpkick.task.FreshnessStamp.write(
                            outBase, KSP_STAMP, "ksp", "", stampInputs, stampCp, ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The Java source roots kotlinc reads declarations from in a mixed module: the hand-written
     * root plus the KSP round's generated-Java dir (Hilt components are Java — a Kotlin class
     * extending a generated base must resolve it during Kotlin analysis).
     */
    private static List<Path> kotlinJavaSourceRoots(
            boolean mixedWithJava, boolean compact, Path dir, BuildLayout layout, PluginBuild.Declarations decls) {
        if (!mixedWithJava) return null;
        List<Path> roots = new ArrayList<>();
        roots.add(compact ? dir.resolve("src") : dir.resolve("src/main/java"));
        Path kspJava = kspOutBase(layout).resolve("java");
        if (Files.isDirectory(kspJava)) roots.add(kspJava);
        // Plugin-contributed generated dirs can carry Java that Kotlin sources reference
        // (protoc: the --kotlin_out DSL wraps its own --java_out message classes).
        if (decls != null) {
            for (PluginBuild.TaskDecl step : decls.steps()) {
                for (String rel : step.contributesSources()) {
                    Path contributed =
                            PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                    if (Files.isDirectory(contributed)) roots.add(contributed);
                }
            }
        }
        return roots;
    }

    /** The {@code major.minor} language level of a full Kotlin version ({@code 2.4.0} → 2.4). */
    private static String majorMinor(String version) {
        int first = version.indexOf('.');
        int second = version.indexOf('.', first + 1);
        return second > 0 ? version.substring(0, second) : version;
    }

    private static String joinPaths(List<Path> paths, String sep) {
        StringBuilder b = new StringBuilder();
        for (Path pth : paths) {
            if (b.length() > 0) b.append(sep);
            b.append(pth.toAbsolutePath());
        }
        return b.toString();
    }

    /** KSP's freshness companion, mirroring compile-kotlin's stamp discipline. */
    private static final String KSP_STAMP = ".kspstamp";

    private static Task compileJavaStep(Ctx cx, PluginBuild.Declarations pluginDecls) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COMPILE_JAVA)
                .stage(BuildStage.COMPILE)
                .label("Compiling")
                .kind(TaskKind.CPU)
                .requires(javaCompileRequires(mixed, cx.mixedGroovy(), pluginDecls, cx.ksp()))
                // Ticks count sources (granularity); weight is the bar share. javac
                // is opaque — one progress(sources.size) on completion — so ease the
                // slice forward over time while it runs instead of sitting flat.
                .weight(() -> plan.get().compileJava())
                .interpolated()
                .ticks(() -> {
                    // Populate the cache if not yet done (may have been filled by
                    // parse-build execute or by EffortWeights.predict via collectJavaSources).
                    List<Path> srcs = javaMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectJavaSources(javaMainSrcDir);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        javaMainSrcRef.compareAndSet(null, srcs);
                        srcs = javaMainSrcRef.get();
                    }
                    return srcs.size();
                })
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    // javac always writes to the canonical classes dir (java/main/).
                    // The Kotlin incremental compiler gets its own dir (kotlin/main/)
                    // so it cannot prune Java's output; the assembler merges both.
                    Path javaOut = classes;
                    List<Path> sources = javaSources(ctx);
                    List<Path> generated = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".java");
                    List<Path> kspGenerated = kspGeneratedSources(ctx.require(LAYOUT), ".java");
                    if (!generated.isEmpty() || !kspGenerated.isEmpty()) {
                        sources = new ArrayList<>(sources);
                        sources.addAll(generated);
                        sources.addAll(kspGenerated);
                        // Re-publish the union so write-stamp records the same input set
                        // this compile checked (else the fast freshness path never holds).
                        ctx.put(JAVA_SOURCES, sources);
                    }
                    if (sources.isEmpty()) {
                        ctx.label("no Java sources");
                        Files.createDirectories(javaOut);
                        ctx.put(BUILD_OUTCOME, "no-sources");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> baseClasspath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> classpath = baseClasspath;
                    Path groovyJar = null;
                    if (mixed) {
                        // See Kotlin's output so Java can reference Kotlin types.
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).kotlinClassesDir());
                    }
                    if (cx.mixedGroovy()) {
                        // See Groovy's output so Java can reference Groovy types — plus the
                        // version-matched groovy jar: every Groovy class implements
                        // groovy.lang.GroovyObject, which javac must resolve.
                        groovyJar = groovyCompileJar(ctx, cas);
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).groovyClassesDir());
                        classpath.add(groovyJar);
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp =
                            (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                    boolean rerun = in.session().config().rebuildOr(false);
                    // The shared stamp recipeforecast and write-stamp use it too.
                    List<Path> stampInputs = mainStampClasspath(
                            baseClasspath, processorCp, mixed, cx.mixedGroovy(), ctx.require(LAYOUT), groovyJar);
                    if (!rerun
                            && cc.jumpkick.task.FreshnessStamp.isFresh(
                                    javaOut,
                                    cc.jumpkick.task.FreshnessStamp.JAVA_STAMP,
                                    sources,
                                    stampInputs,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(BUILD_OUTCOME, "up-to-date");
                        ctx.progress(sources.size());
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    if (cx.mixedGroovy()) {
                        // The joint Groovy compile retained Java-visible stubs — put them on
                        // javac's sourcepath so Java→Groovy references resolve even before the
                        // real Groovy classes are visible; the assemble merge overwrites any
                        // stub-compiled.class with the real Groovy output afterwards.
                        Path stubs = ctx.require(LAYOUT).groovyStubsDir();
                        if (Files.isDirectory(stubs)) {
                            javacArgs = new ArrayList<>(javacArgs);
                            javacArgs.add("--source-path");
                            javacArgs.add(stubs.toAbsolutePath().toString());
                        }
                    }
                    CompileRequest request = CompileRequest.builder()
                            .sources(sources)
                            .classpath(classpath)
                            .outputDir(javaOut)
                            .release(ctx.require(RELEASE))
                            .extraOptions(javacArgs)
                            .javaHome(ctx.require(JAVA_HOME))
                            .processorPath(processorCp)
                            .build();
                    String taskId = ActionKey.qualifiedTaskId("compile-main", javaOut);
                    Path javaStateDir = in.cache()
                            .resolve("actions")
                            .resolve("incremental-java")
                            .resolve(taskId);
                    // Reweight the bar slice now that the real request is known: a CAS
                    // action-cache hit means a cheap hard-link restore (3), not a full
                    // javac (ceil(sources × 0.1)). Uses the exact key
                    // JavaIncrementalCompile will look up, so the estimate matches what
                    // actually happens — no plan-start reconstruction divergence.
                    if (!rerun) {
                        try {
                            boolean restores = actionCache
                                    .lookup(ActionKey.forJavac(
                                            taskId, request, cc.jumpkick.model.BuildIdentity.cacheKeyVersion()))
                                    .isPresent();
                            ctx.reweight(
                                    restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
                        } catch (Exception ignored) {
                            /* keep the up-front estimate */
                        }
                    }
                    // With processors declared, hand the incremental compiler an AP setup:
                    // a *lazy* plugin-jar resolver + a stable generated-sources dir. The
                    // engine routes through the plugin only once it has detected
                    // source-generating processors, so bytecode-only processors (e.g.
                    // Lombok) and first builds never resolve it — which matters because
                    // a jk build that didn't bundle the plugin (or its sha resource)
                    // would otherwise fail here even though the plugin isn't needed.
                    // When it *is* needed but unavailable, warn once and fall back to
                    // plain javac (correct, just without incremental AP provenance).
                    cc.jumpkick.task.JavaIncrementalCompile.ApSetup ap = null;
                    if (!processorCp.isEmpty()) {
                        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations");
                        Files.createDirectories(genDir);
                        ap = new cc.jumpkick.task.JavaIncrementalCompile.ApSetup(
                                () -> {
                                    try {
                                        return PluginJar.JAVA_COMPILER.locate(cas);
                                    } catch (RuntimeException e) {
                                        ctx.warn(
                                                "javac",
                                                "java-compiler worker unavailable ("
                                                        + e.getMessage()
                                                        + "); compiling with plain javac"
                                                        + " (no incremental annotation-processing provenance)");
                                        return null;
                                    }
                                },
                                genDir);
                    }
                    ctx.label("compiling " + sources.size() + " sources");
                    cc.jumpkick.task.JavaIncrementalCompile.Result r = cc.jumpkick.task.JavaIncrementalCompile.run(
                            taskId,
                            request,
                            cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
                            !rerun,
                            !in.ephemeralActions(), // verify-scratch: no persistent residue
                            actionCache.cas(),
                            actionCache,
                            javaStateDir,
                            ap);
                    ctx.put(ACTION_KEY, r.actionKey());
                    // Forward every javac diagnostic to the terminal, by severity:
                    // errors fail the build, warnings/notes (e.g. deprecation) are
                    // surfaced but don't. Strip the leading severity word — the
                    // console renderer adds its own ✗/⚠ marker.
                    boolean errored = false;
                    for (CompileResult.Diagnostic d : r.diagnostics()) {
                        if (d.severity() == CompileResult.Severity.ERROR) {
                            ctx.error("javac", d.describe());
                            errored = true;
                        } else {
                            ctx.warn("javac", d.describe());
                        }
                    }
                    if (!r.success()) {
                        // Never fail silently: if no ERROR diagnostic surfaced (crash,
                        // swallowed output), say so explicitly.
                        if (!errored) {
                            ctx.error(
                                    "javac",
                                    "compile failed without compiler diagnostics (outcome: " + r.outcome() + ")");
                        }
                        throw new RuntimeException("javac reported errors");
                    }
                    if (r.cacheHit()) {
                        ctx.label("cache hit " + r.actionKey().substring(0, 8));
                        ctx.cached();
                    }
                    ctx.put(BUILD_OUTCOME, r.outcome());
                    ctx.progress(sources.size());
                })
                .build();
    }

    private static String[] kotlinCompileRequires(PluginBuild.Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        if (ksp) requires.add("ksp");
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    private static String[] javaCompileRequires(
            boolean mixed, boolean mixedGroovy, PluginBuild.Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        if (mixed) requires.add(TaskNames.COMPILE_KOTLIN);
        if (mixedGroovy) requires.add(TaskNames.COMPILE_GROOVY);
        if (ksp) requires.add("ksp");
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    private static String[] groovyCompileRequires(PluginBuild.Declarations decls) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    /** True when the module declares {@code [processor-dependencies]} entries. */
    private static boolean hasProcessorDeps(JkBuild build) {
        List<cc.jumpkick.model.Dependency> procs =
                build.dependencies().byScope().get(Scope.PROCESSOR);
        return procs != null && !procs.isEmpty();
    }

    private static Task compileKotlinStep(Ctx cx, PluginBuild.Declarations pluginDecls) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COMPILE_KOTLIN)
                .stage(BuildStage.COMPILE)
                .label("Kotlin")
                .kind(TaskKind.CPU)
                // Kotlin compiles first (reads Java declarations from source), so it
                // only needs the base steps — javac runs after it in a mixed module
                // plus any source-generating plugin steps.
                .requires(kotlinCompileRequires(pluginDecls, cx.ksp()))
                // Ticks count Kotlin sources (granularity); weight is the bar share
                // (see compile-java). kotlinc is opaque too, so ease it over time.
                .weight(() -> plan.get().compileKotlin())
                .interpolated()
                .ticks(() -> {
                    List<Path> srcs = kotlinMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        kotlinMainSrcRef.compareAndSet(null, srcs);
                        srcs = kotlinMainSrcRef.get();
                    }
                    return srcs.size();
                })
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes); // compile-java may be skipped
                    List<Path> ktSources = kotlinSources(ctx);
                    // Plugin-contributed generated Kotlin (a KSP round, a codegen step) joins the
                    // source list exactly like the Java side — the freshness stamp and the plugin
                    // see generated files as ordinary sources.
                    List<Path> generatedKt = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".kt");
                    List<Path> kspKt = kspGeneratedSources(ctx.require(LAYOUT), ".kt");
                    if (!generatedKt.isEmpty() || !kspKt.isEmpty()) {
                        ktSources = new ArrayList<>(ktSources);
                        ktSources.addAll(generatedKt);
                        ktSources.addAll(kspKt);
                        // Re-publish so write-stamp-kotlin records what this compile checked.
                        ctx.put(KOTLIN_SOURCES, ktSources);
                    }
                    if (ktSources.isEmpty()) {
                        ctx.label("no Kotlin sources");
                        ctx.put(KOTLIN_OUTCOME, "no-sources");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    // Freshness inputs: Kotlin sources plus — in a mixed module
                    // the Java sources, since kotlinc compiles against the Java
                    // output (kotlincCp includes `classes`) and a Java edit can
                    // make our.class files stale. Unlike compile-java there is
                    // no content-hash action cache behind this stamp, so the
                    // check must err conservative: any Java change forces a
                    // Kotlin recompile. The output dir itself is deliberately not
                    // an input (directory mtimes don't track in-place.class
                    // rewrites, and copy-resources churns it).
                    List<Path> freshInputs = new ArrayList<>(ktSources);
                    if (mixedWithJava) freshInputs.addAll(javaSources(ctx));
                    // A shrunken Kotlin source set (a variant switch dropping an extra-src root)
                    // must not leave the dropped classes in the merged output: kotlinc's IC
                    // prunes its own dir, but the assemble merge into classes/ is additive.
                    // Start the merged tree clean — both stamps die with it, so javac re-runs
                    // too (rare: only on source removals).
                    if (cc.jumpkick.task.FreshnessStamp.hasRemovedSources(
                            classes, cc.jumpkick.task.FreshnessStamp.KOTLIN_STAMP, freshInputs)) {
                        cc.jumpkick.util.PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun
                            && cc.jumpkick.task.FreshnessStamp.isFresh(
                                    classes,
                                    cc.jumpkick.task.FreshnessStamp.KOTLIN_STAMP,
                                    freshInputs,
                                    classpath,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(KOTLIN_OUTCOME, "up-to-date");
                        ctx.progress(ktSources.size());
                        return;
                    }
                    ctx.label("compiling " + ktSources.size() + " Kotlin sources");
                    // Kotlin compiles into its own dir, then we merge into the
                    // shared classes dir. The incremental compiler owns its output
                    // dir and prunes files it didn't produce — so it can't share a
                    // dir with javac's output (it would delete the.class files).
                    Path ktOut = ctx.require(LAYOUT).kotlinClassesDir();
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, classes);
                    Path workingDir = in.cache()
                            .resolve("actions")
                            .resolve("incremental-kotlin")
                            .resolve(taskId);
                    // Mixed module: Kotlin reads the Java declarations from source
                    // (analysis only — it emits no Java bytecode; javac does next).
                    cc.jumpkick.task.KotlinCompile.Result kr = compileKotlinSources(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            ktSources,
                            classpath,
                            ktOut,
                            taskId,
                            workingDir,
                            kotlinJavaSourceRoots(mixedWithJava, compact, in.dir(), ctx.require(LAYOUT), pluginDecls));
                    if (!kr.success()) {
                        ctx.error("kotlinc", kr.output());
                        throw new RuntimeException("kotlinc reported errors");
                    }
                    if (kr.cacheHit()) {
                        ctx.label("cache hit " + kr.actionKey().substring(0, 8));
                        ctx.cached();
                    }
                    // Kotlin-only: publish straight into the classes dir. Mixed:
                    // leave it in ktOut for `assemble-classes` to merge after javac.
                    if (!mixedWithJava) copyResources(ktOut, classes);
                    ctx.put(KOTLIN_OUTCOME, "compiled");
                    ctx.progress(ktSources.size());
                })
                .build();
    }

    private static Task compileGroovyStep(Ctx cx, PluginBuild.Declarations pluginDecls) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> groovyMainSrcRef = cx.groovyMainSrcRef();
        boolean compact = cx.compact();
        boolean mixedGroovy = cx.mixedGroovy();
        return Task.builder(TaskNames.COMPILE_GROOVY)
                .stage(BuildStage.COMPILE)
                .label("Groovy")
                .kind(TaskKind.CPU)
                // Groovy compiles first (joint mode reads Java *declarations* by sweeping the
                // .java roots; javac runs after it in a mixed module), so it only needs the
                // base steps plus any source-generating plugin steps.
                .requires(groovyCompileRequires(pluginDecls))
                .weight(() -> plan.get().compileGroovy())
                .interpolated()
                .ticks(() -> {
                    List<Path> srcs = groovyMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectGroovySources(in.dir(), compact);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        groovyMainSrcRef.compareAndSet(null, srcs);
                        srcs = groovyMainSrcRef.get();
                    }
                    return srcs.size();
                })
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes); // compile-java may be skipped
                    List<Path> gvSources = groovySources(ctx);
                    // Plugin-contributed generated Groovy joins the source list exactly like the
                    // Kotlin side — the freshness stamp and the worker see generated files as
                    // ordinary sources.
                    List<Path> generatedGv = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".groovy");
                    if (!generatedGv.isEmpty()) {
                        gvSources = new ArrayList<>(gvSources);
                        gvSources.addAll(generatedGv);
                        // Re-publish so write-stamp-groovy records what this compile checked.
                        ctx.put(GROOVY_SOURCES, gvSources);
                    }
                    if (gvSources.isEmpty()) {
                        ctx.label("no Groovy sources");
                        ctx.put(GROOVY_OUTCOME, "no-sources");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    // Freshness inputs: Groovy sources plus — in a mixed module — the Java
                    // sources, since joint mode resolves against them (any Java edit can make
                    // our.class files or retained stubs stale). Same conservative posture as
                    // compile-kotlin; the action cache behind decides precisely.
                    List<Path> freshInputs = new ArrayList<>(gvSources);
                    if (mixedGroovy) freshInputs.addAll(javaSources(ctx));
                    // A shrunken Groovy source set must not leave dropped classes in the merged
                    // output (the assemble merge into classes/ is additive).
                    if (cc.jumpkick.task.FreshnessStamp.hasRemovedSources(
                            classes, cc.jumpkick.task.FreshnessStamp.GROOVY_STAMP, freshInputs)) {
                        cc.jumpkick.util.PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun
                            && cc.jumpkick.task.FreshnessStamp.isFresh(
                                    classes,
                                    cc.jumpkick.task.FreshnessStamp.GROOVY_STAMP,
                                    freshInputs,
                                    classpath,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(GROOVY_OUTCOME, "up-to-date");
                        ctx.progress(gvSources.size());
                        return;
                    }
                    ctx.label("compiling " + gvSources.size() + " Groovy sources");
                    // Groovy compiles into its own dir, then we merge into the shared classes
                    // dir (the worker's action cache snapshots its whole output dir — it must
                    // never share one with javac).
                    Path gvOut = ctx.require(LAYOUT).groovyClassesDir();
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, classes);
                    // Mixed module: joint mode sweeps the Java roots for resolution only
                    // stubs are retained for javac's sourcepath; jk's javac worker stays
                    // authoritative for the real Java outputs.
                    cc.jumpkick.task.GroovyCompile.Result gr = compileGroovySources(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            gvSources,
                            classpath,
                            gvOut,
                            taskId,
                            mixedGroovy
                                    ? kotlinJavaSourceRoots(true, compact, in.dir(), ctx.require(LAYOUT), pluginDecls)
                                    : null,
                            mixedGroovy ? ctx.require(LAYOUT).groovyStubsDir() : null);
                    if (!gr.success()) {
                        ctx.error("groovyc", gr.output());
                        throw new RuntimeException("groovyc reported errors");
                    }
                    if (gr.cacheHit()) {
                        ctx.label("cache hit " + gr.actionKey().substring(0, 8));
                        ctx.cached();
                    }
                    // Groovy-only: publish straight into the classes dir. Mixed:
                    // leave it in gvOut for `assemble-classes` to merge after javac.
                    if (!mixedGroovy) copyResources(gvOut, classes);
                    ctx.put(GROOVY_OUTCOME, "compiled");
                    ctx.progress(gvSources.size());
                })
                .build();
    }

    private static Task copyResourcesStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COPY_RESOURCES)
                .stage(BuildStage.COMPILE)
                .label("Resources")
                .kind(TaskKind.CPU)
                // After AFTER_COMPILE SPI so generated classes land before resource merge.
                .requires(TaskNames.BUILD_LOGIC_AFTER_COMPILE)
                .weight(() -> plan.get().fullyCached() ? 0 : W_RESOURCES)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    // /1145: SIMPLE uses top-level resources/; TRADITIONAL uses src/main/resources.
                    // Plugin-contributed resource roots (grails-app/conf, i18n, views) merge after.
                    List<Path> resDirs = new ArrayList<>();
                    Path resMain = cc.jumpkick.layout.ModuleLayout.mainResourcesDir(in.dir(), compact);
                    if (Files.isDirectory(resMain)) resDirs.add(resMain);
                    for (var root : cc.jumpkick.layout.ModuleLayout.pluginContributedRoots(in.dir())) {
                        if (!root.resource()) continue;
                        Path dir = in.dir().resolve(root.relative());
                        if (Files.isDirectory(dir)) resDirs.add(dir);
                    }
                    // [build] extra-resources: individual files from outside the module, each with
                    // its own destination and optional rename, so they cannot ride resDirs.
                    List<ExtraResources.Copy> extra = ExtraResources.resolve(ctx.require(PROJECT), in.dir());
                    if (!resDirs.isEmpty() || !extra.isEmpty()) {
                        ctx.label("copy resources");
                        for (Path dir : resDirs) copyResources(dir, classes);
                        for (ExtraResources.Copy c : extra) {
                            Path target = classes.resolve(c.destination());
                            Files.createDirectories(target.getParent());
                            Files.copy(c.source(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        ctx.label("no static resources");
                    }
                    // Project build logic: AFTER_RESOURCES anchor.
                    try {
                        boolean ran = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.AFTER_RESOURCES,
                                ctx::label);
                        if (ran) ctx.label("build-logic applied");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * SPI anchor {@code BEFORE_COMPILE}: named build-logic tasks before main language compile
     * (codegen). Product stage {@link BuildStage#GENERATE}.
     */
    private static Task buildLogicBeforeCompileStep(Ctx cx) {
        Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        return Task.builder(TaskNames.BUILD_LOGIC_BEFORE_COMPILE)
                .stage(BuildStage.GENERATE)
                .label("Build logic (before compile)")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PARSE_BUILD, TaskNames.RESOLVE_DEPS, TaskNames.ENSURE_JDK)
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    try {
                        BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.BEFORE_COMPILE,
                                ctx::label);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** SPI anchor {@code AFTER_COMPILE}: named build-logic tasks after main classes exist. */
    private static Task buildLogicAfterCompileStep(Ctx cx) {
        Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.BUILD_LOGIC_AFTER_COMPILE)
                .stage(BuildStage.COMPILE)
                .label("Build logic (after compile)")
                .kind(TaskKind.CPU)
                .requires(mainCompile)
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    try {
                        BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.AFTER_COMPILE,
                                ctx::label);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** SPI anchor {@code BEFORE_PACKAGE}: named build-logic tasks immediately before jar/image. */
    private static Task buildLogicBeforePackageStep(Ctx cx) {
        Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        return Task.builder(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE)
                .stage(BuildStage.PACKAGE)
                .label("Build logic (before package)")
                .kind(TaskKind.CPU)
                .requires(beforePackageRequires(in))
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    try {
                        BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.BEFORE_PACKAGE,
                                ctx::label);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** BEFORE_PACKAGE waits on resources (and tests when they run) so packaging sees a complete tree. */
    private static String[] beforePackageRequires(Inputs in) {
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.COPY_RESOURCES);
        if (!in.skipTests()) requires.add(TaskNames.RUN_TESTS);
        return requires.toArray(new String[0]);
    }

    private static Task compileTestStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COMPILE_TEST)
                .stage(BuildStage.TEST)
                .label("Test Compile")
                .kind(TaskKind.CPU)
                // AFTER_COMPILE SPI may generate types tests import. copy-resources is a real
                // input, not just ordering: the test classpath (and its action-key fingerprint)
                // includes classes/main, which copy-resources writes — racing it fingerprints a
                // half-copied dir and intermittently crashes on vanishing files under -r.
                .requires(TaskNames.BUILD_LOGIC_AFTER_COMPILE, TaskNames.RESOLVE_DEPS, TaskNames.COPY_RESOURCES)
                .weight(() -> plan.get().compileTest())
                .interpolated() // opaque javac/kotlinc call — ease it over time
                .ticks(1)
                .execute(ctx -> {
                    var sel = in.session() == null
                            ? cc.jumpkick.config.TestSelection.DEFAULT
                            : in.session().testSelection();
                    List<String> discovered = cc.jumpkick.layout.TestSuites.discover(in.dir(), compact);
                    var resolved = sel.resolve(discovered);
                    if (!resolved.ok()) {
                        // Workspace run: a named suite need not exist in EVERY module — the
                        // IDE-generated `jk test --suite integration` config must run where the
                        // suite exists and skip the rest, not fail the workspace.
                        // Single-module runs keep the hard error (typo protection).
                        if (in.projectModules().size() > 1) {
                            ctx.label("suite not present — skipped");
                            ctx.put(NO_TEST_SOURCES, true);
                            ctx.progress(1);
                            return;
                        }
                        throw new IllegalArgumentException(resolved.missingMessage());
                    }
                    List<String> suiteNames = resolved.suites();
                    Path javaTestSrc = cc.jumpkick.layout.TestSuites.primaryJavaRoot(in.dir(), compact, suiteNames);
                    List<Path> javaTest =
                            cc.jumpkick.layout.TestSuites.collectJavaSources(in.dir(), compact, suiteNames);
                    List<Path> ktTest =
                            cc.jumpkick.layout.TestSuites.collectKotlinSources(in.dir(), compact, suiteNames);
                    List<Path> gvTest =
                            cc.jumpkick.layout.TestSuites.collectGroovySources(in.dir(), compact, suiteNames);
                    if (javaTest.isEmpty() && ktTest.isEmpty() && gvTest.isEmpty()) {
                        ctx.label("no test sources");
                        ctx.put(NO_TEST_SOURCES, true);
                        ctx.progress(1);
                        return;
                    }
                    // Store combined test sources for the TestStamp in run-tests.
                    List<Path> allTestSources = new ArrayList<>();
                    allTestSources.addAll(javaTest);
                    allTestSources.addAll(ktTest);
                    allTestSources.addAll(gvTest);
                    ctx.put(TEST_SOURCES, allTestSources);
                    @SuppressWarnings("unchecked")
                    List<Path> compileCp = (List<Path>) ctx.require(COMPILE_TEST_CP);
                    List<Path> baseCp = new ArrayList<>();
                    baseCp.add(ctx.require(MAIN_CLASSES));
                    baseCp.addAll(compileCp);
                    // A Groovy module's classes (main or test) implement groovy.lang.GroovyObject
                    // javac (and groovyc itself) must resolve it from the version-matched jar.
                    if (cx.groovyModule() || !gvTest.isEmpty()) {
                        baseCp.add(groovyCompileJar(ctx, cas));
                    }
                    Path testClasses = ctx.require(TEST_CLASSES);
                    // All suites share classes/test and run-tests scans it: when the SELECTION
                    // changes, wipe the shared output and the per-language merge sources, or the
                    // previous selection's classes and copied resources keep running/shadowing
                    // under the new one indefinitely. `.jk-suites` records the
                    // selection that produced the tree (excluded from action stores/fingerprints
                    // by the.jk- rule).
                    String selectionKey = String.join(",", suiteNames);
                    Path suiteMarker = testClasses.resolve(".jk-suites");
                    String prevSelection = Files.isRegularFile(suiteMarker)
                            ? Files.readString(suiteMarker).trim()
                            : null;
                    if (prevSelection != null && !prevSelection.equals(selectionKey)) {
                        cc.jumpkick.util.PathUtil.deleteRecursively(testClasses);
                        for (Path langOut : List.of(
                                ctx.require(LAYOUT).kotlinTestClassesDir(),
                                ctx.require(LAYOUT).groovyTestClassesDir())) {
                            if (Files.isDirectory(langOut)) {
                                cc.jumpkick.util.PathUtil.deleteRecursively(langOut);
                            }
                        }
                    }
                    boolean mixedTest = !javaTest.isEmpty() && !ktTest.isEmpty();
                    boolean mixedTestGv = !javaTest.isEmpty() && !gvTest.isEmpty();

                    // Groovy test sources first (joint mode sweeps the Java test roots for
                    // resolution), so Java tests can reference Groovy test types. In a mixed
                    // test module each language gets its own output dir, merged below.
                    Path gvTestOut = mixedTestGv ? ctx.require(LAYOUT).groovyTestClassesDir() : testClasses;
                    if (!gvTest.isEmpty()) {
                        ctx.label("compiling " + gvTest.size() + " Groovy test sources");
                        String gvTaskId = ActionKey.qualifiedTaskId("compile-test-groovy", testClasses);
                        List<Path> gvJavaRoots = null;
                        if (mixedTestGv) {
                            gvJavaRoots = new ArrayList<>();
                            for (String suite : suiteNames) {
                                for (Path root : cc.jumpkick.layout.TestSuites.javaRoots(in.dir(), compact, suite)) {
                                    if (Files.isDirectory(root)) gvJavaRoots.add(root);
                                }
                            }
                        }
                        cc.jumpkick.task.GroovyCompile.Result gr = compileGroovySources(
                                ctx, in, cas, actionCache, gvTest, baseCp, gvTestOut, gvTaskId, gvJavaRoots, null);
                        if (!gr.success()) {
                            ctx.error("groovyc", gr.output());
                            throw new RuntimeException("test groovyc reported errors");
                        }
                    }

                    // Kotlin test sources first, so Java tests can reference Kotlin
                    // test types (mirrors the main mixed-module ordering). In a mixed
                    // test module each language gets its own output dir, merged below.
                    Path ktTestOut = mixedTest ? ctx.require(LAYOUT).kotlinTestClassesDir() : testClasses;
                    if (!ktTest.isEmpty()) {
                        ctx.label("compiling " + ktTest.size() + " Kotlin test sources");
                        String ktTaskId = ActionKey.qualifiedTaskId("compile-test-kotlin", testClasses);
                        Path ktWorkingDir = in.cache()
                                .resolve("actions")
                                .resolve("incremental-kotlin")
                                .resolve(ktTaskId);
                        cc.jumpkick.task.KotlinCompile.Result kr = compileKotlinSources(
                                ctx,
                                in,
                                cas,
                                actionCache,
                                ktTest,
                                baseCp,
                                ktTestOut,
                                ktTaskId,
                                ktWorkingDir,
                                mixedTest ? List.of(javaTestSrc) : null);
                        if (!kr.success()) {
                            ctx.error("kotlinc", kr.output());
                            throw new RuntimeException("test kotlinc reported errors");
                        }
                    }

                    // Java test sources, against the Kotlin/Groovy test output in a mixed module.
                    if (!javaTest.isEmpty()) {
                        Path javaTestOut = testClasses; // javac always writes to java/test/
                        List<Path> javaCp = baseCp;
                        if (mixedTest || mixedTestGv) {
                            javaCp = new ArrayList<>(baseCp);
                            if (mixedTest) javaCp.add(ktTestOut);
                            if (mixedTestGv) javaCp.add(gvTestOut);
                        }
                        @SuppressWarnings("unchecked")
                        List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                        // Run the same declared annotation processors over test sources:
                        // modern javac only honors processors named by -processorpath, so
                        // without this a Lombok-using test wouldn't see its generated modules.
                        @SuppressWarnings("unchecked")
                        List<Path> processorCp =
                                (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                        cc.jumpkick.task.JavaIncrementalCompile.ApSetup ap = null;
                        if (!processorCp.isEmpty()) {
                            Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations", "test");
                            Files.createDirectories(genDir);
                            ap = new cc.jumpkick.task.JavaIncrementalCompile.ApSetup(
                                    () -> {
                                        try {
                                            return PluginJar.JAVA_COMPILER.locate(cas);
                                        } catch (RuntimeException e) {
                                            ctx.warn(
                                                    "javac",
                                                    "java-compiler worker unavailable ("
                                                            + e.getMessage()
                                                            + "); compiling tests with plain javac"
                                                            + " (no incremental annotation-processing provenance)");
                                            return null;
                                        }
                                    },
                                    genDir);
                        }
                        boolean ok = TestSupport.compileWithCache(
                                ctx,
                                TaskNames.COMPILE_TEST,
                                javaTestSrc,
                                javaTestOut,
                                javaCp,
                                processorCp,
                                ctx.require(RELEASE),
                                javacArgs,
                                ctx.require(JAVA_HOME),
                                ap,
                                cas,
                                in.cache());
                        if (!ok) throw new RuntimeException("test compile failed");
                    }

                    // In mixed test mode, kotlin/groovy output needs to be merged into
                    // testClasses (java/test/). Java test output already went there directly.
                    if (mixedTest && !ktTest.isEmpty()) {
                        Files.createDirectories(testClasses);
                        copyResources(ktTestOut, testClasses);
                    }
                    if (mixedTestGv && !gvTest.isEmpty()) {
                        Files.createDirectories(testClasses);
                        copyResources(gvTestOut, testClasses);
                    }
                    // Test resources ride the test classpath next to compiled tests (Gradle's
                    // processTestResources). Without this, getResourceAsStream fixtures NPE under
                    // self-host.
                    // copy resources for every suite in this run's selection
                    // (default test/resources/ + e.g. integration/resources/).
                    List<Path> suiteResDirs =
                            cc.jumpkick.layout.ModuleLayout.suiteResourceDirs(in.dir(), compact, suiteNames);
                    for (Path resTest : suiteResDirs) {
                        Files.createDirectories(testClasses);
                        copyResources(resTest, testClasses);
                    }
                    // Fixtures affect test outcomes but classes/test is not on the runtime cp
                    // run-tests folds these dirs into its TestStamp key.
                    ctx.put(TEST_RESOURCE_DIRS, suiteResDirs);
                    Files.createDirectories(testClasses);
                    Files.writeString(suiteMarker, selectionKey);
                    ctx.progress(1);
                })
                .build();
    }

    private static Task runTestsStep(Ctx cx, PluginBuild.Declarations pluginDecls, List<String> extraRequires) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        List<String> testRequires = new ArrayList<>();
        testRequires.add(TaskNames.COMPILE_TEST);
        testRequires.add(TaskNames.COPY_RESOURCES);
        testRequires.addAll(extraRequires);
        if (pluginDecls != null) {
            for (PluginBuild.TaskDecl step : pluginDecls.steps()) {
                if (step.testOnly() || !step.contributesTestClasspath().isEmpty()) {
                    testRequires.add("plugin-" + step.name());
                }
            }
        }
        return Task.builder(TaskNames.RUN_TESTS)
                .stage(BuildStage.TEST)
                .label("Testing")
                .kind(TaskKind.IO)
                .requires(testRequires.toArray(new String[0]))
                .weight(() -> plan.get().runTests())
                .ticks(in.estimatedTestCount())
                .execute(ctx -> {
                    if (ctx.get(NO_TEST_SOURCES).orElse(false)) {
                        ctx.label("no tests to run");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> testRtCp = (List<Path>) ctx.require(TEST_RUNTIME_CP);
                    testRtCp = new ArrayList<>(testRtCp);
                    // Plugin test-classpath contributions (contributesTestClasspath — e.g. the
                    // android plugin's Robolectric test_config dir) join the test runtime cp.
                    testRtCp.addAll(pluginTestClasspath(ctx.require(LAYOUT), pluginDecls));
                    // The provided platform (android.jar) rides LAST: unit tests calling framework
                    // stubs get the platform's throw-on-call contract (AGP's default posture), and
                    // anything real on the classpath shadows it.
                    testRtCp.addAll(contributedProvidedFor(ctx));
                    Path testClassesForStamp = ctx.require(TEST_CLASSES);
                    @SuppressWarnings("unchecked")
                    List<Path> testSrcs = ctx.get(TEST_SOURCES).orElse(java.util.List.of());
                    // Plugin jars handed to the test JVM ([build.test-plugin-jars])
                    // plugin-forking tests' behavior depends on their content, so resolve
                    // them up front so they also feed the freshness key below.
                    JkBuild projectUnderTest = ctx.require(PROJECT);
                    // Worker jars feed both the forked JVM and the TestStamp
                    // nested-engine CLI modules enrich with engine + every PluginJar so the stamp
                    // matches what the suite actually loads — same set forecast uses.
                    Map<String, String> workerJars = testStampWorkerJars(in.dir(), projectUnderTest);
                    // Nested-engine suites (jk-cli): isolate JK_STATE_DIR so EngineTestExtension
                    // cannot kill the host engine running this test step. Sandboxed JK_HOME/JK_M2_LOCAL
                    // plus this module's [test] env — without it a forked test JVM inherits the
                    // engine's environment and runs against the developer's real product layout.
                    // Nested-engine isolation layers on top and wins on any key both set.
                    Map<String, String> testEnv = new java.util.LinkedHashMap<>(
                            TestEnv.forModule(projectUnderTest, in.dir(), ctx.require(LAYOUT)));
                    if (needsNestedEngineIsolation(projectUnderTest)) {
                        testEnv.putAll(nestedEngineTestEnv(in.dir()));
                    }

                    // Incremental test skip: a content key over every input that affects
                    // the outcome — own main output, test sources, the *content* of the
                    // runtime classpath (sibling modules included), the lock, and the
                    // toolchain/runner/plugin identity. Unchanged → skip the runner.
                    @SuppressWarnings("unchecked")
                    List<Path> testResDirs = ctx.get(TEST_RESOURCE_DIRS).orElse(java.util.List.of());
                    // [test] default-exclude-tags reaches jk build / BSP toothe CLI
                    // resolves defaults only for `jk test`; when the session selection carries
                    // no tags at all, apply this module's own config defaults here. The
                    // effective selection feeds BOTH the stamp and the runner.
                    var effectiveSel = effectiveSelection(in.session().testSelection(), in.dir());
                    String stampKey = cc.jumpkick.task.TestStamp.computeKey(
                            testSrcs,
                            ctx.require(MAIN_CLASSES),
                            testResDirs,
                            in.lockFile(),
                            testRtCp,
                            testStampExtras(
                                    workerJars,
                                    effectiveSel,
                                    projectUnderTest.build().testEnv(),
                                    in.dir()));
                    String testTaskId = ActionKey.qualifiedTaskId(TaskNames.RUN_TESTS, testClassesForStamp);
                    // --force forces a real test run, matching the compile/package
                    // freshness checks above (which all guard on !rerun). Without
                    // this guard the action record would skip the runner even when
                    // the user explicitly asked to bypass build caches.
                    boolean rerun = in.session().config().rebuildOr(false);
                    // The "tests passed for this input" marker lives in the CAS (keyed by
                    // the content key), NOT in target/ — so it survives `jk clean`: a later
                    // build that restores byte-identical classes recomputes the same key and
                    // skips the runner, mirroring how the compile cache survives clean.
                    // stampKey is null only when computeKey failed open (unreadable
                    // input) — treat that as "not cached" and run the tests.
                    if (!rerun && stampKey != null) {
                        var greenRecord = actionCache.lookup(stampKey);
                        if (greenRecord.isPresent()) {
                            ctx.reweight(EffortWeights.TOKEN); // cache/stamp skip — token tick
                            ctx.label("tests up-to-date");
                            ctx.cached();
                            // Replay the green run's counts (stored on the marker) so the summary
                            // line reads "Passed N tests", not "No tests" — without this a
                            // legitimate skip was indistinguishable from a module with no test
                            // sources. Markers written before counts were stored replay nothing;
                            // the next real run upgrades them.
                            cc.jumpkick.run.TestSummary previous = stampedSummary(greenRecord.get());
                            if (previous != null) ctx.put(TEST_RESULT, previous);
                            return; // skip — nothing changed since last green run
                        }
                    }
                    // Tests are actually running: claim the real test slice, symmetric
                    // to how compile reweights itself up. Without this a step the
                    // forecast under-sized (predicted SKIP, but the CAS marker was
                    // missing so we run) stays pinned near-zero while the slow,
                    // serialized run executes — the "stuck near 100% during tests" bug.
                    // Reweight through the SAME learned ledger predict used, NOT the
                    // raw static per-method floor: the static constant is ~10× hot for a
                    // fast suite, so reweighting to it ballooned the denominator (574
                    // tests × 8 → ~11 min) the instant testing began, then collapsed as
                    // the quick tests flew by — the wildly-jumping ETA. Learned == the
                    // up-front estimate, so a correctly-forecast step reweights to the
                    // same value (a no-op) and the countdown stays steady.
                    ctx.reweight(EffortWeights.learned(
                            StepTimings.load(in.cache()),
                            in.dir().toString(),
                            TaskNames.RUN_TESTS,
                            in.estimatedTestCount(),
                            EffortWeights.runTestsWeight(in.estimatedTestCount()),
                            in.projectModules().stream().map(Path::toString).toList()));
                    List<Path> runtimeCp = new ArrayList<>();
                    runtimeCp.add(ctx.require(MAIN_CLASSES));
                    runtimeCp.addAll(testRtCp);
                    // Language runtimes keyed on the SELECTED suites' sources (TEST_SOURCES is
                    // selection-scoped) — the old default-suite-only collectors missed a
                    // Kotlin/Groovy-only named suite and the forked JVM lacked the runtime
                    boolean ktTestSources = testSrcs.stream()
                            .anyMatch(p ->
                                    p.toString().endsWith(".kt") || p.toString().endsWith(".kts"));
                    boolean gvTestSources =
                            testSrcs.stream().anyMatch(p -> p.toString().endsWith(".groovy"));
                    if (kotlinModule || ktTestSources) {
                        runtimeCp.add(kotlinStdlib(ctx, cas));
                    }
                    if (cx.groovyModule() || gvTestSources) {
                        for (Path jar : groovyRuntime(ctx, cas)) {
                            if (!runtimeCp.contains(jar)) runtimeCp.add(jar);
                        }
                    }

                    // Module pin ([test] workers / [build] test-workers) wins over CLI for hermetic
                    // opt-out (Mill testParallelism = false). 0 = auto min(jobs, classes).
                    int testWorkers = projectUnderTest.build().effectiveTestWorkers(in.workerCount());
                    String moduleLabel = projectUnderTest.project().group()
                            + ":"
                            + projectUnderTest.project().name();
                    TestProgressListener listener =
                            TestSupport.bridgeListener(ctx, testWorkers, in.verbose(), moduleLabel);
                    TestSummary result;
                    // Serialize test execution across concurrently-built units unless the
                    // user opted into parallel tests — shared ports/locks/fixtures.
                    boolean gated = !in.session().parallelTests();
                    if (gated) TEST_GATE.acquireUninterruptibly();
                    try {
                        result = new JUnitLauncher()
                                .withModuleLabel(moduleLabel)
                                .withTagFilters(effectiveSel.includeTags(), effectiveSel.excludeTags())
                                .run(
                                        ctx.require(JAVA_HOME),
                                        ctx.require(TEST_CLASSES),
                                        runtimeCp,
                                        in.cache(),
                                        testWorkers,
                                        workerJars,
                                        testEnv,
                                        listener,
                                        ctx.require(LAYOUT).testResultsDir());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ctx.error("test", "interrupted");
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        ctx.error("test", e.getMessage());
                        throw e;
                    } finally {
                        if (gated) TEST_GATE.release();
                    }
                    ctx.put(TEST_RESULT, result);
                    if (!result.allPassed()) {
                        // No marker on failure — the next build re-runs (the absence
                        // of a record for this key is the "not yet green" signal).
                        // Surface each failure (name + stack trace) above the bar
                        // not just the count — like Maven/Gradle.
                        for (String line : TestSupport.renderFailures(result)) ctx.output(line);
                        throw new RuntimeException(
                                result.failed() + " test failure" + (result.failed() == 1 ? "" : "s"));
                    }
                    // All tests passed — record a CAS marker keyed by the content key so a
                    // later build / explain skips the runner when inputs are unchanged. It lives
                    // in the CAS (not target/), so it survives `jk clean`: after clean+build the
                    // compile cache restores byte-identical classes, the key recomputes the
                    // same, and the marker is found. The green counts ride the record so the
                    // skip path can replay them in its summary.
                    // Always store on success — including --redo/--force. Rerun only means
                    // "do not restore/skip the runner"; the marker still uses the normal
                    // content key (not a verify scratch salt), so the next explain must see it
                    // (same contract as compile). Skip only when the key failed open.
                    if (stampKey != null) {
                        actionCache.storeWithOutputs(
                                testTaskId,
                                stampKey,
                                java.util.Map.of(),
                                java.util.Map.of(
                                        "tests.total", String.valueOf(result.total()),
                                        "tests.succeeded", String.valueOf(result.succeeded()),
                                        "tests.skipped", String.valueOf(result.skipped())));
                    }
                })
                .build();
    }

    /** The green run's counts replayed off a run-tests marker; {@code null} for markers written
     * before counts were stored (or with unparseable ones) — the caller then replays nothing. */
    private static cc.jumpkick.run.TestSummary stampedSummary(cc.jumpkick.task.ActionCache.ActionRecord record) {
        try {
            String total = record.outputs().get("tests.total");
            if (total == null) return null;
            long succeeded = Long.parseLong(record.outputs().getOrDefault("tests.succeeded", total));
            long skipped = Long.parseLong(record.outputs().getOrDefault("tests.skipped", "0"));
            return new cc.jumpkick.run.TestSummary(Long.parseLong(total), succeeded, 0, skipped, java.util.List.of());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Task packageJarStep(
            Ctx cx,
            PluginBuild.Active pluginActive,
            PluginBuild.Declarations pluginDecls,
            Map<String, String> variantSecrets) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean groovyModule = cx.groovyModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        boolean javaStamp = mixedWithJava
                || TaskNames.COMPILE_JAVA.equals(mainCompile)
                || (!kotlinModule && !groovyModule);
        return Task.builder(TaskNames.PACKAGE_JAR)
                .stage(BuildStage.PACKAGE)
                .label("Packaging")
                .kind(TaskKind.CPU)
                .requires(packageRequires(in, pluginDecls, javaStamp, kotlinModule, groovyModule))
                .weight(() -> plan.get().pkg())
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path jarPath = layout.mainJar();
                    if (pluginDecls != null && pluginDecls.packager() != null) {
                        // The packager's declared artifact extension replaces.jar (an APK, …).
                        jarPath = PluginBuild.mainArtifactPath(layout, pluginActive);
                        Files.createDirectories(jarPath.getParent());
                        packagePlugin(
                                ctx, in, cas, project, classes, jarPath, pluginActive, pluginDecls, variantSecrets);
                        return;
                    }
                    Files.createDirectories(jarPath.getParent());
                    String mainClass = project.mainClass();
                    // Application jars embed the lockfile-derived SBOM (libraries don't:
                    // their consumers' lockfiles are the truth for the final classpath).
                    byte[] sbom = null;
                    if (project.isApplication()) {
                        Lockfile sbomLock = ctx.get(LOCKFILE).orElse(null);
                        if (sbomLock != null) sbom = applicationSbom(project, sbomLock, cas);
                    }
                    // Packaging cache: the jar is a pure function of the main classes
                    // (resources already copied in), the main-class, the manifest, and
                    // the SBOM content (a lock change re-embeds).
                    List<String> tokens = List.of(
                            "classes:" + cc.jumpkick.task.ClasspathFingerprint.entry(classes),
                            "main:" + (mainClass == null ? "" : mainClass),
                            "sbom:" + (sbom == null ? "" : cc.jumpkick.util.Hashing.sha256Hex(sbom)),
                            "manifest:" + project.manifest());
                    String pkgTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, jarPath);
                    String pkgKey =
                            ActionKey.forArtifact(pkgTask, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), tokens);
                    if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
                        // Sidecar is not in the action cache — refresh for thin PluginMain workers.
                        writeWorkerClasspathSidecar(in.dir(), project, jarPath, in.cache());
                        ctx.put(JAR_PATH, jarPath);
                        ctx.label(jarPath.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + jarPath.getFileName());
                    JarPackager.JarRequest jarRequest = JarPackager.JarRequest.of(classes, jarPath);
                    if (mainClass != null && !mainClass.isBlank()) jarRequest = jarRequest.withMainClass(mainClass);
                    Map<String, String> jarAttrs = new LinkedHashMap<>(project.manifest());
                    if (sbom != null) {
                        jarAttrs.put("Sbom-Format", "CycloneDX");
                        jarAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                        jarRequest = jarRequest.withExtraEntries(Map.of(SBOM_JAR_ENTRY, sbom));
                    }
                    if (!jarAttrs.isEmpty()) jarRequest = jarRequest.withAttributes(jarAttrs);
                    new JarPackager().packageJar(jarRequest);
                    storePackaged(
                            in.cache(),
                            pkgTask,
                            pkgKey,
                            tokens,
                            jarPath.getParent(),
                            List.of(jarPath),
                            !in.ephemeralActions());
                    // Thin PluginMain workers: write .classpath next to the jar so -cp launches
                    // find plugin-sdk and other runtime deps (JK-1347).
                    writeWorkerClasspathSidecar(in.dir(), project, jarPath, in.cache());
                    ctx.put(JAR_PATH, jarPath);
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * When packaging a PluginMain worker, write {@code <jar>.classpath} for thin launches. No-op
     * for libraries / ordinary applications. Prefer lock/workspace closure; fall back to {@link
     * WorkerClasspath#paths} discovery so pure-jk thin jars still get {@code plugin-sdk} (JK-1347).
     */
    private static void writeWorkerClasspathSidecar(Path moduleDir, JkBuild project, Path jarPath, Path cache) {
        String main = project.mainClass();
        if (main == null || !"cc.jumpkick.plugin.process.PluginMain".equals(main)) return;
        try {
            Path jarAbs = jarPath.toAbsolutePath().normalize();
            List<Path> side = new ArrayList<>();
            try {
                for (Path d : ModuleRuntimeClasspath.jars(moduleDir, project, JkStores.cas(cache))) {
                    if (d == null) continue;
                    Path abs = d.toAbsolutePath().normalize();
                    if (!abs.equals(jarAbs) && !side.contains(abs)) side.add(abs);
                }
            } catch (Exception ignored) {
                /* fall through to WorkerClasspath.paths */
            }
            // The closure is authoritative when it produced anything: merging the OLD sidecar back
            // in (via paths()) would carry removed/upgraded deps forever (JK-1352). Only an empty
            // closure (e.g. empty lock mid-bootstrap) falls back to sidecar + findPluginSdk.
            if (side.isEmpty()) {
                for (Path p : WorkerClasspath.paths(jarPath)) {
                    Path abs = p.toAbsolutePath().normalize();
                    if (!abs.equals(jarAbs) && !side.contains(abs)) side.add(abs);
                }
            }
            WorkerClasspath.writeSidecar(jarPath, side);
        } catch (Exception ignored) {
            // Best-effort: launch may still work if the jar vendors the codec or findPluginSdk runs.
        }
    }

    /**
     * package-jar's requires: SPI BEFORE_PACKAGE (which itself waits on resources/tests), plus
     * every before-PACKAGE plugin step.
     */
    private static String[] packageRequires(
            Inputs in, PluginBuild.Declarations decls, boolean useJava, boolean useKotlin, boolean useGroovy) {
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE);
        // Freshness stamps must stay on the package path so target-closure prune retains them.
        if (useJava) requires.add(TaskNames.WRITE_STAMP);
        if (useKotlin) requires.add(TaskNames.WRITE_STAMP_KOTLIN);
        if (useGroovy) requires.add(TaskNames.WRITE_STAMP_GROOVY);
        if (decls != null) {
            for (PluginBuild.TaskDecl step : decls.steps()) {
                if (step.packageTime()) requires.add("plugin-" + step.name());
            }
        }
        return requires.toArray(new String[0]);
    }

    /**
     * True when a declared task must run before the compilers: source generation, or other
     * resolve-window work that does not consume classes (e.g. android-manifest feeds aapt2).
     */
    static boolean beforeCompile(PluginBuild.TaskDecl step) {
        if (step.sourceGenerating()) return true;
        if (step.testOnly() || step.packageTime()) return false;
        // Intermediate tasks with no classes input (and no package/test contributions) run after
        // resolve, not after copy-resources — otherwise they cycle with compile→resources.
        return step.inputs() == null || !step.inputs().contains("classes");
    }

    /**
     * The single classes-dir-replacing task ({@code transformsClasses}), if any. Two transforms are
     * an error; validated at BuildPlan construction.
     */
    static PluginBuild.TaskDecl transformStep(PluginBuild.Declarations decls) {
        if (decls == null) return null;
        PluginBuild.TaskDecl transform = null;
        for (PluginBuild.TaskDecl s : decls.steps()) {
            if (!s.transforms()) continue;
            if (transform != null) {
                throw new IllegalStateException("plugin tasks " + transform.name() + " and " + s.name()
                        + " both declare transformsClasses — at most one task may replace the classes dir"
                        + " (conflicts are errors, not priorities)");
            }
            if (beforeCompile(s)) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses but is source-generating — a transform rewrites"
                        + " compiled classes after compile");
            }
            if (!s.packageTime()) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses but is not package-time — the transform must"
                        + " finish before anything consumes the replaced classes");
            }
            if (!s.inputs().contains("classes")) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses but not In.classes() — the classes dir is what"
                        + " it transforms");
            }
            if (!s.contributesClasses().isEmpty()
                    || !s.contributesResources().isEmpty()
                    || !s.contributesSources().isEmpty()) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses and contributes* — a transform REPLACES the"
                        + " classes dir; contributions merge, and the two don't compose");
            }
            if (!s.outputs().contains(s.transformsClasses())) {
                throw new IllegalStateException("plugin task " + s.name() + " transformsClasses(\""
                        + s.transformsClasses() + "\") must name a declared output dir");
            }
            transform = s;
        }
        return transform;
    }

    /**
     * The stage a plugin task is scheduled in, from the same predicates {@link
     * #pluginTask} uses to build its {@code requires} — inference must never contradict the
     * edges, or {@link BuildPlan} rejects a plan the planner itself produced.
     */
    static BuildStage pluginWindow(PluginBuild.TaskDecl step) {
        if (beforeCompile(step)) return BuildStage.GENERATE;
        if (step.testOnly()) return BuildStage.TEST;
        return BuildStage.COMPILE;
    }

    /**
     * The latest stage a plugin task may claim. {@code run-tests} (TEST) requires every
     * test-classpath contributor, so those may not sit downstream of TEST.
     */
    private static BuildStage pluginCeiling(PluginBuild.TaskDecl step) {
        boolean requiredByTests =
                step.testOnly() || (step.contributesTestClasspath() != null && !step.contributesTestClasspath().isEmpty());
        return requiredByTests ? BuildStage.TEST : BuildStage.IMAGE;
    }

    /**
     * Product stage for a plugin task. A plugin may declare one to sharpen the UI fold (dex is
     * {@code package}, not {@code compile}), but only within the window its scheduling allows —
     * a contradiction is the plugin's error and says so.
     */
    static BuildStage pluginStage(PluginBuild.TaskDecl step) {
        BuildStage window = pluginWindow(step);
        String declared = step.stage();
        if (declared == null || declared.isBlank()) return window;
        BuildStage stage = BuildStage.fromWireExact(declared)
                .orElseThrow(() -> new IllegalStateException("plugin task " + step.name() + " declares stage `"
                        + declared + "` — expected one of " + BuildStage.wireNames()));
        BuildStage ceiling = pluginCeiling(step);
        if (stage.pipelineOrder() < window.pipelineOrder() || stage.pipelineOrder() > ceiling.pipelineOrder()) {
            throw new IllegalStateException("plugin task " + step.name() + " declares stage `" + stage.wireName()
                    + "` but is scheduled in the " + window.wireName() + " window"
                    + (ceiling == BuildStage.TEST ? " and is required by run-tests" : "")
                    + " — declare a stage between `" + window.wireName() + "` and `" + ceiling.wireName() + "`");
        }
        return stage;
    }

    /**
     * The DAG edges a declared plugin task rides: its own declarations, the window anchors, and
     * the peer/transform outputs its inputs name. Shares the {@link #beforeCompile} /
     * {@link PluginBuild.TaskDecl#testOnly()} split with {@link #pluginWindow} so stage and edges
     * cannot disagree.
     */
    static List<String> pluginRequires(PluginBuild.TaskDecl step, PluginBuild.TaskDecl transform) {
        boolean beforeCompile = beforeCompile(step);
        if (beforeCompile && step.inputs().contains("classes")) {
            throw new IllegalStateException("plugin task " + step.name()
                    + " is source-generating but declares In.classes() — generated-source tasks"
                    + " consume project files (In.projectFiles), config, or other task outputs");
        }
        List<String> requires = new ArrayList<>();
        // Explicit plugin-declared edges first.
        if (step.requires() != null) {
            for (String r : step.requires()) {
                if (r != null && !r.isBlank()) requires.add(r);
            }
        }
        if (beforeCompile) {
            requires.add(TaskNames.PARSE_BUILD);
            requires.add(TaskNames.RESOLVE_DEPS);
            requires.add(TaskNames.ENSURE_JDK);
            // Project build-logic codegen (BEFORE_COMPILE) before plugin source generators.
            requires.add(TaskNames.BUILD_LOGIC_BEFORE_COMPILE);
        } else if (step.testOnly()) {
            requires.add(TaskNames.PARSE_BUILD);
            requires.add(TaskNames.RESOLVE_DEPS);
            requires.add(TaskNames.ENSURE_JDK);
        } else {
            requires.add(TaskNames.COPY_RESOURCES);
        }
        // Peer plugin outputs (In.stepOutput) — declared inputs ARE the dependency graph.
        for (String input : step.inputs()) {
            if (input.startsWith("step:")) requires.add("plugin-" + input.substring("step:".length()));
        }
        // Classes consumers wait on the transform (dex after Hilt rewrite).
        if (transform != null
                && !step.name().equals(transform.name())
                && step.inputs().contains("classes")) {
            requires.add("plugin-" + transform.name());
        }
        return requires;
    }

    /**
     * One declared build-plugin task: engine fingerprints inputs, restores on hit, forks on miss.
     */
    private static Task pluginTask(
            Ctx cx, PluginBuild.Active active, PluginBuild.TaskDecl step, PluginBuild.TaskDecl transform) {
        Inputs in = cx.in();
        boolean beforeCompile = beforeCompile(step);
        List<String> requires = pluginRequires(step, transform);
        return Task.builder("plugin-" + step.name())
                .label(step.name())
                .kind(TaskKind.CPU)
                .stage(pluginStage(step))
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                // A plugin command forks its process and can dominate a build (d8 dex, AOT), yet its
                // static reservation is a token 1 unit — price it from the running metrics once this
                // machine has seen it run (own-project average, else host average).
                .weight(() -> EffortWeights.learnedFixedWeight(in.dir().toString(), "plugin-" + step.name(), 1))
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path javaHome = ctx.require(JAVA_HOME);
                    Path scratch = PluginBuild.taskScratch(layout, step.name());
                    // Before compile no classes exist to scan — the declared main (or null) rides.
                    String startClass =
                            beforeCompile(step) ? project.mainClass() : resolvedMain(project, in.dir(), classes);

                    List<Path> classpath =
                            PluginBuild.productionClasspath(in.dir(), in.cache(), in.lockFile(), project);
                    List<PluginBuild.ProdEntry> prodEntries = step.inputs().contains("runtime-entries")
                            ? PluginBuild.productionEntries(in.dir(), in.cache(), in.lockFile(), project)
                            : List.of();

                    // Manifest-contributed tool artifacts (aapt2, r8, a platform jar) — fetched
                    // into the cache, handed to the body by artifact name, keyed like any input.
                    java.util.Map<String, Path> toolExtras = PluginBuild.fetchStepDependencies(
                            project, in.dir(), cx.cas(), PluginBuild.sdkPins(in.lockFile()));

                    // Action key: exactly the declared inputs, plus the facts the body sees.
                    List<String> tokens = new ArrayList<>();
                    for (String input : step.inputs()) {
                        switch (input) {
                            case "classes" ->
                                tokens.add("classes:" + cc.jumpkick.task.ClasspathFingerprint.entry(classes));
                            case "runtime-classpath" ->
                                tokens.add("cp:" + cc.jumpkick.task.ClasspathFingerprint.of(classpath));
                            case "runtime-entries" -> {
                                tokens.add("cp:" + cc.jumpkick.task.ClasspathFingerprint.of(classpath));
                                for (var pe : prodEntries) {
                                    if (pe.container() != null) {
                                        tokens.add("container:" + pe.fileName() + ":"
                                                + cc.jumpkick.task.ClasspathFingerprint.entry(pe.container()));
                                    }
                                }
                            }
                            case "config" -> tokens.add("config:" + PluginBuild.configToken(active.config()));
                            default -> {
                                if (input.startsWith("step:")) {
                                    Path other = PluginBuild.taskScratch(layout, input.substring("step:".length()));
                                    tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(other));
                                } else if (input.startsWith("project:")) {
                                    Path files = in.dir().resolve(input.substring("project:".length()));
                                    tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(files));
                                }
                            }
                        }
                    }
                    for (var tool : toolExtras.entrySet()) {
                        tokens.add("tool:" + tool.getKey() + ":"
                                + cc.jumpkick.task.ClasspathFingerprint.entry(tool.getValue()));
                    }
                    tokens.add("facts:" + project.project().group() + ":"
                            + project.project().name() + ":"
                            + project.project().version() + ":"
                            + project.project().javaRelease() + ":"
                            + startClass);
                    // The step's CODE is an input: a changed plugin jar must re-run the
                    // step, or a plugin upgrade (or first-party dev iteration) silently restores
                    // outputs produced by the old code.
                    tokens.add("worker:"
                            + cc.jumpkick.task.ClasspathFingerprint.entry(
                                    PluginBuild.workerJarFor(active, in.cache())));
                    String taskId = ActionKey.qualifiedTaskId("plugin-" + step.name(), scratch);
                    String actionKey =
                            ActionKey.forArtifact(taskId, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), tokens);
                    cc.jumpkick.task.ActionCache actionCache = cx.actionCache();
                    var hit = actionCache.lookup(actionKey);
                    if (hit.isPresent()) {
                        try {
                            if (actionCache.restore(hit.get(), scratch)) {
                                if (step.transforms()) {
                                    ctx.put(MAIN_CLASSES, scratch.resolve(step.transformsClasses()));
                                }
                                ctx.label(step.name() + " up-to-date");
                                ctx.progress(1);
                                return;
                            }
                            // false: missing/corrupt blob — fall through to a fresh run.
                        } catch (IOException e) {
                            // A missing CAS blob (pruned cache) falls through to a fresh run.
                        }
                    }

                    cc.jumpkick.util.PathUtil.deleteRecursively(scratch); // stale outputs never survive
                    Files.createDirectories(scratch);
                    ctx.label(step.name());
                    PluginBuild.SpecWriter specWriter = new PluginBuild.SpecWriter()
                            .op("run-step", step.name(), active.manifest().id())
                            .config(active.config())
                            .project(project, startClass)
                            .layout(classes, in.dir(), scratch)
                            .javaHome(javaHome)
                            .classpath(classpath);
                    for (var pe : prodEntries) {
                        specWriter.entry(pe.fileName(), pe.jar(), pe.snapshot(), pe.container());
                    }
                    for (var tool : toolExtras.entrySet()) {
                        specWriter.extra(tool.getKey(), tool.getValue());
                    }
                    for (String input : step.inputs()) {
                        if (input.startsWith("step:")) {
                            String other = input.substring("step:".length());
                            specWriter.stepOutput(other, PluginBuild.taskScratch(layout, other));
                        }
                    }
                    Path spec = specWriter.write();
                    try {
                        PluginBuild.runWorker(active, in.cache(), spec, ctx::label);
                    } catch (IOException e) {
                        ctx.error(step.name(), e.getMessage());
                        throw e;
                    } finally {
                        Files.deleteIfExists(spec);
                    }
                    actionCache.store(taskId, actionKey, java.util.Map.of(), scratch);
                    // A transform's output IS the classes dir from here on: re-point MAIN_CLASSES
                    // so packaging, later steps' In.classes, and the native tail read it
                    // (ordering: consumers carry a requires edge on this step).
                    if (step.transforms()) {
                        ctx.put(MAIN_CLASSES, scratch.resolve(step.transformsClasses()));
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Plugin packager instead of plain jar: engine caches on declared inputs, fetches packager
     * deps, prepares SBOM, and hands coordinate-named runtime entries to the plugin.
     */
    private static void packagePlugin(
            TaskContext ctx,
            Inputs in,
            Cas cas,
            JkBuild project,
            Path classes,
            Path jarPath,
            PluginBuild.Active active,
            PluginBuild.Declarations decls,
            Map<String, String> secrets)
            throws Exception {
        Lockfile lock = ctx.require(LOCKFILE);
        BuildLayout layout = ctx.require(LAYOUT);
        ClasspathResolver resolver = new ClasspathResolver(cas);
        String startClass = resolvedMain(project, in.dir(), classes);

        // Coordinate-named runtime entries: lock artifacts + workspace sibling jars — the SAME
        // set steps see via In.runtimeEntries(). Packaging from the lock alone drops sibling
        // module jars and ships a Boot/assembly artifact that cannot start (JK-1415).
        List<PluginBuild.ProdEntry> entries =
                PluginBuild.productionEntries(in.dir(), in.cache(), in.lockFile(), project);
        List<CycloneDxSbom.Component> sbomComponents = new ArrayList<>();
        for (ClasspathResolver.Entry entry : resolver.entriesFor(lock, ClasspathResolver.RUNTIME)) {
            Lockfile.Artifact a = entry.artifact();
            sbomComponents.add(
                    new CycloneDxSbom.Component(a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex()));
        }
        // Packagers get the packager-dependency artifacts AND the step-dependency tools (the
        // same artifacts commands receive — an AAB packager forks bundletool exactly like a step
        // forks aapt2). A packager-dependency wins a name collision.
        java.util.Map<String, Path> extras = new LinkedHashMap<>(
                PluginBuild.fetchStepDependencies(project, in.dir(), cas, PluginBuild.sdkPins(in.lockFile())));
        extras.putAll(PluginBuild.fetchPackagerDependencies(project, in.dir(), cas));

        // Action key from the declared inputs + facts — any config, classes, dependency-set,
        // step-output, extra-artifact, or manifest change re-packages; nothing else does.
        List<Path> entryJars = new ArrayList<>(entries.size());
        for (PluginBuild.ProdEntry e : entries) {
            if (e.jar() != null) entryJars.add(e.jar());
        }
        List<String> tokens = new ArrayList<>();
        for (String input : decls.packager().inputs()) {
            switch (input) {
                case "classes" -> tokens.add("classes:" + cc.jumpkick.task.ClasspathFingerprint.entry(classes));
                case "runtime-classpath", "runtime-entries" -> {
                    tokens.add("libs:" + cc.jumpkick.task.ClasspathFingerprint.of(entryJars));
                    // Container content (an AAR's res/assets/jni) is packaged input too — an
                    // assets-only AAR bump must re-package even though no classes jar changed.
                    for (PluginBuild.ProdEntry e : entries) {
                        if (e.container() != null) {
                            tokens.add("container:" + e.fileName() + ":"
                                    + cc.jumpkick.task.ClasspathFingerprint.entry(e.container()));
                        }
                    }
                }
                case "config" -> tokens.add("config:" + PluginBuild.configToken(active.config()));
                default -> {
                    if (input.startsWith("step:")) {
                        Path other = PluginBuild.taskScratch(layout, input.substring("step:".length()));
                        tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(other));
                    } else if (input.startsWith("project:")) {
                        Path files = in.dir().resolve(input.substring("project:".length()));
                        tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(files));
                    }
                }
            }
        }
        List<Path> extraJars = new ArrayList<>(extras.values());
        tokens.add("extras:" + cc.jumpkick.task.ClasspathFingerprint.of(extraJars));
        if (!secrets.isEmpty()) {
            // A changed signing credential re-signs (the signature is part of the artifact);
            // the key carries only a digest — a secret value never appears anywhere readable.
            StringBuilder sb = new StringBuilder();
            for (var e : new java.util.TreeMap<>(secrets).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            tokens.add("secrets:"
                    + cc.jumpkick.util.Hashing.sha256Hex(
                            sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        tokens.add("facts:" + project.project().group() + ":"
                + project.project().name() + ":" + project.project().version() + ":" + startClass);
        tokens.add("manifest:" + project.manifest());
        // Packager identity (e.g. shrink vs boot) so CLI packaging overrides cannot cache-collide.
        tokens.add("packaging:" + decls.packager().name());
        // The packager's CODE is an input, same as plugin steps (see pluginTask).
        tokens.add(
                "worker:" + cc.jumpkick.task.ClasspathFingerprint.entry(PluginBuild.workerJarFor(active, in.cache())));
        String pkgTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, jarPath);
        String pkgKey = ActionKey.forArtifact(pkgTask, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), tokens);
        if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
            ctx.put(JAR_PATH, jarPath);
            ctx.label(jarPath.getFileName() + " up-to-date");
            ctx.cached();
            ctx.progress(1);
            return;
        }

        // SBOM (always on): free and deterministic straight from the lockfile.
        byte[] sbom = CycloneDxSbom.write(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                sbomComponents);
        Path sbomFile = Files.createTempFile("jk-plugin-sbom-", ".cdx.json");
        Files.write(sbomFile, sbom);

        PluginBuild.SpecWriter spec = new PluginBuild.SpecWriter()
                .op("package", null, active.manifest().id())
                .config(active.config())
                .project(project, startClass)
                .layout(classes, in.dir(), layout.moduleTargetDir().resolve("plugin"))
                .javaHome(ctx.require(JAVA_HOME))
                .artifact(jarPath);
        for (PluginBuild.ProdEntry e : entries) spec.entry(e.fileName(), e.jar(), e.snapshot(), e.container());
        for (var e : extras.entrySet()) spec.extra(e.getKey(), e.getValue());
        for (var e : secrets.entrySet()) spec.secret(e.getKey(), e.getValue());
        spec.extra("sbom", sbomFile);
        for (PluginBuild.TaskDecl step : decls.steps()) {
            Path scratch = PluginBuild.taskScratch(layout, step.name());
            if (Files.isDirectory(scratch)) spec.stepOutput(step.name(), scratch);
        }
        Path specFile = spec.write();
        // A stale conventional sibling from an earlier run must never survive a re-package.
        String staleName = jarPath.getFileName().toString();
        int staleDot = staleName.lastIndexOf('.');
        if (staleDot > 0 && !staleName.endsWith(".jar")) {
            Files.deleteIfExists(jarPath.resolveSibling(staleName.substring(0, staleDot) + ".jar"));
        }
        List<String> workerLines;
        try {
            workerLines = PluginBuild.runWorker(active, in.cache(), specFile, ctx::label);
        } catch (IOException e) {
            ctx.error("package", e.getMessage());
            throw e;
        } finally {
            Files.deleteIfExists(specFile);
            Files.deleteIfExists(sbomFile);
        }
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException(
                    "plugin packager " + decls.packager().name() + " reported success but produced no " + jarPath);
        }
        // A container packager (an AAR) may also emit the conventional classes jar next to the
        // main artifact — the host-classpath view workspace siblings compile against. Both cache
        // under the same key so a hit restores the pair.
        List<Path> produced = new ArrayList<>();
        produced.add(jarPath);
        String artifactName = jarPath.getFileName().toString();
        int dot = artifactName.lastIndexOf('.');
        if (dot > 0 && !artifactName.endsWith(".jar")) {
            Path conventional = jarPath.resolveSibling(artifactName.substring(0, dot) + ".jar");
            if (Files.isRegularFile(conventional)) produced.add(conventional);
        }
        // Packager-declared extras (PackageIo.produced — quarkus fast-jar lib/ siblings): a
        // multi-file layout must cache whole or a hit after `jk clean` restores a broken
        // artifact. Directories expand recursively; escapes of the artifact dir
        // are a packager bug.
        Path outBase = jarPath.getParent().toAbsolutePath().normalize();
        for (String line : workerLines) {
            if (!"produced".equals(cc.jumpkick.plugin.protocol.Jsonl.str(line, "t"))) continue;
            Path p = Path.of(String.valueOf(cc.jumpkick.plugin.protocol.Jsonl.str(line, "path")))
                    .toAbsolutePath()
                    .normalize();
            if (!p.startsWith(outBase)) {
                throw new IOException("packager declared produced path outside the artifact dir: " + p);
            }
            if (Files.isRegularFile(p)) {
                produced.add(p);
            } else if (Files.isDirectory(p)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile).forEach(produced::add);
                }
            }
        }
        storePackaged(in.cache(), pkgTask, pkgKey, tokens, jarPath.getParent(), produced, !in.ephemeralActions());
        ctx.put(JAR_PATH, jarPath);
        ctx.progress(1);
    }

    /** The resolved application entry point: declared, else the unique compiled main (when scannable). */
    private static String resolvedMain(JkBuild project, Path moduleDir, Path classes) throws IOException {
        String main = project.mainClass();
        if ((main == null || main.isBlank())
                && PluginBuild.shape(project, moduleDir)
                        .map(sh -> sh.mainScan())
                        .orElse(false)) {
            main = cc.jumpkick.layout.MainClassScanner.scanUnique(classes);
        }
        return main;
    }

    /**
     * The application SBOM entry + manifest headers shared by every packager: the lockfile's
     * production RUNTIME components as CycloneDX (see {@link CycloneDxSbom}). Returns null when
     * there is no lockfile to speak from.
     */
    static byte[] applicationSbom(JkBuild project, Lockfile lock, Cas cas) {
        List<CycloneDxSbom.Component> components = new ArrayList<>();
        for (ClasspathResolver.Entry entry : new ClasspathResolver(cas).entriesFor(lock, ClasspathResolver.RUNTIME)) {
            Lockfile.Artifact a = entry.artifact();
            components.add(
                    new CycloneDxSbom.Component(a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex()));
        }
        return CycloneDxSbom.write(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                components);
    }

    /** SBOM path inside plain/assembly application jars (jar root = classpath root). */
    static final String SBOM_JAR_ENTRY = "META-INF/sbom/application.cdx.json";

    private static Task writeStampStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.WRITE_STAMP)
                .stage(BuildStage.COMPILE)
                .requires(TaskNames.COMPILE_JAVA)
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
                .ticks(1)
                .execute(ctx -> {
                    String outcome = ctx.get(BUILD_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path javaOut = classes; // javac always writes to java/main/
                    @SuppressWarnings("unchecked")
                    List<Path> sources = (List<Path>) ctx.require(JAVA_SOURCES);
                    @SuppressWarnings("unchecked")
                    List<Path> baseClasspath = (List<Path>) ctx.require(CLASSPATH);
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp =
                            (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                    // Match compile-java's freshness inputs exactly — the shared recipe includes
                    // the processor path the old copy dropped, which kept processor modules from
                    // ever stamp-matching.
                    List<Path> stampInputs = mainStampClasspath(
                            baseClasspath,
                            processorCp,
                            mixed,
                            cx.mixedGroovy(),
                            ctx.require(LAYOUT),
                            cx.mixedGroovy() ? groovyCompileJar(ctx, cx.cas()) : null);
                    String actionKey = ctx.get(ACTION_KEY).orElse("");
                    cc.jumpkick.task.FreshnessStamp.write(
                            javaOut,
                            cc.jumpkick.task.FreshnessStamp.JAVA_STAMP,
                            "compile-main",
                            actionKey,
                            sources,
                            stampInputs,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    private static Task writeStampKotlinStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.WRITE_STAMP_KOTLIN)
                .stage(BuildStage.COMPILE)
                .requires(TaskNames.COMPILE_KOTLIN)
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
                .ticks(1)
                .execute(ctx -> {
                    String outcome = ctx.get(KOTLIN_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> freshInputs = new ArrayList<>(kotlinSources(ctx));
                    if (mixedWithJava) freshInputs.addAll(javaSources(ctx));
                    cc.jumpkick.task.FreshnessStamp.write(
                            classes,
                            cc.jumpkick.task.FreshnessStamp.KOTLIN_STAMP,
                            TaskNames.COMPILE_KOTLIN,
                            "",
                            freshInputs,
                            classpath,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    private static Task writeStampGroovyStep(Ctx cx) {
        Inputs in = cx.in();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        boolean mixedGroovy = cx.mixedGroovy();
        return Task.builder(TaskNames.WRITE_STAMP_GROOVY)
                .stage(BuildStage.COMPILE)
                .requires(TaskNames.COMPILE_GROOVY)
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
                .ticks(1)
                .execute(ctx -> {
                    String outcome = ctx.get(GROOVY_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> freshInputs = new ArrayList<>(groovySources(ctx));
                    if (mixedGroovy) freshInputs.addAll(javaSources(ctx));
                    cc.jumpkick.task.FreshnessStamp.write(
                            classes,
                            cc.jumpkick.task.FreshnessStamp.GROOVY_STAMP,
                            TaskNames.COMPILE_GROOVY,
                            "",
                            freshInputs,
                            classpath,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    private static Task assembleClassesStep(Ctx cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        boolean mixedGroovy = cx.mixedGroovy();
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.COMPILE_JAVA);
        if (mixed) requires.add(TaskNames.COMPILE_KOTLIN);
        if (mixedGroovy) requires.add(TaskNames.COMPILE_GROOVY);
        return Task.builder(TaskNames.ASSEMBLE_CLASSES)
                .stage(BuildStage.COMPILE)
                .label("Assembling")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .weight(() -> plan.get().fullyCached() ? 0 : W_ASSEMBLE)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    String jOutcome = ctx.get(BUILD_OUTCOME).orElse("");
                    String kOutcome = ctx.get(KOTLIN_OUTCOME).orElse("");
                    String gOutcome = ctx.get(GROOVY_OUTCOME).orElse("");
                    boolean settled = settledOutcome(jOutcome)
                            && (!mixed || settledOutcome(kOutcome))
                            && (!mixedGroovy || settledOutcome(gOutcome));
                    if (settled) { // all unchanged → classes already holds every language's output
                        ctx.label("up to date");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("assemble classes");
                    Files.createDirectories(classes);
                    // Java output already lives in classes (java/main/); merge the other
                    // language dirs in. The Groovy merge runs after javac, so real Groovy
                    // classes overwrite any stub-compiled duplicates.
                    if (mixed) copyResources(ctx.require(LAYOUT).kotlinClassesDir(), classes);
                    if (mixedGroovy) copyResources(ctx.require(LAYOUT).groovyClassesDir(), classes);
                    ctx.progress(1);
                })
                .build();
    }

    private static boolean settledOutcome(String outcome) {
        return outcome.equals("up-to-date") || outcome.equals("no-sources");
    }

    /**
     * Append the artifact tails the project's {@code jk.toml} declares after {@link #coreBuilder}:
     *
     * <ul>
     *   <li>{@code [application] assembly = true} → fat-jar step
     *   <li>{@code [native] always = true} → Graal native-image step (opt-in product of {@code jk
     *       build} / {@code jk run} / install — same lever as {@code jk native})
     *   <li>{@code sources = "always"} → sources-jar step
     * </ul>
     *
     * <p>{@code graalHome} is the GraalVM the client resolved (install / {@code jk native}); pass
     * {@code null} for plain {@code jk build} and the step falls back to {@code GRAALVM_HOME} / the
     * project JDK. {@code allowNative=false} skips the native tail for workspace prereq modules
     * that are not themselves selected for native (JK-1361).
     */
    public static void appendDeclaredTails(BuildPlan.Builder b, Inputs in) {
        appendDeclaredTails(b, in, null, true);
    }

    /** As {@link #appendDeclaredTails(BuildPlan.Builder, Inputs)} with an explicit Graal home. */
    public static void appendDeclaredTails(BuildPlan.Builder b, Inputs in, Path graalHome) {
        appendDeclaredTails(b, in, graalHome, true);
    }

    /**
     * As {@link #appendDeclaredTails(BuildPlan.Builder, Inputs, Path)} with {@code allowNative} for
     * workspace prereq modules that must stay JVM-only.
     *
     * <p>{@link BuildPlan.Builder#terminal} keeps only the named task and its <em>upstream</em>
     * requires-closure. Core ends at {@code package-jar}; assembly / native / sources-jar are
     * <em>downstream</em> of that terminal, so they must re-root the terminal (via a synthetic
     * join when more than one tail is present) or {@link BuildPlan.Builder#build()} prunes them
     * and fat jars / native images never run on {@code jk build}.
     */
    public static void appendDeclaredTails(BuildPlan.Builder b, Inputs in, Path graalHome, boolean allowNative) {
        // Test and compile plans stop at run-tests / write-stamp: package-jar is not in the
        // plan for tails to hang off, and re-rooting the terminal would run packaging (or
        // fail validation) under `jk test` / `jk compile`.
        if (in.testOnly() || in.compileOnly()) return;
        try {
            JkBuild project = applyAssemblyOverride(JkBuildParser.parse(in.buildFile()), in.session());
            List<String> leaves = new ArrayList<>();
            if (project.assembly()) {
                b.addTask(assemblyStep(in.cache(), in.lockFile(), !in.ephemeralActions()));
                leaves.add(TaskNames.PACKAGE_ASSEMBLY);
            }
            if (allowNative && project.nativeMode() == JkBuild.NativeMode.ALWAYS) {
                b.addTask(nativeStep(
                        in.dir(),
                        in.cache(),
                        in.lockFile(),
                        in.jdksDir(),
                        graalHome,
                        null,
                        List.of()));
                leaves.add(TaskNames.NATIVE_IMAGE);
            }
            if (project.project().sourcesMode() == JkBuild.SourcesMode.ALWAYS) {
                b.addTask(sourcesStep(in.cache(), !in.ephemeralActions()));
                leaves.add(TaskNames.PACKAGE_SOURCES);
            }
            if (leaves.isEmpty()) return;
            if (leaves.size() == 1) {
                b.terminal(leaves.get(0));
                return;
            }
            // Multiple independent tails of package-jar — join them so prune keeps every branch.
            b.addTask(Task.builder(DELIVER_JOIN)
                    .stage(BuildStage.PACKAGE)
                    .requires(leaves.toArray(String[]::new))
                    .weight(0)
                    .ticks(0)
                    .execute(ctx -> {
                        /* join only */
                    })
                    .build());
            b.terminal(DELIVER_JOIN);
        } catch (Exception ignored) {
        }
    }

    /**
     * Synthetic join for multiple declared tails (assembly + native + sources). Not a real work
     * step — only exists so {@link BuildPlan.Builder#terminal} can keep every leaf.
     */
    static final String DELIVER_JOIN = "deliver";

    /** Zero-work join terminal for mixed-language {@code jk compile}: keeps every language's stamp (and the assembler) in the pruned closure. */
    static final String COMPILE_JOIN = "compile-join";

    /**
     * Apply {@link cc.jumpkick.config.Session#assemblyOverride} (CLI {@code --fat}/{@code --shrink})
     * over the parsed manifest for this invocation only. Prefer the request {@link Inputs#session}
     * over ambient {@link SessionContext} so single-build plan construction (outside {@code
     * SessionContext.where}) still sees the wire override.
     */
    static JkBuild applyAssemblyOverride(JkBuild build, cc.jumpkick.config.Session session) {
        String raw = session != null ? session.assemblyOverride() : "";
        if (raw == null || raw.isBlank()) {
            raw = SessionContext.current().assemblyOverride();
        }
        if (raw == null || raw.isBlank()) return build;
        JkBuild.AssemblyMode mode = JkBuildParser.parseAssemblyOverride(raw);
        if (mode == null) return build;
        return JkBuildParser.withAssemblyModeOverride(build, mode);
    }

    // ---- tail steps ----------------------------------------------------

    /** Assembly-jar packaging — requires package-jar. */
    public static Task assemblyStep(Path cache, Path lockFile) {
        return assemblyStep(cache, lockFile, true);
    }

    /** As {@link #assemblyStep(Path, Path)}; {@code persist=false} keeps verify-scratch keys out of the cache. */
    public static Task assemblyStep(Path cache, Path lockFile, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_ASSEMBLY)
                .stage(BuildStage.PACKAGE)
                .label("Assembly")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.assemblyWeight(lockFile.getParent()))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path assemblyJar = layout.assemblyJar();
                    // Module-scoped runtime closure (not the whole workspace lock) — JK-1345.
                    List<Path> depJars = assemblyDependencyJars(layout.moduleRoot(), project, lockFile, cache);
                    // Packaging cache: the fat jar is a pure function of the main
                    // classes, the bundled dependency jars' content, the main-class,
                    // and the manifest.
                    List<String> tokens = List.of(
                            "classes:" + cc.jumpkick.task.ClasspathFingerprint.entry(classes),
                            "deps:" + cc.jumpkick.task.ClasspathFingerprint.of(depJars),
                            "main:" + (project.mainClass() == null ? "" : project.mainClass()),
                            "manifest:" + project.manifest(),
                            "packaging:fat"); // distinct from shrink / thin package-jar
                    String shTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_ASSEMBLY, assemblyJar);
                    String shKey =
                            ActionKey.forArtifact(shTask, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), tokens);
                    if (restorePackaged(cache, shKey, assemblyJar.getParent())) {
                        ctx.label(assemblyJar.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + assemblyJar.getFileName());
                    byte[] assemblySbom = null;
                    Map<String, String> assemblyAttrs = new LinkedHashMap<>(project.manifest());
                    if (Files.exists(lockFile)) {
                        assemblySbom = applicationSbom(project, LockfileReader.read(lockFile), JkStores.cas(cache));
                        assemblyAttrs.put("Sbom-Format", "CycloneDX");
                        assemblyAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                    }
                    new AssemblyPackager()
                            .packageAssembly(new AssemblyPackager.AssemblyRequest(
                                    classes,
                                    depJars,
                                    assemblyJar,
                                    project.mainClass(),
                                    assemblyAttrs,
                                    assemblySbom == null ? Map.of() : Map.of(SBOM_JAR_ENTRY, assemblySbom),
                                    0L));
                    storePackaged(cache, shTask, shKey, tokens, assemblyJar.getParent(), List.of(assemblyJar), persist);
                    ctx.progress(1);
                })
                .build();
    }

    /** Sources-jar packaging — writes {@code <artifact>-<version>-sources.jar} to the artifact dir. */
    public static Task sourcesStep(Path cache) {
        return sourcesStep(cache, true);
    }

    /** As {@link #sourcesStep(Path)}; {@code persist=false} keeps verify-scratch keys out of the cache. */
    public static Task sourcesStep(Path cache, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_SOURCES)
                .stage(BuildStage.PACKAGE)
                .label("Sources")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(W_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path moduleRoot = layout.moduleRoot();
                    Path sourcesJar = layout.sourcesJar();
                    // Source roots: simple layout uses src/, traditional uses src/main/java + src/main/kotlin.
                    boolean compact = CompileSupport.isSimpleLayout(project.project(), moduleRoot);
                    List<Path> sourceRoots = compact
                            ? List.of(moduleRoot.resolve("src"))
                            : List.of(moduleRoot.resolve("src/main/java"), moduleRoot.resolve("src/main/kotlin"));
                    // Cache key: hash of all source roots' content.
                    String srcHash = String.join(
                            ";",
                            sourceRoots.stream()
                                    .map(r -> {
                                        try {
                                            return cc.jumpkick.task.ClasspathFingerprint.entry(r);
                                        } catch (Exception e) {
                                            return "";
                                        }
                                    })
                                    .toList());
                    List<String> tokens = List.of("sources:" + srcHash);
                    String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_SOURCES, sourcesJar);
                    String key = ActionKey.forArtifact(task, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), tokens);
                    if (restorePackaged(cache, key, sourcesJar.getParent())) {
                        ctx.label(sourcesJar.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + sourcesJar.getFileName());
                    byte[] bytes = cc.jumpkick.cache.SourcesJar.build(sourceRoots);
                    Files.createDirectories(sourcesJar.getParent());
                    Files.write(sourcesJar, bytes);
                    storePackaged(cache, task, key, tokens, sourcesJar.getParent(), List.of(sourcesJar), persist);
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * GraalVM native-image step — what {@code jk native} composes onto the core build (and {@code jk
     * install} of a native application adds explicitly). Builds an <em>executable</em> when a main
     * class is resolvable ({@code mainOverride} > {@code [native].main-class} > {@code
     * [project].main}), otherwise a <em>shared library</em> ({@code native-image --shared}). Run
     * directly as a subprocess; requires package-jar.
     *
     * <p>{@code graalHome} is the GraalVM the CLI's {@code GraalResolver} selected (its {@code
     * bin/native-image} is used); when {@code null} the step falls back to the project JDK / {@code
     * $GRAALVM_HOME} / {@code PATH} search.
     */
    public static Task nativeStep(
            Path dir,
            Path cache,
            Path lockFile,
            Path jdksDir,
            Path graalHome,
            String mainOverride,
            List<String> extraArgs) {
        // Install / native plans never run under verify's ephemeral scratch — persist.
        final boolean persist = true;
        List<String> extra = extraArgs == null ? List.of() : extraArgs;
        return Task.builder(TaskNames.NATIVE_IMAGE)
                .stage(BuildStage.PACKAGE)
                .label("Native")
                .kind(TaskKind.IO)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.nativeWeight(dir))
                .ticks(10) // preamble(1) + 8 native-image stages + done(1)
                .execute(ctx -> {
                    // Fail-fast: verify native-image is available before compilation
                    // has already run and the user has waited for potentially minutes.
                    // Resolution: explicit graalHome (client) → $GRAALVM_HOME → project JDK →
                    // running JVM. [native] always = true on jk build takes this path with
                    // graalHome=null and relies on env / project JDK having native-image.
                    Path javaHomeEarly = resolveNativeImageHome(graalHome, dir, jdksDir);
                    if (cc.jumpkick.tool.NativeImageDriver.resolve(javaHomeEarly)
                            .isEmpty()) {
                        ctx.error(
                                "native",
                                cc.jumpkick.tool.NativeImageDriver.notFoundError(javaHomeEarly)
                                        .getMessage());
                        throw new RuntimeException("native-image not found");
                    }

                    JkBuild project = ctx.require(PROJECT);
                    JkBuild.NativeConfig nativeCfg = project.nativeConfig()
                            .orElseGet(() -> new JkBuild.NativeConfig(null, null, List.of(), null, false));
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path mainJar = layout.mainJar();
                    if (!Files.exists(mainJar)) {
                        ctx.error("native", "jar not found at " + mainJar);
                        throw new RuntimeException("missing main jar for native-image");
                    }
                    // Resolution order: --main CLI flag > [native].main-class > [application].main.
                    // A resolvable main → executable; none → shared library (--shared).
                    String mainClass = (mainOverride != null && !mainOverride.isBlank())
                            ? mainOverride
                            : (nativeCfg.mainClass() != null ? nativeCfg.mainClass() : project.mainClass());
                    if ((mainClass == null || mainClass.isBlank())
                            && PluginBuild.shape(project, dir)
                                    .map(sh -> sh.mainScan())
                                    .orElse(false)) {
                        // main-scan packagers carry exactly one main — same scan packaging used.
                        mainClass = cc.jumpkick.layout.MainClassScanner.scanUnique(layout.classesDir());
                    }
                    boolean shared = (mainClass == null || mainClass.isBlank());
                    if (shared) mainClass = null;
                    // Output path: [native].name overrides the artifact-derived name.
                    // Executable → target/<name>; library → target/lib<name> (native-image
                    // appends the platform extension.so/.dylib/.dll and emits C headers).
                    Path out;
                    if (nativeCfg.name() != null) {
                        String nm = nativeCfg.name();
                        out = layout.moduleTargetDir().resolve(shared && !nm.startsWith("lib") ? "lib" + nm : nm);
                    } else {
                        out = shared ? layout.nativeLibrary() : layout.nativeBinary();
                    }
                    Files.createDirectories(out.getParent());
                    // Args: [native].args (project-level) + extra (CLI --) in that order
                    List<String> allArgs = new ArrayList<>(nativeCfg.args());
                    allArgs.addAll(extra);

                    Path javaHome = javaHomeEarly; // resolved above in fail-fast check

                    List<Path> classpath = new ArrayList<>();
                    if (PluginBuild.shape(project, dir)
                            .map(sh -> sh.classesRun())
                            .orElse(false)) {
                        // A classes-run packager's jar is not classpath-able (e.g. Boot's
                        // BOOT-INF nesting) — native-image gets the exploded classes plus
                        // whatever the plugin's steps contributed (generated classes +
                        // META-INF/native-image hints), produced just before this step.
                        classpath.add(layout.classesDir());
                        var activeOpt = PluginBuild.activeCodePlugin(project, dir);
                        if (activeOpt.isPresent()) {
                            var decls = PluginBuild.declarations(
                                    activeOpt.get(), project, dir, cache, layout.moduleTargetDir());
                            for (Path contributed : PluginBuild.contributedDirs(decls, layout)) {
                                if (Files.isDirectory(contributed)) classpath.add(contributed);
                            }
                        }
                    } else {
                        classpath.add(mainJar);
                    }
                    // Module-scoped runtime closure + workspace sibling jars (JK-1345).
                    for (Path p : assemblyDependencyJars(dir, project, lockFile, cache)) {
                        if (!classpath.contains(p)) classpath.add(p);
                    }

                    // Reachability metadata (general, not Boot-specific): third-party libs
                    // publish native-image config to the GraalVM metadata repository rather
                    // than their own jars. Matched dirs ride -H:ConfigurationFileDirectories;
                    // unavailable (offline) degrades to building without it.
                    List<Path> metadataDirs = List.of();
                    if (Files.exists(lockFile)) {
                        Lockfile metaLock = LockfileReader.read(lockFile);
                        List<Lockfile.Artifact> runtimeArtifacts = new ArrayList<>();
                        for (Lockfile.Artifact a : metaLock.artifacts()) {
                            if (a.inAnyScope(ClasspathResolver.RUNTIME) && a.checksum() != null) {
                                runtimeArtifacts.add(a);
                            }
                        }
                        cc.jumpkick.repo.RepoGroup metaRepos =
                                RepoGroupBuilder.buildFor(project, null, JkStores.cas(cache));
                        metadataDirs = ReachabilityMetadata.configDirs(
                                cache, metaRepos, runtimeArtifacts, msg -> ctx.label(msg));
                    }
                    if (!metadataDirs.isEmpty()) {
                        StringBuilder dirsArg = new StringBuilder();
                        for (Path d : metadataDirs) {
                            if (dirsArg.length() > 0) dirsArg.append(',');
                            dirsArg.append(d.toAbsolutePath());
                        }
                        // Prepended (before [native].args + CLI extras) so user flags win;
                        // the unlock pair scopes the experimental option to just this flag.
                        List<String> withMeta = new ArrayList<>();
                        withMeta.add("-H:+UnlockExperimentalVMOptions");
                        withMeta.add("-H:ConfigurationFileDirectories=" + dirsArg);
                        withMeta.add("-H:-UnlockExperimentalVMOptions");
                        withMeta.addAll(allArgs);
                        allArgs = withMeta;
                    }

                    // Packaging cache (executable only): the binary is a pure function of
                    // the runtime classpath, the build args, the main class, and the GraalVM
                    // toolchain. Shared libraries (+ generated C headers) aren't cached yet.
                    Path releaseFile = javaHome.resolve("release");
                    String graalTok = Files.isRegularFile(releaseFile)
                            ? cc.jumpkick.util.Hashing.sha256Hex(releaseFile)
                            : javaHome.toString();
                    List<String> nativeTokens = List.of(
                            "cp:" + cc.jumpkick.task.ClasspathFingerprint.of(classpath),
                            "args:" + String.join(" ", allArgs),
                            "main:" + (mainClass == null ? "" : mainClass),
                            "shared:" + shared,
                            "out:" + out.getFileName(),
                            "graal:" + graalTok);
                    String nTask = ActionKey.qualifiedTaskId(TaskNames.NATIVE_IMAGE, out);
                    String nKey = ActionKey.forArtifact(
                            nTask, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), nativeTokens);
                    if (!shared && restorePackaged(cache, nKey, out.getParent())) {
                        ctx.label(out.getFileName() + " up-to-date");
                        ctx.progress(1);
                        return;
                    }

                    ctx.label("native-image " + out.getFileName());

                    // Progress listener: parse [N/M] headers from native-image stdout.
                    // ticks(10) is declared upfront (preamble + 8 GraalVM stages + done).
                    // Ticks: 1 preamble (when step 1 first appears) +
                    // 8 steps ([1/8]…[8/8]) +
                    // 1 final (ctx.progress after run returns) = 10.
                    // Fallback: if no [N/M] headers appear (older GraalVM, --quiet),
                    // the listener never fires and the single ctx.progress(1) at the end
                    // is the only tick — the bar jumps to 1/10, which is acceptable.
                    java.util.concurrent.atomic.AtomicBoolean preambleDone =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    cc.jumpkick.tool.NativeImageDriver.ProgressListener listener = (current, total, label) -> {
                        if (preambleDone.compareAndSet(false, true)) {
                            ctx.progress(1); // preamble done (output before [1/N])
                        }
                        ctx.label("[" + current + "/" + total + "] " + label);
                        ctx.progress(1); // stage N started = stage N-1 done
                    };

                    int exit = cc.jumpkick.tool.NativeImageDriver.run(
                            new cc.jumpkick.tool.NativeImageDriver.Request(
                                    javaHome, classpath, mainClass, out, allArgs, shared),
                            listener,
                            ctx::output);
                    if (exit != 0) {
                        ctx.error("native", "native-image exited " + exit);
                        throw new RuntimeException("native-image failed (exit " + exit + ")");
                    }
                    // Final tick: completes the last native-image step (or the only tick
                    // when no progress headers were emitted).
                    ctx.progress(1);
                    if (!shared) {
                        storePackaged(cache, nTask, nKey, nativeTokens, out.getParent(), List.of(out), persist);
                    }
                })
                .build();
    }

    // ---- helpers --------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Path> javaSources(TaskContext ctx) {
        return (List<Path>) ctx.get(JAVA_SOURCES).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Path> kotlinSources(TaskContext ctx) {
        return (List<Path>) ctx.get(KOTLIN_SOURCES).orElse(List.of());
    }

    /**
     * GraalVM / JDK home that has {@code bin/native-image}: client-resolved home first, then
     * {@code $GRAALVM_HOME}, then the project JDK, then the running JVM.
     */
    static Path resolveNativeImageHome(Path graalHome, Path projectDir, Path jdksDir) {
        if (graalHome != null
                && cc.jumpkick.tool.NativeImageDriver.resolve(graalHome).isPresent()) {
            return graalHome;
        }
        String env = System.getenv("GRAALVM_HOME");
        if (env != null && !env.isBlank()) {
            Path fromEnv = Path.of(env);
            if (cc.jumpkick.tool.NativeImageDriver.resolve(fromEnv).isPresent()) return fromEnv;
        }
        try {
            return cc.jumpkick.jdk.JdkResolver.forProject(projectDir, jdksDir)
                    .map(cc.jumpkick.jdk.InstalledJdk::home)
                    .orElseGet(JavaHomes::runningJavaHome);
        } catch (IOException e) {
            return JavaHomes.runningJavaHome();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Path> groovySources(TaskContext ctx) {
        return (List<Path>) ctx.get(GROOVY_SOURCES).orElse(List.of());
    }

    /**
     * The resolved paths of {@code [[contribute.provided-classpath]]} entries — declared
     * step-dependency artifacts (an SDK platform jar) that join the COMPILE classpaths only.
     */
    private static List<Path> contributedProvidedClasspath(
            cc.jumpkick.model.JkBuild project, Inputs in, cc.jumpkick.cache.Cas cas) {
        List<String> names = cc.jumpkick.plugin.manifest.PluginContributions.providedClasspath(project, in.dir());
        if (names.isEmpty()) return List.of();
        try {
            java.util.Map<String, Path> fetched =
                    PluginBuild.fetchStepDependencies(project, in.dir(), cas, PluginBuild.sdkPins(in.lockFile()));
            List<Path> out = new ArrayList<>();
            for (String name : names) {
                Path path = fetched.get(name);
                if (path == null) {
                    throw new RuntimeException("[[contribute.provided-classpath]] names `" + name
                            + "` but no step-dependency resolved under that artifact name");
                }
                out.add(path);
            }
            return out;
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("cannot resolve the plugin-contributed compile classpath: " + e.getMessage(), e);
        }
    }

    /**
     * The {@code -processorpath} / KSP processor classpath: the lock's PROCESSOR scope plus any
     * workspace siblings declared in {@code [processor-dependencies]} and their own external
     * closures.
     *
     * <p>A processor runs as a program, so it needs its own dependencies (a KSP processor needs
     * {@code symbol-processing-api}, an emitter library, …) — hence the sibling-lockfile loop,
     * mirroring {@link #mainCompileClasspath}. Sibling jars come from the declared closure rather
     * than the built set so {@code jk explain} reproduces the same action key after a clean.
     */
    public static List<Path> processorClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings) throws IOException {
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, Set.of(Scope.PROCESSOR)));
        for (Path jar : siblings.siblingClosureJars()) {
            if (!cp.contains(jar)) cp.add(jar);
        }
        for (Path sibLock : siblings.siblingLockfiles()) {
            try {
                Lockfile sl = LockfileReader.read(sibLock);
                for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN)) {
                    if (!cp.contains(p)) cp.add(p);
                }
            } catch (Exception ignored) {
                /* best-effort: a sibling's lock may be absent */
            }
        }
        return cp;
    }

    /**
     * Declared {@code [processor-dependencies]} entries that resolve to nothing — not a workspace
     * sibling and absent from the lock. Silently skipping code generation is the worst failure mode
     * for an annotation-driven project, so callers turn this into a build error.
     *
     * <p>After {@link cc.jumpkick.model.WorkspaceMerge#resolveSiblingCoordinates}, workspace
     * processors are real {@code group:name} coords (so {@link
     * cc.jumpkick.model.Dependency#isWorkspace()} is false) and still never appear in the lock —
     * pass the {@link WorkspaceClasspath} result so rewritten siblings stay covered.
     */
    public static List<String> unresolvedProcessorDeps(JkBuild project, Lockfile lock) {
        return unresolvedProcessorDeps(project, lock, null);
    }

    public static List<String> unresolvedProcessorDeps(
            JkBuild project, Lockfile lock, WorkspaceClasspath.Result processorSiblings) {
        java.util.Set<String> locked = lockModules(lock);
        java.util.Set<String> siblings = new java.util.HashSet<>();
        if (processorSiblings != null) {
            siblings.addAll(processorSiblings.siblingCoords());
        }
        List<String> missing = new ArrayList<>();
        for (cc.jumpkick.model.Dependency dep : project.dependencies().of(Scope.PROCESSOR)) {
            if (dep.isWorkspace()) continue; // covered by the missing-sibling guard
            if (siblings.contains(dep.module())) continue;
            if (!locked.contains(dep.module())) missing.add(dep.module());
        }
        return missing;
    }

    public static List<Path> mainCompileClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings) throws IOException {
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN));
        // The declared closure (deterministic jar paths) — not just the built ones
        // so the action key is stable whether or not target/ is currently populated.
        // In a valid build the siblings are all built (the missing-sibling check
        // upstream guarantees it), so these are the same paths javac compiles against;
        // after `jk clean` they still let `jk explain` reproduce the build's key.
        cp.addAll(siblings.siblingClosureJars());
        for (Path sibLock : siblings.siblingLockfiles()) {
            try {
                Lockfile sl = LockfileReader.read(sibLock);
                for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN)) {
                    if (!cp.contains(p)) cp.add(p);
                }
            } catch (Exception ignored) {
                /* best-effort: a sibling's lock may be absent */
            }
        }
        return cp;
    }

    /**
     * Jars to embed in an assembly / native-image classpath for one module — delegates to {@link
     * ModuleRuntimeClasspath} (JK-1345 / JK-1347).
     */
    static List<Path> assemblyDependencyJars(Path moduleDir, JkBuild project, Path lockFile, Path cache)
            throws IOException {
        return ModuleRuntimeClasspath.jars(moduleDir, project, lockFile, JkStores.cas(cache));
    }

    /**
     * Compile Kotlin {@code sources} into {@code outputDir} via the plugin (action-cached: restores
     * from the CAS on an exact-input hit without launching the plugin, else compiles incrementally).
     * Shared by the main {@code compile-kotlin} and {@code compile-test} steps. The caller owns
     * freshness stamps, output assembly, and outcome reporting.
     *
     * @param javaSourceRoots when non-empty, passed as {@code -Xjava-source-roots} so a mixed module's
     * Kotlin can read Java declarations from source
     */
    private static cc.jumpkick.task.KotlinCompile.Result compileKotlinSources(
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
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        KotlinPluginSetup.Prepared kt;
        // The installed plugins' [[contribute.kotlin-plugin]] entries (e.g. spring-boot's
        // all-open, and no-arg gated on jakarta.persistence via classpath-has) — evaluated
        // from the manifest, fetched version-locked to the compiler actually used. The
        // embeddable variants match the BTA plugin's embeddable compiler.
        java.util.Set<String> lockModules = lockModules(ctx.require(LOCKFILE));
        List<KotlincRequest.Plugin> ktPlugins = new ArrayList<>();
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            kt = KotlinPluginSetup.prepare(repos, cas, kotlinVersion);
            // Same null-defaulting as KotlinPluginSetup.prepare — a contributed plugin must
            // match the compiler actually used.
            String pluginVersion = (kotlinVersion == null || kotlinVersion.isBlank())
                    ? cc.jumpkick.kotlin.KotlinResolver.DEFAULT_VERSION
                    : kotlinVersion;
            for (var use : cc.jumpkick.plugin.manifest.PluginContributions.kotlinPlugins(
                    ctx.require(PROJECT), workingDir, pluginVersion, lockModules)) {
                Path jar = repos.tryFetchArtifact(
                                cc.jumpkick.model.Coordinate.of(use.group(), use.artifact(), use.version()))
                        .map(hit -> hit.fetched().cachePath())
                        .orElseThrow(() -> new RuntimeException("cannot fetch the " + use.id()
                                + " Kotlin compiler plugin (" + use.group() + ":" + use.artifact() + ":"
                                + use.version() + ") — a plugin contribution requires it"));
                ktPlugins.add(new KotlincRequest.Plugin(use.id(), jar, use.options()));
            }
            // Project-declared [[kotlin-plugins]] (serialization et al.) ride the same lane;
            // an omitted coordinate version means "match the compiler" — the org.jetbrains.kotlin
            // plugin convention, and the only version that can load into this kotlinc anyway.
            for (var decl : ctx.require(PROJECT).build().kotlinPlugins()) {
                String[] parts = decl.coordinate().split(":");
                String version = parts.length == 3 ? parts[2] : pluginVersion;
                Path jar = repos.tryFetchArtifact(cc.jumpkick.model.Coordinate.of(parts[0], parts[1], version))
                        .map(hit -> hit.fetched().cachePath())
                        .orElseThrow(() -> new RuntimeException("cannot fetch the " + decl.id()
                                + " Kotlin compiler plugin (" + parts[0] + ":" + parts[1] + ":" + version
                                + ") — declared under [[kotlin-plugins]]"));
                ktPlugins.add(new KotlincRequest.Plugin(decl.id(), jar, decl.options()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin compiler", e);
        }
        // Compilation classpath: project deps + the version-matched stdlib (the
        // in-process plugin has no kotlin-home to auto-supply it; -no-stdlib).
        List<Path> compileCp = new ArrayList<>(classpath);
        compileCp.add(kt.stdlib());
        List<String> ktArgs = new ArrayList<>();
        ktArgs.add("-no-stdlib");
        // Contributed kotlinc args (e.g. spring-boot's -java-parameters, mirroring its javac
        // -parameters — Boot reflects on parameter names). User-position args still win: these
        // sit before extraArgs additions exactly where the hard-coded flag used to.
        for (String arg : cc.jumpkick.plugin.manifest.PluginContributions.kotlinArgs(
                ctx.require(PROJECT), workingDir, lockModules)) {
            if (!ktArgs.contains(arg)) ktArgs.add(arg);
        }
        // Compiler plugins ride the typed BTA COMPILER_PLUGINS argument — raw -Xplugin/-P
        // strings in extraArgs are silently ignored by the BTA execution path.
        if (javaSourceRoots != null && !javaSourceRoots.isEmpty()) {
            StringBuilder roots = new StringBuilder();
            for (Path root : javaSourceRoots) {
                if (roots.length() > 0) roots.append(',');
                roots.append(root.toAbsolutePath());
            }
            ktArgs.add("-Xjava-source-roots=" + roots);
        }
        Files.createDirectories(outputDir);
        String moduleName = ctx.require(PROJECT).project().name();
        // The incremental state is only valid for the exact compile CONFIG that produced it:
        // BTA's IC sees "no source changes" after an args/plugins/module-name change and would
        // emit nothing into a clean output dir. Key the working dir by a config hash so any
        // config change starts fresh IC state (stale dirs age out with the cache).
        String configToken = cc.jumpkick.util.Hashing.sha256Hex((CompileSupport.kotlinJvmTarget(ctx.require(RELEASE))
                                + "|" + moduleName + "|" + String.join(",", ktArgs) + "|"
                                + ktPlugins.stream()
                                        .map(p -> p.id() + "=" + p.options())
                                        .collect(java.util.stream.Collectors.joining(",")))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .substring(0, 12);
        Path icWorkingDir =
                workingDir == null ? null : workingDir.resolveSibling(workingDir.getFileName() + "-" + configToken);
        KotlincRequest req = KotlincRequest.builder()
                .sources(sources)
                .classpath(compileCp)
                .outputDir(outputDir)
                .jvmTarget(CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)))
                .workerClasspath(kt.workerClasspath())
                .javaHome(ctx.require(JAVA_HOME))
                .workingDir(icWorkingDir)
                .snapshotDir(in.cache().resolve("kotlin-cp-snapshots"))
                .extraArgs(ktArgs)
                .plugins(ktPlugins)
                // Lockstep with the KSP round's -module-name: internal-member mangling
                // (member$module_name) is baked into call sites KSP-generated Java emits
                // (Hilt factories calling internal providers).
                .moduleName(moduleName)
                .build();
        boolean rerun = in.session().config().rebuildOr(false);
        // Reweight from the real request: a CAS hit is a cheap restore (3), else a
        // full kotlinc. Same forKotlinc key KotlinCompile.run looks up.
        if (!rerun) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forKotlinc(taskId, req, cc.jumpkick.model.BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        return cc.jumpkick.task.KotlinCompile.run(
                taskId,
                req,
                cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache);
    }

    /**
     * Compile Groovy {@code sources} into {@code outputDir} via the plugin (action-cached: restores
     * from the CAS on an exact-input hit without launching the plugin, else forks a full compile
     * Groovy has no incremental state). Shared by the main {@code compile-groovy} and {@code
     * compile-test} steps. The caller owns freshness stamps, output assembly, and outcome reporting.
     *
     * @param javaSourceRoots when non-empty, joint mode: the worker sweeps {@code.java} under them
     * for resolution only (jk's javac worker owns the real Java outputs)
     * @param stubsOut when non-null, Java-visible stubs are retained there for javac's sourcepath
     */
    private static cc.jumpkick.task.GroovyCompile.Result compileGroovySources(
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
        String groovyVersion = CompileToolchain.groovyVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        GroovyPluginSetup.Prepared gv;
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            gv = GroovyPluginSetup.prepare(repos, cas, groovyVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Groovy compiler", e);
        }
        // Compilation classpath: project deps + the version-matched groovy jar (user Groovy
        // code compiles against the Groovy runtime types).
        List<Path> compileCp = new ArrayList<>(classpath);
        compileCp.add(gv.groovyJar());
        Files.createDirectories(outputDir);
        if (stubsOut != null) Files.createDirectories(stubsOut);
        // Contributed groovyc args (e.g. grails' --parameters — data binding reflects on
        // parameter names), deduped; mirrors the javac/kotlinc lanes.
        List<String> gvArgs = new ArrayList<>();
        for (String arg : cc.jumpkick.plugin.manifest.PluginContributions.groovyArgs(
                ctx.require(PROJECT), in.dir(), lockModules(ctx.require(LOCKFILE)))) {
            if (!gvArgs.contains(arg)) gvArgs.add(arg);
        }
        // Joint mode sweeps.java sources through a real javac pass — annotation processors
        // must run there or generated members fail resolution.
        @SuppressWarnings("unchecked")
        List<Path> processorCp = javaSourceRoots == null
                ? List.of()
                : (List<Path>) ctx.get(PROCESSOR_CP).orElse(java.util.List.of());
        GroovycRequest req = GroovycRequest.builder()
                .sources(sources)
                .javaSourceRoots(javaSourceRoots == null ? List.of() : javaSourceRoots)
                .classpath(compileCp)
                .processorPath(processorCp)
                .outputDir(outputDir)
                .stubsOut(stubsOut)
                .jvmTarget(ctx.require(RELEASE))
                .workerClasspath(gv.workerClasspath())
                .extraArgs(gvArgs)
                .build();
        boolean rerun = in.session().config().rebuildOr(false);
        // Reweight from the real request: a CAS hit is a cheap restore (3), else a
        // full groovyc. Same forGroovyc key GroovyCompile.run looks up.
        if (!rerun) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forGroovyc(taskId, req, cc.jumpkick.model.BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        return cc.jumpkick.task.GroovyCompile.run(
                taskId,
                req,
                cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache);
    }

    /**
     * The compile-main freshness-stamp classpath side — ONE recipe shared by the live check,
     * {@code write-stamp}, and the forecastthe base compile classpath, then the
     * mixed-language sibling outputs javac sees (kotlin/groovy classes dirs + the version-matched
     * groovy jar), then the annotation-processor path (not on the compile classpath, but a
     * processor bump must bust the stamp). Hand-maintained copies of this recipe drifted twice:
     * the forecast missed the mixed-language entries and write-stamp missed {@code processorCp},
     * so mixed and processor modules never stamp-matched.
     */
    static List<Path> mainStampClasspath(
            List<Path> baseClasspath,
            List<Path> processorCp,
            boolean mixedKotlin,
            boolean mixedGroovy,
            BuildLayout layout,
            Path groovyCompileJar) {
        List<Path> inputs = new ArrayList<>(baseClasspath);
        if (mixedKotlin) inputs.add(layout.kotlinClassesDir());
        if (mixedGroovy) {
            inputs.add(layout.groovyClassesDir());
            if (groovyCompileJar != null) inputs.add(groovyCompileJar);
        }
        if (processorCp != null) inputs.addAll(processorCp);
        return inputs;
    }

    /**
     * The version-matched {@code groovy} jar for javac's classpath in a mixed module: every Groovy
     * class implements {@code groovy.lang.GroovyObject}, so Java code referencing a Groovy type
     * needs the jar to resolve the supertype. Warm after compile-groovy's setup (CAS-memoized).
     */
    private static Path groovyCompileJar(TaskContext ctx, Cas cas) throws IOException {
        String groovyVersion = CompileToolchain.groovyVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return GroovyPluginSetup.prepare(repos, cas, groovyVersion).groovyJar();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Groovy compile jar", e);
        }
    }

    /**
     * The version-matched Groovy runtime closure (already in the CAS from the worker setup).
     * Groovy output needs it on the <em>runtime</em> classpath — compilation pairs the groovy jar
     * onto the compile classpath, but the JVM still needs the full runtime closure when the code
     * runs (mirrors {@link #kotlinStdlib}).
     */
    private static List<Path> groovyRuntime(TaskContext ctx, Cas cas) throws IOException {
        String groovyVersion = CompileToolchain.groovyVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        if (groovyVersion == null || groovyVersion.isBlank()) {
            groovyVersion = cc.jumpkick.groovy.GroovyResolver.DEFAULT_VERSION;
        }
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return GroovyToolResolver.resolveRuntime(repos, cas, groovyVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Groovy runtime", e);
        }
    }

    /** The resolved lock's {@code group:artifact} names — the classpath-has condition's universe. */
    static java.util.Set<String> lockModules(Lockfile lock) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (var a : lock.artifacts()) {
            out.add(a.name());
            // Rows are keyed by full package id (g:a:type:classifier) since package identity
            // gained type/classifier; consumers (classpath-has conditions, processor-dependency
            // checks) still speak plain group:artifact — expose that form too.
            out.add(a.moduleGroup() + ":" + a.moduleArtifact());
        }
        return out;
    }

    /**
     * The version-matched {@code kotlin-stdlib} path (already in the CAS from the plugin closure).
     * Kotlin output needs it on the <em>runtime</em> classpath too — compilation pairs the stdlib
     * with {@code -no-stdlib}, but the JVM still needs {@code kotlin.jvm.internal.*} etc. when the
     * code runs.
     */
    private static Path kotlinStdlib(TaskContext ctx, Cas cas) throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return KotlinPluginSetup.prepare(repos, cas, kotlinVersion).stdlib();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin stdlib", e);
        }
    }

    private static void copyResources(Path resourceDir, Path classesDir) throws IOException {
        if (!Files.exists(resourceDir)) return;
        try (Stream<Path> stream = Files.walk(resourceDir)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(source)) continue;
                Path relative = resourceDir.relativize(source);
                Path target = classesDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Map each workspace sibling to its main output jar, keyed by both project name and {@code
     * group:artifact} coord. Used by {@link #workerJarProps} to locate {@code test-plugin-jars}
     * entries. Empty when this module isn't in a workspace.
     */
    static Map<String, Path> siblingMainJars(Path moduleDir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        var rootOpt = cc.jumpkick.config.WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (String module : rootManifest.workspace().modules()) {
            Path dir = root.resolve(module);
            Path manifest = dir.resolve("jk.toml");
            if (!Files.exists(manifest)) continue;
            JkBuild sib;
            try {
                sib = JkBuildParser.parse(manifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(dir, sib);
            // An assembly plugin runs from its -all.jar — that's the artifact
            // that bundles plugin-api/PluginMain and the plugin's deps; a
            // plain module ships only its main jar.
            Path jar = sib.assembly() ? layout.assemblyJar() : layout.mainJar();
            String name = sib.project().name();
            out.put(name, jar);
            out.put(sib.project().group() + ":" + name, jar);
            // test-plugin-jars uses short names ("test-runner"); first-party
            // workers publish as jk-<short> — alias so sibling lookup works.
            if (name.startsWith("jk-") && name.length() > 3) {
                out.put(name.substring(3), jar);
            }
            // Also key by the module directory basename (plugins/test-runner → test-runner).
            Path base = dir.getFileName();
            if (base != null) {
                out.putIfAbsent(base.toString(), jar);
            }
        }
        return out;
    }

    /**
     * Packaging cache (mirrors the compile {@link ActionCache} path, for artifacts). Returns {@code
     * true} when a cached artifact for {@code key} was hard-linked back into {@code baseDir} — the
     * caller then skips the (re)packaging work.
     *
     * <p>Invariant: every packaging step that writes a jar under {@code target/} also
     * {@link #storePackaged stores} an action record. A jar without a record only happens when the
     * action cache was deleted out of band while {@code target/} was kept — recovery is to
     * re-package (this miss path), not to trust the on-disk jar as authoritative.
     *
     * <p>{@code --redo}/{@code --force} skip <em>restore</em> (always re-package) but still
     * {@link #storePackaged store} — same contract as {@link JavaIncrementalCompile}: the next
     * {@code jk explain} / incremental build must see a CACHE_HIT, not a phantom repackage.
     */
    private static boolean restorePackaged(Path cacheRoot, String key, Path baseDir) throws IOException {
        // rebuildOr already subsumes force (JkConfig: force implies rebuild).
        if (cc.jumpkick.config.SessionContext.current().config().rebuildOr(false)) {
            return false;
        }
        ActionCache ac = new ActionCache(JkStores.cacheCas(cacheRoot), cacheRoot.resolve("actions"));
        var hit = ac.lookup(key);
        return hit.isPresent() && ac.restoreArtifacts(hit.get(), baseDir);
    }

    /**
     * Record a freshly-produced packaging artifact so a later build / explain can skip it. Writes
     * even under {@code --redo} — redo only means "do not restore/skip work", not "do not
     * teach the cache" (parity with compile). {@code persist=false} is {@code jk verify}'s scratch
     * rebuild: packaging DOES run there (the artifact is what verify diffs), its keys embed the
     * unique scratch path so they can never recur, and a store would be a permanent orphan record
     * plus CAS copies on every verify run.
     */
    private static void storePackaged(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist)
            throws IOException {
        if (!persist) return;
        new ActionCache(JkStores.cacheCas(cacheRoot), cacheRoot.resolve("actions"))
                .storeArtifacts(taskId, key, Map.of("inputs", String.join(";", tokens)), baseDir, artifacts);
    }

    /** Test hook: {@link #storePackaged} under a rebuild session must still persist. */
    static void storePackagedForTest(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist)
            throws IOException {
        storePackaged(cacheRoot, taskId, key, tokens, baseDir, artifacts, persist);
    }

    private static Map<String, String> workerJarProps(Path moduleDir, List<String> modules) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        if (modules.isEmpty()) return props;
        Map<String, Path> jarByModule = siblingMainJars(moduleDir);
        for (String module : modules) {
            // Accept short names (test-runner), artifact ids (jk-test-runner), or either already.
            var wj = cc.jumpkick.engine.plugin.PluginJar.byArtifactId(module);
            if (wj.isEmpty()) wj = cc.jumpkick.engine.plugin.PluginJar.byArtifactId("jk-" + module);
            if (wj.isEmpty()) continue;
            Path jar = jarByModule.get(module);
            if (jar == null) jar = jarByModule.get(wj.get().artifactId());
            if (jar == null && module.startsWith("jk-")) jar = jarByModule.get(module.substring(3));
            if (jar != null && Files.exists(jar)) {
                props.put(wj.get().jarProperty(), jar.toAbsolutePath().toString());
            } else {
                // Not a built sibling — self-host by reusing the running jk's plugin
                // jar (located via its sha resource + CAS, or a -D override).
                Path located = wj.get().locateOrNull(cc.jumpkick.cache.JkStores.cas(cc.jumpkick.util.JkDirs.cache()));
                if (located != null) props.put(wj.get().jarProperty(), located.toString());
            }
        }
        return props;
    }

    /**
     * CLI integration suite: tests spawn a real engine via the wire and register {@code
     * EngineTestExtension}, which force-stops the engine after each class. Under pure-jk {@code jk
     * test} that must not share the host engine's {@code JK_STATE_DIR} (host would die mid-suite).
     */
    static boolean needsNestedEngineIsolation(JkBuild project) {
        if (project == null || project.project() == null) return false;
        String name = project.project().name();
        if ("jk-cli".equals(name)) return true;
        return "cc.jumpkick.cli.Jk".equals(project.mainClass());
    }

    /**
     * Resolve engine assembly + every first-party worker jar so CLI tests match Gradle's {@code
     * -Djk.engine.jar} / {@code -Djk.*.plugin.jar} wiring.
     *
     * <p>{@code jk test} (testOnly) does not package the engine assembly, so the workspace
     * {@code *-all.jar} is often missing. Fall back to the host engine jar (the process serving
     * this build) or the materialized install under {@code VersionStore} — same fat jar Gradle
     * hands CLI tests via {@code :engine:shadowJar}.
     */
    static void enrichCliTestProps(Path moduleDir, Map<String, String> props) throws IOException {
        Map<String, Path> siblings = siblingMainJars(moduleDir);
        Path engine = resolveEngineJarForNestedTests(siblings);
        if (engine != null) {
            props.put("jk.engine.jar", engine.toAbsolutePath().toString());
        }
        for (PluginJar w : PluginJar.values()) {
            if (props.containsKey(w.jarProperty())) continue;
            Path jar = siblings.get(w.artifactId());
            if (jar == null && w.artifactId().startsWith("jk-")) {
                jar = siblings.get(w.artifactId().substring(3));
            }
            if (jar != null && Files.isRegularFile(jar)) {
                props.put(w.jarProperty(), jar.toAbsolutePath().toString());
            } else {
                Path located = w.locateOrNull(JkStores.cas(cc.jumpkick.util.JkDirs.cache()));
                if (located != null) props.put(w.jarProperty(), located.toString());
            }
        }
    }

    /**
     * Engine jar for nested CLI suites: workspace assembly when present, else the host process's
     * fat jar / installed VersionStore materialization.
     */
    static Path resolveEngineJarForNestedTests(Map<String, Path> siblings) {
        Path engine = siblings != null ? siblings.get("jk-engine") : null;
        if (engine == null && siblings != null) engine = siblings.get("engine");
        if (engine != null && Files.isRegularFile(engine)) return engine.normalize();
        return locateHostEngineJar();
    }

    /**
     * Fat engine jar this process was launched from, the same version under {@link
     * cc.jumpkick.cache.VersionStore}, or a monorepo product path ({@code build/dist/lib},
     * Gradle {@code build/libs}, pure-jk {@code target/server/engine}). Null only when none
     * of those exist (cold checkout with no install and no prior package).
     */
    static Path locateHostEngineJar() {
        try {
            var cs = cc.jumpkick.engine.EngineMain.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path p = Path.of(cs.getLocation().toURI());
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                    return p.normalize();
                }
            }
        } catch (Exception ignored) {
            // fall through — exploded test classpath is common under Gradle
        }
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) continue;
            Path p = Path.of(entry);
            String name = p.getFileName() != null ? p.getFileName().toString() : "";
            if (Files.isRegularFile(p)
                    && name.endsWith(".jar")
                    && (name.startsWith("jk-engine") || name.equals("jk-engine.jar"))) {
                return p.toAbsolutePath().normalize();
            }
        }
        try {
            var mat = cc.jumpkick.cache.VersionStore.current()
                    .resolve(cc.jumpkick.model.JkVersion.VERSION);
            if (mat.isPresent() && Files.isRegularFile(mat.get().engineJar())) {
                return mat.get().engineJar().toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // Isolated JK_HOME (Gradle :engine:test / nested CLI suite) has no versions tree.
        }
        // Last resort: monorepo product outputs relative to user.dir (and parents). Pure-jk
        // nested isolation runs with user.dir = clients/cli; host run-tests has monorepo root
        // or server/engine as cwd under Gradle.
        return findMonorepoEngineJar(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    /** Prefer fat assembly, then dist/shadow, then thin main jar under known layout roots. */
    static Path findMonorepoEngineJar(Path start) {
        String ver = cc.jumpkick.model.JkVersion.VERSION;
        Path walk = start;
        for (int up = 0; up < 5 && walk != null; up++, walk = walk.getParent()) {
            for (String rel : List.of(
                    "target/server/engine/jk-engine-" + ver + "-all.jar",
                    "build/dist/lib/jk-engine-" + ver + ".jar",
                    "server/engine/build/libs/jk-engine-" + ver + ".jar",
                    "build/libs/jk-engine-" + ver + ".jar",
                    "target/server/engine/jk-engine-" + ver + ".jar",
                    "server/engine/target/jk-engine-" + ver + "-all.jar",
                    "server/engine/target/jk-engine-" + ver + ".jar")) {
                Path p = walk.resolve(rel);
                if (Files.isRegularFile(p)) return p.normalize();
            }
        }
        return null;
    }

    /**
     * Isolated {@code JK_HOME} + short {@code JK_STATE_DIR} under {@code /tmp} (UDS path length) for
     * nested-engine CLI tests. Keeps the host engine's socket alone.
     *
     * <p><strong>Fully sandboxed product layout</strong> — cache and store both live under
     * {@code $JK_HOME}. Never point {@code JK_CACHE_DIR} or {@code JK_STORE_DIR} at the host: a
     * prior bug set them to the developer's real trees so {@code SelfPurgeCommandTest} /
     * {@code jk cache purge} / {@code jk self purge --store} wiped action-cache and install-local
     * workers mid-{@code jk build}. After that, post-green {@code jk explain} reported a full
     * rebuild and subsequent tests could not find {@code jk-test-runner}.
     *
     * <p>Plugin/worker jars for nested suites still arrive via {@code -Djk.*.plugin.jar} props
     * ({@link #enrichCliTestProps}), not by sharing the host store.
     */
    static Map<String, String> nestedEngineTestEnv(Path moduleDir) throws IOException {
        Path jkHome = moduleDir.resolve("target").resolve("test-jk-home");
        Files.createDirectories(jkHome);
        String runId = Long.toString(System.currentTimeMillis(), 36) + "-"
                + Integer.toHexString(System.identityHashCode(moduleDir) & 0xffff);
        Path stateDir = Path.of("/tmp", "jk-cli-" + runId);
        Files.createDirectories(stateDir);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JK_HOME", jkHome.toAbsolutePath().toString());
        env.put("JK_JDKS_DIR", jkHome.resolve("jdks").toAbsolutePath().toString());
        env.put("JK_STATE_DIR", stateDir.toAbsolutePath().toString());
        // Intentionally no JK_CACHE_DIR / JK_STORE_DIR — both resolve under JK_HOME.
        env.put("JK_STREAM_IDLE_MS", "45000");
        env.put("TERM", "xterm-256color");
        env.put("CI", "false");
        // Clear NO_COLOR so TUI ANSI assertions match Gradle's deterministic setup.
        env.put("NO_COLOR", "");
        // Nested engines + workers: train-on-miss is pure overhead under the suite.
        env.put("JK_AOT_TRAIN", "off");
        return env;
    }

    /**
     * The selection the runner actually executes: the session's, with this module's
     * {@code [test] default-exclude-tags} folded in when the session carries no tags at all
     * (jk build / BSP without data —. `jk test` resolves defaults CLI-side and its
     * selection already carries them.
     */
    static cc.jumpkick.config.TestSelection effectiveSelection(cc.jumpkick.config.TestSelection sel, Path moduleDir) {
        if (!sel.includeTags().isEmpty() || !sel.excludeTags().isEmpty()) return sel;
        List<String> defaults = cc.jumpkick.config.JkBuildParser.parseDefaultExcludeTags(moduleDir.resolve("jk.toml"));
        if (defaults.isEmpty()) return sel;
        return cc.jumpkick.config.TestSelection.of(sel.suites(), sel.allSuites(), List.of(), defaults);
    }

    /**
     * Worker / engine jar props that feed both the forked test JVM and the {@link
     * cc.jumpkick.task.TestStamp} extras. Includes declared {@code [build] test-plugin-jars} and,
     * for nested-engine CLI modules, every first-party {@link PluginJar} plus the engine assembly
     * forecast and live run-tests must hash the same set).
     */
    public static Map<String, String> testStampWorkerJars(Path dir, JkBuild project) throws IOException {
        Map<String, String> props =
                new LinkedHashMap<>(workerJarProps(dir, project.build().testPluginJars()));
        if (needsNestedEngineIsolation(project)) {
            enrichCliTestProps(dir, props);
        }
        return props;
    }

    /**
     * The run-tests stamp's identity tokens for {@code project} at {@code dir} — the same set the
     * build folds into its {@link cc.jumpkick.task.TestStamp} key, so {@code jk explain}'s forecast
     * predicts test-skip without drifting.
     */
    public static List<String> testStampExtras(Path dir, JkBuild project) throws IOException {
        return testStampExtras(
                testStampWorkerJars(dir, project),
                effectiveSelection(cc.jumpkick.config.TestSelection.DEFAULT, dir),
                project.build().testEnv(),
                dir);
    }

    /**
     * Full {@link cc.jumpkick.task.TestStamp} key for the default {@code jk build} selection
     * single factory for forecast and any offline checker. {@code testRuntimeCp} must match the
     * build's runtime classpath for the stamp (lock deps + workspace sibling jars; plugin
     * contributions optional for non-plugin modules).
     */
    public static String runTestsStampKey(
            Path dir, JkBuild project, boolean compact, Path mainClasses, Path lockFile, List<Path> testRuntimeCp)
            throws IOException {
        List<String> discovered = cc.jumpkick.layout.TestSuites.discover(dir, compact);
        var resolved = cc.jumpkick.config.TestSelection.DEFAULT.resolve(discovered);
        List<String> suites = resolved.ok() ? resolved.suites() : List.of(cc.jumpkick.layout.TestSuites.DEFAULT);
        List<Path> stampSrcs = new ArrayList<>();
        stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectJavaSources(dir, compact, suites));
        stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectKotlinSources(dir, compact, suites));
        stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectGroovySources(dir, compact, suites));
        return cc.jumpkick.task.TestStamp.computeKey(
                stampSrcs,
                mainClasses,
                cc.jumpkick.layout.ModuleLayout.suiteResourceDirs(dir, compact, suites),
                lockFile,
                testRuntimeCp,
                testStampExtras(dir, project));
    }

    /**
     * Stamp extras with env expansion and secret hashing for a module at {@code moduleDir}
     * . There is deliberately no lookup-free overload: it would silently skip
     * both expansion and hashing and produce a key that disagrees with this one.
     */
    static List<String> testStampExtras(
            Map<String, String> workerJars,
            cc.jumpkick.config.TestSelection selection,
            Map<String, String> testEnv,
            Path moduleDir) {
        cc.jumpkick.config.EnvLookup lookup =
                moduleDir == null ? null : cc.jumpkick.config.BuildEnv.lookupFor(moduleDir);
        cc.jumpkick.config.SecretRedactor redactor = lookup == null
                ? cc.jumpkick.config.SecretRedactor.none()
                : cc.jumpkick.config.SecretRedactor.from(lookup);
        return testStampExtras(workerJars, selection, testEnv, redactor, lookup);
    }

    /**
     * Stamp extras. Declared {@code [test] env} values only (sandbox defaults stay out — they are
     * absolute paths that would defeat cache sharing). Environment references expand through
     * {@code lookup}; a {@code.env}-sourced value is hashed into the key, never written verbatim
     *
     */
    static List<String> testStampExtras(
            Map<String, String> workerJars,
            cc.jumpkick.config.TestSelection selection,
            Map<String, String> testEnv,
            cc.jumpkick.config.SecretRedactor redactor,
            cc.jumpkick.config.EnvLookup lookup) {
        List<String> extras = new ArrayList<>();
        extras.add("jk:" + cc.jumpkick.model.BuildIdentity.cacheKeyVersion());
        // Suite + tag filters are part of the outcome.
        if (selection != null) extras.add("sel:" + selection.identityToken());
        // [test] env changes what the suite sees, so it must retest.
        cc.jumpkick.config.SecretRedactor secrets =
                redactor == null ? cc.jumpkick.config.SecretRedactor.none() : redactor;
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(testEnv).entrySet()) {
            String raw = e.getValue() == null ? "" : e.getValue();
            String expanded = raw;
            boolean envResolved = false;
            if (lookup != null && raw.indexOf('$') >= 0) {
                // Expand ${VAR} for cache identity, but leave ${target}/${module} as tokens so
                // the key stays portable across checkouts (same instinct as the sandbox defaults).
                boolean[] resolved = {false};
                try {
                    expanded = cc.jumpkick.config.Interpolation.expand(raw, "[test].env." + e.getKey(), var -> {
                        if ("target".equals(var) || "module".equals(var)) return "${" + var + "}";
                        String v = lookup.get(var);
                        if (v != null) resolved[0] = true;
                        return v;
                    });
                } catch (cc.jumpkick.config.JkBuildParseException ex) {
                    // Unset var — keep the raw text so a broken reference still changes the key.
                    expanded = raw;
                }
                envResolved = resolved[0];
            }
            String keyed = secrets.forCacheKey(expanded);
            if (envResolved && keyed.equals(expanded)) {
                // Non-secret env reference (${HOME}, a CI id): the VALUE still keys the stamp
                // a changed environment retestsbut the literal must not land in a
                // (potentially shared) key: no absolute paths or ids on disk.
                keyed = cc.jumpkick.config.SecretRedactor.KEY_PREFIX + cc.jumpkick.util.Hashing.sha256Hex(expanded);
            }
            extras.add("test-env:" + e.getKey() + "=" + keyed);
        }
        // Plugin jars by content — a plugin change retests the module that forks it.
        for (Map.Entry<String, String> e : workerJars.entrySet()) {
            String fp;
            try {
                fp = cc.jumpkick.task.ClasspathFingerprint.entry(Path.of(e.getValue()));
            } catch (IOException ex) {
                fp = "err";
            }
            extras.add("worker:" + e.getKey() + "=" + fp);
        }
        return extras;
    }
}
