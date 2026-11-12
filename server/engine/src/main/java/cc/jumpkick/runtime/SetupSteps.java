// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.JavacDefaults;
import cc.jumpkick.config.JavaRelease;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.groovy.GroovyResolver;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkEnsureProgress;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.lock.LockRewriteGuard;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.scala.ScalaResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One plan's setup steps: {@code parse-build} (the manifest, the lock and the source lists),
 * {@code resolve-deps} (the module's classpaths) and {@code ensure-jdk}. Built once per plan from
 * what its {@link BuildPlanner.Ctx} holds.
 */
final class SetupSteps {

    private final BuildPlanner.Inputs in;
    private final Cas cas;
    private final Supplier<EffortWeights.Plan> plan;
    private final AtomicReference<@Nullable List<Path>> javaMainSrcRef;
    private final AtomicReference<@Nullable List<Path>> kotlinMainSrcRef;
    private final AtomicReference<@Nullable List<Path>> groovyMainSrcRef;
    private final Path javaMainSrcDir;
    private final ModuleShape shape;
    private final boolean compact;
    private final Supplier<JkBuild> manifest;
    private final PluginBuild.StepTools tools;

    SetupSteps(BuildPlanner.Ctx cx) {
        this.in = cx.in();
        this.cas = cx.cas();
        this.plan = cx.plan();
        this.javaMainSrcRef = cx.javaMainSrcRef();
        this.kotlinMainSrcRef = cx.kotlinMainSrcRef();
        this.groovyMainSrcRef = cx.groovyMainSrcRef();
        this.javaMainSrcDir = cx.javaMainSrcDir();
        this.shape = cx.shape();
        this.compact = cx.compact();
        this.manifest = cx.manifest();
        this.tools = cx.tools();
    }

    /**
     * What the manifest has to say once it is parsed: a shadowed module says once, on the build
     * after its POM changed, what the effective POM declares that the in-place build does not
     * carry; a declared language set says which sources of another language it leaves uncompiled;
     * a Kotlin, Groovy or Scala below its floor says what the module compiles with instead, and a
     * {@code java} below jk's floor says the toolchain cross-compiles for it.
     */
    private static void warnManifestRows(TaskContext ctx, Path dir, JkBuild project) {
        for (String row : ShadowManifests.drainTier3(dir)) ctx.warn("pom", row);
        for (String row : Languages.undeclaredWithSources(project.project(), dir)) ctx.warn("languages", row);
        if (JavaRelease.belowFloor(project.project().java())) {
            ctx.warn("java", JavaRelease.floorNote(project.project().java()));
        }
        if (project.project().kotlin() instanceof VersionSelector.Exact exact
                && KotlinResolver.belowFloor(exact.version())) {
            ctx.warn("kotlin", KotlinResolver.floorNote(exact.version()));
        }
        if (project.project().groovy() instanceof VersionSelector.Exact exact
                && GroovyResolver.belowFloor(exact.version())) {
            ctx.warn("groovy", GroovyResolver.floorNote(exact.version()));
        }
        if (project.project().scala() instanceof VersionSelector.Exact exact
                && ScalaResolver.belowFloor(exact.version())) {
            ctx.warn("scala", ScalaResolver.floorNote(exact.version()));
        }
    }

    Task parseBuild() {
        return Task.builder(TaskNames.PARSE_BUILD)
                .forecast(StepForecast.RIDES)
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
                            Log.debug("parseBuild: Exception ignored", e);
                        }
                    }
                    return 10;
                })
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project;
                    try {
                        // The effective manifest the plan was built from: plan construction and
                        // step bodies read one config, or a task gets scheduled against a config
                        // its body cannot.
                        project = manifest.get();
                    } catch (RuntimeException e) {
                        ctx.error("toml", Errors.text(e));
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    warnManifestRows(ctx, in.dir(), project);
                    BuildLayout layout = BuildLayout.of(in.dir(), project);
                    ctx.put(LAYOUT, layout);

                    if (!Files.exists(in.lockFile())) {
                        ctx.label("resolving dependencies: no jk-lock.toml yet");
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
                        ctx.put(LOCKFILE, memberView(in, Objects.requireNonNull(result.lockfile(), "lockfile")));
                    } else if (AutoLock.isStale(in.dir(), in.lockFile())) {
                        ctx.label("jk.toml changed: updating jk-lock.toml");
                        Lockfile existing = LockfileReader.read(in.lockFile());
                        Lockfile updated;
                        try {
                            updated = AutoLock.maybeReLock(
                                    in.dir(),
                                    existing,
                                    in.lockFile(),
                                    in.cache(),
                                    null,
                                    List.of(),
                                    true,
                                    ResolveObserver.NOOP,
                                    ctx::output);
                        } catch (LockRewriteGuard.LockRewriteRefused e) {
                            ctx.error("verbatim", Errors.text(e));
                            throw new RuntimeException("lock refused");
                        }
                        ctx.put(LOCKFILE, memberView(in, updated != null ? updated : existing));
                    } else {
                        ctx.put(LOCKFILE, memberView(in, followFirstPartyPins(in, project, ctx)));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);

                    // Shared by the main- and test-compile steps (both read JAVAC_ARGS).
                    // Classpaths are published in resolve-deps AFTER sync — same reason
                    // JAVA_HOME is published in ensure-jdk, not here.
                    ctx.put(JAVAC_ARGS, effectiveJavacArgs(project, in.dir(), lock, in.profileName()));
                    // Reuse source lists that the tick suppliers may have already walked.
                    // If the ticks haven't fired yet (unusual ordering), populate and cache now.
                    coverModule(ctx, in.dir(), in.skipTests());
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
                PluginContributions.javacArgs(project, dir, lock.classpathModules()),
                profile == null ? List.of() : profile.javacArgs());
    }

    Task syncDeps() {
        return Task.builder(TaskNames.RESOLVE_DEPS)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.RESOLVE)
                .label("Classpath")
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .weight(() -> plan.get().sync())
                .ticks(1)
                .execute(ctx -> {
                    // The workspace's SYNC phase put every locked artifact on disk before this plan
                    // ran; this step derives the module's classpaths from them.
                    ctx.label("resolve classpath");
                    try {
                        publishClasspaths(ctx, in, shape, cas, tools);
                        // Ahead of the KSP round and every compile that reads its output.
                        PlannerKsp.dropRetiredRounds(shape, ctx.require(LAYOUT), ctx.require(PROJECT));
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
     * The module's classpaths ({@link ModuleClasspaths}), published once the SYNC phase put every
     * checksummed lock row on disk. What the derivation leaves to its caller happens here first:
     * the siblings the main compile and the processor path read must have compiled, every declared
     * processor must resolve, and the plugins' provided jars and processors are fetched.
     *
     * <p>Only the main compile's and the processors' sibling trees are required here: a sibling
     * only the test scopes name is no admission edge, so {@link SiblingWaits#awaitSiblingTestCompile} requires
     * it; the jars are what the package and test steps read, and {@link SiblingWaits#awaitSiblingArtifacts}
     * and {@link SiblingWaits#awaitSiblingTestRuntime} require them where they are first needed.
     */
    static void publishClasspaths(
            TaskContext ctx, BuildPlanner.Inputs in, ModuleShape shape, Cas cas, PluginBuild.StepTools tools)
            throws Exception {
        Lockfile lock = ctx.require(LOCKFILE);
        JkBuild project = ctx.require(PROJECT);
        Path dir = in.dir();
        SiblingWaits.requireSiblingsCompiled(
                ctx,
                WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.COMPILE_SCOPES),
                "dependency module not compiled: ");
        // Plugin-contributed PROVIDED classpath (an Android platform jar): javac sees it,
        // runtime/packaging never do. Resolved through the same engine fetch the steps use, so the
        // compile action key fingerprints it.
        ProvidedClasspath.Provided provided = ProvidedClasspath.contributed(project, in, cas, tools);
        // A declared processor that cannot be found generates nothing, and a build that silently
        // skips code generation is worse than one that fails. A sibling processor is loaded from
        // its classes tree, which holds its service registration once the sibling's resources are
        // copied — the point its tree is published at.
        SiblingWaits.requireSiblingsCompiled(
                ctx,
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.PROCESSOR)),
                "processor module not compiled: ");
        List<String> unresolvedProcessors = ProcessorPaths.unresolved(
                project, lock, WorkspaceClasspath.resolve(dir, project, ClasspathResolver.PROCESSOR_PATH));
        if (!unresolvedProcessors.isEmpty()) {
            for (String unresolved : unresolvedProcessors)
                ctx.error("processor", "processor " + unresolved + " is not in jk-lock.toml; run jk lock");
            throw new RuntimeException("unresolved processor dependencies");
        }
        // The processors the module's plugins contribute: each is a closure the lock pins, and one
        // the lock lacks fails here, as a declared processor the lock lacks does above.
        ContributedProcessors.Paths contributedProcessors;
        try {
            contributedProcessors = ContributedProcessors.resolve(project, dir, lock, cas);
        } catch (IOException e) {
            ctx.error("processor", String.valueOf(e.getMessage()));
            throw new RuntimeException("unresolved contributed processors", e);
        }
        ctx.put(
                CLASSPATHS,
                ModuleClasspaths.derive(
                        dir,
                        project,
                        shape,
                        lock,
                        new ClasspathResolver(cas),
                        new ModuleClasspaths.Contributions(provided, contributedProcessors),
                        ModuleClasspaths.Mode.BUILD));
    }

    Task ensureJdk() {
        return Task.builder(TaskNames.ENSURE_JDK)
                .forecast(StepForecast.RIDES)
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
                                new JdkEnsureProgress(ctx));
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
                        JdkInstallNotice.post(outcome);
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The workspace lock as this module reads it: a member's partition rows in place of the
     * workspace's for the coordinates it disagrees on ({@link Lockfile#forMember}). A standalone
     * project, or the workspace root itself, reads the lock as written.
     */
    static Lockfile memberView(BuildPlanner.Inputs in, Lockfile lock) {
        return MemberRows.view(lock, in.lockFile(), in.dir());
    }

    /**
     * The lock's first-party plugin rows follow the running jk ({@link FirstPartyPins}); a
     * standalone project hears about a move here, once. A workspace member finds its rows already
     * moved by the workspace preflight. A lock that cannot be read fails loudly on the read below,
     * with the reader's own message.
     */
    private static Lockfile followFirstPartyPins(BuildPlanner.Inputs in, JkBuild project, TaskContext ctx)
            throws IOException {
        Set<String> declared = new HashSet<>();
        for (PluginDeclaration declaration : project.plugins()) declared.add(declaration.coordinate());
        List<FirstPartyPins.Repin> moved = FirstPartyPins.follow(in.dir(), declared);
        if (!moved.isEmpty()) ctx.output(FirstPartyPins.describe(moved));
        return LockfileReader.read(in.lockFile());
    }

    /**
     * Snapshot the module's input trees for this job; a link to a directory in a source, resource
     * or test root fails the step, naming it: the walk
     * does not enter one, so the tree behind it would otherwise be left out of the compile, its
     * key and the jar without a word.
     */
    static void coverModule(TaskContext ctx, Path moduleDir, boolean skipTests) throws IOException {
        InputTrees.coverModule(moduleDir);
        for (Path root : ModuleLayoutPlugins.fingerprintRoots(moduleDir, skipTests)) {
            if (!Files.isDirectory(root)) continue;
            List<Path> links = InputTrees.of(root).directoryLinks();
            if (links.isEmpty()) continue;
            String rel = moduleDir.relativize(links.getFirst()).toString().replace('\\', '/');
            ctx.error("directory-link", rel + " is " + MemoInputs.DIRECTORY_LINK);
            throw new IllegalStateException(rel + " is " + MemoInputs.DIRECTORY_LINK);
        }
    }
}
