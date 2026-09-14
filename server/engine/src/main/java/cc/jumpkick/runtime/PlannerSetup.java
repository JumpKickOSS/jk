// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerSupport.contributedProvidedClasspath;
import static cc.jumpkick.runtime.PlannerSupport.lockModules;
import static cc.jumpkick.runtime.PlannerSupport.unresolvedProcessorDeps;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.JavacDefaults;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkInstallListener;
import cc.jumpkick.jdk.JdkProgressLabel;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Parse / resolve-deps / ensure-jdk steps for {@link BuildPlanner#coreBuilder}.
 */
public final class PlannerSetup {

    private PlannerSetup() {}

    static Task parseBuildStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        AtomicReference<@Nullable List<Path>> groovyMainSrcRef = cx.groovyMainSrcRef();
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
                        } catch (Exception e) {
                            Log.debug("parseBuildStep: Exception ignored", e);
                        }
                    }
                    return 10;
                })
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project;
                    try {
                        // Same override the plan was built from (jk assemble --fat/--minified).
                        // Plan construction and step bodies must read one effective config, or a
                        // task gets scheduled against a config its body cannot.
                        project = VariantApply.apply(
                                        applyAssemblyOverride(JkBuildParser.parse(in.buildFile()), in.session()),
                                        in.dir(),
                                        Variants.Selection.parse(in.variant()),
                                        in.clientEnv())
                                .build();
                    } catch (RuntimeException e) {
                        ctx.error("toml", Errors.text(e));
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    BuildLayout layout = BuildLayout.of(in.dir(), project);
                    ctx.put(LAYOUT, layout);

                    if (!Files.exists(in.lockFile())) {
                        ctx.label("resolve deps (first run)");
                        LockFlow.Result result;
                        try {
                            // noDefaultFeatures=false — same feature selection as `jk lock`.
                            result = LockFlow.run(in.lockDir(), in.cache(), List.of(), false, null);
                        } catch (UnsatisfiableException e) {
                            ctx.error("verbatim", Errors.text(e));
                            throw new RuntimeException("dependency resolution failed");
                        }
                        if (result.status() != 0) {
                            ctx.error(
                                    "verbatim",
                                    result.error() != null ? result.error() : "dependency resolution failed");
                            throw new RuntimeException("lock failed");
                        }
                        ctx.put(LOCKFILE, Objects.requireNonNull(result.lockfile(), "lockfile"));
                    } else if (AutoLock.isStale(in.dir(), in.lockFile())) {
                        ctx.label("jk.toml changed — updating lock");
                        Lockfile existing = LockfileReader.read(in.lockFile());
                        Lockfile updated = AutoLock.maybeReLock(
                                in.dir(),
                                existing,
                                in.lockFile(),
                                in.cache(),
                                null,
                                List.of(),
                                true,
                                ResolveObserver.NOOP,
                                ctx::output);
                        ctx.put(LOCKFILE, updated != null ? updated : existing);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(in.lockFile()));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);

                    // Shared by the main- and test-compile steps (both read JAVAC_ARGS).
                    // Classpaths are published in resolve-deps AFTER sync — same reason
                    // JAVA_HOME is published in ensure-jdk, not here.
                    ctx.put(JAVAC_ARGS, effectiveJavacArgs(project, in.dir(), lock, in.profileName()));
                    // Reuse source lists that the tick suppliers may have already walked.
                    // If the ticks haven't fired yet (unusual ordering), populate and cache now.
                    InputTrees.coverModule(in.dir());
                    List<Path> javaMainSrcs = javaMainSrcRef.get();
                    if (javaMainSrcs == null) {
                        javaMainSrcs = CompileSupport.collectJavaSources(javaMainSrcDir);
                        javaMainSrcRef.compareAndSet(null, javaMainSrcs);
                        javaMainSrcs = javaMainSrcRef.get();
                    }
                    List<Path> kotlinMainSrcs = kotlinMainSrcRef.get();
                    List<Path> groovyMainSrcs = groovyMainSrcRef.get();
                    // [build] extra-src roots (variant overlays folded in by VariantApply) and
                    // plugin-contributed source roots ([[contribute.source-roots]] — grails-app/…)
                    // join the source set here — the tick suppliers' pre-walk never saw them.
                    // Each union is derived by PlannerCompile, not here: `jk explain` has to
                    // reproduce these lists exactly to gate and key the compile steps, and a
                    // second copy of the fold is a drift the parity guard cannot.
                    javaMainSrcs = PlannerCompile.javaAndScalaSources(project, in.dir(), compact, javaMainSrcs);
                    javaMainSrcRef.set(javaMainSrcs);
                    kotlinMainSrcs = kotlinMainSrcs == null
                            ? PlannerCompile.mainKotlinSources(project, in.dir(), compact)
                            : PlannerCompile.mainKotlinSources(project, in.dir(), kotlinMainSrcs);
                    groovyMainSrcs = groovyMainSrcs == null
                            ? PlannerCompile.mainGroovySources(project, in.dir(), compact)
                            : PlannerCompile.mainGroovySources(project, in.dir(), groovyMainSrcs);
                    kotlinMainSrcRef.set(kotlinMainSrcs);
                    groovyMainSrcRef.set(groovyMainSrcs);
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

    /**
     * The javac args every compile step of this module runs with: debug info and default lint per
     * {@code [build]}, the plugin contributions the lock resolves, then the selected profile's own
     * args, which win by coming last. The forecast derives its compile keys from this same body,
     * so a {@code --profile} that changes the args changes the forecast's key exactly as it
     * changes the build's.
     */
    static List<String> effectiveJavacArgs(JkBuild project, Path dir, Lockfile lock, @Nullable String profileName) {
        Profile profile = CompileSupport.resolveProfile(project.profiles(), profileName);
        return JavacDefaults.effectiveArgs(
                project.build().lint(),
                project.build().debug(),
                PluginContributions.javacArgs(project, dir, lockModules(lock)),
                profile == null ? List.of() : profile.javacArgs());
    }

    static Task syncDepsStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
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
                    boolean mirrorToM2 = project.project().m2integration();
                    // Ticks are already counted up front by estimateTicks (artifact
                    // count); progress(1)-per-artifact below fills it.
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + pkg.displayCoord());
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
                        public void failed(Lockfile.Artifact pkg, @Nullable String err) {
                            ctx.error("dep", pkg.displayCoord() + " — " + err);
                            ctx.progress(1);
                        }
                    };
                    boolean refresh = in.session().config().forceOr(false);
                    var report = new CacheSync(cas, new Http(), mirrorToM2).sync(lock, observer, refresh);
                    if (report.hasErrors()) throw new RuntimeException("dep sync had errors");
                    // Classpaths must be resolved HERE, after jars are on disk. parse-build used
                    // to snapshot them first; on a cold store ClasspathResolver soft-skipped
                    // missing rows and compile saw an empty CP → javac "package does not exist"
                    // even though resolve-deps then fetched everything successfully.
                    ctx.label("resolve classpath");
                    try {
                        publishClasspaths(ctx, in, cas, cx.tools());
                    } catch (RuntimeException e) {
                        ctx.error("classpath", Errors.text(e));
                        throw e;
                    } catch (Exception e) {
                        ctx.error("classpath", e.getMessage() == null ? e.toString() : e.getMessage());
                        throw new RuntimeException(e);
                    }
                })
                .build();
    }

    /**
     * Lock + workspace sibling classpaths for compile / test / processors. Called only after
     * {@code resolve-deps} sync so every checksummed lock row is on disk ({@code requirePresent}).
     *
     * <p>Siblings enter the compile classpaths through their classes trees, never their jars: a
     * tree is whole once the sibling has compiled, so this module is admitted — and reaches this
     * step — while the sibling may still be packaging and testing. Only the trees are required
     * here. The jars, and the test output a tests kind selects, are what the package and test
     * steps read, and {@link #awaitSiblingArtifacts} requires them where they are first needed.
     */
    static void publishClasspaths(TaskContext ctx, BuildPlanner.Inputs in, Cas cas, PluginBuild.StepTools tools)
            throws Exception {
        Lockfile lock = ctx.require(LOCKFILE);
        JkBuild project = ctx.require(PROJECT);
        ClasspathResolver resolver = new ClasspathResolver(cas);

        WorkspaceClasspath.Result mainSiblings =
                WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN));
        requireSiblingsCompiled(ctx, mainSiblings, "sibling not compiled — ");
        // Lockfile + sibling classes trees + siblings' transitive lockfile deps — the
        // exact classpath `jk explain` re-derives, so the action keys match.
        List<Path> mainCp = PlannerSupport.mainCompileClasspath(lock, resolver, mainSiblings, true);
        // Plugin-contributed PROVIDED classpath (an Android platform jar): javac
        // sees it, runtime/packaging never do. Resolved through the same engine
        // fetch the steps use, so the compile action key fingerprints it.
        List<Path> contributedProvided = contributedProvidedClasspath(project, in, cas, tools);
        mainCp.addAll(contributedProvided);
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
        // that silently skips code generation is worse than one that fails. A sibling
        // processor is loaded from its classes tree, which holds its service registration
        // once the sibling's resources are copied — the point its tree is published at.
        requireSiblingsCompiled(ctx, processorSiblings, "processor sibling not compiled — ");
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
        ctx.put(PROCESSOR_CP, PlannerSupport.processorClasspath(lock, resolver, processorSiblings, true));

        WorkspaceClasspath.Result testSiblings = WorkspaceClasspath.resolve(
                in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
        // Only when compile-test is planned: under --skip-tests a test-only sibling may be outside
        // the build's cone, and nothing in this plan consumes the test classpath, so its absence is
        // the cone working, not a broken setup.
        if (!PlannerResources.skipJUnit(in)) {
            requireSiblingsCompiled(ctx, testSiblings, "test sibling not compiled — ");
        }
        // Both test classpaths name the declared closure, built or not: a sibling's test classes
        // and jar may still be on their way when this step runs, and a list filtered to what is
        // on disk now would silently drop them from the tests.
        List<Path> compileTestCp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST, true));
        compileTestCp.addAll(testSiblings.siblingClosureClasses());
        List<Path> testRuntimeCp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.TEST, true));
        testRuntimeCp.addAll(testSiblings.siblingClosureJars());
        // A sibling's own external deps (e.g. resolver's maven-artifact) must
        // also reach the test classpath, or tests exercising sibling code hit
        // NoClassDefFoundError. Mirrors the main-cp sibling-lockfile loop above.
        for (Path sibLock : testSiblings.siblingLockfiles()) {
            try {
                Lockfile sl = LockfileReader.read(sibLock);
                for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN, true)) {
                    if (!compileTestCp.contains(p)) compileTestCp.add(p);
                }
                for (Path p : resolver.classpathFor(sl, ClasspathResolver.RUNTIME, true)) {
                    if (!testRuntimeCp.contains(p)) testRuntimeCp.add(p);
                }
            } catch (Exception e) {
                /* best-effort */
                Log.debug("failed: best-effort", e);
            }
        }
        compileTestCp.addAll(contributedProvided);
        ctx.put(PROVIDED_CP, contributedProvided);
        ctx.put(COMPILE_TEST_CP, compileTestCp);
        ctx.put(TEST_RUNTIME_CP, testRuntimeCp);
    }

    /**
     * The one shape of the missing-sibling guard for the compile classpaths. The resolve writes
     * the accurate cause into {@code missingSiblingClasses}; failing here attributes it to setup,
     * where the sibling is named — not to the compile that would otherwise surface a {@code
     * cannot find symbol} pointing at the wrong file.
     */
    private static void requireSiblingsCompiled(TaskContext ctx, WorkspaceClasspath.Result siblings, String prefix) {
        if (siblings.missingSiblingClasses().isEmpty()) return;
        for (String missing : siblings.missingSiblingClasses()) {
            ctx.error("workspace", prefix + missing);
        }
        throw new RuntimeException("missing workspace siblings");
    }

    /**
     * The point a plan first reads a sibling's jar — or the test classes and fixtures a tests kind
     * or {@code fixtures = true} selects: wait for every sibling this module reads to have
     * published its artifacts, then require them on disk. The wait is the module's side of the
     * workspace schedule ({@link cc.jumpkick.runtime.base.SiblingArtifacts}); the check after it
     * names the jar a failed or sourceless sibling did not produce, so the step that would have
     * read it fails on the sibling rather than on a {@code NoClassDefFoundError} of its own.
     *
     * <p>Test-view artifacts are required only when this plan compiles tests: under
     * {@code --skip-tests} no sibling produces fixtures or test classes and nothing here reads them.
     */
    static void awaitSiblingArtifacts(TaskContext ctx, BuildPlanner.Inputs in) throws Exception {
        in.siblings().awaitArtifacts(ctx::cancelled);
        if (ctx.cancelled()) return;
        JkBuild project = ctx.require(PROJECT);
        WorkspaceClasspath.Result mainSiblings =
                WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN));
        requireSiblingsBuilt(ctx, mainSiblings, "sibling not built — ");
        if (!PlannerResources.skipJUnit(in)) {
            WorkspaceClasspath.Result testSiblings = WorkspaceClasspath.resolve(
                    in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
            requireSiblingsBuilt(ctx, testSiblings, "test sibling not built — ");
        }
    }

    /**
     * The test compile's side of the wait. Its classpath is the siblings' classes trees, which the
     * schedule already admitted this module on, plus what a {@code kind = "tests"} edge or {@code
     * fixtures = true} selects: the siblings' test classes and fixtures, outputs of their test
     * stage that publish with their artifacts. A plan that selects neither compiles its tests
     * without waiting for any sibling to package.
     */
    static void awaitSiblingTestOutputs(TaskContext ctx, BuildPlanner.Inputs in) throws Exception {
        if (WorkspaceClasspath.selectsTestOutputs(ctx.require(PROJECT))) awaitSiblingArtifacts(ctx, in);
    }

    /** The runtime-view guard: {@code missingSiblingJars} names each jar, test output or fixtures dir absent. */
    private static void requireSiblingsBuilt(TaskContext ctx, WorkspaceClasspath.Result siblings, String prefix) {
        if (siblings.missingSiblingJars().isEmpty()) return;
        for (String missing : siblings.missingSiblingJars()) {
            ctx.error("workspace", prefix + missing);
        }
        throw new RuntimeException("missing workspace siblings");
    }

    static Task ensureJdkStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
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
                        JdkEnsure.Outcome outcome = JdkEnsure.ensure(
                                in.dir(),
                                in.jdksDir(),
                                project,
                                lock,
                                m -> ctx.warn("jdk", m),
                                true,
                                new EnsureJdkProgress(ctx));
                        // JAVA_HOME is published HERE, not in parse-build: resolving before the
                        // ensure meant the FIRST build against a never-installed pin snapshotted
                        // the running JVM and compiled/tested on the wrong JDK (self-healing on
                        // the next build — but wrong once is wrong). The ensure's own outcome is
                        // authoritative; the walk is only the no-pin fallback.
                        ctx.put(
                                JAVA_HOME,
                                outcome.jdkOpt()
                                        .map(InstalledJdk::home)
                                        .orElseGet(() -> JavaHomes.resolveJavaHome(in.dir())));
                        // Already on disk / locked — no download work this run.
                        if (outcome.source() != JdkEnsure.Source.INSTALLED) ctx.cached();
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Live {@code ensure-jdk} detail: {@code downloading Temurin 25 ▰…▱ 50%} then
     * {@code installing … 100%}. Percent-throttled so the wire coalescer is not flooded.
     */
    static final class EnsureJdkProgress implements JdkInstallListener {
        private final TaskContext ctx;
        private volatile String name = "JDK";
        private volatile int lastPct = Integer.MIN_VALUE;

        EnsureJdkProgress(TaskContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onDownloadStart(String label, long totalBytes) {
            if (label != null && !label.isBlank()) name = label;
            lastPct = Integer.MIN_VALUE;
            emitDownload(0, totalBytes);
        }

        @Override
        public void onDownloadProgress(long readBytes, long totalBytes) {
            emitDownload(readBytes, totalBytes);
        }

        @Override
        public void onExtractStart(String label) {
            if (label != null && !label.isBlank()) name = label;
            ctx.label(JdkProgressLabel.installing(name));
        }

        private void emitDownload(long read, long total) {
            if (total <= 0) {
                if (lastPct == -1) return;
                lastPct = -1;
                ctx.label(JdkProgressLabel.downloading(name, 0, 0));
                return;
            }
            int pct = JdkProgressLabel.percent(read, total);
            if (pct == lastPct) return;
            lastPct = pct;
            ctx.label(JdkProgressLabel.downloading(name, read, total));
        }
    }
}
