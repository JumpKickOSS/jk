// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.PlannerTails.appendDeclaredTails;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.CompileStamp;
import cc.jumpkick.task.MainAbi;
import cc.jumpkick.test.AffectedTests;
import cc.jumpkick.wire.runtime.BuildKnobs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.With;
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
        SessionCancel.bindHold(() -> SessionContext.current().cancel().awaitResumed());
    }

    private BuildPlanner() {}

    // ---- shared cross-step keys ---------------------------------------
    public static final BuildPlanKey<JkBuild> PROJECT = BuildPlanKey.scalar("project", JkBuild.class);
    public static final BuildPlanKey<Lockfile> LOCKFILE = BuildPlanKey.scalar("lockfile", Lockfile.class);
    public static final BuildPlanKey<Path> JAVA_HOME = BuildPlanKey.scalar("java-home", Path.class);

    /** The locked Node.js home with its package manager; published by {@code ensure-node}. */
    public static final BuildPlanKey<NodeHome> NODE_HOME = BuildPlanKey.scalar("node-home", NodeHome.class);

    /** A node build's output directory, once {@code node-build} has written or restored it. */
    public static final BuildPlanKey<Path> NODE_OUT = BuildPlanKey.scalar("node-out", Path.class);

    /** The {@code node-install} key {@code node_modules} answers to; later node steps hash it. */
    public static final BuildPlanKey<String> NODE_INSTALLED = BuildPlanKey.scalar("node-installed", String.class);

    /** {@code [node] exports}: each name to the output it names, once the node build has it on disk. */
    public static final BuildPlanKey<Map<String, Path>> NODE_EXPORTS =
            BuildPlanKey.map("node-exports", String.class, Path.class);

    public static final BuildPlanKey<Integer> RELEASE = BuildPlanKey.scalar("release", Integer.class);

    /** Every classpath the module's compiles and tests read; published by {@code resolve-deps}. */
    static final BuildPlanKey<ModuleClasspaths> CLASSPATHS = BuildPlanKey.scalar("classpaths", ModuleClasspaths.class);

    public static final BuildPlanKey<List<Path>> JAVA_SOURCES = BuildPlanKey.list("java-sources", Path.class);

    public static final BuildPlanKey<List<Path>> KOTLIN_SOURCES = BuildPlanKey.list("kotlin-sources", Path.class);

    public static final BuildPlanKey<List<Path>> GROOVY_SOURCES = BuildPlanKey.list("groovy-sources", Path.class);

    public static final BuildPlanKey<List<String>> JAVAC_ARGS = BuildPlanKey.list("javac-args", String.class);

    /** The javac test compile's action key — a run-tests stamp input, absent when no javac test sources exist. */
    public static final BuildPlanKey<String> COMPILE_TEST_ACTION_KEY =
            BuildPlanKey.scalar("compile-test-action-key", String.class);

    /** The Kotlin test compile's action key — the same stamp input, absent when no Kotlin test sources exist. */
    public static final BuildPlanKey<String> COMPILE_TEST_KOTLIN_ACTION_KEY =
            BuildPlanKey.scalar("compile-test-kotlin-action-key", String.class);

    /** The Groovy test compile's action key — the same stamp input, absent when no Groovy test sources exist. */
    public static final BuildPlanKey<String> COMPILE_TEST_GROOVY_ACTION_KEY =
            BuildPlanKey.scalar("compile-test-groovy-action-key", String.class);

    /** What compile-java decided, for write-stamp, assemble-classes and the steps after them. */
    public static final BuildPlanKey<CompileStamp> JAVA_COMPILE =
            BuildPlanKey.scalar("java-compile", CompileStamp.class);

    /** What compile-kotlin decided; its action key is what the merged tree is held against. */
    public static final BuildPlanKey<CompileStamp> KOTLIN_COMPILE =
            BuildPlanKey.scalar("kotlin-compile", CompileStamp.class);

    /** What compile-groovy decided. */
    public static final BuildPlanKey<CompileStamp> GROOVY_COMPILE =
            BuildPlanKey.scalar("groovy-compile", CompileStamp.class);

    public static final BuildPlanKey<List<Path>> TEST_SOURCES = BuildPlanKey.list("test-sources", Path.class);

    /** Suite resource dirs copied into target/test-classes — a TestStamp input. */
    public static final BuildPlanKey<List<Path>> TEST_RESOURCE_DIRS =
            BuildPlanKey.list("test-resource-dirs", Path.class);

    /** Each directory suite's own resource roots when a run compiles several apart; empty otherwise. */
    static final BuildPlanKey<SuiteResources> SUITE_RESOURCES =
            BuildPlanKey.scalar("suite-resources", SuiteResources.class);

    /**
     * {@code built} once a packaging step wrote the module's artifact anew, whatever its compiles
     * found cached; absent while none did, and the module's outcome is then compile-java's.
     */
    public static final BuildPlanKey<String> BUILD_OUTCOME = BuildPlanKey.scalar("build-outcome", String.class);

    public static final BuildPlanKey<Path> MAIN_CLASSES = BuildPlanKey.scalar("main-classes", Path.class);
    public static final BuildPlanKey<Path> TEST_CLASSES = BuildPlanKey.scalar("test-classes", Path.class);
    public static final BuildPlanKey<BuildLayout> LAYOUT = BuildPlanKey.scalar("layout", BuildLayout.class);
    public static final BuildPlanKey<TestSummary> TEST_RESULT = BuildPlanKey.scalar("test-result", TestSummary.class);
    /** The runner's {@code tag-excluded} warning on a {@code --class} run the tag filter emptied. */
    public static final BuildPlanKey<String> TAG_EXCLUDED = BuildPlanKey.scalar("tag-excluded", String.class);

    public static final BuildPlanKey<Boolean> NO_TEST_SOURCES = BuildPlanKey.scalar("no-test-sources", Boolean.class);
    /** The suite a {@code --class} that matched nothing in this module named instead, when one owns it. */
    public static final BuildPlanKey<String> CLASS_SUITE_HINT = BuildPlanKey.scalar("class-suite-hint", String.class);

    /** What a javac main compile that ran changed, for {@code --affected}. */
    public static final BuildPlanKey<MainAbi> MAIN_ABI = BuildPlanKey.scalar("main-abi", MainAbi.class);

    public static final BuildPlanKey<AffectedTests> AFFECTED_TESTS =
            BuildPlanKey.scalar("affected-tests", AffectedTests.class);

    static final List<BuildPlanKey<?>> STATE_KEYS = List.of(
            PROJECT,
            LOCKFILE,
            JAVA_HOME,
            NODE_HOME,
            NODE_OUT,
            NODE_INSTALLED,
            NODE_EXPORTS,
            RELEASE,
            CLASSPATHS,
            JAVA_SOURCES,
            KOTLIN_SOURCES,
            GROOVY_SOURCES,
            JAVAC_ARGS,
            JAVA_COMPILE,
            KOTLIN_COMPILE,
            GROOVY_COMPILE,
            COMPILE_TEST_ACTION_KEY,
            COMPILE_TEST_KOTLIN_ACTION_KEY,
            COMPILE_TEST_GROOVY_ACTION_KEY,
            TEST_SOURCES,
            TEST_RESOURCE_DIRS,
            SUITE_RESOURCES,
            BUILD_OUTCOME,
            MAIN_CLASSES,
            TEST_CLASSES,
            LAYOUT,
            TEST_RESULT,
            TAG_EXCLUDED,
            NO_TEST_SOURCES,
            CLASS_SUITE_HINT,
            MAIN_ABI,
            AFFECTED_TESTS);

    /**
     * Everything a build needs that isn't carried through the plan's state. Every component has a
     * {@code with} copy; {@link #of} is where one starts.
     *
     * @param variant variant selection; {@code null} becomes {@code ""}
     * @param clientEnv client-resolved env for {@code env:} indirection; {@code null} becomes empty
     * @param siblings this module's side of the workspace schedule's artifact wait: what its package
     *     and test steps call before reading a sibling's jar. Set by the workspace prepare phase; a
     *     single-module plan keeps {@link SiblingArtifacts#NONE}
     * @param compileTests a compile-only plan that also compiles the tests and runs none, the {@code
     *     TEST_COMPILE} goal's
     */
    @With
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
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv,
            boolean ephemeralActions,
            SiblingArtifacts.Gate siblings,
            boolean compileTests) {

        public Inputs {
            variant = variant == null ? "" : variant;
            clientEnv = clientEnv == null ? Map.of() : clientEnv;
        }

        /**
         * {@code dir}'s manifest and lock, one worker, no profile, the full lifecycle, and the
         * variant and client env from {@code session}. Commands that install a selection on the
         * session ({@code jk run}, {@code test}, {@code image}, {@code native}, {@code publish})
         * parameterize every plan factory without each one threading those fields.
         */
        public static Inputs of(Path dir, Path cache, Session session) {
            return new Inputs(
                    dir,
                    cache,
                    ManifestPaths.manifestIn(dir),
                    LockPaths.lockFile(dir),
                    dir,
                    1,
                    0,
                    null,
                    null,
                    false,
                    false,
                    false,
                    false,
                    Set.of(),
                    session,
                    session.variant(),
                    session.clientEnv(),
                    false,
                    SiblingArtifacts.NONE,
                    false);
        }

        /**
         * The inputs a real {@code jk build} constructs for one module — the single factory both
         * {@code jk build} and {@code jk explain}'s ETA use, so the two can't drift in what they feed
         * the effort-weight prediction. A {@code jdksDir} of {@code null} is load-bearing: it routes
         * {@link EffortWeights#jdkWeight} through the full JDK probe chain (PATH / JAVA_HOME / GraalVM
         * / SDKMAN / …) instead of the empty managed JDK root, so an already-installed JDK predicts a
         * zero-cost {@code ensure-jdk} rather than a phantom download.
         *
         * @param workers {@code 0} is auto at run-tests; the forecast prices it as one
         * @param projectModules the build graph's module dirs, so the prediction can borrow a
         *     project-tier learned rate for a not-yet-built module
         * @param testOnly the plan stops before packaging ({@code jk test})
         * @param compileOnly the plan stops after compiling ({@code jk compile})
         */
        public static Inputs forBuild(
                Path dir,
                Path cache,
                int workers,
                @Nullable Path jdksDir,
                @Nullable String profile,
                boolean skipTests,
                boolean verbose,
                Set<Path> projectModules,
                boolean testOnly,
                boolean compileOnly) {
            Session session = SessionContext.current();
            boolean skip = BuildKnobs.skipsTests(session.testSelection(), testOnly, skipTests);
            int estimatedTestCount =
                    skip ? 0 : TestSupport.estimateAllSuiteTestCount(dir, CompileSupport.isSimpleLayout(dir));
            return of(dir, cache, session)
                    .withWorkerCount(workers > 0 ? workers : 1)
                    .withEstimatedTestCount(estimatedTestCount)
                    .withProfileName(profile)
                    .withJdksDir(jdksDir)
                    .withSkipTests(skip)
                    .withVerbose(verbose)
                    .withTestOnly(testOnly)
                    .withCompileOnly(compileOnly)
                    .withProjectModules(projectModules);
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
    static final int W_JAVADOC = 8;
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
        return new CorePlan(in, forceRebuild).build();
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
            BuildLogicInputTokens buildLogicInputTokens,
            BuildLogicScope buildLogicScope,
            Path javaMainSrcDir,
            ModuleShape shape,
            Supplier<JkBuild> manifest,
            PlannerGuards.GuardsPlan guards,
            PluginBuild.StepTools tools) {

        /** A module's context, with fresh step tools and its build-logic scope. */
        static Ctx of(
                Inputs in,
                Cas cas,
                ActionCache actionCache,
                Supplier<EffortWeights.Plan> plan,
                AtomicReference<@Nullable List<Path>> javaMainSrcRef,
                AtomicReference<@Nullable List<Path>> kotlinMainSrcRef,
                AtomicReference<@Nullable List<Path>> groovyMainSrcRef,
                BuildLogicInputTokens buildLogicInputTokens,
                Path javaMainSrcDir,
                ModuleShape shape,
                Supplier<JkBuild> manifest,
                PlannerGuards.GuardsPlan guards) {
            return new Ctx(
                    in,
                    cas,
                    actionCache,
                    plan,
                    javaMainSrcRef,
                    kotlinMainSrcRef,
                    groovyMainSrcRef,
                    buildLogicInputTokens,
                    BuildLogicScope.of(in.dir()),
                    javaMainSrcDir,
                    shape,
                    manifest,
                    guards,
                    new PluginBuild.StepTools());
        }

        boolean compact() {
            return shape.compact();
        }

        /** Kotlin and Java: kotlinc, then javac against its output, then assemble-classes. */
        boolean mixed() {
            return shape.mixed();
        }

        /** Groovy and Java: joint groovyc, then javac, then assemble-classes. */
        boolean mixedGroovy() {
            return shape.mixedGroovy();
        }

        boolean kotlinModule() {
            return shape.kotlin();
        }

        boolean groovyModule() {
            return shape.groovy();
        }

        /** javac runs in this module, whatever else does. */
        boolean mixedWithJava() {
            return shape.java();
        }

        /** The step the main compile ends with. */
        String mainCompile() {
            return shape.mainCompile();
        }

        /** The module runs a main KSP round. */
        boolean ksp() {
            return shape.ksp();
        }
    }

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
