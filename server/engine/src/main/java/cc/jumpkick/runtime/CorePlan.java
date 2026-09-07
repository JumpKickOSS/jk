// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Variants;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The core build plan: parse → sync → compile → resources → test → package, with the plugin and
 * guard steps between. The fields are the module's shape as the phases discover it — languages,
 * layout, the parsed manifest, the plugin declarations — each phase leaving what the next reads,
 * in the order {@link #build} calls them; {@link Steps} is every step the graph phases pick from.
 */
final class CorePlan {
    private final BuildPlanner.Inputs in;
    private final boolean forceRebuild;
    private final Cas cas;
    private final ActionCache actionCache;

    // Compose only the language steps the project uses, so a single-language project never shows
    // a no-op step for the other. Explicit jk.toml opt-ins (java/kotlin) win; otherwise the
    // languages are inferred from the source tree (see CompileSupport.resolveLanguages).
    private boolean useJava = true;
    private boolean useKotlin;
    private boolean useGroovy;
    private boolean useScala;
    private boolean compactLayout;
    private boolean workspaceNoSources;
    private @Nullable JkBuild parsedBuild;
    private Map<String, String> variantSecrets = Map.of();

    private PluginBuild.@Nullable Active pluginActive;
    private PluginBuild.@Nullable Declarations pluginDecls;

    CorePlan(BuildPlanner.Inputs in, boolean forceRebuild) {
        this.in = in;
        this.forceRebuild = forceRebuild;
        this.cas = JkStores.storeCas(); // artifact store CAS (deps, workers)
        this.actionCache = new ActionCache(JkStores.cacheCas(in.cache()), CacheTree.ACTIONS.under(in.cache()));
    }

    BuildPlan.Builder build() {
        readShape();
        rejectUnsupportedLanguagePairs();
        readPluginDeclarations();
        BuildPlanner.Ctx cx = context();
        Steps s = steps(cx);
        BuildPlan.Builder b = BuildPlan.builder("build")
                .stateKeys(BuildPlanner.STATE_KEYS)
                .addTask(s.parseBuild())
                .addTask(s.syncDeps())
                .addTask(s.ensureJdk());
        if (workspaceNoSources) return workspaceRootPlan(b, cx);
        addCompile(b, cx, s);
        if (in.compileOnly()) return compileOnlyPlan(b, cx, s);
        boolean skipJUnit = PlannerResources.skipJUnit(in);
        addTestsAndPackaging(b, cx, s, skipJUnit);
        return terminal(b, cx, skipJUnit);
    }

    // ---- shape ---------------------------------------------------------------------------

    private void readShape() {
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
            jkBuild = BuildPlanner.applyAssemblyOverride(jkBuild, in.session());
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
    }

    private void rejectUnsupportedLanguagePairs() {
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
    }

    /**
     * Build-plugin code layer: learn the registered steps/packager over the file-cached describe
     * protocol. A missing plugin jar or a broken registration must fail the build loudly here, not
     * mid-plan.
     */
    private void readPluginDeclarations() {
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
    }

    // ---- context -------------------------------------------------------------------------

    /** The build-scoped services, estimation state and layout flags every core step shares. */
    private BuildPlanner.Ctx context() {
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
        final boolean kspEnabled = useKotlin && parsedBuild != null && PlannerCompile.hasProcessorDeps(parsedBuild);
        return new BuildPlanner.Ctx(
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
                kspEnabled,
                PlannerGuards.detect(in));
    }

    // ---- steps ---------------------------------------------------------------------------

    /** Every step the graph phases pick from, built once; whether fixtures and a guard suite exist. */
    private record Steps(
            Task parseBuild,
            Task syncDeps,
            Task ensureJdk,
            Task compileJava,
            Task compileKotlin,
            Task compileGroovy,
            Task copyResources,
            boolean hasFixtures,
            Task compileTestFixtures,
            boolean hasGuardSuite,
            Task compileGuard,
            Task compileTest,
            Task runTests,
            List<Task> pluginSteps,
            Task packageJar,
            Task writeStamp,
            Task writeStampKotlin,
            Task writeStampGroovy,
            Task assembleClasses) {}

    private Steps steps(BuildPlanner.Ctx cx) {
        Task parseBuild = PlannerSetup.parseBuildStep(cx);
        Task syncDeps = PlannerSetup.syncDepsStep(cx);
        Task ensureJdk = PlannerSetup.ensureJdkStep(cx);
        Task compileJava = PlannerCompile.compileJavaStep(cx, pluginDecls);
        Task compileKotlin = PlannerCompile.compileKotlinStep(cx, pluginDecls);
        Task compileGroovy = PlannerCompile.compileGroovyStep(cx, pluginDecls);
        Task copyResources = PlannerResources.copyResourcesStep(cx);
        boolean hasFixtures = parsedBuild != null && PlannerFixtures.declared(parsedBuild);
        Task compileTestFixtures = PlannerFixtures.compileTestFixturesStep(cx);
        boolean hasGuardSuite = PlannerGuardSuite.declared(in.dir(), compactLayout);
        Task compileGuard = PlannerGuardSuite.compileGuardStep(cx, compactLayout);
        Task compileTest = PlannerTest.compileTestStep(cx, hasFixtures);
        // In testOnly plans no package path exists to anchor the freshness stamps, so
        // run-tests carries them in its requires — otherwise the target-closure prune drops
        // them and the edit→test loop re-runs the main compile every invocation.
        List<String> testStampRequires = new ArrayList<>();
        if (in.testOnly()) {
            if (useJava) testStampRequires.add(TaskNames.WRITE_STAMP);
            if (useKotlin) testStampRequires.add(TaskNames.WRITE_STAMP_KOTLIN);
            if (useGroovy) testStampRequires.add(TaskNames.WRITE_STAMP_GROOVY);
        }
        Task runTests = PlannerTest.runTestsStep(cx, pluginDecls, testStampRequires);
        List<Task> pluginSteps = new ArrayList<>();
        PluginBuild.TaskDecl transform = PlannerPlugin.transformStep(pluginDecls);
        if (pluginDecls != null) {
            for (PluginBuild.TaskDecl step : pluginDecls.steps()) {
                pluginSteps.add(PlannerPlugin.pluginTask(cx, pluginActive, step, transform));
            }
        }
        Task packageJar = PlannerPackage.packageJarStep(cx, pluginActive, pluginDecls, variantSecrets);
        Task writeStamp = PlannerPackage.writeStampStep(cx);
        // Kotlin's freshness companion (cf. write-stamp for Java). Mirrors the
        // input set compile-kotlin checked: Kotlin sources, plus Java sources in
        // a mixed module. No action-cache key exists yet — the direct kotlinc
        // path leaves it empty until incremental Kotlin lands.
        Task writeStampKotlin = PlannerPackage.writeStampKotlinStep(cx);
        // Groovy's freshness companion, mirroring write-stamp-kotlin.
        Task writeStampGroovy = PlannerPackage.writeStampGroovyStep(cx);
        // Merge the per-language output dirs into the shared classes dir that
        // packaging, tests, and the run/native tails all read (mixed modules only).
        Task assembleClasses = PlannerPackage.assembleClassesStep(cx);
        return new Steps(
                parseBuild,
                syncDeps,
                ensureJdk,
                compileJava,
                compileKotlin,
                compileGroovy,
                copyResources,
                hasFixtures,
                compileTestFixtures,
                hasGuardSuite,
                compileGuard,
                compileTest,
                runTests,
                pluginSteps,
                packageJar,
                writeStamp,
                writeStampKotlin,
                writeStampGroovy,
                assembleClasses);
    }

    // ---- graph ---------------------------------------------------------------------------

    /**
     * Workspace root with no sources: validate jk.toml, sync deps, and run the root's own
     * `after-build` logic. The graph orders this unit behind every member, so by the time the
     * step executes the whole workspace is built.
     */
    private BuildPlan.Builder workspaceRootPlan(BuildPlan.Builder b, BuildPlanner.Ctx cx) {
        String guardTerminal = PlannerGuards.appendRootLanes(b, cx, TaskNames.RESOLVE_DEPS, false);
        if (guardTerminal != null) {
            b.alsoKeep(
                    TaskNames.GUARD_MODEL,
                    TaskNames.GUARD_WORKSPACE,
                    TaskNames.GUARD_TREE,
                    TaskNames.GUARD_FIXTURES,
                    TaskNames.GUARD_OUTPUT);
        }
        if (BuildLogicToml.resolve(in.dir()).isPresent()) {
            b.addTask(PlannerResources.buildLogicAfterBuildStep(cx));
            String gate = PlannerResources.appendGate(b, cx, false, in.testOnly(), true);
            return b.terminal(gate != null ? gate : TaskNames.BUILD_LOGIC_AFTER_BUILD);
        }
        return b.terminal(guardTerminal != null ? guardTerminal : TaskNames.RESOLVE_DEPS);
    }

    /** Codegen, the language compiles, the guard lanes, and the classes assembler for mixed modules. */
    private void addCompile(BuildPlan.Builder b, BuildPlanner.Ctx cx, Steps s) {
        // BEFORE_COMPILE / GENERATE: codegen before any language compile (or KSP).
        b.addTask(PlannerResources.buildLogicBeforeCompileStep(cx));
        if (cx.ksp()) {
            b.addTask(PlannerKsp.kspStep(cx, pluginDecls));
        }
        if (useGroovy) {
            b.addTask(s.compileGroovy());
        }
        if (useJava) {
            b.addTask(s.compileJava());
        }
        if (useKotlin) {
            b.addTask(s.compileKotlin());
        }
        if (PlannerGuards.moduleLanesOnThisBuild(cx.guards(), PlannerResources.runGuardScripts(in))) {
            List<String> after = new ArrayList<>();
            if (useJava) after.add(TaskNames.COMPILE_JAVA);
            if (useKotlin) after.add(TaskNames.COMPILE_KOTLIN);
            if (useGroovy) after.add(TaskNames.COMPILE_GROOVY);
            if (cx.mixed() || cx.mixedGroovy()) after.add(TaskNames.ASSEMBLE_CLASSES);
            if (s.hasGuardSuite()) after.add(TaskNames.COMPILE_GUARD);
            b.addTask(PlannerGuards.moduleStep(cx, after.toArray(String[]::new)));
            boolean packagesHere = !in.testOnly() && !in.compileOnly();
            PlannerGuards.appendRootLanes(b, cx, TaskNames.GUARD, packagesHere);
            // Nothing downstream consumes a lane; keep them through the terminal prune.
            b.alsoKeep(
                    TaskNames.GUARD,
                    TaskNames.GUARD_MODEL,
                    TaskNames.GUARD_WORKSPACE,
                    TaskNames.GUARD_TREE,
                    TaskNames.GUARD_FIXTURES,
                    TaskNames.GUARD_OUTPUT);
        } else if (cx.guards().enabled()) {
            // on-build = false: the module lanes wait for the gate; the model lane still runs.
            PlannerGuards.appendRootLanes(b, cx, TaskNames.RESOLVE_DEPS, !in.testOnly() && !in.compileOnly());
            b.alsoKeep(
                    TaskNames.GUARD_MODEL,
                    TaskNames.GUARD_WORKSPACE,
                    TaskNames.GUARD_TREE,
                    TaskNames.GUARD_FIXTURES,
                    TaskNames.GUARD_OUTPUT);
        }
        if (cx.mixed() || cx.mixedGroovy()) {
            b.addTask(s.assembleClasses());
        }
    }

    /**
     * `jk compile` stops here: lock → sync → compile (+ freshness stamps), no
     * resources/test/package. Everything later depends on these steps.
     */
    private BuildPlan.Builder compileOnlyPlan(BuildPlan.Builder b, BuildPlanner.Ctx cx, Steps s) {
        List<String> stamps = new ArrayList<>();
        if (useJava) {
            b.addTask(s.writeStamp());
            stamps.add(TaskNames.WRITE_STAMP);
        }
        if (useKotlin) {
            b.addTask(s.writeStampKotlin());
            stamps.add(TaskNames.WRITE_STAMP_KOTLIN);
        }
        if (useGroovy) {
            b.addTask(s.writeStampGroovy());
            stamps.add(TaskNames.WRITE_STAMP_GROOVY);
        }
        if (stamps.isEmpty()) return b.terminal(cx.mainCompile());
        if (stamps.size() == 1) return b.terminal(stamps.get(0));
        // Mixed module: every language's stamp (and the classes assembler) is an
        // independent leaf — a single-stamp terminal would prune the others and the
        // pruned language recompiles every run. Join them so the closure keeps each
        // one (same idiom as the deliver join).
        if (cx.mixed() || cx.mixedGroovy()) {
            stamps.add(TaskNames.ASSEMBLE_CLASSES);
        }
        b.addTask(Task.builder(BuildPlanner.COMPILE_JOIN)
                .stage(BuildStage.COMPILE)
                .requires(stamps.toArray(String[]::new))
                .weight(0)
                .ticks(0)
                .execute(ctx -> {
                    /* join only */
                })
                .build());
        return b.terminal(BuildPlanner.COMPILE_JOIN);
    }

    /** Resources, the test branch, the guard suite, the plugin steps and packaging, then the stamps. */
    private void addTestsAndPackaging(BuildPlan.Builder b, BuildPlanner.Ctx cx, Steps s, boolean skipJUnit) {
        // Build-logic AFTER_COMPILE before resources / AFTER_RESOURCES.
        b.addTask(PlannerResources.buildLogicAfterCompileStep(cx));
        b.addTask(s.copyResources());
        if (!skipJUnit) {
            if (s.hasFixtures()) b.addTask(s.compileTestFixtures());
            b.addTask(s.compileTest()).addTask(s.runTests());
        }
        // The guard suite is not a test: it compiles whether or not tests are skipped, and stays in
        // the plan when a target closure would otherwise prune it.
        if (s.hasGuardSuite()) {
            b.addTask(s.compileGuard());
            b.alsoKeep(TaskNames.COMPILE_GUARD);
        }
        // `jk test` stops at run-tests — it never packages a jar. Plugin steps run only
        // when packaging does: they exist to feed the packaged/native artifact. The exception
        // is plugin tasks run-tests itself requires (test-only or test-classpath contributors,
        // mirroring runTestsStep's requires) plus any plugin tasks those transitively require —
        // without them the plan fails validation before anything runs.
        if (!in.testOnly()) {
            for (Task p : s.pluginSteps()) b.addTask(p);
            b.addTask(PlannerResources.buildLogicBeforePackageStep(cx));
            b.addTask(s.packageJar());
        } else if (pluginDecls != null) {
            addTestClasspathPlugins(b, s.pluginSteps(), pluginDecls);
        }
        // write-stamp is the Java-compile freshness companion; only when Java ran.
        if (useJava) {
            b.addTask(s.writeStamp());
        }
        // write-stamp-kotlin is the Kotlin-compile freshness companion.
        if (useKotlin) {
            b.addTask(s.writeStampKotlin());
        }
        // write-stamp-groovy is the Groovy-compile freshness companion.
        if (useGroovy) {
            b.addTask(s.writeStampGroovy());
        }
    }

    /** The plugin tasks a test-only plan still needs: test-classpath contributors and what they require. */
    private static void addTestClasspathPlugins(
            BuildPlan.Builder b, List<Task> pluginSteps, PluginBuild.Declarations decls) {
        Map<String, Task> pluginByName = new LinkedHashMap<>();
        for (Task p : pluginSteps) pluginByName.put(p.name(), p);
        ArrayDeque<String> want = new ArrayDeque<>();
        for (PluginBuild.TaskDecl step : decls.steps()) {
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

    private BuildPlan.Builder terminal(BuildPlan.Builder b, BuildPlanner.Ctx cx, boolean skipJUnit) {
        String gate = PlannerResources.appendGate(b, cx, !skipJUnit, in.testOnly(), false);
        if (in.testOnly()) {
            if (gate != null) return b.terminal(gate);
            if (!skipJUnit) return b.terminal(TaskNames.RUN_TESTS);
            return b.terminal(TaskNames.COPY_RESOURCES);
        }
        return b.terminal(TaskNames.PACKAGE_JAR);
    }
}
