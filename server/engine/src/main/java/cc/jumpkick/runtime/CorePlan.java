// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.SourceRefs;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // a no-op step for the other.
    private ModuleShape shape = ModuleShape.UNREAD;
    private @Nullable JkBuild parsedBuild;
    private @Nullable RuntimeException manifestFailure;
    private Map<String, String> variantSecrets = Map.of();

    private ActivePlugins.@Nullable Declared plugins;
    private @Nullable PluginDeclarations pluginDecls;
    private NodeShape.Kind nodeKind = NodeShape.Kind.NONE;
    // A JVM module that declares node without building any: its tests get the locked Node.js.
    private boolean testNode;

    CorePlan(BuildPlanner.Inputs in, boolean forceRebuild) {
        this.in = in;
        this.forceRebuild = forceRebuild;
        this.cas = JkStores.storeCas(); // artifact store CAS (deps, workers)
        this.actionCache = new ActionCache(JkStores.cacheCas(in.cache()), CacheTree.ACTIONS.under(in.cache()));
    }

    BuildPlan.Builder build() {
        readManifest();
        readPluginDeclarations();
        readShape();
        rejectUnsupportedLanguagePairs();
        BuildPlanner.Ctx cx = context();
        Steps s = steps(cx);
        BuildPlan.Builder b = BuildPlan.builder("build")
                .stateKeys(BuildPlanner.STATE_KEYS)
                .addTask(s.parseBuild())
                .addTask(s.syncDeps())
                .addTask(s.ensureJdk());
        // The declared tails plan from the manifest this plan was built from.
        if (parsedBuild != null) b.fact(JkBuild.class, parsedBuild);
        if (nodeKind != NodeShape.Kind.NONE || testNode) b.addTask(PlannerNodeSetup.ensureNodeStep());
        if (nodeKind == NodeShape.Kind.MODULE) return nodeModulePlan(b, cx);
        if (shape.coordinatorOnly()) return workspaceRootPlan(b, cx);
        addCompile(b, cx, s);
        if (in.compileOnly()) return compileOnlyPlan(b, cx, s);
        if (nodeKind == NodeShape.Kind.SIDE_BY_SIDE) nodeArm().planSideBySide(b);
        boolean skipJUnit = PlannerResources.skipJUnit(in);
        addTestsAndPackaging(b, cx, s, skipJUnit);
        return terminal(b, cx, skipJUnit);
    }

    // ---- shape ---------------------------------------------------------------------------

    private void readManifest() {
        try {
            // The plan is built from the effective manifest under Inputs.session (not ambient
            // SessionContext): single-build constructs the plan outside SessionContext.where.
            EffectiveManifest effective = EffectiveManifest.read(in);
            variantSecrets = effective.secrets();
            parsedBuild = effective.build();
            nodeKind = NodeShape.kind(parsedBuild, in.dir());
            testNode = nodeKind == NodeShape.Kind.NONE
                    && !parsedBuild.project().nodeSpec().isEmpty();
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (Exception e) {
            // Unparseable/missing jk.toml — parse-build will surface the real error.
            Log.debug("readManifest: Unparseable/missing jk.toml", e);
            manifestFailure = e instanceof RuntimeException r ? r : new IllegalStateException(e.getMessage(), e);
        }
    }

    /** The effective manifest this plan was built from, for parse-build; or why it could not be read. */
    private Supplier<JkBuild> manifest() {
        JkBuild effective = parsedBuild;
        RuntimeException failure = manifestFailure;
        return () -> {
            if (effective != null) return effective;
            throw failure != null ? failure : new IllegalStateException("no jk.toml in " + in.dir());
        };
    }

    /** The module's compilers, once its plugin declarations are known: their Java sources need javac. */
    private void readShape() {
        if (parsedBuild == null) return;
        try {
            shape = ModuleShape.of(parsedBuild, in.dir(), pluginDecls);
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (RuntimeException e) {
            Log.debug("readShape: languages unreadable; parse-build reports why", e);
        }
    }

    private void rejectUnsupportedLanguagePairs() {
        // Scala compiles with Java in one Zinc session, but a second stub-generating compiler
        // (kotlinc / groovyc) can't parse .scala, so the combo would fail with a cryptic
        // unresolved reference: refuse it by name instead.
        if (shape.scala() && (shape.kotlin() || shape.groovy())) {
            throw new IllegalStateException("scala+" + (shape.kotlin() ? "kotlin" : "groovy")
                    + " in one module is not supported; put the languages in separate modules");
        }
    }

    /**
     * Build-plugin code layer: learn every active plugin's registered steps/packager over the
     * file-cached describe protocol. A missing plugin jar or a broken registration must fail the
     * build loudly here, not mid-plan.
     */
    private void readPluginDeclarations() {
        if (parsedBuild != null) {
            try {
                BuildLayout layout = BuildLayout.of(in.dir(), parsedBuild);
                plugins = ActivePlugins.declared(parsedBuild, in.dir(), in.cache(), layout.moduleTargetDir());
                if (plugins != null) pluginDecls = plugins.decls();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    // ---- context -------------------------------------------------------------------------

    /** The build-scoped services, estimation state and layout flags every core step shares. */
    private BuildPlanner.Ctx context() {
        // Effectively-final copies for the step lambdas.
        final boolean mixedWithJava = shape.java();
        final boolean compact = shape.compact();
        final boolean kotlinModule = shape.kotlin();
        final boolean groovyModule = shape.groovy();

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
        // walk per scope across anchors and the guard's tree lane (same pattern as javaMainSrcRef).
        final BuildLogicInputTokens buildLogicInputTokens = new BuildLogicInputTokens();
        final Path javaMainSrcDir = compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java");
        return BuildPlanner.Ctx.of(
                in,
                cas,
                actionCache,
                plan,
                javaMainSrcRef,
                kotlinMainSrcRef,
                groovyMainSrcRef,
                buildLogicInputTokens,
                javaMainSrcDir,
                shape,
                manifest(),
                PlannerGuards.detect(in));
    }

    // ---- steps ---------------------------------------------------------------------------

    /** Every step the graph phases pick from, built once; whether fixtures and a guard suite exist. */
    private record Steps(
            Task parseBuild,
            Task syncDeps,
            Task ensureJdk,
            Task ksp,
            Task compileJava,
            Task compileKotlin,
            Task compileGroovy,
            Task copyResources,
            @Nullable Task buildInfo,
            @Nullable Task moduleServices,
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
        SetupSteps setup = new SetupSteps(cx);
        Task parseBuild = setup.parseBuild();
        Task syncDeps = setup.syncDeps();
        Task ensureJdk = setup.ensureJdk();
        KspArm kspArm = new KspArm(cx, pluginDecls);
        Task ksp = kspArm.ksp();
        Task compileJava = new CompileSteps(cx, pluginDecls).compileJava();
        LangCompileSteps langCompiles = new LangCompileSteps(cx, pluginDecls);
        Task compileKotlin = langCompiles.compileKotlin();
        Task compileGroovy = langCompiles.compileGroovy();
        Task copyResources = PlannerResources.copyResourcesStep(cx, pluginDecls);
        Task buildInfo =
                parsedBuild != null && parsedBuild.build().buildInfo() != null ? PlannerBuildInfo.step(cx) : null;
        Task moduleServices =
                parsedBuild != null && parsedBuild.build().moduleServices() ? PlannerModuleServices.step(cx) : null;
        boolean hasFixtures = parsedBuild != null && PlannerFixtures.declared(parsedBuild);
        Task compileTestFixtures = PlannerFixtures.compileTestFixturesStep(cx);
        boolean hasGuardSuite = PlannerGuardSuite.declared(in.dir(), shape.compact());
        Task compileGuard = PlannerGuardSuite.compileGuardStep(cx, shape.compact());
        Task compileTest = new TestCompileSteps(cx, hasFixtures, pluginDecls, kspArm).compileTest();
        // In testOnly plans no package path exists to anchor the freshness stamps, so
        // run-tests carries them in its requires — otherwise the target-closure prune drops
        // them and the edit→test loop re-runs the main compile every invocation.
        List<String> testStampRequires = new ArrayList<>();
        // A test that reads git.properties off the classpath (Boot's /info) waits for its writer.
        if (buildInfo != null) testStampRequires.add(TaskNames.BUILD_INFO);
        // As does a test that loads the module's services off the class path.
        if (moduleServices != null) testStampRequires.add(TaskNames.MODULE_SERVICES);
        // The suite reads the versioned classes ahead of the main ones.
        if (PlannerVersions.declared(parsedBuild)) testStampRequires.add(TaskNames.COMPILE_VERSIONS);
        if (testNode) testStampRequires.add(TaskNames.ENSURE_NODE);
        if (in.testOnly()) {
            if (shape.java()) testStampRequires.add(TaskNames.WRITE_STAMP);
            if (shape.kotlin()) testStampRequires.add(TaskNames.WRITE_STAMP_KOTLIN);
            if (shape.groovy()) testStampRequires.add(TaskNames.WRITE_STAMP_GROOVY);
        }
        Task runTests = new TestSteps(cx, pluginDecls).runTests(testStampRequires);
        List<Task> pluginSteps = new ArrayList<>();
        TaskDecl transform = PlannerPlugin.transformStep(pluginDecls);
        if (plugins != null) {
            for (TaskDecl step : plugins.decls().steps()) {
                pluginSteps.add(PlannerPlugin.pluginTask(cx, plugins.ownerOf(step), step, transform));
            }
        }
        PackageSteps packaging = new PackageSteps(cx);
        Task packageJar = packaging.packageJar(
                plugins == null ? null : plugins.packager(),
                pluginDecls,
                variantSecrets,
                PlannerVersions.declared(parsedBuild),
                nodeKind == NodeShape.Kind.SIDE_BY_SIDE && !in.testOnly());
        Task writeStamp = packaging.writeStamp();
        // Kotlin's freshness companion (cf. write-stamp for Java). Mirrors the
        // input set compile-kotlin checked: Kotlin sources, plus Java sources in
        // a mixed module. No action-cache key exists yet — the direct kotlinc
        // path leaves it empty until incremental Kotlin lands.
        Task writeStampKotlin = packaging.writeStampKotlin();
        // Groovy's freshness companion, mirroring write-stamp-kotlin.
        Task writeStampGroovy = packaging.writeStampGroovy();
        // Merge the per-language output dirs into the shared classes dir that
        // packaging, tests, and the run/native tails all read (mixed modules only).
        Task assembleClasses = packaging.assembleClasses();
        return new Steps(
                parseBuild,
                syncDeps,
                ensureJdk,
                ksp,
                compileJava,
                compileKotlin,
                compileGroovy,
                copyResources,
                buildInfo,
                moduleServices,
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
    /**
     * A dedicated node module: its node steps, joined with the root guard lanes when it is the
     * invocation root.
     */
    private BuildPlan.Builder nodeModulePlan(BuildPlan.Builder b, BuildPlanner.Ctx cx) {
        List<String> leaves = new ArrayList<>(nodeArm().plan(b));
        String guardTerminal = PlannerGuards.appendRootLanes(b, cx, TaskNames.RESOLVE_DEPS, false, BuildStage.COMPILE);
        if (guardTerminal != null) leaves.add(guardTerminal);
        if (leaves.size() == 1) return b.terminal(leaves.get(0));
        b.addTask(Task.builder(BuildPlanner.DELIVER_JOIN)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.NATIVE)
                .requires(leaves.toArray(String[]::new))
                .weight(0)
                .ticks(0)
                .execute(ctx -> {
                    /* join only */
                })
                .build());
        return b.terminal(BuildPlanner.DELIVER_JOIN);
    }

    /** The module's node build, a dedicated node module's or the one beside its JVM sources; planned once. */
    private NodeArm nodeArm() {
        JkBuild project = Objects.requireNonNull(parsedBuild, "node build manifest");
        Path nodeDir = Objects.requireNonNull(NodeShape.nodeDir(project, in.dir()), "node directory");
        return new NodeArm(in, project, nodeDir, actionCache, !in.compileOnly() && !PlannerResources.skipJUnit(in));
    }

    private BuildPlan.Builder workspaceRootPlan(BuildPlan.Builder b, BuildPlanner.Ctx cx) {
        String guardTerminal = PlannerGuards.appendRootLanes(b, cx, TaskNames.RESOLVE_DEPS, false, BuildStage.COMPILE);
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

    /** Codegen, the resource copy, the language compiles, the guard lanes, and the classes assembler for mixed modules. */
    private void addCompile(BuildPlan.Builder b, BuildPlanner.Ctx cx, Steps s) {
        // BEFORE_COMPILE / GENERATE: codegen before any language compile (or KSP).
        b.addTask(PlannerResources.buildLogicBeforeCompileStep(cx));
        if (cx.ksp()) {
            b.addTask(s.ksp());
        }
        // Resources before any compile, as Maven's process-resources: a processor reads them from
        // the class output, and what it writes at a resource's path is what the jar keeps.
        b.addTask(s.copyResources());
        if (shape.groovy()) {
            b.addTask(s.compileGroovy());
        }
        if (shape.java()) {
            b.addTask(s.compileJava());
        }
        if (shape.kotlin()) {
            b.addTask(s.compileKotlin());
        }
        // `jk compile` runs no lane: it has neither the guard suite's compile nor the test classes the
        // module lane indexes, so a guard step here would require steps the plan does not carry.
        if (!in.compileOnly()) addGuardLanes(b, cx, s);
        if (cx.mixed() || cx.mixedGroovy()) {
            b.addTask(s.assembleClasses());
        }
        if (PlannerVersions.declared(parsedBuild)) b.addTask(PlannerVersions.compileVersionsStep(cx));
    }

    /** The module lane after the compiles it indexes, and the root lanes; or, off-build, the model lane alone. */
    private void addGuardLanes(BuildPlan.Builder b, BuildPlanner.Ctx cx, Steps s) {
        if (PlannerGuards.moduleLanesOnThisBuild(cx.guards(), PlannerResources.runGuardScripts(in))) {
            List<String> after = new ArrayList<>();
            if (shape.java()) after.add(TaskNames.COMPILE_JAVA);
            if (shape.kotlin()) after.add(TaskNames.COMPILE_KOTLIN);
            if (shape.groovy()) after.add(TaskNames.COMPILE_GROOVY);
            if (cx.mixed() || cx.mixedGroovy()) after.add(TaskNames.ASSEMBLE_CLASSES);
            if (s.hasGuardSuite()) after.add(TaskNames.COMPILE_GUARD);
            // The lane indexes the test classes exactly when the plan compiles them, and then waits
            // for compile-test; without the edge a rebuild rewrites target/test-classes under the indexer.
            boolean afterTests = PlannerGuards.indexesTestClasses(in);
            if (afterTests) after.add(TaskNames.COMPILE_TEST);
            BuildStage guardStage = afterTests ? BuildStage.TEST : BuildStage.COMPILE;
            b.addTask(PlannerGuards.moduleStep(cx, guardStage, afterTests, after.toArray(String[]::new)));
            PlannerGuards.appendRootLanes(b, cx, TaskNames.GUARD, !in.testOnly(), guardStage);
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
            PlannerGuards.appendRootLanes(b, cx, TaskNames.RESOLVE_DEPS, !in.testOnly(), BuildStage.COMPILE);
            b.alsoKeep(
                    TaskNames.GUARD_MODEL,
                    TaskNames.GUARD_WORKSPACE,
                    TaskNames.GUARD_TREE,
                    TaskNames.GUARD_FIXTURES,
                    TaskNames.GUARD_OUTPUT);
        }
    }

    /**
     * `jk compile` stops here: lock → sync → the resource copy → compile (+ freshness stamps),
     * no test or package. The resources are part of the tree, not of packaging: a dependent
     * compiles against this module's {@code target/classes}, a processor sibling is found through
     * the service registration the copy lands there, and a tree published without them is one
     * the next build rewrites under its consumers' keys. Everything later depends on these steps.
     * A goal that compiles the tests ({@link cc.jumpkick.wire.runtime.WorkspaceTarget#TEST_COMPILE})
     * adds the test compile, run by nothing.
     */
    private BuildPlan.Builder compileOnlyPlan(BuildPlan.Builder b, BuildPlanner.Ctx cx, Steps s) {
        b.addTask(PlannerResources.buildLogicAfterCompileStep(cx));
        List<String> leaves = new ArrayList<>();
        boolean tests = in.compileTests();
        if (tests) {
            if (s.hasFixtures()) b.addTask(s.compileTestFixtures());
            b.addTask(s.compileTest());
            leaves.add(TaskNames.COMPILE_TEST);
        }
        if (shape.java()) {
            b.addTask(s.writeStamp());
            leaves.add(TaskNames.WRITE_STAMP);
        }
        if (shape.kotlin()) {
            b.addTask(s.writeStampKotlin());
            leaves.add(TaskNames.WRITE_STAMP_KOTLIN);
        }
        if (shape.groovy()) {
            b.addTask(s.writeStampGroovy());
            leaves.add(TaskNames.WRITE_STAMP_GROOVY);
        }
        // Every stamp, the after-compile scripts and (mixed) the classes assembler is an independent
        // leaf — a single-leaf terminal would prune the others, and a pruned language recompiles
        // every run. Join them so the closure keeps each one (same idiom as the deliver join).
        if (cx.mixed() || cx.mixedGroovy()) {
            leaves.add(TaskNames.ASSEMBLE_CLASSES);
        }
        if (PlannerVersions.declared(parsedBuild)) leaves.add(TaskNames.COMPILE_VERSIONS);
        // After the compile and the resource copy before it.
        leaves.add(PlannerResources.CLASSES_WHOLE);
        // A processor sibling is found through the services files module-services writes.
        if (s.moduleServices() != null) {
            b.addTask(s.moduleServices());
            leaves.add(TaskNames.MODULE_SERVICES);
        }
        // Every plugin step joins the plan, and the terminal keeps the ones the compile requires:
        // the source generators the compiles read. A classes transform (bytecode enhancement)
        // makes the module's classes what they are, so a recompile runs it too: `jk dev` launches
        // from its output.
        for (Task p : s.pluginSteps()) b.addTask(p);
        TaskDecl transform = PlannerPlugin.transformStep(pluginDecls);
        if (transform != null) leaves.add("plugin-" + transform.name());
        b.addTask(Task.builder(BuildPlanner.COMPILE_JOIN)
                .forecast(StepForecast.RIDES)
                .stage(transform != null ? BuildStage.PACKAGE : tests ? BuildStage.TEST : BuildStage.COMPILE)
                .requires(leaves.toArray(String[]::new))
                .weight(0)
                .ticks(0)
                .execute(ctx -> {
                    /* join only */
                })
                .build());
        return b.terminal(BuildPlanner.COMPILE_JOIN);
    }

    /** The after-compile scripts, the test branch, the guard suite, the plugin steps and packaging, then the stamps. */
    private void addTestsAndPackaging(BuildPlan.Builder b, BuildPlanner.Ctx cx, Steps s, boolean skipJUnit) {
        // Build-logic AFTER_COMPILE: the resources and AFTER_RESOURCES ran before the compile.
        b.addTask(PlannerResources.buildLogicAfterCompileStep(cx));
        if (s.buildInfo() != null) b.addTask(s.buildInfo());
        if (s.moduleServices() != null) b.addTask(s.moduleServices());
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
        // Every plugin step joins the plan, and the terminal keeps the ones its steps require: the
        // source generators the compiles read, what feeds the forked test JVM, and — when it
        // packages — what feeds the packaged artifact. `jk test` stops at run-tests and never
        // packages a jar, so the packaging plugin steps fall away with package-jar.
        for (Task p : s.pluginSteps()) b.addTask(p);
        if (!in.testOnly()) {
            b.addTask(PlannerResources.buildLogicBeforePackageStep(
                    cx, s.buildInfo() != null, s.moduleServices() != null));
            b.addTask(s.packageJar());
        }
        // write-stamp is the Java-compile freshness companion; only when Java ran.
        if (shape.java()) {
            b.addTask(s.writeStamp());
        }
        // write-stamp-kotlin is the Kotlin-compile freshness companion.
        if (shape.kotlin()) {
            b.addTask(s.writeStampKotlin());
        }
        // write-stamp-groovy is the Groovy-compile freshness companion.
        if (shape.groovy()) {
            b.addTask(s.writeStampGroovy());
        }
    }

    private BuildPlan.Builder terminal(BuildPlan.Builder b, BuildPlanner.Ctx cx, boolean skipJUnit) {
        String gate = PlannerResources.appendGate(b, cx, !skipJUnit, in.testOnly(), false);
        if (in.testOnly()) {
            if (gate != null) return b.terminal(gate);
            if (!skipJUnit) return b.terminal(TaskNames.RUN_TESTS);
            return b.terminal(PlannerResources.CLASSES_WHOLE);
        }
        return b.terminal(TaskNames.PACKAGE_JAR);
    }
}
