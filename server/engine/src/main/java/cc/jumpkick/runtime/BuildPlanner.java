// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.PlannerTails.appendDeclaredTails;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClassAbi;
import cc.jumpkick.test.AffectedTests;
import java.nio.file.Path;
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

    /** The javac test compile's action key — a run-tests stamp input, absent when no javac test sources exist. */
    public static final BuildPlanKey<String> COMPILE_TEST_ACTION_KEY =
            BuildPlanKey.scalar("compile-test-action-key", String.class);

    /** The Kotlin test compile's action key — the same stamp input, absent when no Kotlin test sources exist. */
    public static final BuildPlanKey<String> COMPILE_TEST_KOTLIN_ACTION_KEY =
            BuildPlanKey.scalar("compile-test-kotlin-action-key", String.class);

    /** The Groovy test compile's action key — the same stamp input, absent when no Groovy test sources exist. */
    public static final BuildPlanKey<String> COMPILE_TEST_GROOVY_ACTION_KEY =
            BuildPlanKey.scalar("compile-test-groovy-action-key", String.class);

    /**
     * The option digest each compile checked its freshness stamp against, published for the
     * write-stamp step so the stamp records exactly what the compile compared.
     */
    public static final BuildPlanKey<String> JAVA_STAMP_DIGEST = BuildPlanKey.scalar("java-stamp-digest", String.class);

    /**
     * The classpath token lines compile-main checked its freshness stamp against ({@code
     * ActionKey.javacClasspathTokens} of the request it keyed), published for write-stamp so the
     * stamp records the same lines the key hashed.
     */
    public static final BuildPlanKey<List<String>> JAVA_STAMP_TOKENS =
            BuildPlanKey.list("java-stamp-tokens", String.class);

    public static final BuildPlanKey<String> KOTLIN_STAMP_DIGEST =
            BuildPlanKey.scalar("kotlin-stamp-digest", String.class);

    /** As {@link #JAVA_STAMP_TOKENS} for compile-kotlin ({@code PlannerLang.kotlinStampTokens}). */
    public static final BuildPlanKey<List<String>> KOTLIN_STAMP_TOKENS =
            BuildPlanKey.list("kotlin-stamp-tokens", String.class);

    public static final BuildPlanKey<String> GROOVY_STAMP_DIGEST =
            BuildPlanKey.scalar("groovy-stamp-digest", String.class);

    /** As {@link #JAVA_STAMP_TOKENS} for compile-groovy ({@code ActionKey.groovycClasspathTokens}). */
    public static final BuildPlanKey<List<String>> GROOVY_STAMP_TOKENS =
            BuildPlanKey.list("groovy-stamp-tokens", String.class);

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

    static final List<BuildPlanKey<?>> STATE_KEYS = List.of(
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
            COMPILE_TEST_ACTION_KEY,
            COMPILE_TEST_KOTLIN_ACTION_KEY,
            COMPILE_TEST_GROOVY_ACTION_KEY,
            JAVA_STAMP_DIGEST,
            JAVA_STAMP_TOKENS,
            KOTLIN_STAMP_DIGEST,
            KOTLIN_STAMP_TOKENS,
            GROOVY_STAMP_DIGEST,
            GROOVY_STAMP_TOKENS,
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
            boolean ephemeralActions,
            SiblingArtifacts.Gate siblings) {

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
                    false,
                    SiblingArtifacts.NONE);
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
                    ephemeralActions,
                    siblings);
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
                    ephemeralActions,
                    siblings);
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
                    ephemeralActions,
                    siblings);
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
                    ephemeralActions,
                    siblings);
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
                    ephemeralActions,
                    siblings);
        }

        /**
         * Copy carrying this module's side of the workspace schedule's artifact wait: what its
         * package and test steps call before reading a sibling's jar. Set by the workspace prepare
         * phase; a single-module plan keeps {@link SiblingArtifacts#NONE}.
         */
        public Inputs withSiblings(SiblingArtifacts.Gate siblings) {
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
                    ephemeralActions,
                    siblings);
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
            boolean compact,
            boolean mixed,
            boolean mixedGroovy,
            boolean kotlinModule,
            boolean groovyModule,
            boolean mixedWithJava,
            String mainCompile,
            boolean ksp,
            PlannerGuards.GuardsPlan guards,
            PluginBuild.StepTools tools) {}

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
